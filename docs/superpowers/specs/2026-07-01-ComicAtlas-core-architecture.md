# ComicAtlas Core Architecture

**版本**: v1.1
**日期**: 2026-07-01
**状态**: Phase 1 设计定稿
**语言**: 中文

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

### 1.3 实体职责

| 实体 | 职责 | 关键字段 |
|------|------|----------|
| **Comic** | 漫画聚合根 | title, source_type, storage_policy, root_key, relative_path |
| **Catalog** | 目录树节点（可选） | comic_id, parent_id, title, sort_order, path, level |
| **Chapter** | 可阅读章节（叶子） | comic_id, catalog_id, chapter_no(原始编号), sort_order, global_order |
| **Page** | 单页图片 | chapter_id, hq_root, hq_path, lq_root, lq_path |
| **StorageRef** | 文件引用（值对象） | root_key, relative_path |

### 1.4 字段语义澄清

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
        └── RegisterImportHandler
                  │
                  ▼
          DirectoryParser（纯函数，不可变输出，无 DB 依赖）
                  │
                  ▼
          ComicMetadata（immutable record）
                  │
                  ▼
          ImportWriter（持久化 + 文件搬迁）
       ┌──────────┴──────────┐
       ▼                     ▼
  CatalogWriter          ChapterWriter
       │                     │
       └──────────┬──────────┘
                  ▼
           PageWriter
       ┌──────────┴──────────┐
       ▼                     ▼
  StorageService       CoverGenerator
       │
       ▼
  ComicImported Event (MQ → API)
                  │
                  ▼
          ImportEventHandler
                  │
                  ▼
          DB INSERT (comic / catalog / chapter / page)
```

### 2.2 ImportContext

Handler 和 Parser 之间通过 `ImportContext` 统一传递上下文，避免参数膨胀：

```java
public record ImportContext(
    SourceType sourceType,          // ZIP / REGISTER / SMB（未来）
    StoragePolicy storagePolicy,    // MANAGED / EXTERNAL
    Path sourcePath,                // 原始来源路径
    boolean generateLq,             // 是否生成 LQ
    boolean overwrite,              // 是否覆盖已存在漫画
    String rootKey,                 // EXTERNAL 模式下的 root key
    String relativePath             // EXTERNAL 模式下的漫画相对路径
) {}
```

### 2.3 组件分层

| 层 | 组件 | 职责 |
|----|------|------|
| **解析层** | DirectoryParser | 目录 → ComicMetadata，纯 NIO，不碰 DB/Storage |
| **元数据** | ComicMetadata | immutable record：title, catalogs, chapters |
| **写入层** | ImportWriter | 协调 CatalogWriter + ChapterWriter + PageWriter |
| **存储层** | StorageService | 文件搬迁 / 引用注册 |
| **事件层** | RabbitMQ | JSON 序列化，携带 taskId + comicId + metadata 路径 |

### 2.4 DirectoryParser 行为

输入：目录路径（`ImportContext.sourcePath`）

```
comic/
    Vol01/           → Catalog(title="Vol01", parent=null, sort=0)
        Ch01/        → Chapter(catalog=Vol01, sort=0, global=0)
        Ch02/        → Chapter(catalog=Vol01, sort=1, global=1)
    Vol02/           → Catalog(title="Vol02", parent=null, sort=1)
        Ch03/        → Chapter(catalog=Vol02, sort=0, global=2)
