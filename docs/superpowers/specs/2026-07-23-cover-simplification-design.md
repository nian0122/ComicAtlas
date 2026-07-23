# 封面简化设计

**日期**: 2026-07-23
**目标**: 导入时生成优化过的封面缩略图，前端直读，去除三层回退和手动选封面

## 现状

当前封面机制有三层回退：
1. `comic.cover_path` 有值 → `/files/hq/{coverPath}`（用户手动选择）
2. 第一章首图回退 → `/files/hq/{id}/{chapter}/{img}`（列表视图自动计算）
3. thumbs 兜底 → `/files/thumbs/{id}/cover.webp`（导入时裸复制）

问题：
- `cover.webp` 是 `Files.copy` 裸复制，未经优化
- 阅读历史硬编码跳过回退链
- 封面选择 API 增加不必要的复杂度

## 目标架构

```
导入时 → ImageOptimizer.generateCover() → thumbs/{comicId}/cover.webp（优化后）
前端 → coverUrl = /files/thumbs/{id}/cover.webp（唯一路径）
```

消除：`comic.cover_path` 列、封面候选 API、手动选封面 UI、三层回退逻辑。

## Worker 端

### WorkerConfig — 新增封面配置

```java
@Data
public static class Cover {
    private int quality = 25;
}
```

`application.yml`:
```yaml
worker:
  cover:
    quality: ${COVER_QUALITY:25}
```

### ImageOptimizer — 新增 generateCover()

Go 工具只支持 `-scan-dir` 批量模式，封面通过临时目录绕：
1. 创建 `temp/cover-{comicId}/`，复制源图
2. 调用 image-optimizer.exe with `-quality {coverQuality} -workers 1 -json`
3. 输出重命名为 `cover.webp`
4. 删临时目录

### DirectoryImportHandler / FileService

原来 `Files.copy(firstImg, thumbsDir.resolve("cover.webp"))` 改为 `imageOptimizer.generateCover(comicId, firstImg)`。

### 删除 ThumbnailGenerator

整个类删除。

## API 端

### 删除的端点

- `GET /api/comics/{id}/covers/candidates`
- `PUT /api/comics/{id}/cover`

### 删除的类

- `CoverCandidateDTO.java`
- `CoverUpdateDTO.java`

### ComicServiceImpl 简化

- 删除方法：`listCoverCandidates()`、`updateCover()`、`resolveFirstPageCoverUrl()`、`buildFallbackCoverMap()`
- `resolveCoverUrl()` 简化为一行：`fileUrlResolver.resolveCover(comicId)`

### FileUrlResolver

删除 `resolveCover(comicId, coverPath)` 重载，只保留 `resolveCover(comicId) → /files/thumbs/{id}/cover.webp`。

### HistoryServiceImpl

硬编码 `/files/thumbs/...` 改为注入 `FileUrlResolver` 调用。

### DB Schema

`comic.cover_path` 标记废弃，不下 DROP（留待下一轮清理）。Entity 字段删除。

## 前端

### ComicEditPage.vue

删除：
- 模板：`el-form-item label="封面"` 块 + 封面选择弹窗
- 变量：`coverDialogVisible`、`coverLoading`、`coverCandidates`、`selectedCoverPageId`
- 方法：`openCoverSelector()`、`saveCover()`

### api.ts

删除：`listCoverCandidates`、`updateCover` 方法 + `CoverUpdateDTO` import。

### types/index.ts

删除：`CoverCandidateDTO`、`CoverUpdateDTO` 接口。

### 不改的文件

`ComicCard`、`ComicPoster`、`HeroBanner`、`HomeHero`、`DetailPage`、`LibraryPage`、`HistoryPage`——只读 `coverUrl`，后端简化后值不变。

## 涉及文件清单

| 模块 | 文件 | 操作 |
|------|------|------|
| Worker | `WorkerConfig.java` | 新增 Cover 内部类 + cover 字段 |
| Worker | `application.yml` | 新增 cover.quality 配置 |
| Worker | `ImageOptimizer.java` | 新增 generateCover() |
| Worker | `DirectoryImportHandler.java` | Files.copy → imageOptimizer.generateCover() |
| Worker | `FileService.java` | Files.copy → imageOptimizer.generateCover() |
| Worker | `ThumbnailGenerator.java` | **删除** |
| API | `ComicController.java` | 删除两个封面端点 |
| API | `ComicService.java` | 删除 `listCoverCandidates`、`updateCover` 接口方法 |
| API | `ComicServiceImpl.java` | 删除 4 个方法，简化 resolveCoverUrl |
| API | `FileUrlResolver.java` | 删除 resolveCover 重载 |
| API | `HistoryServiceImpl.java` | 注入 FileUrlResolver |
| API | `CoverCandidateDTO.java` | **删除** |
| API | `CoverUpdateDTO.java` | **删除** |
| API | `Comic.java` | 删除 coverPath 字段 |
| 测试 | `ComicCoverControllerTest.java` | **删除** |
| DB  | `schema.sql` | cover_path 标注废弃 |
| 前端 | `ComicEditPage.vue` | 删除封面选择 UI 和逻辑 |
| 前端 | `api.ts` | 删除封面相关方法 |
| 前端 | `types/index.ts` | 删除两个封面接口 |
