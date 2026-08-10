# 导入流水线 (Import Pipeline)

**最后更新**: 2026-08-10
**状态**: 生产环境使用  
**维护者**: ComicAtlas 团队

---

## 1. 概述

ComicAtlas 采用统一导入流水线处理所有漫画来源（ZIP、目录、EHentai 等）。不同来源最终都走同一条路径：解析来源 → 目录规范化 → 生成结构化元数据 → 暂存搬文件 → 两阶段落库 → 逐章最终化确认。

统一模型的好处：新增来源只需实现一个 Handler 和可选 Parser，无需改动 API 侧落库逻辑。

**核心数据流**：

```
Acquire → ImportTask → Handler routing → DirectoryParser → DirectoryTree 
       → MetadataAssembler（目录规范化）→ ComicMetadata → 写清单+暂存文件 → metadata.json
       → task.completed → API staging 落库 → 逐章 finalize 请求 → Worker 最终化搬运
       → 逐章 finalize completed → API 全部 media READY → task FINALIZING → MetadataRefreshEvent
       → Worker 从 DB 重建 canonical metadata.json（chapterId 布局）
       → ImportMetadataRefreshCompleted → comic READY + task SUCCESS
```

---

## 2. 为什么统一导入流水线

不同来源（ZIP、REGISTER、EHENTAI、未来 Torrent）最终都需要：

1. 解析来源（文件系统或网络）
2. 生成结构化元数据（catalog/chapter/page）
3. 搬文件到统一存储（HQ/LQ/Thumbs）
4. 落库（写入 comic/catalog/chapter/page 表）

如果每个来源独立实现一套 ImportService，会导致：

- 重复落库逻辑，难以维护
- 新增来源需要改动多处代码
- 元数据格式不一致

统一流水线后：

- `DirectoryParser` 只负责解析文件系统，输出纯 `DirectoryTree`
- `MetadataAssembler` 负责业务语义转换，将 `DirectoryTree` 转为 `ComicMetadata`
- `StorageService` 负责文件生命周期，不写数据库业务表
- API Service 是数据库业务表的唯一写入方

---

## 3. 数据流详细图

```
Source (ZIP / Directory / EHentai)
         │
         ▼
ImportController (API Service)
         │
         ▼
ImportService: 创建 comic(IMPORTING) + import_task(PENDING)
         │
         ▼
MQ: task.created → import.task.queue
         │
         ▼
Worker ImportTaskHandler (消费 MQ)
         │
         ├─ sourceType="ZIP" ──────────► ZipImportHandler
         │                                    │
         │                                    ▼ 解压到 temp
         │                                    │
         │                                    ▼ 委托 DirectoryImportHandler
         │
         ├─ sourceType="DIRECTORY" ────► DirectoryImportHandler
         │  (REGISTER 的别名)               │
         │                                  ▼ DirectoryParser
         │
          └─ sourceType="EHENTAI" ─────► EhentaiDownloadService (下载→解压→委托 DirectoryImportHandler)
         
所有路径最终汇聚到 DirectoryImportHandler:
         │
         ▼
DirectoryParser.parse(sourcePath)
         │
         ▼
DirectoryTree (纯目录结构，无业务语义)
         │
         ▼
MetadataAssembler.assembleWithWarnings(tree, ctx)  ← 目录规范化
         │         （根混合→本书散页、嵌套混合→本目录散页、
         │          globalOrder DFS 1..N、sortOrder 每父作用域连续、
         │          空目录 EMPTY_DIRECTORY 警告）
         ▼
ComicMetadata (包含 catalogs + chapters + mediaItems)
         │
         ▼
MediaAnalyzer.analyze(): 图片尺寸 + ffprobe 视频元数据
         │
         ▼
写导入清单 manifest.json（imports/{taskId}/）+ 按 {comicId}/{globalOrder} 暂存搬文件
         │
         ▼
生成封面（CoverCandidateSelector 命名候选 → 图片 → 视频抽帧降级）
         │
         ▼
写 metadata.json（metadata/{taskId}.json 与 metadata/{comicId}.json）
         │
         ▼
MQ: task.completed → import.result.queue  （清单保留给最终化阶段）
         │
         ▼
API ImportEventHandler → ImportPersistenceService.persistCompleted
         │     INSERT catalog + chapter(DRAFT) + media(STAGING/PENDING)
         │     task → IMPORTING，逐章写入 Outbox finalize 请求
         ▼
MQ: import.storage.finalize.requested（逐章）
         │
         ▼
Worker ImportStorageFinalizeHandler：按清单校验尺寸 →
         │     把 hq/{comicId}/{globalOrder} 移动到 hq/{comicId}/{chapterId}
         │     发布逐章 finalize.completed，并从清单移除本章条目（清空才删清单）
         ▼
MQ: import.storage.finalize.completed（逐章）
         │
         ▼
API applyFinalizeCompleted：本章 media/chapter → READY；
         │     全部 media READY → task → FINALIZING（comic 仍 IMPORTING）
         │     发 MetadataRefreshEvent(taskId, comicId)（comic.export.metadata.refresh.requested）
         ▼
Worker MetadataRefreshHandler（taskId 非空分支）：从 DB 重建 metadata/{comicId}.json
         │     （hqPath 天然为 {comicId}/{chapterId} 最终布局，原子写入）
         ▼
MQ: import.metadata.refresh.completed / failed
         │
         ▼
API ImportMetadataRefreshResultHandler：inbox 幂等 →
              completed → comic READY + task SUCCESS
              failed → task FAILED + comic IMPORT_FAILED（可重试，旧 JSON 完整）
```

