# ComicAtlas 项目文档重构 — 第一阶段设计

**日期**: 2026-07-16
**主题**: 建立 Canonical Architecture 文档体系，同步 API 与数据库文档
**策略**: 分阶段更新（B）+ 第一阶段建立 Canonical Architecture（C）
**状态**: 设计待实现

---

## 1. 背景与目标

最近几周 ComicAtlas 的导入架构、存储模型和任务模型发生较大变化：

- 导入模型统一为 `Acquire → ImportTask → HandlerFactory → DirectoryParser → MetadataAssembler → ComicMetadata → StorageManager → ComicImported`
- `DirectoryParser` 与 `MetadataAssembler` 职责分离
- `Page` 增加 `hq_status` / `lq_status` / `hq_size` / `lq_size`
- `Comic` 增加 `description`、`cover_path` 等字段
- 新增管理功能：元数据编辑、标签、搜索、封面、存储扫描恢复

现有文档分散在 `docs/superpowers/specs/`、`docs/api.md`、`docs/frontend/` 等位置，缺少一份面向维护者的权威架构手册，导致：

1. 同一流程在 API 文档、Worker 文档、Spec 中重复描述，容易不一致。
2. 数据库字段和状态枚举没有集中说明，新开发者需要翻代码。
3. URL、存储策略等细节散落在不同文档中。

本阶段目标：

> 建立 `docs/architecture/` 作为 Canonical 架构层，同步 `docs/api.md` 和 `docs/database/schema.md`，让后续 Worker、Reader、Admin、前端文档都能引用它，而不是重复描述。

---

## 2. 文档目录结构（第一阶段）

```text
docs/
├── architecture/                          # 新增：Canonical 架构层
│   ├── 01-system-overview.md              # 模块全景 + 职责边界
│   ├── 02-import-pipeline.md              # 导入流水线（核心事实来源）
│   ├── 03-storage.md                      # 存储模型 + 存储策略
│   └── adr/
│       └── 0001-unified-import-pipeline.md # ADR：统一导入流水线
├── api.md                                 # 同步：/tasks/import、/tasks/scan、状态机
├── database/
│   └── schema.md                          # 新增：表、字段、ER、状态枚举
├── frontend/                              # 本次不动
├── worker/                                # 本次不动
├── issues/                                # 本次不动
├── release/                               # 本次不动
└── testing/                               # 本次不动
```

### 说明

- `docs/superpowers/specs/2026-07-01-ComicAtlas-core-architecture.md` 保留为历史 spec，新的 `docs/architecture/` 是面向维护者的权威手册。
- 后续 Worker/Reader/Admin/前端文档在涉及“为什么这样设计”时，链接到 `architecture/02-import-pipeline.md` 或 `03-storage.md`，不再重复流程。

---

## 3. Canonical Architecture 文档

### 3.1 `01-system-overview.md`

目标：让新开发者 5 分钟理解系统由哪些模块组成、各自职责、边界在哪里。

内容：

1. **系统分层**
   - API Service：HTTP API、MQ 消费、数据库写入
   - Worker Service：文件处理、MQ 消费、存储写入
   - Gateway：路由、Nacos 发现
   - Frontend：Vue3 管理界面与阅读器
   - Infrastructure：MySQL、Redis、RabbitMQ、Nginx

2. **模块职责表**

| 模块 | 职责 | 不做什么 |
|------|------|----------|
| `ImportController` | 接收导入请求，创建 `ImportTask` | 不碰文件系统 |
| `ImportService` | 任务持久化、状态推进 | 不解析文件 |
| `ImportTaskHandler` (Worker) | 消费 MQ，路由到具体 Handler | 不写数据库 |
| `HandlerFactory` | 按 `sourceType` 创建 Handler | 不执行业务逻辑 |
| `ZipImportHandler` | 解压 ZIP 到 temp | 不解析漫画语义 |
| `DirectoryImportHandler` | 委托 DirectoryParser 处理目录 | 不解析漫画语义 |
| `DirectoryParser` | 输出纯目录树 `DirectoryTree` | 不了解 Catalog/Chapter 语义 |
| `MetadataAssembler` | `DirectoryTree` → `ComicMetadata` | 不碰文件系统 |
| `StorageManager` | 文件布局、复制/移动、Root 管理、metadata 持久化、HQ/LQ/Thumbs 输出 | 不写数据库业务表 |
| `ImportEventHandler` (API) | 消费完成事件，写 catalog/chapter/page | 不碰文件系统 |
| `ReaderService` | 按 `global_order` 取 prev/next | 不生成图片 |
| `FileUrlResolver` | `Page` → URL | 不管理物理文件 |

