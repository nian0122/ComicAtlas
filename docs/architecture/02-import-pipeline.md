# 导入流水线 (Import Pipeline)

**最后更新**: 2026-07-16  
**状态**: 生产环境使用  
**维护者**: ComicAtlas 团队

---

## 1. 概述

ComicAtlas 采用统一导入流水线处理所有漫画来源（ZIP、目录、EHentai 等）。不同来源最终都走同一条路径：解析来源 → 生成结构化元数据 → 搬文件 → 落库。

统一模型的好处：新增来源只需实现一个 Handler 和可选 Parser，无需改动 API 侧落库逻辑。

**核心数据流**：

```
Acquire → ImportTask → Handler routing → DirectoryParser → DirectoryTree 
       → MetadataAssembler → ComicMetadata → StorageService → metadata.json 
       → API Consumer → Database
```

---

## 2. 为什么统一导入流水线

不同来源（ZIP、DIRECTORY、EHENTAI、未来 Torrent）最终都需要：

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
         ├─ sourceType="REGISTER" ─────► DirectoryImportHandler
         │  (或 "DIRECTORY" 别名)           │
         │                                  ▼ DirectoryParser
         │
         └─ sourceType="EHENTAI" ─────► FileService (独立流程)
         
所有路径最终汇聚到 DirectoryImportHandler:
         │
         ▼
DirectoryParser.parse(sourcePath)
         │
         ▼
DirectoryTree (纯目录结构，无业务语义)
         │
         ▼
MetadataAssembler.assemble(tree, ctx)
         │
         ▼
ComicMetadata (包含 catalogs + chapters + pages)
         │
         ▼
StorageService.store(): 搬文件到 HQ/{comicId}/{chapterId}/
         │
         ▼
writeMetadata(): 写 metadata.json 到 MANGA_ROOT/metadata/{taskId}.json
         │
         ▼
MQ: task.success → import.result.queue
         │
         ▼
API ImportEventHandler (消费 MQ)
         │
         ▼
读取 metadata.json → INSERT catalog/chapter/page → UPDATE comic(READY)
```

---

## 4. ImportTask 状态机

`ImportTaskStatus` 枚举定义在 `api-service/.../common/enums/ImportTaskStatus.java`：

```java
public enum ImportTaskStatus { 
    PENDING,    // 等待处理
    PARSING,    // 解析中（DirectoryParser 阶段）
    IMPORTING,  // 导入中（搬文件、写 metadata）
    SUCCESS,    // 成功（终态）
    FAILED      // 失败（终态）
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
SUCCESS

任意非终态 ──► CANCELLED (用户取消)
```

**终态**：`SUCCESS`、`FAILED`、`CANCELLED`

到达终态后不可回退到非终态。`ImportEventHandler` 中通过 `TERMINAL_STATUSES = Set.of("SUCCESS", "FAILED")` 强制约束。

**状态推进时机**：

| 状态 | 触发方 | 时机 |
|------|--------|------|
| `PENDING` | API ImportService | 创建任务时 |
| `PARSING` | Worker ImportTaskHandler | 开始解析前 |
| `IMPORTING` | Worker DirectoryImportHandler | 解析完成，开始搬文件（可选，当前代码未显式设置） |
| `SUCCESS` | API ImportEventHandler | 读取 metadata.json 并落库成功后 |
| `FAILED` | Worker ImportTaskHandler | 捕获异常时 |
| `CANCELLED` | API ImportService | 用户主动取消时 |

---

## 5. 关键模型

### 5.1 DirectoryTree

**位置**: `worker-service/.../file/parse/DirectoryTree.java`

**职责**: 纯文件系统结构，无业务语义。

```java
public record DirectoryTree(
    Path path,           // 目录绝对路径
    String name,         // 目录名
    List<Path> imageFiles,  // 图片文件列表
    List<DirectoryTree> children  // 子目录
) {
    public boolean isLeaf() { return children.isEmpty(); }
    public boolean hasChildren() { return !children.isEmpty(); }
}
```

**特点**：

- 不包含 Catalog/Chapter 概念
- 只记录目录结构和图片列表
- 由 `DirectoryParser` 生成

### 5.2 ComicMetadata

**位置**: `worker-service/.../file/parse/ComicMetadata.java`

**职责**: 包含业务语义的漫画元数据。

```java
public record ComicMetadata(
    String title,
    String author,
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
        List<PageInfo> pages
    ) {}
    