> **关键点**：`task.completed` 只代表 staging/元数据就绪，**不等于漫画可阅读**；
> 漫画进入 READY 必须等全部章节的 finalize.completed（逐章确认协议，见第 7 节）。

---

## 4. ImportTask 状态机

`ImportTaskStatus` 枚举定义在 `api-service/.../common/enums/ImportTaskStatus.java`：

```java
public enum ImportTaskStatus { 
    PENDING,     // 等待处理
    PARSING,     // 解析中（DirectoryParser 阶段）
    IMPORTING,   // 导入中（搬文件、写 metadata、staging 落库）
    FINALIZING,  // 全部章节存储最终化完成，等待 DB→JSON 元数据重建成功结果
    SUCCESS,     // 成功（终态）
    FAILED,      // 失败（终态）
    CANCELLED    // 取消（终态）
}
```

**状态转换图**：

```
PENDING
   │
   ▼
PARSING ──────────► FAILED
   │                  ▲
   ▼                  │
IMPORTING ────────────┘
   │
   ▼
FINALIZING ──────────► FAILED
   │                      ▲
   │                      │
   ▼                      │
SUCCESS ──────────────────┘

任意非终态 ──► CANCELLED (用户取消)
```

**终态**：`SUCCESS`、`FAILED`、`CANCELLED`

到达终态后不可回退到非终态。`ImportEventHandler` 中通过 `TERMINAL_STATUSES = Set.of("SUCCESS", "FAILED")` 强制约束；`FINALIZING` 作为防重标记，防止乱序/重投的 finalize.completed 重复触发收尾。

**状态推进时机**：

| 状态 | 触发方 | 时机 |
|------|--------|------|
| `PENDING` | API ImportService | 创建任务时 |
| `PARSING` | Worker ImportTaskHandler | 开始解析前 |
| `IMPORTING` | API ImportPersistenceService.persistCompleted | Worker 发布 task.completed 后 staging 落库完成（staging 就绪，等待最终化） |
| `FINALIZING` | API ImportPersistenceService | 全部章节 media 最终化 READY 后（pendingCount==0），发出 MetadataRefreshEvent 请求 DB→JSON 重建（comic 仍 IMPORTING） |
| `SUCCESS` | API ImportMetadataRefreshResultHandler | 收到 ImportMetadataRefreshCompleted 且 task==FINALIZING、comic==IMPORTING 才收尾（否则幂等跳过） |
| `FAILED` | Worker ImportTaskHandler（解析/搬运失败）、API applyFinalizeFailed（存储最终化失败）或 API ImportMetadataRefreshResultHandler（元数据重建失败） | 捕获异常 / 收到 finalize.failed / 收到 ImportMetadataRefreshFailed |
| `CANCELLED` | API ImportService | 用户主动取消时 |

