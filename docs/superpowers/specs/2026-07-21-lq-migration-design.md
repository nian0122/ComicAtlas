# LQ 迁移导入设计

**日期**: 2026-07-21
**状态**: 待实现
**类型**: 临时迁移功能（一次性使用）

---

## 背景

部分漫画在旧系统中以 LQ（低质量）形式存储，对应的 HQ（高质量）文件大小为 0（空白占位），实际内容仅在 LQ 目录下存在。需将这些漫画导入 ComicAtlas 新系统。

**示例目录结构**:
```
F:\games\comics\h_photograph\写真\陆萱萱\   ← HQ 文件存在但大小为 0
F:\games\comics\l_photograph\写真\陆萱萱\   ← LQ 文件存在，内容完整
```

---

## 约束条件（已确认）

1. **HQ 文件严格大小为 0**：判断条件明确，无需模糊阈值。
2. **目录命名规则统一**：所有目录遵循 `h_*` / `l_*` 前缀规则。
3. **临时功能**：仅提供后端 API，不修改前端批量导入 UI。
4. **LQ 已优化**：不需要触发 LQ 生成，导入后 `lq_status` 仍为 `NOT_GENERATED`，但系统不会为这些漫画执行 LQ 压缩。

---

## 方案选择

采用 **专用 `MIGRATE_LQ` sourceType 方案**，原因：

- **零改动通用阅读链路**：`FileUrlResolver` 和 `ReaderService` 无需任何修改。
- **最小化侵入**：只改动 Worker 搬运逻辑，API 侧仅需增加 sourceType 路由。
- **诚实处理空文件**：如果沿用现有 `DIRECTORY` sourceType，MediaAnalyzer 会把空文件标记为 `READY`，DirectoryImportHandler 跳过搬运，最终 DB 有记录但文件系统不存在，阅读器 404。

---

## 架构设计

### 数据流

```
POST /api/tasks/import
  { sourceType: "MIGRATE_LQ", sourcePath: "F:/games/comics/h_photograph/写真/陆萱萱" }
    ↓
ImportServiceImpl: case "MIGRATE_LQ"
  验证 sourcePath 存在 → INSERT comic(IMPORTING) + import_task(PENDING)
    ↓
MQ: ImportTaskCreated
    ↓
Worker ImportTaskHandler: 路由到 DirectoryImportHandler
  传入 ImportContext(sourceType="MIGRATE_LQ", sourcePath=hqPath)
    ↓
DirectoryImportHandler: 核心回填逻辑
  推断 LQ 路径 (h_* → l_*) → 遍历文件 → HQ 大小 == 0 → 尝试 LQ 文件
  → 搬运到系统 HQ → 重建 metadata 修正 fileSize
    ↓
MQ: task.completed
    ↓
API ImportEventHandler: 零改动
  读 metadata → INSERT catalog/chapter/page → comic→READY
```

### 改动点清单

| 模块 | 改动 | 说明 |
|------|------|------|
| `ImportServiceImpl` | 增加 `case "MIGRATE_LQ"` | 验证 sourcePath，创建 comic + task |
| `ImportTaskHandler` | 路由分支 | 复用 `DIRECTORY` 路由到 DirectoryImportHandler，无需新增 handler |
| `DirectoryImportHandler` | **核心改动** | 空文件检测 + LQ 路径推断 + LQ 回填 + fileSize 修正 |
| `ImportContext` | **零改动** | LQ 路径推断在 Worker 层完成，无需传递 |
| `ImportEventHandler` | **零改动** | metadata 格式兼容现有逻辑 |
| `FileUrlResolver` | **零改动** | 阅读器无感知 |
| `前端` | **零改动** | 临时功能，只提供后端 API |

---

## 核心逻辑：LQ 回填

### 1. LQ 路径推断（Worker 层）

```java
// HQ: F:\games\comics\h_photograph\写真\陆萱萱
// LQ: F:\games\comics\l_photograph\写真\陆萱萱

private Path inferLqPath(Path hqPath) {
    String hqStr = hqPath.toString();
    // 将路径中第一个 h_ 前缀段替换为 l_（不区分大小写）
    String lqStr = hqStr.replaceFirst("(?i)\\\\h_([^\\\\]+)", "\\\\l_$1");
    Path lqPath = Path.of(lqStr);
    if (!Files.exists(lqPath)) {
        throw new RuntimeException("LQ 路径不存在: " + lqPath);
    }
    return lqPath;
}
```

### 2. 空文件检测与回填

`ComicMetadata.ChapterInfo` 和 `MediaInfo` 均为不可变 record，回填后需重建对象替换原列表元素。

