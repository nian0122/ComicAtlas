# ComicAtlas Core Architecture

**版本**: v1.3 (Frozen)
**日期**: 2026-07-12
**状态**: Phase 1 基线架构 — 仅修正已确认的实现性设计缺陷
**语言**: 中文

> **冻结声明**：本文档为 ComicAtlas 核心架构基线。后续除非发现设计缺陷，否则只修改 Implementation Plan，不再修改本架构。
>
> **实现约定**：Java 全部使用 `enum`，DB 全部使用 `VARCHAR`，禁止使用 MySQL `ENUM` 类型。MyBatis 通过 `EnumTypeHandler` 映射。

> **v1.3 修正**：当前产品只验收 ZIP/DIRECTORY 的 MANAGED 导入。目录树只影响 Catalog 组织；每个 Chapter 在一部漫画内由 DFS 生成唯一且稳定的 `global_order`，因此 MANAGED 文件统一存为 `{comicId}/{globalOrder}/{imageName}`。Worker 写文件时尚未拥有数据库自增的 `chapterId`，不能将其作为物理路径键。

---

## 目录

1. [领域模型](#1-领域模型)
2. [导入流水线](#2-导入流水线)
3. [存储模型](#3-存储模型)
4. [阅读模型](#4-阅读模型)
5. [同步模型](#5-同步模型-phase-2-预留)
6. [状态机](#6-状态机)
7. [API 设计](#7-api-设计)
8. [数据库 Schema](#8-数据库-schema)
9. [枚举规范](#9-枚举规范)
10. [架构决策记录](#10-架构决策记录)

---

## 1. 领域模型

### 1.1 领域关系图

```
                    Comic
                      │
          ┌───────────┴───────────┐
          ▼                       ▼
       Catalog                   Chapter
    （可选组织层）              （唯一阅读单元）
          │                        │
          ▼                        ▼
       (children)                Page
                                   │
                                   ▼
                              StorageRef
                              （值对象，文件引用）
                                   │
                         ┌─────────┴─────────┐
                         ▼                   ▼
                  StorageService       FileUrlResolver
                  （文件定位）           （HTTP URL）
```

### 1.2 核心原则

- **Catalog 管组织，Chapter 管阅读**——二者互不耦合
- **Chapter 永远是叶子节点**——可阅读、有 page、有阅读记录
- **Catalog 是可选组织结构**——不影响 Comic 的阅读模型，普通漫画 Catalog=空
- **Comic 不直接包含 Catalog**——Catalog 是 Chapter 的外挂组织方式，不是 Comic 的核心组成
- **StorageRef 是值对象**——统一 MANAGED 和 EXTERNAL 的文件引用
- **Comic.storage_policy 描述整体策略**，**Page.StorageRef 描述具体文件位置**——两个不同概念，不重复

### 1.3 DDD 聚合边界

```
Comic（聚合根）
  ├── Catalog（实体，可选）
  ├── Chapter（实体）
  │     └── Page（实体）
  └── ImportTask（关联实体）
```

**持久化规则**：

- `ComicRepository.save(metadata)` 负责整个聚合的持久化
- `ImportWriter` 只负责协调（调 Parser + 调 Repository），不了解内部 Writer 细节
- 新增实体（Tag、Author、Series）时只改 Repository，不改 ImportWriter

### 1.4 实体职责

| 实体 | 职责 | 关键字段 |
|------|------|----------|
| **Comic** | 漫画聚合根 | title, source_type, storage_policy, root_key, relative_path |
| **Catalog** | 目录树节点（可选） | comic_id, parent_id, title, sort_order, path, level |
| **Chapter** | 可阅读章节（叶子） | comic_id, catalog_id, chapter_no(原始编号), sort_order, global_order |
| **Page** | 单页图片 | chapter_id, hq_root, hq_path, lq_root, lq_path |
| **StorageRef** | 文件引用（值对象） | root_key, relative_path |

### 1.5 字段语义澄清

| 字段 | 所在表 | 语义 |
|------|--------|------|
| `storage_policy` | comic | MANAGED / EXTERNAL — 整本漫画的存储策略 |
| `StorageRef` | page | root_key + relative_path — 每一页文件的具体位置 |
| `chapter_no` | chapter | 原始来源编号（"001", "EX", "番外"）— 仅作标识，不参与排序 |
| `sort_order` | chapter/catalog | 同级（同一 Catalog 内）排序 |
| `global_order` | chapter | 全书唯一线性阅读顺序 — 阅读器唯一排序依据 |

---

## 2. 导入流水线

### 2.1 完整流程图

```
Source (ZIP / Directory)
        │
        ▼
ImportHandlerFactory
        │
        ├── ZipImportHandler
        │         │
        │         ▼ 解压到 temp
        │
        └── DirectoryImportHandler
                  │
                  ▼
          DirectoryParser        → DirectoryTree（纯目录结构）
                  │
                  ▼
          MetadataAssembler      → ComicMetadata（immutable record）
                  │
                  ▼
          ImportWriter            → 协调层
                  │
                  ▼
          ComicRepository.save() → 聚合持久化（含 catalog/chapter/page）
       ┌──────────┴──────────┐
       ▼                     ▼
  StorageService       CoverGenerator
       │
       ▼
  ComicImported Event (MQ → API)
```

### 2.2 ImportContext

Handler 和 Parser 之间通过 `ImportContext` 统一传递上下文：

```java
public record ImportContext(
    SourceType sourceType,          // ZIP / DIRECTORY；其他来源后续扩展
    StoragePolicy storagePolicy,    // 当前固定 MANAGED；EXTERNAL 后续扩展
    Path sourcePath,                // 原始来源路径
    boolean generateLq,             // 是否生成 LQ
    boolean overwrite,              // 是否覆盖已存在漫画
    String rootKey,                 // EXTERNAL 模式下的 root key
    String relativePath             // EXTERNAL 模式下的漫画相对路径
) {}
```

### 2.3 SourceType vs StoragePolicy

> SourceType 决定数据从哪里来；StoragePolicy 决定导入后如何管理文件。两者独立。

| SourceType | StoragePolicy | 说明 |
|------------|---------------|------|
| ZIP | MANAGED | 解压 → 搬入 HQ |
| DIRECTORY | MANAGED | 扫描目录 → 搬入 HQ |
| EHENTAI（未来） | 待定 | 独立来源适配器，不属于当前验收范围 |
| REGISTER / SMB / NAS（未来） | EXTERNAL | 原地引用能力，后续阶段实现 |

`EXTERNAL` 保留为领域模型扩展点，但不属于当前 Phase 1 的 API、Worker 或验收范围。

### 2.4 组件分层

| 层 | 组件 | 职责 |
|----|------|------|
| **解析层** | DirectoryParser | 目录 → DirectoryTree，纯 NIO，不关心业务语义 |
| **组装层** | MetadataAssembler | DirectoryTree → ComicMetadata，注入 Catalog/Chapter 语义 |
| **元数据** | ComicMetadata | immutable record：title, catalogs, chapters |
| **写入层** | ImportWriter | 协调：调 Parser → 调 StorageService → 调 Repository |
| **持久层** | ComicRepository | 聚合持久化（Comic + Catalog + Chapter + Page 原子写入） |
| **存储层** | StorageService | 文件搬迁 / 引用注册 |
| **事件层** | RabbitMQ | JSON 序列化，携带 taskId + comicId + metadata 路径 |

### 2.5 DirectoryParser → MetadataAssembler 关系

```
DirectoryParser（无敌业务语义）
         │
         ▼
   DirectoryTree（纯树）
         │
         ▼
   MetadataAssembler（注入业务语义）
         │
         ▼
   ComicMetadata（Catalog + Chapter + global_order）
```

- `DirectoryParser` 只输出目录结构（名称、层级、文件列表），不知道 Catalog/Chapter 区分
- `MetadataAssembler` 负责将目录结构映射为业务模型（含图叶子→Chapter，纯目录节点→Catalog，DFS→global_order）
- 未来在线来源（JSON/API）可直接生成 ComicMetadata，绕开 Parser

### 2.6 ImportWriter 行为

**MANAGED 模式**（ZIP / DIRECTORY → storage_policy=MANAGED）：

1. `StorageLayout.forPage(comicId, globalOrder, imageName)` → 决定 relativePath
2. `StorageService.store(sourceFile, "HQ", relativePath)` → 搬文件
3. Page 写入 `hq_root=HQ, hq_path=relativePath`
4. `ComicRepository.save(metadata)` → 聚合持久化

**EXTERNAL 模式**（REGISTER → storage_policy=EXTERNAL）：

1. 不动文件
2. Parser 直接给出 relativePath = `Vol01/Ch01/001.jpg`
3. Page 写入 `hq_root=LOCAL, hq_path=relativePath`
4. `ComicRepository.save(metadata)` → 聚合持久化

### 2.7 新增 Handler 扩展点

未来新增来源只需实现 ImportHandler：

```java
public interface ImportHandler {
    ComicMetadata handle(ImportContext ctx);
}
```

---

## 3. 存储模型

### 3.1 两个维度

```
SourceType（从哪里来）         StoragePolicy（怎么存）
────────────────────          ──────────────────
ZIP                            MANAGED（搬入托管）
REGISTER                       EXTERNAL（原地引用）
SMB（未来）                    注册不改文件
OSS（未来）                    托管
```

两者独立。

### 3.2 StorageService 接口

```java
public interface StorageService {
    StorageRef store(Path source, String rootKey, String relativePath);
    Path resolve(StorageRef ref);
    boolean exists(StorageRef ref);
    void delete(StorageRef ref);
}
```

职责：只管文件，不关心 HTTP，不关心 Comic/Chapter/Page。

### 3.3 StorageRef 与文件存在性

**StorageRef 只是引用，不保证文件一定存在。** 外部文件可能被用户删除或移动。Reader 在返回 URL 前应调用 `StorageService.exists(ref)` 做最终检查。缺失文件返回 `hqStatus=MISSING`，不抛异常。

### 3.4 FileUrlResolver 接口

```java
public interface FileUrlResolver {
    String resolve(Page page);
}
```

职责：Page → HTTP URL，可引入权限/签名/尺寸参数。

### 3.5 StorageLayout 接口

```java
public interface StorageLayout {
    String forPage(Long comicId, int globalOrder, String imageName);
}
```

职责：决定 MANAGED 文件的目录结构。当前为 `{comicId}/{globalOrder}/{imageName}`；`globalOrder` 在同一漫画内唯一，适用于平铺和树状目录。

版本预留：`StorageLayout` 可通过 `layout.version` 配置切换路径格式。

### 3.6 StorageRef（值对象）

```java
public record StorageRef(String rootKey, String relativePath) {}
```

- DB 存 `hq_root` + `hq_path`，代码层始终操作 `StorageRef`
- `storageType`（FILESYSTEM/S3/OSS）由配置中心维护，不存 DB
- 与 `Comic.storage_policy`（MANAGED/EXTERNAL）是两个独立概念

### 3.7 配置映射

```yaml
storage:
  roots:
    HQ:
      type: FILESYSTEM
      path: D:/manga/hq
    LQ:
      type: FILESYSTEM
      path: D:/manga/lq
    LOCAL:
      type: FILESYSTEM
      path: F:/games/comics
  url-prefix: /comic/files
```

### 3.8 存储迁移

- 改 `storage.roots.HQ.path` → `E:/NAS/manga/hq`
- **不改 page 表**
- FileUrlResolver 自动定位到新路径

---

## 4. 阅读模型

### 4.1 核心原则

```
Catalog   → 浏览，组织层级
Reading   → 翻页，线性顺序

二者独立，互不影响。
Catalog 是可选结构，不影响 Comic 的阅读模型。
```

### 4.2 两个视图

```
Catalog 视图                          Reading 视图
（漫画详情页左侧导航）                   （阅读器翻页顺序）
─────────────────────                 ──────────────────
📁 Vol.1                             Chapter 1 → Chapter 2
   📄 Chapter 1                      → Chapter 3 → Chapter 4
   📄 Chapter 2
📁 Vol.2
   📄 Chapter 3
   📄 Chapter 4
```

### 4.3 CatalogTree 是 ViewModel

```java
public record CatalogNode(
    Long id, String title,
    List<CatalogNode> children,
    List<ChapterRef> chapters
) {}

public record ChapterRef(
    Long id, String chapterNo, String title,
    int globalOrder, int pageCount,
    ChapterReadingStatus status  // UNREAD | READING | READ（预留）
) {}
```

由 `CatalogService.buildTree()` 从 `catalog` + `chapter` 表组装，不存 DB。

### 4.4 阅读器只需一个接口

```
GET /api/chapters/{id}
```

返回：

```json
{
    "chapter": { "id": 10, "title": "第1话", "globalOrder": 0 },
    "pages": [ { "pageNumber": 1, "hqUrl": "...", "lqUrl": "..." } ],
    "prevChapterId": null,
    "nextChapterId": 12
}
```

prev/next 基于 `global_order` 一次 SQL：

```sql
-- 上一章
SELECT id FROM chapter WHERE comic_id=? AND global_order < ? ORDER BY global_order DESC LIMIT 1
-- 下一章
SELECT id FROM chapter WHERE comic_id=? AND global_order > ? ORDER BY global_order ASC LIMIT 1
```

不需要 JOIN catalog，不需要递归。

### 4.5 阅读记录

表不变：`(comic_id, chapter_id, page_number)`。

恢复流程：`reading_history → chapterId → loadChapter → 翻到 pageNumber`。

### 4.6 服务拆分

| 服务 | 职责 |
|------|------|
| ComicQueryService | 列表、详情（不含 catalogTree） |
| CatalogService | buildTree |
| ReaderService | buildReadingSequence / findPrev / findNext / flatten |

职责边界：

- `CatalogService` 只管 `buildTree()`，不负责阅读顺序
- `ReaderService` 负责 `global_order` 相关的所有逻辑
- 普通漫画没有 Catalog 时，ReaderService 直接按 `global_order` 返回扁平列表

---

## 5. 同步模型（Phase 2 预留）

### 5.1 动机

EXTERNAL 漫画不是"导入一次就完"，外部目录可能变化。

### 5.2 三阶段流程

```
Scan（DirectoryParser 重新扫描）
        │
        ▼
Diff（对比 DB 中的 ComicMetadata）
        │
  输出：新增 M 个 Chapter / 删除 N 个 Chapter / 修改 K 个 Page
        │
        ▼
Apply（ComicRepository 更新 DB）
```

Diff 作为独立步骤，输出变更清单。Apply 负责执行。增量同步时 Diff 可复用。

### 5.3 Fingerprint

Parser 可选计算章节指纹（numberOfPages + totalSize + firstPageHash），用于智能匹配。

---

## 6. 状态机

### 6.1 Comic 状态 + 事件

| 当前状态 | 事件 | 下一状态 |
|----------|------|----------|
| - | CreateTask | IMPORTING |
| IMPORTING | ImportSuccess | READY |
| IMPORTING | ImportFailed | IMPORTING（重试） |
| READY | RequestDelete | DELETING |
| DELETING | DeleteComplete | DELETED |
| READY | StartRescan | RESCANNING |
| RESCANNING | RescanComplete | READY |

### 6.2 ImportTask 状态 + 事件

| 当前状态 | 事件 | 下一状态 |
|----------|------|----------|
| - | TaskCreated | PENDING |
| PENDING | WorkerPicked | PARSING |
| PARSING | ParseSuccess | IMPORTING |
| PARSING | ParseFailed | FAILED |
| IMPORTING | ImportSuccess | SUCCESS |
| IMPORTING | ImportFailed | FAILED |
| FAILED | Retry | PENDING |

### 6.3 Page 的 HQ/LQ 状态

```
hq_status: PENDING → READY
                    → MISSING（0 字节文件 / StorageService.exists=false）

lq_status: PENDING → READY
                    → FAILED
```

- `hq_status` 和 `lq_status` 是独立状态，互不影响
- LQ 为手动触发，不随导入自动流转

---

## 7. API 设计

### 7.1 漫画

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/comics` | 列表（分页+筛选） |
| GET | `/api/comics/{id}` | 基本信息（不含 catalogTree，大漫画独立缓存） |
| DELETE | `/api/comics/{id}` | 删除（异步，状态→DELETING） |

### 7.2 目录（独立接口）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/comics/{id}/catalog` | 目录树（独立接口，大章节数独立缓存） |

### 7.3 阅读

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/chapters/{id}` | 章节详情 + pages + prev/next |
| GET | `/api/pages/{id}` | 单页（含 hqUrl/lqUrl） |

### 7.4 导入

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/tasks/import` | 创建导入任务 `{ sourceType, sourcePath }` |
| GET | `/api/tasks/{id}` | 任务状态 |

### 7.5 阅读记录

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/history/{comicId}` | 获取进度 |
| PUT | `/api/history/{comicId}` | 更新进度 `{ chapterId, pageNumber }` |

### 7.6 同步（Phase 2）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/comics/{id}/rescan` | Scan → Diff → Apply |
| GET | `/api/comics/{id}/diff` | 查看变更（不执行 Apply） |

### 7.7 Controller 分层

```
CatalogController → CatalogService  → buildTree()
ReaderController  → ReaderService   → ReaderDTO（含 prev/next/pages）
ImportController  → ImportService   → createTask()
ComicController   → ComicQueryService → list/detail
```

每个 Controller 只依赖一个 Service。

---

## 8. 数据库 Schema

### 8.1 变更摘要

**新增表**：

```sql
CREATE TABLE catalog (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id    BIGINT NOT NULL,
    parent_id   BIGINT DEFAULT NULL,
    title       VARCHAR(255) NOT NULL,
    sort_order  INT DEFAULT 0,
    path        VARCHAR(512) DEFAULT NULL,   -- 物化路径，格式 /1/2/3
    level       INT DEFAULT 0,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES catalog(id) ON DELETE CASCADE,
    UNIQUE INDEX uk_comic_parent_title (comic_id, parent_id, title),
    INDEX idx_comic_parent (comic_id, parent_id),
    INDEX idx_path (path)
);
```

**修改 comic 表**：

```sql
ALTER TABLE comic CHANGE COLUMN storage_type storage_policy VARCHAR(16) DEFAULT 'MANAGED';
```

**修改 chapter 表**：

```sql
ALTER TABLE chapter
    ADD COLUMN catalog_id    BIGINT DEFAULT NULL AFTER comic_id,
    ADD COLUMN sort_order    INT DEFAULT 0 AFTER chapter_no,
    ADD COLUMN global_order  INT DEFAULT 0 AFTER sort_order,
    DROP INDEX uk_comic_chapter,
    ADD UNIQUE INDEX uk_catalog_chapter (comic_id, catalog_id, chapter_no),
    ADD FOREIGN KEY (catalog_id) REFERENCES catalog(id) ON DELETE SET NULL,
    ADD INDEX idx_comic_global (comic_id, global_order);
```

**修改 page 表**：

```sql
ALTER TABLE page
    DROP COLUMN image_name,
    ADD COLUMN hq_root VARCHAR(32) DEFAULT 'HQ' AFTER chapter_id,
    ADD COLUMN hq_path VARCHAR(512) AFTER hq_root,
    ADD COLUMN lq_root VARCHAR(32) DEFAULT NULL AFTER hq_path,
    ADD COLUMN lq_path VARCHAR(512) AFTER lq_root;
```

### 8.2 完整 ERD

```
comic ──────────┬── catalog ── catalog (self-ref)
                │        │
                │        └── chapter ── page
                │
                ├── chapter (catalog_id = NULL)
                │        │
                │        └── page
                │
                ├── import_task
                │
                ├── tag ── comic_tag ── comic
                │
                ├── reading_history ── chapter
                │
                └── operation_log
```

### 8.3 字段语义

| 表 | 字段 | 语义 |
|----|------|------|
| comic | `storage_policy` | MANAGED / EXTERNAL（存储策略） |
| catalog | `path` | 物化路径（`/1/2/3`） |
| catalog | `level` | 树深度 |
| chapter | `chapter_no` | 原始编号（不参与排序） |
| chapter | `sort_order` | 同级 Catalog 内排序 |
| chapter | `global_order` | 全书唯一线性阅读顺序 |
| page | `hq_root` | storage.roots 的 key（HQ/LOCAL） |
| page | `hq_path` | 相对于 root 的文件路径 |

---

## 9. 枚举规范

### 9.1 统一规则

- **Java**：全部使用 `enum`
- **数据库**：全部使用 `VARCHAR(32)`
- **MyBatis**：通过 `EnumTypeHandler` 映射
- **禁止**：MySQL `ENUM` 类型（扩展需 ALTER TABLE，多 DB 行为不一致）

### 9.2 枚举定义

```java
public enum SourceType { ZIP, DIRECTORY, EHENTAI, REGISTER, SMB }

public enum StoragePolicy { MANAGED, EXTERNAL }

public enum ComicStatus { IMPORTING, READY, DELETING, DELETED, RESCANNING }

public enum ImportTaskStatus { PENDING, PARSING, IMPORTING, SUCCESS, FAILED }

public enum HqStatus { READY, MISSING, PENDING }

public enum LqStatus { READY, PENDING, FAILED }

public enum ReadingStatus { UNREAD, READING, READ }

public enum OperationType { IMPORT, DELETE, RESCAN, UPDATE }
```

### 9.3 非枚举字段

`root_key`（HQ/LQ/LOCAL）保持 `String`——它是配置项，用户可自定义（NAS/USB/ARCHIVE），非固定业务枚举。

---

## 10. 架构决策记录

| 决策 | 理由 |
|------|------|
| Catalog + Chapter 双表 | 不同业务对象，避免大量 NULL 和 if/else |
| `storage_policy` 命名 | 区分策略（MANAGED/EXTERNAL）与类型（FILESYSTEM/S3） |
| Page.StorageRef 与 Comic.storage_policy 独立 | 文件位置（页级）vs 整体策略（书级） |
| Catalog 为可选组织结构 | 普通漫画无需目录节点 |
| CatalogTree 为 ViewModel | 运行时组装，不存 DB |
| `global_order` 为全书线性阅读顺序 | 阅读器无需递归 Catalog |
| `global_order` 作为当前 MANAGED 路径键 | Worker 先写文件、API 后生成自增 chapterId；树状与平铺目录均可复用同一布局 |
| StorageService 不返回 URL | 文件与 HTTP 职责分离 |
| EXTERNAL 路径固化为 relative_path | 避免每次请求扫描目录 |
| `chapter_no` 仅作原始编号 | 排序由 `sort_order`/`global_order` 独立维护 |
| ImportContext 统一传递上下文 | 避免 Handler→Parser 参数膨胀 |
| ComicMetadata 为 immutable record | Parser 纯函数，Writer 转换 |
| flatten() 归 ReaderService | Catalog 管组织，Reader 管阅读顺序 |
| ComicRepository 负责聚合持久化 | ImportWriter 不感知内部实体数量 |
| DirectoryParser 输出 DirectoryTree | 纯目录结构，业务语义由 Assembler 注入 |
| Scan → Diff → Apply 三阶段同步 | Diff 独立可复用 |
| StorageRef 不保证文件存在 | 外部文件可能被删，Reader 最终检查 |
| ComicDetail 与 Catalog 拆接口 | 大章节数独立缓存 |
| Enum + VARCHAR 禁止 MySQL ENUM | 统一规范，易扩展，多 DB 一致 |
| `rootKey` 保持 String | 用户可自定义存储根，非固定枚举 |