> `IMPORTING` 状态横跨 staging 落库与最终化两个阶段：task.completed 后任务仍处于 IMPORTING（staging 就绪但文件未就位），直到全部 media READY 才进入 `FINALIZING` 等待元数据重建结果，重建成功才置 SUCCESS。最终化失败走 `STORAGE_FINALIZE_*` 错误码并置 FAILED（可重试）；元数据重建失败（`ImportMetadataRefreshFailed`）置 FAILED 且 comic IMPORT_FAILED，旧 JSON 完整可重试。

---

## 4.1 目录规范化规则（无损递归）

`MetadataAssembler.assembleWithWarnings` 是目录规范化的唯一入口，按结构（而非标题）递归判定节点类型：

| 节点形态 | 处理 |
|----------|------|
| 纯媒体根（根只有媒体无子目录） | 生成单个 Chapter（无 Catalog） |
| 混合根（根有媒体 + 子目录） | 生成 `catalogIndex=null` 的**本书散页** Chapter（先于所有子目录），再递归顶层子目录 |
| 嵌套混合（目录自身有媒体 + 子目录） | 生成 Catalog，先生成挂在该 Catalog 下的**本目录散页** Chapter，再递归 children |
| 纯媒体目录 | 直接生成 Chapter（目录话数，挂在父 Catalog 下，无父则 catalog_id 为 NULL） |
| 纯子目录（只有子目录无媒体） | 生成 Catalog 后递归 children |
| 空子树（无媒体且无子目录） | 不建 Catalog/Chapter，返回 `EMPTY_DIRECTORY` 警告（不中断导入） |

**排序规则**：

- `globalOrder`：按规范化 DFS 从 **1** 连续分配 1..N（全书唯一，阅读器 prev/next 依据）。
- `sortOrder`：每个父作用域独立计数器从 **0** 连续分配（作用域键 = catalogIndex，`null` 表示漫画根）。Catalog 与 Chapter 共用同一作用域计数器，保证同级内目录与章节互不重叠。
- 同名页（跨章 001.jpg）、自然排序（1 < 2 < 10）由 `DirectoryParser` 的自然路径排序保证，规范化层不重排媒体。

> 漫画根**不创建**具名 Catalog：匿名根只存在于输出模型中（`CatalogNode` 的 `id/title` 为 null），用于承载本书散页。

---

## 4.2 两阶段最终化协议（逐章确认）

导入文件不是一次到位，而是分两阶段落库，最终化按**章节独立确认**：

**Phase 1 — staging 落库（API `ImportPersistenceService.persistCompleted`）**：

1. Worker 完成解析、暂存搬运与 metadata 写出后发布 `task.completed`。
2. API 读取 metadata.json：INSERT `catalog` / `chapter(DRAFT)` / `media(STAGING, hq_status=PENDING)`，comic 保持 IMPORTING，task → IMPORTING。
3. API 在**同一事务**内为每个章节写入 Outbox 一条 `ImportStorageFinalizeRequestedEvent`（`sourceDir=hq/{comicId}/{globalOrder}`，`targetDir=hq/{comicId}/{chapterId}`，媒体映射 fileName→fileName）。

**Phase 2 — 逐章最终化（Worker `ImportStorageFinalizeHandler` + API `applyFinalizeCompleted`）**：

