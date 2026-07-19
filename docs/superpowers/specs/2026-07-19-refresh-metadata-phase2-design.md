# Refresh Metadata Phase 2 — 设计规范

**日期**：2026-07-19
**状态**：Draft
**版本**：2.0

---

## 1. 目标

重构 `refresh-metadata`，使其不依赖旧 `metadata.json` 快照。结构从 DB 读取，图片数据从 HQ 目录实时扫描，page 层增量同步。完成后重新导出 metadata.json。

---

## 2. 核心原则

| # | 原则 |
|---|------|
| P1 | DB 是运行时唯一 Source of Truth；metadata.json 是可恢复快照（导出产物） |
| P2 | 章节结构（catalog/chapter）以 DB 为准，刷新不动 |
| P3 | 图片数据以 HQ 文件为准——文件增删即 page 增删 |
| P4 | page 执行增量 CRUD（文件存在+DB有→UPDATE，文件存在+DB无→INSERT，DB有+文件无→DELETE） |
| P5 | 刷新完成后，从 DB 最终状态重新导出 metadata.json |

---

## 3. 数据流

```
1. 加锁 READY → REFRESHING
2. 读 DB：comic + catalog + chapter 结构（保留 title/author/category 不变）
3. 扫 HQ：hq/{comicId}/{globalOrder}/* → fileSize, width, height
4. 事务内 CRUD page：
   ├── 文件存在 + DB 有对应 page → UPDATE fileSize/width/height
   ├── 文件存在 + DB 无对应 page → INSERT
   └── DB 有 page + 文件不存在   → DELETE
5. UPDATE chapter.pageCount, comic.totalPages/fileSize/hqSize
6. MetadataExporter.export(comicId) → 覆盖 metadata.json
7. 解锁 → READY
```

---

## 4. 组件设计

### 4.1 AdminServiceImpl.refreshMetadata() 变更

**原流程（Phase 1）**：读 metadata.json → Replace catalog/chapter/page

**新流程（Phase 2）**：
- 移除 metadata.json 读取
- 新增 HQ 扫描逻辑
- catalog/chapter 不删除（保留结构）
- page 增量 CRUD
- 末尾调用 `MetadataExporter.export()`

### 4.2 HQ 扫描

```
scanPages(comicId, globalOrder):
  dir = hq/{comicId}/{globalOrder}/
  for each image file (自然排序):
    → imageName, fileSize, width, height
  → List<PageInfo>
```

width/height 使用 `ImageReader.getWidth(0)/getHeight(0)`（已在 MetadataAssembler 中实现），兼容 WebP（需要 webp-imageio 依赖）。

### 4.3 page 增量同步

**匹配策略**：按 `imageName` 匹配。同一 chapter 下，HQ 文件名 vs DB `hq_path` 最后一段。

**pageNumber 规则**：
- UPDATE（已有 page）：保留原 pageNumber 不变
- INSERT（新 page）：`max(existing pageNumbers) + 1`（追加在末位）
- DELETE 后不重新编号（允许 pageNumber 出现空缺）

```java
// 伪代码
Set<String> hqImages = scanHQImages(comicId, globalOrder);
Map<String, Page> dbPages = loadPagesByChapter(chapterId); // key=imageName
int nextPageNumber = dbPages.isEmpty() ? 1 : maxPageNumber(dbPages) + 1;

for (String img : hqImages) {
    if (dbPages.containsKey(img)) {
        // UPDATE: fileSize, width, height, hqStatus → "READY"
    } else {
        // INSERT: hqRoot="HQ", hqPath={comicId}/{globalOrder}/{img},
        //         hqStatus="READY", lqStatus="NOT_GENERATED",
        //         fileSize, width, height, pageNumber=nextPageNumber++
    }
}
for (String img : dbPages.keySet()) {
    if (!hqImages.contains(img)) → DELETE
}

### 4.4 MetadataExporter

```java
@Component
public class MetadataExporter {

    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
    private final ObjectMapper objectMapper;

    @Value("${MANGA_ROOT:D:/manga}")
    private String mangaRoot;

    /**
     * 从 DB 导出完整元数据快照到 D:/manga/metadata/{comicId}.json
     */
    public Path export(Long comicId) throws IOException {
        // 1. SELECT comic
        // 2. SELECT catalog → 还原树（parent_id → parentIndex）
        // 3. SELECT chapter（按 global_order）
        // 4. SELECT page（按 chapter_id）
        // 5. imageName = hq_path 最后一段
        // 6. 序列化 → metadata/{comicId}.json
    }
}
```

**JSON 结构**与 Worker 侧 `DirectoryImportHandler.writeMetadata()` 一致（version=2）。

**parentIndex 还原**：DB 存 `parent_id`（自增），JSON 需要列表索引。先加载全部 catalog，建立 `id → index` 映射。

**imageName 提取**：`hq_path` 格式 `{comicId}/{globalOrder}/{imageName}`，`substringAfterLast('/')` 即为 imageName。

---

## 5. 与 Phase 1 的兼容

| 场景 | Phase 1 | Phase 2 |
|------|---------|---------|
| 编辑页按钮 | `POST /refresh-metadata` | **同一端点，内部逻辑替换** |
| metadata.json 缺失 | 422 报错 | **不需要**（从 DB + HQ 生成） |
| catalog/chapter | Replace | **不动** |
| page | Replace（全量删插） | **增量 CRUD** |

**前端无感知**：同一个 API，响应格式不变，行为增强。

---

## 6. 涉及文件

| 文件 | 变更类型 |
|------|----------|
| `AdminServiceImpl.java` | 重写 refreshMetadata()：移除 metadata.json 读取，新增 HQ 扫描 + page 增量同步 |
| `MetadataExporter.java` | **新文件**（`api-service`） |
| `DirectoryImportHandler.java` | 可选：`writeMetadata()` 改为委托 `MetadataExporter`（去重） |

---

## 7. 已知限制

| 限制 | 说明 |
|------|------|
| chapter 结构以 DB 为准 | HQ 新增 `{globalOrder}/` 目录不会自动创建章节（Phase 1 行为保留） |
| 不解析目录名 | 章节名、目录名来自 DB，不从 HQ 目录名推断 |
| width/height 依赖 webp-imageio | WebP 图片需要 SPI 依赖，JPEG/PNG 原生支持 |