3. **数据流总图**（ASCII/Mermaid）

```text
Source (ZIP / Directory / EHentai / Torrent)
        │
        ▼
ImportController / ImportService
        │
        ▼
ImportTask (DB + MQ task.created)
        │
        ▼
Worker ImportTaskHandler
        │
        ▼
HandlerFactory
        │
        ├── ZipImportHandler ──► 解压 ──┐
        └── DirectoryImportHandler ─────┤
                                      ▼
                            DirectoryParser ──► DirectoryTree
                                      │
                                      ▼
                            MetadataAssembler ──► ComicMetadata
                                      │
                                      ▼
                            StorageManager ──► HQ / LQ / Thumbs + metadata.json
                                      │
                                      ▼
                            MQ task.completed
                                      │
                                      ▼
                            API ImportEventHandler ──► DB (comic/catalog/chapter/page)
```

4. **核心设计原则**
   - `DirectoryParser` 只负责解析文件系统，不了解业务语义。
   - `MetadataAssembler` 负责业务语义转换，将 `DirectoryTree` 转为 `ComicMetadata`。
   - `StorageManager` 负责文件生命周期，不写数据库业务表。
   - API Service 是数据库业务表的唯一写入方。

---

### 3.2 `02-import-pipeline.md`

目标：描述导入流水线的完整链路，作为所有导入相关文档的事实来源。

内容：

1. **为什么统一导入流水线**
   - 不同来源（ZIP、DIRECTORY、EHENTAI、未来 Torrent）最终都需要：解析来源 → 生成结构化元数据 → 搬文件 → 落库。
   - 统一模型后，新增来源只需实现一个 Handler 和可选 Parser，无需改动 API 侧落库逻辑。

2. **数据流详细图**

```text
Acquire
    │
    ▼
ImportTask
    │
    ▼
HandlerFactory
    │
    ├── ZipImportHandler
    │         │
    │         ▼ 解压到 temp
    │         │
    │         ▼ DirectoryParser
    │
    ├── DirectoryImportHandler
    │         │
    │         ▼ DirectoryParser
    │
    └── EHentaiImportHandler (future)
              │
              ▼ DirectoryParser / WebParser
              │
              ▼ MetadataAssembler
              │
              ▼ StorageManager
              │
              ▼ ComicImported
              │
              ▼ API Consumer
              │
              ▼ Database
```

3. **关键模型**

- `DirectoryTree`：纯文件系统结构，无业务语义。
- `ComicMetadata`：包含 `comic`、`catalogs`、`chapters`、`pages` 的业务模型。
- `ImportTask`：领域任务，记录 `sourceType`、`sourcePath`、`status`、`progress`。

4. **模块职责边界**

| 模块 | 输入 | 输出 | 关键约束 |
|------|------|------|----------|
| `DirectoryParser` | 文件系统目录路径 | `DirectoryTree` | 不识别 ZIP/EHentai 等特殊语义 |
| `MetadataAssembler` | `DirectoryTree` + 可选元数据 | `ComicMetadata` | 决定 catalog/chapter/page 组织 |
| `StorageManager` | `ComicMetadata` + 源文件 | HQ/LQ/Thumbs + metadata.json | 不写 DB 业务表 |
| `ImportEventHandler` | `task.completed` 事件 + metadata.json | DB 记录 | 不碰文件系统 |

5. **ImportTask 状态机**

```text
PENDING
   │
   ▼
PARSING        ──► FAILED
   │
   ▼
PROCESSING     ──► FAILED
   │
   ▼
COMPLETED
```

6. **时序图**

- API 创建 ImportTask → 发送 `task.created`
- Worker Handler 消费 → 解析 → 搬文件 → 写 metadata.json → 发送 `task.completed`
- API 消费 → 读 metadata.json → INSERT catalog/chapter/page → 更新 comic 状态

---

### 3.3 `03-storage.md`

目标：定义存储模型、存储策略和 StorageManager 职责，不绑定具体 URL 实现。

内容：

1. **StoragePolicy（存储策略）**

| 策略 | 说明 | 当前状态 |
|------|------|----------|
| `MANAGED` | 文件由 ComicAtlas 统一管理，搬入 HQ/LQ/Thumbs | Phase 1 使用 |
| `EXTERNAL` | 文件外部管理，DB 只存引用 | 未来预留 |
| `OBJECT_STORAGE` | 对象存储（S3/MinIO 等） | 未来预留 |

