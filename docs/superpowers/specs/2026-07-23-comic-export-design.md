# 漫画导出 ZIP 设计

**日期**: 2026-07-23
**状态**: 已审阅
**范围**: API + Worker + 前端 + comic-common

---

## 1. 背景与目标

用户可以将整本漫画导出为 ZIP 文件，保存到本地 `D:/manga/export/` 目录。导出内容包含原图/视频、目录结构和元数据，方便备份、迁移或重新导入。

**核心目标**:
- 整本漫画一键导出为 ZIP
- HQ 优先，已删除的页面自动降级为 LQ
- ZIP 内部保持用户友好的目录结构（按 catalog 树 + chapter title）
- 与导入流水线完全对称的异步事件架构
- 导出历史不覆盖，按时间戳命名

**非目标**:
- 不涉及单章导出（Phase II）
- 不涉及自动清理导出文件
- 不涉及 FileTask 统一抽象（本次新建 `export_task`，统一留给后续重构）

---

## 2. 架构原则（本次明确）

| 角色 | 读 MySQL | 写 MySQL | 操作文件系统 |
|------|---------|---------|------------|
| API | ✅ | ✅（唯一写入方） | ❌ |
| Worker | ✅（只读） | ❌ | ✅ |

> 这是对原有"Worker 不直连 MySQL"约束的正式升级：Worker 可以只读查询业务数据库来完成文件处理，但所有业务状态更新必须通过 MQ 事件回到 API。

---

## 3. 数据模型

### 3.1 export_task 表

```sql
CREATE TABLE export_task (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    comic_id    BIGINT      NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | RUNNING | SUCCESS | FAILED
    progress    SMALLINT    NOT NULL DEFAULT 0,          -- 0-100，预留进度字段
    output_root VARCHAR(20),                             -- 存储根键，如 'EXPORT'
    output_path VARCHAR(500),                            -- 相对路径，如 'Frieren_20260723_102355.zip'
    output_size BIGINT      NOT NULL DEFAULT 0,          -- 输出文件大小（字节）
    error_msg   VARCHAR(500),
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME
);
```

> `progress` 字段 Phase I 预留但暂不实现实时进度推送，仅用于 PENDING(0) → RUNNING(0) → SUCCESS(100) / FAILED(-1)。

### 3.2 存储路径规范

与现有 Storage 体系保持一致，不存绝对路径：

| 字段 | 值 | 含义 |
|------|-----|------|
| `output_root` | `EXPORT` | 存储根键 |
| `output_path` | `Frieren_20260723_102355.zip` | 相对于 `EXPORT` 根的路径 |

物理路径：`StorageProperties.roots["EXPORT"].resolve(output_path)` → `D:/manga/export/Frieren_20260723_102355.zip`

配置新增（`application.yml`）：
```yaml
storage:
  roots:
    HQ:  { type: FILESYSTEM, path: ${MANGA_ROOT:D:/manga}/hq }
    LQ:  { type: FILESYSTEM, path: ${MANGA_ROOT:D:/manga}/lq }
    EXPORT: { type: FILESYSTEM, path: ${MANGA_ROOT:D:/manga}/export }   # 新增
```

### 3.3 ZIP 内部结构

```
{Frieren}/                            ← rootDirName = ComicTitleSanitizer.sanitize(comic.title)
├── metadata.json                     ← v2 格式，与 Import 完全一致（MetadataExporter 同款）
├── 第1话/                            ← catalog 树 + chapter.title（无 catalog 时按 chapter.title 建一级文件夹）
│   ├── 001.jpg
│   ├── 002.jpg
│   └── 003.jpg
├── 第2话/
│   ├── 001.jpg
│   └── ...
└── ...
```

### 3.4 包含 / 不包含

| 包含 | 不包含 |
|------|--------|
| metadata.json (v2) | 封面缩略图 (thumbs/) |
| HQ 原图 / 视频 | LQ 缓存（除非 HQ 已删除做降级） |
| HQ 删除后降级的 LQ | 阅读历史 / 收藏状态 |
| catalog 目录结构 | 数据库 ID |
| | task 信息 |
| | 临时文件 / cache |

---

## 4. 完整数据流