1. Worker 按章节消费 finalize 请求：以 `imports/{taskId}/manifest.json` 清单为尺寸基准做幂等校验（目标存在且尺寸匹配视为已完成；冲突/缺失发布失败事件），把 `hq/{comicId}/{globalOrder}` 逐文件移动到 `hq/{comicId}/{chapterId}`。
2. 每章移动/校验成功**立即**发布一次 `ImportStorageFinalizeCompletedEvent`（不等待全部章），随后 `rewriteWithoutChapter` 从清单移除本章条目；清单清空才删除（延后清理不阻断）。
3. API `applyFinalizeCompleted` 按事件 `chapterId` 将本章 media/chapter → READY，并用事件返回的 `targetDir` 修正 media.hqPath（幂等，行锁串行化防丢失更新）。
4. 仅当该 comic 下**全部 media** 都 hq_status=READY 时，进入收尾前置态：task → **FINALIZING**（comic 仍 IMPORTING），并发出 `MetadataRefreshEvent(taskId, comicId)` 请求 Worker 从 DB 重建 canonical metadata.json。
5. 任一章节最终化失败（`STORAGE_FINALIZE_MANIFEST_MISSING` / `SIZE_CONFLICT` / `CONFLICT` / `SOURCE_MISSING` 等）→ API `applyFinalizeFailed` 置 task FAILED、comic IMPORT_FAILED（可重试；重试会清空章节/媒体结构并重置状态）。

**Phase 3 — canonical metadata 重建（Worker `MetadataRefreshHandler` + API `ImportMetadataRefreshResultHandler`）**：

1. Worker 消费 `MetadataRefreshEvent`（taskId 非空分支）：从 DB 重建 `metadata/{comicId}.json`，hqPath 天然为 `{comicId}/{chapterId}` 最终布局，原子写入后发布 `ImportMetadataRefreshCompletedEvent` / `ImportMetadataRefreshFailedEvent`（comic.import exchange）。
2. API `ImportMetadataRefreshResultHandler` inbox 幂等消费：completed → 校验 task==FINALIZING 且 comic==IMPORTING 后收尾（comic → READY、task → SUCCESS、统计重算、管理任务项 SUCCEEDED、缓存失效）；failed → task FAILED、comic IMPORT_FAILED（旧 JSON 完整，可重试）。
3. 状态不符（乱序/重投）按幂等跳过，不重复收尾。

> 导入期 Worker 写出的 `metadata.json`（globalOrder 暂存布局）只是 staging 落库的中间载体；**canonical metadata 永远是最终化后由 DB 重建的 chapterId 布局**，供灾难恢复与转码/刷新重建复用。

**关键约束**：

- **清单生命周期**：`DirectoryImportHandler` 不删除清单（恢复点）。清单在最终化阶段供尺寸校验，由最终化 handler 逐章移除、清空后删除。提前删除会导致 sourceDir≠targetDir 时 `MANIFEST_MISSING`，或 sourceDir==targetDir（chapterId==globalOrder）时静默跳过而不发布 Completed（漫画卡 IMPORTING）。
- `chapterId == globalOrder` 时（暂存即最终位置）无需移动文件，仅校验存在与尺寸并照常发布 Completed。
- Worker 最终化 handler 不访问 MySQL；结果一律经 MQ 事件回传 API。

---

## 4.3 封面候选降级

封面由 `CoverCandidateSelector` 在 Worker 端从全部媒体中按固定优先级排序，`CoverGenerator` 逐个候选尝试生成：

| 优先级 | 候选 |
|--------|------|
| 0..4 | 命名表精确匹配：`cover(0)`、`封面(1)`、`表紙(2)`、`front(3)`、`folder(4)` |
| 5 | 全书图片兜底（自然顺序） |
| 6 | 视频兜底（按 globalOrder → pageNumber 顺序抽帧） |

同一优先级内按：目录深度升序 → 自然相对路径 → globalOrder → pageNumber 排序。**降级**：单个候选生成失败（文件缺失/转换异常）保留 cause 继续下一候选；全部候选失败仅告警，不阻断导入（漫画无封面，读者端显示占位）。产物写入 `thumbs/{comicId}/cover.webp`。

---

## 4.4 DIRECTORY 扫描预览（批量导入）

导入前可对父目录做一次**扫描预览**（`POST /api/tasks/directory-scan`，异步）：