2. **MANAGED 文件布局**

```text
MANGA_ROOT/
├── hq/
│   └── {comicId}/
│       └── {globalOrder}/
│           └── {imageName}
├── lq/
│   └── {comicId}/
│       └── {globalOrder}/
│           └── {imageName}
├── thumbs/
│   └── {comicId}/
│       └── cover.jpg
└── metadata/
    └── {comicId}.json
```

3. **StorageManager 职责**

StorageManager 负责：

- 文件布局（决定文件存到哪里）
- 文件复制/移动
- Root 管理（HQ / LQ / Thumbs）
- metadata.json 持久化
- HQ / LQ / Thumbs 输出

StorageManager **不负责**：

- 写数据库业务表（comic/catalog/chapter/page）
- 生成 HTTP URL
- 决定业务语义（catalog/chapter 组织）

4. **Page 存储字段**

| 字段 | 说明 |
|------|------|
| `hq_root` | HQ 存储根的 key，如 `HQ` |
| `hq_path` | 相对路径，如 `{comicId}/{globalOrder}/{imageName}` |
| `lq_root` | LQ 存储根的 key |
| `lq_path` | LQ 相对路径 |
| `hq_status` | HQ 文件状态 |
| `lq_status` | LQ 文件状态 |
| `hq_size` | HQ 文件大小 |
| `lq_size` | LQ 文件大小 |

5. **URL 生成**

> 图片 URL 由 `StorageManager`/`FileUrlResolver` 生成。
>
> `Storage` 文档不绑定具体 URL 模式；具体 URL 规范（如 Nginx 路由映射）写在 `docs/api.md` 或 Reader 文档中。

---

### 3.4 `adr/0001-unified-import-pipeline.md`

目标：记录为什么采用统一导入流水线，而不是每个来源一套 Parser/Service。

内容：

1. **问题**
   - ZIP、Directory、EHentai、Torrent 等不同来源如何复用落库逻辑？
   - 如何避免为每个来源写一套 ImportService？

2. **决策**
   - 统一为 `Acquire → ImportTask → HandlerFactory → DirectoryParser → MetadataAssembler → ComicMetadata → StorageManager → ComicImported`。
   - `DirectoryParser` 输出纯 `DirectoryTree`，`MetadataAssembler` 负责业务语义转换。

3. **替代方案**
   - 方案 A：每个来源独立 Service（ZipImportService、DirectoryImportService、EHentaiImportService）。 rejected：重复落库逻辑，难以维护。
   - 方案 B：统一 Pipeline，来源只影响前置 Handler/Parser。 accepted。

4. **后果**
   - 新增来源只需实现 Handler + Parser，无需改动 API 落库。
   - `DirectoryTree` 成为可复用的中间结构。
   - 需要定义清晰的 `ComicMetadata` 模型。

---

## 4. `docs/api.md` 更新

目标：补充新接口、状态机，并引用架构文档。

更新内容：

1. **导入任务接口**

```http
POST /api/tasks/import
Content-Type: application/json

{
  "sourceType": "ZIP",
  "sourcePath": "D:/downloads/comic.zip"
}
```

```http
POST /api/tasks/import
Content-Type: application/json

{
  "sourceType": "DIRECTORY",
  "sourcePath": "D:/manga/temp/ComicA"
}
```

2. **扫描任务接口（如已存在）**

```http
POST /api/tasks/scan
Content-Type: application/json

{
  "sourceType": "DIRECTORY",
  "sourcePath": "D:/manga/inbox"
}
```

3. **ImportTask 状态机**

```text
PENDING → PARSING → PROCESSING → COMPLETED
              │           │
              ▼           ▼
           FAILED      FAILED
```

4. **引用架构文档**

> 完整导入流程见 `docs/architecture/02-import-pipeline.md`。
> 存储模型见 `docs/architecture/03-storage.md`。

5. **保持现有内容**
   - 漫画列表/搜索、详情、元数据编辑、标签绑定、封面、目录树、阅读、历史、LQ、仪表盘、操作日志、标签、管理（rebuild/scan-recover/db-delete）等接口保留。

---

## 5. `docs/database/schema.md` 新增

目标：集中描述数据库结构、字段语义和状态枚举。

内容：

1. **ER 图**（ASCII/Mermaid）

2. **表结构**