```
POST /api/comics/{id}/export                    ← StoragePage 触发
        │
   ┌────▼──────────────────────────────────┐
   │ ExportController.createExport(comicId) │
   └────┬──────────────────────────────────┘
        │
   ┌────▼──────────────────────────────────────────┐
   │ ExportServiceImpl.createExportTask(comicId)     │
   │   ├── comic.status != READY → 409              │
   │   ├── 已有 PENDING/RUNNING → 409 (幂等)         │
   │   ├── INSERT export_task(PENDING)              │
   │   └── afterCommit → convertAndSend(             │
   │         exchange="comic.export",                │
   │         routingKey="task.created",              │
   │         ExportTaskCreatedEvent)                 │
   └────┬──────────────────────────────────────────┘
        │
   ┌────▼──────────────────────────────────┐
   │ Worker ExportTaskHandler               │  MQ 消费
   │   ├── Publish ExportTaskStartedEvent   │
   │   ├── ExportCollector (只读 DB)        │
   │   ├── ExportFileResolver (HQ/LQ决策)   │
   │   ├── ExportManifest (组装清单)        │
   │   ├── ZipBuilder (纯 IO)              │
   │   └── Publish ExportTaskCompletedEvent │
   └────┬──────────────────────────────────┘
        │
   ┌────▼──────────────────────────────────┐
   │ API ExportStartedHandler               │
   │   └── UPDATE export_task(RUNNING)      │
   └───────────────────────────────────────┘
        │ (并行)
   ┌────▼──────────────────────────────────┐
   │ API ExportCompletedHandler             │
   │   └── UPDATE export_task(              │
   │         SUCCESS, output_path, size)    │
   └───────────────────────────────────────┘
```

**失败路径**：
```
Worker 异常 → Publish ExportTaskFailedEvent
  → API ExportFailedHandler → UPDATE export_task(FAILED, error_msg)
  → MQ 重试（manual ACK，3 次后进 DLQ）
```

---

## 5. Worker 组件详设

### 5.1 ExportTaskHandler

MQ 入口，路由编排，不包含业务逻辑。

```java
@RabbitListener(queues = "export.task.queue")
public void handle(ExportTaskCreatedEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
    // 1. 发布 ExportTaskStartedEvent
    // 2. ExportCollector.collect(comicId) → ExportManifest
    // 3. ZipBuilder.build(manifest, outputPath)
    // 4. 发布 ExportTaskCompletedEvent
    // 5. channel.basicAck(tag, false)
}
```

### 5.2 ExportCollector

只读 DB，查询漫画全量数据，组装为中间数据结构。

```java
public ExportCollector {
    // 依赖注入：ComicMapper, ChapterMapper, CatalogMapper, MediaMapper (MyBatis Plus)
    
    public CollectedData collect(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        List<Chapter> chapters = chapterMapper.selectByComicIdOrderByGlobalOrder(comicId);
        List<Catalog> catalogs = catalogMapper.selectByComicId(comicId);
        List<Media> allMedia = mediaMapper.selectByComicId(comicId);
        // 返回未组装的原始数据，交给 ExportFileResolver + ExportManifest 处理
    }
}
```

### 5.3 ExportFileResolver

逐页决策取 HQ 还是 LQ，输出 `StorageRef`。

```java
public StorageRef resolve(Media media) {
    if (media.getMediaType() == MediaType.VIDEO) {
        return new StorageRef(media.getHqRoot(), media.getHqPath());  // 视频无 LQ
    }
    if (media.getHqStatus() == HqStatus.READY) {
        return new StorageRef(media.getHqRoot(), media.getHqPath());
    }
    if (media.getHqStatus() == HqStatus.DELETED && media.getLqStatus() == LqStatus.READY) {
        return new StorageRef(media.getLqRoot(), media.getLqPath());  // 降级 LQ
    }
    throw new ExportFileNotFoundException(media.getId());  // HQ 丢失且 LQ 不可用
}
```

### 5.4 ExportManifest

纯数据 record，无任何业务依赖。ZipBuilder 的唯一输入。

```java
public record ExportManifest(
    String rootDirName,          // ComicTitleSanitizer 处理后的安全目录名
    String metadataJson,         // 预序列化的 metadata.json (v2)
    List<Entry> entries          // 文件清单
) {
    public record Entry(
        String targetPath,       // ZIP 内路径，如 "第1话/001.jpg"
        Path sourceFile          // 物理源文件路径
    ) {}
}
```