- 输出规范化候选列表：每个子目录的名称、相对路径、图片/视频/总媒体统计、`kind`（COMIC/DIRECTORY）。
- 输出可展开的**规范化预览树**（根混合/嵌套混合按第 4.1 节规则推演后的目录形态）。
- 结构化警告（`preview.warnings`）：`UNREADABLE_DIRECTORY`（目录不可读，阻断该项）、`LIMIT_EXCEEDED`、`EMPTY_DIRECTORY`、`MIXED_DIRECTORY`（图文混排提示）、`SYMLINK_SKIPPED`（符号链接跳过）等。
- 阻断项（ERROR 级警告）不可勾选；可导入项可勾选后批量提交（`POST /api/tasks/import/batch`，返回真实 batchId）。
- 旧契约扫描结果（缺少 `preview`/`warnings` 字段）前端仍渲染简版，不报错。

---

## 5. 关键模型

### 5.1 DirectoryTree

**位置**: `worker-service/.../importer/DirectoryTree.java`

**职责**: 纯文件系统结构，无业务语义。

```java
public record DirectoryTree(
    Path path,           // 目录绝对路径
    String name,         // 目录名
    List<Path> mediaFiles,   // 当前目录下的媒体文件（图片 + 视频）
    List<DirectoryTree> children  // 子目录
) {
    public boolean isLeaf() { return mediaFiles != null && !mediaFiles.isEmpty(); }
    public boolean hasChildren() { return children != null && !children.isEmpty(); }
}
```

**特点**：

- 不包含 Catalog/Chapter 概念
- 只记录目录结构和媒体文件列表（图片 + 视频）
- 由 `DirectoryParser` 生成

### 5.2 ComicMetadata

**位置**: `worker-service/.../media/ComicMetadata.java`

**职责**: 包含业务语义的漫画元数据。

```java
public record ComicMetadata(
    String title,
    String author,
    String category,     // 分类（可选）
    List<String> tags,
    List<CatalogInfo> catalogs,  // 目录树
    List<ChapterInfo> chapters   // 章节列表
) {
    public record CatalogInfo(
        String title,
        int sortOrder,
        Integer parentIndex  // catalogs 列表索引，非 DB 主键
    ) {}
    
    public record ChapterInfo(
        String title,
        String chapterNo,
        int sortOrder,
        int globalOrder,     // 全书阅读顺序
        Integer catalogIndex,  // 所属 catalog 索引
        String sourceDir,    // 源目录相对路径
        List<MediaInfo> pages
    ) {}
    
    public record MediaInfo(
        String fileName,     // 媒体文件名（图片 + 视频）
        int pageNumber,
        String hqStatus,
        String lqStatus,
        long fileSize,
        Integer width,
        Integer height,
        String mediaType,    // IMAGE / VIDEO
        BigDecimal duration,  // 视频时长（秒），仅 VIDEO
        String container,     // 视频容器，仅 VIDEO
        String videoCodec,    // 视频编码，仅 VIDEO
        String audioCodec     // 音频编码，仅 VIDEO
    ) {}
}
```

**特点**：

- 包含 Catalog/Chapter 层级关系
- `globalOrder` 决定全书阅读顺序
- `parentIndex` / `catalogIndex` 是列表索引，落库时转换为 DB 主键
- 媒体字段 `mediaType` 区分图片（IMAGE）与视频（VIDEO），视频条目额外带 `duration` / `container` / `videoCodec` / `audioCodec`

### 5.3 ImportContext

**位置**: `worker-service/.../importer/ImportContext.java`

**职责**: 导入上下文，记录来源信息。

```java
public record ImportContext(
    String sourceType,   // ZIP / DIRECTORY / EHENTAI
    Path sourcePath,     // 来源路径
    boolean generateLq,  // 是否生成 LQ
    boolean overwrite,   // 是否覆盖
    String titleHint     // 标题提示（ZIP 解压时使用）
) {}
```

---

## 6. 模块职责边界