    public record PageInfo(
        String imageName,
        int pageNumber,
        String hqStatus,
        String lqStatus,
        long fileSize,
        Integer width,
        Integer height
    ) {}
}
```

**特点**：

- 包含 Catalog/Chapter 层级关系
- `globalOrder` 决定全书阅读顺序
- `parentIndex` / `catalogIndex` 是列表索引，落库时转换为 DB 主键

### 5.3 ImportContext

**位置**: `worker-service/.../file/parse/ImportContext.java`

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
| `MetadataAssembler` | `DirectoryTree` + `ImportContext` | `ComicMetadata` | 决定 catalog/chapter/page 组织 |
| `DirectoryImportHandler` | `ImportContext` + taskId + comicId | `metadata.json` 路径 | 委托 Parser/Assembler，搬文件 |
| `ZipImportHandler` | ZIP 文件路径 | 委托 `DirectoryImportHandler` | 解压到 temp，清理临时文件 |
| `StorageService` | 源文件 + 目标路径 | 文件存储到 HQ/LQ/Thumbs | 不写 DB 业务表 |
| `ImportEventHandler` | `task.success` 事件 + `metadata.json` | DB 记录 | 不碰文件系统 |

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
       │                │                 │                │                 │
       │                │ 4. Consume task.created          │                 │
       │                │────────────────>│                │                 │
       │                │                 │                │                 │
       │                │                 │ 5. Update status: PARSING        │
       │                │<────────────────│                │                 │
       │                │                 │                │                 │
       │                │                 │ 6. DirectoryParser.parse()       │
       │                │                 │───────────────>│                 │
       │                │                 │<───────────────│                 │
       │                │                 │  DirectoryTree  │                 │
       │                │                 │                │                 │
       │                │                 │ 7. MetadataAssembler.assemble()  │
       │                │                 │───────────────>│                 │
       │                │                 │<───────────────│                 │
       │                │                 │  ComicMetadata  │                 │
       │                │                 │                │                 │
       │                │                 │ 8. StorageService.store()        │
       │                │                 │───────────────>│                 │
       │                │                 │                │ 搬文件到 HQ     │
       │                │                 │<───────────────│                 │
       │                │                 │                │                 │
       │                │                 │ 9. writeMetadata()               │
       │                │                 │───────────────>│                 │
       │                │                 │                │ 写 metadata.json│
       │                │                 │<───────────────│                 │
       │                │                 │                │                 │
       │                │ 10. Publish task.success         │                │
       │                │<────────────────│                │                 │
       │                │                 │                │                 │
       │                │ 11. Consume task.success         │                │
       │                │─────────────────────────────────────────────────>│
       │                │                 │                │                 │
       │                │                 │                │ 12. 读取 metadata.json
       │                │                 │                │<────────────────│
       │                │                 │                │                 │
       │                │                 │                │ 13. INSERT catalog/chapter/page
       │                │                 │                │────────────────>│
       │                │                 │                │                 │
       │                │                 │                │ 14. UPDATE comic(READY)
       │                │                 │                │────────────────>│
       │                │                 │                │                 │
       │                │                 │                │ 15. UPDATE import_task(SUCCESS)
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
    case "REGISTER", "DIRECTORY" -> {  // DIRECTORY 是 REGISTER 的别名
        if (normalizedPath == null) throw new IllegalArgumentException("DIRECTORY 需要 sourcePath");
        ImportContext ctx = new ImportContext("DIRECTORY", Path.of(normalizedPath), false, false);
        directoryHandler.handle(ctx, taskId, comicId, mangaRoot);
    }
    case "EHENTAI" -> fileService.processImport(taskId, comicId, sourcePath, sourceType);
    default -> throw new IllegalArgumentException("Unknown sourceType: " + sourceType);
}
```

**注意**：`"DIRECTORY"` 在 Worker 侧作为 `"REGISTER"` 的别名处理，两者走同一逻辑。

**SourceType 枚举**（`api-service/.../common/enums/SourceType.java`）：

```java
public enum SourceType { ZIP, REGISTER, EHENTAI }
```

---

## 9. metadata.json 结构

Worker 写入 `MANGA_ROOT/metadata/{taskId}.json`，API 读取后落库。

**结构示例**：

```json
{
  "version": 2,
  "comic": {
    "title": "漫画标题",
    "author": "作者",
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
      "globalOrder": 0,
      "catalogIndex": 1,
      "sourceDir": "vol1/ch1",
      "pages": [
        {
          "imageName": "001.jpg",
          "pageNumber": 1,
          "hqStatus": "READY",
          "lqStatus": "PENDING",
          "fileSize": 123456,
          "width": 800,
          "height": 1200
        }
      ]
    }
  ]
}
```

**字段说明**：

- `version`: 元数据版本号，当前为 2
- `catalogs[].parentIndex`: catalogs 列表索引，落库时转换为 `parent_id`
- `chapters[].catalogIndex`: 所属 catalog 索引，落库时转换为 `catalog_id`
- `chapters[].globalOrder`: 全书阅读顺序，决定 prev/next 章节
- `pages[].hqStatus`: HQ 文件状态（READY/MISSING/PENDING）
- `pages[].lqStatus`: LQ 文件状态（NOT_GENERATED/PENDING/READY/FAILED）

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