### 5.5 ZipBuilder

纯 IO，零业务依赖。只负责"把这些文件放进这个 ZIP"。

```java
public class ZipBuilder {
    public long build(ExportManifest manifest, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());  // 确保 export/ 目录存在
        try (var zos = new ZipOutputStream(Files.newOutputStream(outputPath))) {
            // 1. 写入 metadata.json（根目录）
            writeEntry(zos, manifest.rootDirName() + "/metadata.json",
                       manifest.metadataJson().getBytes(StandardCharsets.UTF_8));
            // 2. 逐文件写入
            for (var entry : manifest.entries()) {
                String zipPath = manifest.rootDirName() + "/" + entry.targetPath();
                writeEntry(zos, zipPath, Files.readAllBytes(entry.sourceFile()));
            }
        }
        return Files.size(outputPath);
    }
}
```

### 5.6 ComicTitleSanitizer

过滤文件系统非法字符，确保 ZIP 根目录名可用。

```java
public class ComicTitleSanitizer {
    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[<>:\"/\\\\|?*]");
    
    public static String sanitize(String title) {
        String cleaned = ILLEGAL_CHARS.matcher(title).replaceAll("");
        return cleaned.isBlank() ? "comic_export" : cleaned.trim();
    }
}
```

---

## 6. 事件定义

### 6.1 ExportTaskCreatedEvent

```java
public record ExportTaskCreatedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId
) implements ComicEvent {}
```

**routing key**: `task.created`  
**exchange**: `comic.export`

### 6.2 ExportTaskStartedEvent

```java
public record ExportTaskStartedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId
) implements ComicEvent {}
```

**routing key**: `task.started`

### 6.3 ExportTaskCompletedEvent

```java
public record ExportTaskCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String outputRoot,
    String outputPath,
    Long outputSize           // 字节
) implements ComicEvent {}
```

**routing key**: `task.completed`

### 6.4 ExportTaskFailedEvent

```java
public record ExportTaskFailedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String errorCode,
    String errorMessage
) implements ComicEvent {}
```

**routing key**: `task.failed`

### 6.5 ComicEvent 修改

```java
@JsonSubTypes({
    // ... existing 12 types ...
    @JsonSubTypes.Type(value = ExportTaskCreatedEvent.class, name = "ExportTaskCreated"),
    @JsonSubTypes.Type(value = ExportTaskStartedEvent.class, name = "ExportTaskStarted"),
    @JsonSubTypes.Type(value = ExportTaskCompletedEvent.class, name = "ExportTaskCompleted"),
    @JsonSubTypes.Type(value = ExportTaskFailedEvent.class, name = "ExportTaskFailed")
})
public sealed interface ComicEvent
    permits ..., ExportTaskCreatedEvent, ExportTaskStartedEvent,
            ExportTaskCompletedEvent, ExportTaskFailedEvent {}
```

---

## 7. MQ 配置

### API 侧（`RabbitMqConfig.java`）

```java
// Exchange
@Bean
public TopicExchange exportExchange() {
    return new TopicExchange("comic.export");
}

// Queue
@Bean
public Queue exportTaskQueue() {
    return QueueBuilder.durable("export.task.queue")
        .deadLetterExchange("comic.export.dlx")
        .deadLetterRoutingKey("export.task.dlq")
        .build();
}

@Bean
public Queue exportResultQueue() {
    return QueueBuilder.durable("export.result.queue")
        .deadLetterExchange("comic.export.dlx")
        .deadLetterRoutingKey("export.result.dlq")
        .build();
}

// Binding
@Bean
public Binding exportTaskBinding() {
    return BindingBuilder.bind(exportTaskQueue()).to(exportExchange()).with("task.created");
}

@Bean
public Binding exportResultBinding() {
    return BindingBuilder.bind(exportResultQueue()).to(exportExchange()).with("task.*");
}
```

### Worker 侧（对称声明，略）

---

## 8. API 接口

### 8.1 创建导出任务

```http
POST /api/comics/{comicId}/export
```

**前置校验**：
1. `comic.status == READY` → 通过，否则 `409 CONFLICT`
2. 已有 `PENDING` / `RUNNING` 的导出任务 → `409 CONFLICT`（幂等）