```

规则：

- `findComicRoot()`：沿目录树向下，找到第一个含图片或含图片子目录的节点
- 子目录含图片 → Chapter；子目录只含子目录 → Catalog，递归
- `global_order`：DFS 遍历顺序递增
- 单级目录（如 `comic/Ch1/*.jpg`）：不生成 Catalog，Chapter 直接挂 Comic
- **输出不可变**：ComicMetadata 为 record，ImportWriter 自行转换为实体

### 2.5 ImportWriter 行为

**MANAGED 模式**（ZIP → storage_policy=MANAGED）：

1. `StorageLayout.forPage(comicId, chapterId, imageName)` → 决定 relativePath
2. `StorageService.store(sourceFile, "HQ", relativePath)` → 搬文件
3. Page 写入 `hq_root=HQ, hq_path=relativePath`

**EXTERNAL 模式**（REGISTER → storage_policy=EXTERNAL）：

1. 不动文件
2. Parser 直接给出 relativePath = `Vol01/Ch01/001.jpg`
3. Page 写入 `hq_root=LOCAL, hq_path=relativePath`

### 2.6 新增 Handler 扩展点

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

两者独立：

| 来源 | 策略 |
|------|------|
| ZIP → | MANAGED |
| REGISTER → | EXTERNAL |
| SMB → | EXTERNAL |

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

### 3.3 FileUrlResolver 接口

```java
public interface FileUrlResolver {
    String resolve(Page page);
}
```

职责：Page → HTTP URL，可引入权限/签名/尺寸参数。

### 3.4 StorageLayout 接口

```java
public interface StorageLayout {
    String forPage(Long comicId, Long chapterId, String imageName);
}
```

职责：决定 MANAGED 文件的目录结构。现为 `{comicId}/{chapterId}/{imageName}`。

版本预留：`StorageLayout` 可通过 `layout.version` 配置切换路径格式，确保未来迁移兼容。

### 3.5 StorageRef（值对象）

```java
public record StorageRef(String rootKey, String relativePath) {}
```

- DB 存 `hq_root` + `hq_path`，代码层始终操作 `StorageRef`
- `storageType`（FILESYSTEM/S3/OSS）由配置中心维护，不存 DB
- 与 `Comic.storage_policy`（MANAGED/EXTERNAL）是两个独立概念

### 3.6 配置映射

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

### 3.7 存储迁移

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
| ComicQueryService | 列表、详情 |
| CatalogService | buildTree（组装目录树） |
| ReaderService | buildReadingSequence / findPrev / findNext / flatten |

职责边界：

- `CatalogService` 只管 `buildTree()`，不负责阅读顺序
- `ReaderService` 负责 `global_order` 相关的所有逻辑（展平、前后章节、阅读序列）
- 普通漫画没有 Catalog 时，ReaderService 直接按 `global_order` 返回扁平列表

---

## 5. 同步模型（Phase 2 预留）

### 5.1 动机

EXTERNAL 漫画不是"导入一次就完"，外部目录可能变化：

- 新增章节（`Vol03/Ch04/`）
- 删除图片
- 重命名目录

### 5.2 Rescan 流程

```
F:/games/comics/h_photograph/ComicA
        │
        ▼
  DirectoryParser（重新扫描）
        │
        ▼
  Diff（对比 DB 中的 ComicMetadata）
        │
  ┌─────┼─────┐
  ▼     ▼     ▼
新增   删除   修改
Chapter Page  relativePath
```

### 5.3 Fingerprint

Parser 可选计算章节指纹（numberOfPages + totalSize + firstPageHash），用于智能匹配。

---

## 6. 状态机

### 6.1 Comic 状态

```
                    ┌──────────────┐
                    │   IMPORTING  │ ← 导入任务创建
                    └──────┬───────┘
                           │ 导入成功
                           ▼
                    ┌──────────────┐
              ┌─────│    READY     │─────┐
              │     └──────────────┘     │
              │ 删除请求                  │ 重扫（Phase 2）
              ▼                          ▼
     ┌──────────────┐           ┌──────────────┐
     │   DELETING   │           │  RESCANNING  │
     └──────┬───────┘           └──────┬───────┘
            │ 删除完成                   │ 完成
            ▼                          ▼
     ┌──────────────┐           ┌──────────────┐
     │   DELETED    │           │    READY     │
     └──────────────┘           └──────────────┘
```

| 状态 | 含义 |
|------|------|
| IMPORTING | 导入进行中 |
| READY | 可阅读 |
| DELETING | 删除进行中 |
| DELETED | 已删除（软删除） |
| RESCANNING | 重扫进行中（Phase 2） |

### 6.2 ImportTask 状态

```
PENDING → PARSING → IMPORTING → SUCCESS
                              → FAILED
```

| 状态 | 含义 |
|------|------|
| PENDING | 已创建，等待 Worker 拉取 |
| PARSING | Worker 正在执行 DirectoryParser |
| IMPORTING | Worker 正在搬文件/生成 metadata |
| SUCCESS | 导入完成 |
| FAILED | 导入失败（error_message 写入原因） |

### 6.3 Page 的 HQ/LQ 状态

```
hq_status: PENDING → READY
                    → MISSING（0 字节文件）

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
| GET | `/api/comics/{id}` | 详情（含 catalogTree） |
| DELETE | `/api/comics/{id}` | 删除（异步，状态→DELETING） |

### 7.2 阅读

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/chapters/{id}` | 章节详情 + pages + prev/next |
| GET | `/api/pages/{id}` | 单页（含 hqUrl/lqUrl） |

### 7.3 导入

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/tasks/import` | 创建导入任务 `{ sourceType, sourcePath }` |
| GET | `/api/tasks/{id}` | 任务状态 |

### 7.4 目录

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/comics/{id}/catalog` | 目录树 |

### 7.5 阅读记录

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/history/{comicId}` | 获取进度 |
| PUT | `/api/history/{comicId}` | 更新进度 `{ chapterId, pageNumber }` |

### 7.6 同步（Phase 2）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/comics/{id}/rescan` | 重新扫描外部目录 |
| GET | `/api/comics/{id}/diff` | 查看变更 |

### 7.7 Controller 分层

```
CatalogController → CatalogService → buildTree()
ReaderController  → ReaderService  → ReaderDTO（含 prev/next/pages）
ImportController  → ImportService  → createTask()
ComicController   → ComicQueryService → list/detail
```

每个 Controller 只依赖一个 Service，路由简洁。

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
-- storage_type 重命名为 storage_policy，语义更准确
ALTER TABLE comic CHANGE COLUMN storage_type storage_policy VARCHAR(16) DEFAULT 'MANAGED';
```

**修改 chapter 表**：

```sql
ALTER TABLE chapter
    ADD COLUMN catalog_id    BIGINT DEFAULT NULL AFTER comic_id,
    ADD COLUMN sort_order    INT DEFAULT 0 AFTER chapter_no,
    ADD COLUMN global_order  INT DEFAULT 0 AFTER sort_order,
    DROP INDEX uk_comic_chapter,
    -- 同一 Catalog 内 chapter_no 唯一
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
| comic | `storage_policy` | MANAGED / EXTERNAL（存储策略，非文件类型） |
| catalog | `path` | 物化路径（`/1/2/3`），方便子树查询 |
| catalog | `level` | 树深度（0=顶层），路径 LEN 可推导，显式存查询更快 |
| chapter | `chapter_no` | 原始编号（"001", "EX", "番外"），不参与排序 |
| chapter | `sort_order` | 同级 Catalog 内排序 |
| chapter | `global_order` | 全书唯一线性阅读顺序 |
| page | `hq_root` | 对应 storage.roots 配置的 key（HQ/LOCAL） |
| page | `hq_path` | 相对于 root 路径的文件路径 |

---

## 设计决策记录

| 决策 | 理由 |
|------|------|
| Catalog + Chapter 双表而非单表 `type` 字段 | 两种完全不同业务对象，避免大量 NULL 和 if/else |
| `storage_policy` 命名替代 `storage_type` | 区分策略（MANAGED/EXTERNAL）与类型（FILESYSTEM/S3） |
| Page.StorageRef 与 Comic.storage_policy 独立 | 一个是文件位置（页级），一个是整体策略（书级），不重叠 |
| Catalog 是可选组织结构 | 不强制每本漫画都有目录节点，普通漫画无 Catalog |
| Catalog 不存 DB 为 ViewModel | 运行时组装，字段易扩展 |
| `global_order` 作为全书线性阅读顺序 | 阅读器无需递归 Catalog |
| StorageService 不返回 URL | 文件和 HTTP 是两个职责 |
| EXTERNAL 路径固化为 relative_path | 避免每次请求动态扫描目录 |
| `chapter_no` 保留但不参与排序 | 兼容来源编号，排序由 `sort_order`/`global_order` 独立维护 |
| ImportContext 统一传递上下文 | 避免 Handler→Parser 参数膨胀，统一 ZIP/REGISTER/SMB 入口 |
| ComicMetadata 为 immutable record | Parser 为纯函数，Writer 自行转换为实体 |
| flatten() 归属 ReaderService 而非 CatalogService | Catalog 管组织，Reader 管阅读顺序，职责不交叉 |
