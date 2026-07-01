# ComicAtlas Core Architecture

**版本**: v1.0
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
6. [API 设计](#6-api-设计)
7. [数据库 Schema](#7-数据库-schema)

---

## 1. 领域模型

### 1.1 领域关系图

```
                    Comic
                      │
          ┌───────────┴───────────┐
          ▼                       ▼
       Catalog                   Chapter
          │                        │
          ▼                        ▼
       (children)                Page
                                   │
                                   ▼
                              StorageRef
                                   │
                         ┌─────────┴─────────┐
                         ▼                   ▼
                  StorageService       FileUrlResolver
```

### 1.2 核心原则

- **Catalog 管组织，Chapter 管阅读**——二者互不耦合
- **Chapter 永远是叶子节点**——可阅读、有 page、有阅读记录
- **Catalog 永远是分组节点**——不可阅读、无 page、纯结构
- **StorageRef 是值对象**——统一 MANAGED 和 EXTERNAL 的文件引用

### 1.3 实体职责

| 实体 | 职责 | 关键字段 |
|------|------|----------|
| **Comic** | 漫画聚合根 | title, source_type, storage_type, root_key, relative_path |
| **Catalog** | 目录树节点（可选） | comic_id, parent_id, title, sort_order, path, level |
| **Chapter** | 可阅读章节（叶子） | comic_id, catalog_id, chapter_no, sort_order, global_order, page_count |
| **Page** | 单页图片 | chapter_id, hq_root, hq_path, lq_root, lq_path |
| **StorageRef** | 文件引用（值对象） | root_key, relative_path |

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
          DirectoryParser（纯解析，无 DB 依赖）
                  │
                  ▼
          ComicMetadata（record）
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

### 2.2 组件分层

| 层 | 组件 | 职责 |
|----|------|------|
| **解析层** | DirectoryParser | 目录 → ComicMetadata，纯 NIO，不碰 DB/Storage |
| **元数据** | ComicMetadata | record：title, catalogs, chapters |
| **写入层** | ImportWriter | 协调 CatalogWriter + ChapterWriter + PageWriter |
| **存储层** | StorageService | 文件搬迁 / 引用注册 |
| **事件层** | RabbitMQ | JSON 序列化，携带 taskId + comicId + metadata 路径 |

### 2.3 DirectoryParser 行为

输入：目录路径

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

### 2.4 ImportWriter 行为

**MANAGED 模式**（ZIP）：

1. `StorageLayout.forPage(comicId, chapterId, imageName)` → 决定 relativePath
2. `StorageService.store(sourceFile, "HQ", relativePath)` → 搬文件
3. Page 写入 `hq_root=HQ, hq_path=relativePath`

**EXTERNAL 模式**（REGISTER）：

1. 不动文件
2. Parser 直接给出 relativePath = `Vol01/Ch01/001.jpg`
3. Page 写入 `hq_root=LOCAL, hq_path=relativePath`

### 2.5 新增 Handler 扩展点

未来新增来源只需实现 ImportHandler：

```java
public interface ImportHandler {
    ComicMetadata handle(ImportTask task);
}
```

---

## 3. 存储模型

### 3.1 两个维度

```
SourceType（从哪里来）         StoragePolicy（怎么存）
────────────────────          ──────────────────
ZIP                            MANAGED
REGISTER                       EXTERNAL
SMB（未来）                    注册（不动文件）
OSS（未来）                    托管（搬入）
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

职责：决定 MANAGED 文件的目录结构。现为 `{comicId}/{chapterId}/{imageName}`。未来如需 hash sharding，只改此层。

### 3.5 StorageRef（值对象）

```java
public record StorageRef(String rootKey, String relativePath) {}
```

- DB 存 `hq_root` + `hq_path`，代码层始终操作 `StorageRef`
- `storageType` 由配置中心维护（root_key → type 映射），不存 DB

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

`readingOrder` 由 tree 展平得到：

```java
CatalogService.flatten(tree) → List<ChapterRef>  // 按 global_order 排序
```

### 4.4 阅读器接口

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
| CatalogService | buildTree / flatten |
| ReaderService | 章节翻页、阅读记录 |

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

## 6. API 设计

### 6.1 漫画

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/comics` | 列表（分页+筛选） |
| GET | `/api/comics/{id}` | 详情（含 catalogTree） |
| DELETE | `/api/comics/{id}` | 删除（异步） |

### 6.2 阅读

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/chapters/{id}` | 章节详情 + pages + prev/next |
| GET | `/api/pages/{id}` | 单页（含 hqUrl/lqUrl） |

### 6.3 导入

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/tasks/import` | 创建导入任务 `{ sourceType, sourcePath }` |
| GET | `/api/tasks/{id}` | 任务状态 |

### 6.4 目录

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/comics/{id}/catalog` | 目录树 |

### 6.5 阅读记录

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/history/{comicId}` | 获取进度 |
| PUT | `/api/history/{comicId}` | 更新进度 `{ chapterId, pageNumber }` |

### 6.6 同步（Phase 2）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/comics/{id}/rescan` | 重新扫描外部目录 |
| GET | `/api/comics/{id}/diff` | 查看变更 |

---

## 7. 数据库 Schema

### 7.1 变更摘要

**新增表**：

```sql
CREATE TABLE catalog (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id    BIGINT NOT NULL,
    parent_id   BIGINT DEFAULT NULL,
    title       VARCHAR(255) NOT NULL,
    sort_order  INT DEFAULT 0,
    path        VARCHAR(512) DEFAULT NULL,   -- 物化路径（Phase 1 可选）
    level       INT DEFAULT 0,               -- 层级（Phase 1 可选）
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES catalog(id) ON DELETE CASCADE,
    UNIQUE INDEX uk_comic_parent_title (comic_id, parent_id, title),
    INDEX idx_comic_parent (comic_id, parent_id)
);
```

**修改 chapter 表**：

```sql
ALTER TABLE chapter
    ADD COLUMN catalog_id    BIGINT DEFAULT NULL AFTER comic_id,
    ADD COLUMN sort_order    INT DEFAULT 0 AFTER title,
    ADD COLUMN global_order  INT DEFAULT 0 AFTER sort_order,
    DROP INDEX uk_comic_chapter,
    ADD FOREIGN KEY (catalog_id) REFERENCES catalog(id) ON DELETE SET NULL,
    ADD INDEX idx_comic_catalog (comic_id, catalog_id, sort_order),
    ADD INDEX idx_comic_global (comic_id, global_order);
```

**修改 page 表**：

```sql
ALTER TABLE page
    ADD COLUMN hq_root VARCHAR(32) DEFAULT 'HQ' AFTER chapter_id,
    ADD COLUMN hq_path VARCHAR(512) AFTER hq_root,
    ADD COLUMN lq_root VARCHAR(32) DEFAULT NULL AFTER hq_path,
    ADD COLUMN lq_path VARCHAR(512) AFTER lq_root;
```

### 7.2 完整 ERD

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

### 7.3 字段语义

| 表 | 字段 | 语义 |
|----|------|------|
| chapter | `chapter_no` | 原始编号（"001", "EX", "番外"），不参与排序 |
| chapter | `sort_order` | 同级 Catalog 内排序 |
| chapter | `global_order` | 全书唯一线性阅读顺序 |
| catalog | `path` | 物化路径（`/1/2/3`），可选 |
| catalog | `level` | 树深度，可选 |
| page | `hq_root` | 对应 storage.roots 配置的 key（HQ/LOCAL） |
| page | `hq_path` | 相对于 root 路径的文件路径 |

---

## 设计决策记录

| 决策 | 理由 |
|------|------|
| Catalog + Chapter 双表而非单表 `type` 字段 | 两种完全不同业务对象，避免大量 NULL 和 if/else |
| Catalog 不存 DB 为 ViewModel | 运行时组装，字段易扩展 |
| `global_order` 作为全书线性阅读顺序 | 阅读器无需递归 Catalog |
| StorageService 不返回 URL | 文件和 HTTP 是两个职责 |
| EXTERNAL 路径固化为 relative_path | 避免每次请求动态扫描目录 |
| page 保留 `hq_root`/`hq_path` 而非 `image_name` | 支持 MANAGED/EXTERNAL 统一引用 |
| `chapter_no` 保留但仅作原始编号 | 兼容来源编号，排序由 `sort_order`/`global_order` 负责 |