**成功响应**：
- `202 Accepted`: `{ "taskId": 42, "status": "PENDING" }`

### 8.2 查询导出任务

```http
GET /api/export/{taskId}
```

**响应**：
```json
{
  "taskId": 42,
  "comicId": 12,
  "status": "SUCCESS",
  "outputRoot": "EXPORT",
  "outputPath": "Frieren_20260723_102355.zip",
  "outputSize": 339738624,
  "physicalPath": "D:\\manga\\export\\Frieren_20260723_102355.zip"
}
```

> `physicalPath` 由 API 服务端通过 `StorageProperties.roots["EXPORT"].resolve(outputPath)` 计算，前端直接使用无需拼接。

### 8.3 下载导出文件

```http
GET /api/export/{taskId}/download
```

**前置条件**：`task.status == SUCCESS` 且 `output_root + output_path` 存在。

返回 `StreamingResponseBody`，Content-Type: `application/zip`，Content-Disposition: `attachment`。

> 此接口始终可用（不依赖平台），前端/浏览器直接下载 ZIP。

### 8.4 打开导出目录

```http
POST /api/export/{taskId}/open
```

**前置条件**：`task.status == SUCCESS`。

调用 `java.awt.Desktop.open()` 在文件管理器中打开 `EXPORT` 根目录。

> **平台限制**：仅桌面环境（Windows/macOS/Linux Desktop）实现。无桌面环境（Docker / NAS / 无头 Linux Server）返回 `501 Not Implemented`。

---

## 9. 错误处理

| 场景 | 行为 |
|------|------|
| 前置校验失败（comic 非 READY） | API 返回 `409`，不创建任务 |
| 已有进行中的导出任务 | API 返回 `409`（幂等阻止重复创建） |
| 某页 HQ 丢失且 LQ 不可用 | Worker 发送 `ExportTaskFailedEvent`（`errorCode=MISSING_FILE`），MQ 重试 3 次 → DLQ |
| 磁盘空间不足 | Worker 捕获 `IOException` → `ExportTaskFailedEvent`（`errorCode=DISK_FULL`） |
| ZIP 打包过程中 IO 错误 | Worker 抛异常拒绝 ACK → MQ 重试（manual ACK + DLQ） |
| MQ 事件丢失 | DLQ 捕获，API 侧任务状态保持 RUNNING，前端展示超时提示 |
| Worker 崩溃 | MQ 重试（manual ACK），最多 3 次后进 DLQ |
| 无效的 comicId | API 返回 `404` |

---

## 10. 前端行为

### 10.1 触发入口

**StoragePage / StorageTable.vue**：在每行操作按钮区（"删HQ""生LQ"旁边）新增"导出 ZIP"。

> 不在 DetailPage (Reading) 放置导出按钮，保持 Reading / Management 边界清晰。

### 10.2 二次确认弹窗

```
┌──────────────────────────────────────────┐
│ 📦 导出 ZIP：《葬送的芙莉莲》               │
├──────────────────────────────────────────┤
│ 章节：12 章 / 共 200 页                   │
│ 源文件大小：约 324 MB                      │
│                                          │
│ 导出内容：                                │
│  ✓ HQ 原图                               │
│  ✓ HQ 已删除时自动降级为 LQ               │
│  ✓ metadata.json (v2)                   │
│                                          │
│ 输出路径：D:\manga\export\                │
│                                          │
│ [取消]              [开始导出]            │
└──────────────────────────────────────────┘
```

### 10.3 任务状态跟踪

TaskPage 任务列表新增 `EXPORT` 类型行：
- **PENDING** → 灰色图标 + "等待中"
- **RUNNING** → 旋转图标 + "导出中..."
- **SUCCESS** → ✅ + 操作按钮：`[下载]` `[复制路径]` `[重新导出]`
- **FAILED** → ❌ + 错误信息 + `[重试]`

### 10.4 Toast 通知

- 导出完成：`✅ 导出完成：Frieren.zip · 324 MB`
- 导出失败：`❌ 导出失败：磁盘空间不足`

### 10.5 "复制路径"实现

点击复制 → 调用 `GET /api/export/{taskId}` 获取 `physicalPath` → `navigator.clipboard.writeText(physicalPath)`。