```java
// 在 DirectoryImportHandler.handle() 的搬运循环中

Path lqRoot = "MIGRATE_LQ".equals(ctx.sourceType()) ? inferLqPath(ctx.sourcePath()) : null;

List<ComicMetadata.ChapterInfo> updatedChapters = new ArrayList<>();
for (var ch : metadata.chapters()) {
    List<ComicMetadata.MediaInfo> updatedPages = new ArrayList<>();
    for (var page : ch.pages()) {
        Path src = importRoot.resolve(ch.sourceDir()).resolve(page.fileName());
        long actualSize = Files.exists(src) ? Files.size(src) : 0;

        if (actualSize == 0 && lqRoot != null) {
            Path lqSrc = lqRoot.resolve(ch.sourceDir()).resolve(page.fileName());
            if (Files.exists(lqSrc)) {
                src = lqSrc;
                actualSize = Files.size(lqSrc);
            }
        }

        if (actualSize > 0) {
            String relativePath = comicId + "/" + ch.globalOrder() + "/" + page.fileName();
            storageService.store(src, "HQ", relativePath);
            // 重建 MediaInfo，修正 fileSize 为实际搬运大小
            updatedPages.add(new ComicMetadata.MediaInfo(
                page.fileName(), page.pageNumber(),
                "READY", page.lqStatus(),
                actualSize, page.width(), page.height(),
                page.mediaType(), page.duration(), page.container(),
                page.videoCodec(), page.audioCodec()
            ));
        } else {
            log.warn("文件无法回填: taskId={}, file={}", taskId, page.fileName());
        }
    }
    updatedChapters.add(new ComicMetadata.ChapterInfo(
        ch.title(), ch.chapterNo(), ch.sortOrder(), ch.globalOrder(),
        ch.catalogIndex(), ch.sourceDir(), updatedPages
    ));
}

// 重建 metadata，确保 writeMetadata 写入的是修正后的数据
metadata = new ComicMetadata(
    metadata.title(), metadata.author(), metadata.category(),
    metadata.tags(), metadata.catalogs(), updatedChapters
);
```

### 3. 封面生成

复用现有逻辑。由于空文件已被 LQ 回填到系统 HQ，封面图片正常可用。

---

## 错误处理

| 场景 | 行为 |
|------|------|
| HQ 文件大小 == 0，但 LQ 文件不存在 | 记录 warning，跳过该文件，metadata 不记录 |
| 目录结构不匹配（HQ 和 LQ 子目录不一致） | 按相对路径查找 LQ，找不到则跳过，记录 warning |
| LQ 路径推断失败（命名不符合 h_* 规则） | Worker 层报错，task 标记 FAILED，记录错误原因 |
| 视频文件 | HQ 为空但 LQ 无视频 → 正常跳过（符合预期） |

---

## 数据模型影响

- `page.hq_root` = "HQ"
- `page.hq_path` = 系统标准路径 `{comicId}/{chapterId}/{filename}`
- `page.lq_root` = null（初始）
- `page.lq_path` = null（初始）
- `page.hq_status` = "READY"
- `page.lq_status` = "NOT_GENERATED"
- `page.fileSize` = 实际搬运大小（来自 LQ）

阅读器请求 HQ URL → 系统返回 LQ 质量内容（但存储在 HQ 目录）。这是临时功能接受的设计妥协。

---

## 测试策略

### 单元测试
1. **LQ 路径推断**：验证 `h_photograph` → `l_photograph`，大小写不敏感。
2. **空文件检测**：`Files.size() == 0` 触发回填。
3. **fileSize 修正**：metadata 中记录的是 LQ 实际大小，不是 0。

### 冒烟测试
1. 准备临时目录 `test_h/` 和 `test_l/`，`test_h` 中放大小为 0 的图片，`test_l` 中放正常图片。
2. 调用 API 导入，验证系统 HQ 目录下文件大小正确。
3. 验证 DB 中 `page.fileSize > 0`。

### 边界情况
1. 混合场景：部分 HQ 文件非空，部分为空。
2. 空章节：某目录下所有文件都为空且 LQ 缺失 → 该章节 pages 为空，DirectoryImportHandler 正常处理（已有逻辑支持跳过空章节）。

---

## 后续清理

此功能标记为 **临时**。当所有旧系统漫画迁移完成后：
- 可以选择移除 `MIGRATE_LQ` sourceType 和相关逻辑
- 也可以选择保留（无维护成本，不影响其他 sourceType）

---

## 设计评审记录

| 问题 | 决策 |
|------|------|
| 是否修改 FileUrlResolver fallback 逻辑？ | **否**。临时功能不应改动通用链路。 |
| 是否生成 LQ 副本？ | **否**。LQ 内容直接存入系统 HQ 目录，DB 标记为 READY。 |
| 空文件阈值？ | **严格等于 0**。用户确认 HQ 文件大小严格为 0。 |
| 是否支持批量？ | **是**。复用现有 `createBatchImportTasks`，sourceType 传 "MIGRATE_LQ"。 |
| 前端是否增加 UI？ | **否**。临时功能，只提供后端 API，手动调用。 |