| 模块 | 输入 | 输出 | 关键约束 |
|------|------|------|----------|
| `DirectoryParser` | 文件系统目录路径 | `DirectoryTree` | 不识别 ZIP/EHentai 等特殊语义 |
| `MetadataAssembler` | `DirectoryTree` + `ImportContext` | `ComicMetadata` + 结构化警告 | 决定 catalog/chapter/page 组织（规范化规则见 4.1） |
| `DirectoryImportHandler` | `ImportContext` + taskId + comicId | `metadata.json` 路径 | 委托 Parser/Assembler，写清单、暂存搬文件、生成封面；**不删除清单** |
| `ImportStorageFinalizeHandler` | `ImportStorageFinalizeRequestedEvent`（逐章） | 逐章 `ImportStorageFinalizeCompletedEvent` | 按清单校验尺寸，移动 `{globalOrder}`→`{chapterId}`，逐章移除清单条目；不访问 MySQL |
| `ZipImportHandler` | ZIP 文件路径 | 委托 `DirectoryImportHandler` | 解压到 temp，清理临时文件 |
| `StorageService` | 源文件 + 目标路径 | 文件存储到 HQ/LQ/Thumbs | 不写 DB 业务表；路径经 `StorageRoot.resolve()` 双重防线校验 |
| `ImportEventHandler` | `task.completed` 事件 + `metadata.json` | DB 记录（staging） | 不碰文件系统 |
| `ImportPersistenceService` | completed / finalize.completed / finalize.failed / metadata 重建结果事件 | DB 状态推进 | 事务内只做 DB 与路径运算，不做文件 IO；全部 media READY → task FINALIZING + MetadataRefreshEvent，重建成功结果后才 comic READY / task SUCCESS |
| `MetadataRefreshHandler` | `MetadataRefreshEvent`（taskId 非空分支） | `ImportMetadataRefreshCompletedEvent` / `ImportMetadataRefreshFailedEvent` | 从 DB 重建 `metadata/{comicId}.json`（chapterId 最终布局，原子写入）；不访问 MySQL 写 |
| `ImportMetadataRefreshResultHandler` | metadata 重建 completed / failed 事件 | comic/task 收尾或失败 | inbox 幂等；completed → 校验 task==FINALIZING + comic==IMPORTING 才收尾 |

**禁止**：

- Worker 直接写 MySQL → 全部通过 MQ 事件回 API
- `DirectoryParser` 注入业务语义 → 只输出纯目录树
- `MetadataAssembler` 碰文件系统 → 只转换数据结构
- API Service 碰文件系统 → 只读 `metadata.json`

---

## 7. 时序图