### `comic`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 主键 |
| `title` | VARCHAR(255) | 标题 |
| `title_jpn` | VARCHAR(255) | 日文标题（可选） |
| `author` | VARCHAR(255) | 作者 |
| `description` | TEXT | 描述 |
| `category` | VARCHAR(64) | 分类（legacy，不推荐维护） |
| `cover_path` | VARCHAR(512) | 封面相对路径 |
| `source_type` | VARCHAR(16) | ZIP / DIRECTORY / EHENTAI |
| `storage_policy` | VARCHAR(16) | MANAGED / EXTERNAL |
| `status` | VARCHAR(16) | 见 ComicStatus |
| `created_at` / `updated_at` | DATETIME | |

### `page`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 主键 |
| `chapter_id` | BIGINT | 外键 |
| `page_number` | INT | 章节内页码 |
| `hq_root` | VARCHAR(16) | HQ 存储根 key |
| `hq_path` | VARCHAR(512) | HQ 相对路径 |
| `lq_root` | VARCHAR(16) | LQ 存储根 key |
| `lq_path` | VARCHAR(512) | LQ 相对路径 |
| `hq_status` | VARCHAR(16) | 见 HQStatus |
| `lq_status` | VARCHAR(16) | 见 LQStatus |
| `file_size` / `width` / `height` | | 可选元数据 |

### `import_task`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 主键 |
| `comic_id` | BIGINT | 关联漫画（可为空） |
| `source_type` | VARCHAR(16) | ZIP / DIRECTORY / EHENTAI |
| `source_path` | VARCHAR(512) | 来源路径 |
| `status` | VARCHAR(16) | 见 ImportTaskStatus |
| `progress` | INT | 进度百分比 |
| `error_message` | TEXT | 错误信息 |

3. **状态枚举**

### ComicStatus

| 状态 | 含义 |
|------|------|
| `IMPORTING` | 导入中 |
| `READY` | 可阅读 |
| `FAILED` | 导入失败 |
| `PLACEHOLDER` | 占位漫画（扫描恢复创建，待补充信息） |
| `DELETED` | 已删除（逻辑删除） |

### HQStatus

| 状态 | 含义 |
|------|------|
| `READY` | HQ 文件存在且可用 |
| `MISSING` | HQ 文件缺失 |
| `PENDING` | 状态尚未确认 |

### LQStatus

| 状态 | 含义 |
|------|------|
| `READY` | LQ 已生成 |
| `PENDING` | LQ 待生成 |
| `FAILED` | LQ 生成失败 |
| `NOT_GENERATED` | 不自动生成 LQ |

### ImportTaskStatus

| 状态 | 含义 |
|------|------|
| `PENDING` | 等待处理 |
| `PARSING` | 解析中 |
| `PROCESSING` | 处理中（搬文件、生成缩略图等） |
| `COMPLETED` | 完成 |
| `FAILED` | 失败 |
| `CANCELLED` | 已取消 |

---

## 6. 第一阶段 Done 条件

- [ ] `docs/architecture/01-system-overview.md` 完成，包含模块职责表和数据流总图。
- [ ] `docs/architecture/02-import-pipeline.md` 完成，明确 `DirectoryParser → DirectoryTree → MetadataAssembler → ComicMetadata` 职责分离。
- [ ] `docs/architecture/03-storage.md` 完成，包含 StoragePolicy、MANAGED 布局、StorageManager 职责边界，不绑定具体 URL。
- [ ] `docs/architecture/adr/0001-unified-import-pipeline.md` 完成。
- [ ] `docs/api.md` 同步 `/tasks/import`、`/tasks/scan`、ImportTask 状态机，并引用 Architecture 文档。
- [ ] `docs/database/schema.md` 新增，包含表结构、字段、ER 图、状态枚举。
- [ ] 其他现有文档（frontend/worker/reader/admin）在本次只补充指向 Architecture 的引用，不重复描述导入流程。

---

## 7. 明确不在第一阶段

以下放到后续阶段：

- `docs/worker/` 详细 Handler 文档
- `docs/reader/` HQ/LQ 状态、URL 规范细化
- `docs/frontend/` 页面文档更新（Library、Detail、Reader、Task Center）
- `docs/release/` 更新
- `README.md` / 开发规范 / Code Style

---

## 8. 参考

- 项目知识基：`AGENTS.md`
- 历史架构 spec：`docs/superpowers/specs/2026-07-01-ComicAtlas-core-architecture.md`
- 最近功能 spec：`docs/superpowers/specs/2026-07-15-comicatlas-management-features-design.md`
- 现有 API 文档：`docs/api.md`