### 10.6 "打开目录"实现

调用 `POST /api/export/{taskId}/open`，后端在桌面环境调用 `Desktop.open()`。

---

## 11. 新增/修改文件清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `comic-common/event/ExportTaskCreatedEvent.java` | 新建 | API → Worker |
| `comic-common/event/ExportTaskStartedEvent.java` | 新建 | Worker → API |
| `comic-common/event/ExportTaskCompletedEvent.java` | 新建 | Worker → API |
| `comic-common/event/ExportTaskFailedEvent.java` | 新建 | Worker → API |
| `comic-common/event/ComicEvent.java` | 修改 | 新增 4 个子类型 |
| `api/export/controller/ExportController.java` | 新建 | 4 个端点（创建/查询/下载/打开目录） |
| `api/export/service/ExportService.java` | 新建 | 校验 + 创建任务 + 发事件 |
| `api/export/event/ExportStartedHandler.java` | 新建 | 消费 → UPDATE RUNNING |
| `api/export/event/ExportCompletedHandler.java` | 新建 | 消费 → UPDATE SUCCESS |
| `api/export/event/ExportFailedHandler.java` | 新建 | 消费 → UPDATE FAILED |
| `api/config/RabbitMqConfig.java` | 修改 | 声明 export exchange/queue/binding |
| `worker/event/ExportTaskHandler.java` | 新建 | MQ 消费入口，路由编排 |
| `worker/export/ExportCollector.java` | 新建 | 只读 DB 组装数据 |
| `worker/export/ExportFileResolver.java` | 新建 | 逐页 HQ/LQ 决策 |
| `worker/export/ExportManifest.java` | 新建 | 纯数据 record |
| `worker/export/ZipBuilder.java` | 新建 | 纯 IO ZIP 流 |
| `worker/export/ComicTitleSanitizer.java` | 新建 | 文件名安全处理 |
| `worker/mapper/ComicMapper.java` | 新建（如不存在） | Worker 只读查询 comic |
| `worker/mapper/ChapterMapper.java` | 新建（如不存在） | Worker 只读查询 chapter |
| `worker/mapper/CatalogMapper.java` | 新建（如不存在） | Worker 只读查询 catalog |
| `worker/mapper/MediaMapper.java` | 新建（如不存在） | Worker 只读查询 media |
| `worker/config/RabbitMqConfig.java` | 修改 | 声明 export exchange/queue/binding |
| `frontend/services/api.ts` | 修改 | 新增 exportApi |
| `frontend/types/index.ts` | 修改 | 新增 ExportTaskVO |
| `frontend/views/management/storage/StorageTable.vue` | 修改 | 新增"导出 ZIP"按钮 |
| `frontend/views/management/TaskPage.vue` | 修改 | 支持 EXPORT 任务类型 |
| DB | 新建 | `export_task` 建表 |
| `worker-service/.../application.yml` | 修改 | 新增 `EXPORT` storage root |
| `api-service/.../application.yml` | 修改 | 新增 `EXPORT` storage root |

---

## 12. 与现有功能的兼容性

| 功能 | 影响 |
|------|------|
| 导入流程 | 无影响。metadata.json 保持 v2，导出后可直接重新导入 |
| LQ 生成 | 无影响。HQ 删除后导出自动降级 LQ |
| HQ 删除 | 无影响。ExportFileResolver 处理 DELETED 状态 |
| 视频页 | 正常导出。视频无 LQ 降级，始终取 HQ |
| 封面 | 不导出。thumbs/ 独立目录 |
| ProgressiveImage | 无影响 |

---

## 13. 后续可扩展（Phase II+）

- **FileTask 统一抽象**：`import_task` + `export_task` + LQ/HQ 任务 → 统一的 `file_task` 表
- **单章导出**：`POST /api/chapters/{chapterId}/export`
- **导出历史保留策略**：自动覆盖 / 自动编号 / 保留最近 N 个，用户可配置
- **实时进度推送**：`ExportProgressChangedEvent`（10% → 35% → 68% → 100%）
- **导出格式扩展**：TAR / 7Z / 目录直出
- **导出目标扩展**：SMB / 云盘 / 外部存储
- **自动清理**：`expired_at` + 定时清理过期导出文件