```
┌─────────────┐  ┌──────────────┐  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐
│ API Service │  │ RabbitMQ     │  │ Worker      │  │ Storage      │  │ MySQL       │
└──────┬──────┘  └──────┬───────┘  └──────┬──────┘  └──────┬───────┘  └──────┬──────┘
       │                │                 │                │                 │
       │ 1. POST /tasks/import            │                │                 │
       │────────────────>                 │                │                 │
       │                │                 │                │                 │
       │ 2. INSERT comic(IMPORTING)       │                │                 │
       │   INSERT import_task(PENDING)    │                │                 │
       │──────────────────────────────────────────────────────────────────>│
       │                │                 │                │                 │
       │ 3. Publish task.created          │                │                 │
       │───────────────>│                 │                │                 │
       │                │ 4. Consume task.created          │                 │
       │                │────────────────>│                │                 │
       │                │                 │ 5. 规范化+暂存：写清单、搬文件到    │
       │                │                 │    hq/{comicId}/{globalOrder}、    │
       │                │                 │    生成封面、写 metadata            │
       │                │                 │───────────────>│                 │
       │                │                 │                │                 │
       │                │ 6. Publish task.completed（清单保留）│                │
       │                │<────────────────│                │                 │
       │                │                 │                │                 │
       │                │ 7. Consume task.completed          │                │
       │                │─────────────────────────────────────────────────>│
       │                │                 │                │ 8. staging 落库：│
       │                │                 │                │    catalog+chapter(DRAFT)+│
       │                │                 │                │    media(STAGING/PENDING)，│
       │                │                 │                │    task→IMPORTING         │
       │                │                 │                │────────────────>│
       │                │                 │                │                 │
       │                │ 9. Publish finalize.requested（逐章，Outbox）│        │
       │                │<────────────────│                │                 │
       │                │                 │                │                 │
       │                │ 10. Consume finalize.requested    │                │
       │                │────────────────>│                │                 │
       │                │                 │ 11. 按清单校验尺寸，移动             │
       │                │                 │     hq/{globalOrder}→hq/{chapterId} │
       │                │                 │───────────────>│                 │
       │                │                 │                │                 │
       │                │ 12. Publish finalize.completed（逐章）│             │
       │                │<────────────────│                │                 │
       │                │                 │                │                 │
        │                │ 13. Consume finalize.completed     │                │
        │                │─────────────────────────────────────────────────>│
        │                │                 │                │ 14. 本章 media/chapter→READY│
        │                │                 │                │     全部 media READY 时     │
        │                │                 │                │     task→FINALIZING，发    │
        │                │                 │                │     MetadataRefreshEvent   │
        │                │                 │                │────────────────>│
        │                │                 │                │                 │
        │                │ 15. Consume metadata.refresh.requested（taskId 非空）│
        │                │────────────────>│                │                 │
        │                │                 │ 16. 从 DB 重建 metadata/{comicId}.json│
        │                │                 │     （chapterId 最终布局，原子写入）    │
        │                │                 │───────────────>│                 │
        │                │                 │                │                 │
        │                │ 17. Publish import.metadata.refresh.completed│       │
        │                │<────────────────│                │                 │
        │                │                 │                │                 │
        │                │ 18. Consume completed → inbox 幂等  │               │
        │                │─────────────────────────────────────────────────>│
        │                │                 │                │ 19. comic→READY，│
        │                │                 │                │     task→SUCCESS │
        │                │                 │                │────────────────>│
        │                │                 │                │                 │
```

---

## 8. SourceType 路由

`ImportTaskHandler` 根据 `sourceType` 路由到不同 Handler：

```java
switch (sourceType) {
    case "ZIP" -> {
        ImportContext ctx = new ImportContext("ZIP", Path.of(normalizedPath), false, false);
        zipHandler.importZip(ctx, taskId, comicId, mangaRoot);
    }
    case "DIRECTORY" -> {  // 旧 REGISTER 已由 V17 迁移为 DIRECTORY
        if (normalizedPath == null) throw new IllegalArgumentException("DIRECTORY 需要 sourcePath");
        ImportContext ctx = new ImportContext("DIRECTORY", Path.of(normalizedPath), false, false);
        directoryHandler.handle(ctx, taskId, comicId, mangaRoot);
    }
    case "EHENTAI" -> {
        Path sourceDir = ehentaiDownloadService.downloadToSourceDir(taskId, sourcePath);
        directoryHandler.handle(new ImportContext("EHENTAI", sourceDir, false, false),
                taskId, comicId, mangaRoot);
    }
    default -> throw new IllegalArgumentException("Unknown sourceType: " + sourceType);
}
```

**注意**：`"REGISTER"` 来源类型已由迁移脚本 `V17__source_type_register_to_directory.sql` 统一迁移为 `"DIRECTORY"`（同一目录导入逻辑）。

**SourceType 枚举**（`api-service/.../common/enums/SourceType.java`）：

```java
public enum SourceType { ZIP, DIRECTORY, EHENTAI }
```

---

## 9. metadata.json 结构

Worker 写入 `MANGA_ROOT/metadata/{taskId}.json` 与 `MANGA_ROOT/metadata/{comicId}.json`（同一内容），API 读取后 staging 落库；`{comicId}.json` 同时是 DB 记录丢失后 RecoveryEngine 的恢复依据。

> **canonical metadata**：导入期 Worker 写出的 `{comicId}.json` 为 globalOrder 暂存布局，仅服务 staging 落库；全部章节最终化 READY 后，API 经 `MetadataRefreshEvent(taskId, comicId)` 请求 Worker 从 DB 重建 `{comicId}.json`（hqPath 天然为 `{comicId}/{chapterId}` 最终布局，原子写入）。此后 `{comicId}.json` 才是灾难恢复与转码/刷新重建共用的 canonical 副本。

**结构示例**（metadata v3）：

```json
{
  "version": 3,
  "comic": {
    "title": "漫画标题",
    "author": "作者",
    "category": "漫画",
    "tags": ["tag1", "tag2"]
  },
  "catalogs": [
    {
      "title": "目录1",
      "sortOrder": 0,
      "parentIndex": null
    },
    {
      "title": "子目录",
      "sortOrder": 1,
      "parentIndex": 0
    }
  ],
  "chapters": [
    {
      "title": "章节1",
      "chapterNo": "1",
      "sortOrder": 0,
      "globalOrder": 1,
      "catalogIndex": 1,
      "sourceDir": "vol1/ch1",
      "mediaItems": [
        {
          "fileName": "001.jpg",
          "pageNumber": 1,
          "hqStatus": "PENDING",
          "lqStatus": "NOT_GENERATED",
          "fileSize": 123456,
          "mediaType": "IMAGE",
          "width": 800,
          "height": 1200,
          "hqPath": "1/5/001.jpg"
        }
      ]
    }
  ]
}
```

**字段说明**：

- `version`: 元数据版本号，当前为 3（v3 用 `mediaItems[].fileName`，v2 用 `pages[].imageName`）
- `catalogs[].parentIndex`: catalogs 列表索引，落库时转换为 `parent_id`
- `chapters[].catalogIndex`: 所属 catalog 索引，落库时转换为 `catalog_id`；`null` 表示本书散页（漫画根层）
- `chapters[].globalOrder`: 全书阅读顺序（DFS 1..N），决定 prev/next 章节
- `mediaItems[].fileName`: 媒体文件名（图片或视频，保留原始文件名）
- `mediaItems[].mediaType`: 媒体类型（`IMAGE` / `VIDEO`）
- `mediaItems[].hqPath`: **导入期暂存布局** `{comicId}/{globalOrder}/{fileName}`；最终化后 DB 中的 `page.hq_path` 由 API 按事件 `targetDir` 修正为 `{comicId}/{chapterId}/{fileName}`。canonical metadata 由 DB 重建，重建后的 `metadata/{comicId}.json` 中 hqPath 即最终布局 `{comicId}/{chapterId}/{fileName}`（导入期暂存布局只在重建前短暂存在）
- `mediaItems[].hqStatus` / `lqStatus`: 文件状态（staging 期 PENDING / NOT_GENERATED）
- `mediaItems[].width` / `height`: 图片尺寸；视频条目额外带 `duration` / `container` / `videoCodec` / `audioCodec`

---

## 10. 错误处理

**Worker 侧**：

- 捕获异常后发布 `task.failed` 事件
- 根据异常类型分类：`ZIP_ERROR`、`PARSE_ERROR`、`COPY_ERROR`、`UNKNOWN_ERROR`
- 消息 reject（不重入队列）

**API 侧**：

- 消费 `task.failed` 事件，更新 `import_task.status = FAILED`
- 记录 `error_message`
- 幂等性：通过 Redis key + DB 状态双重检查

**取消机制**：

- 用户取消时，API 设置 `import_task.status = CANCELLED`
- Worker 在关键节点检查 `CancelHandler.isCancelled(taskId)`
- 若已取消，抛出异常中断流程

---

## 11. 参考

- **项目知识库**: `AGENTS.md`
- **系统全景**: `docs/architecture/01-system-overview.md`
- **存储模型**: `docs/architecture/03-storage.md`
- **ADR**: `docs/architecture/adr/0001-unified-import-pipeline.md`
- **API 文档**: `docs/api.md`
- **数据库 Schema**: `docs/database/schema.md`
