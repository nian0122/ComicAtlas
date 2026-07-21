# ComicAtlas：章节内图片与视频混排支持

**Date:** 2026-07-20  
**Topic:** media-item-video-support  
**Status:** 待实现

---

## 1. 目标

将 ComicAtlas 从「纯图片漫画库」演进为支持「章节内图片 + 视频混排」的本地个人媒体漫画库，同时保留向 GIF、动态 WebP、Live Photo、纯视频章节乃至音频扩展的能力。

本次设计的核心目标：

- 章节内可以同时包含图片页和视频页，阅读器按统一顺序渲染。
- 视频文件原样导入、原样存储，不在导入阶段转码或修复容器结构。
- Storage 层保持与媒体类型无关，只负责文件生命周期。
- 导入阶段仅增加媒体信息分析（ffprobe）与类型识别。
- 阅读器依赖浏览器原生 `<video>` + HTTP Range 播放，不支持时明确提示用户。
- 对现有图片漫画完全向后兼容。

---

## 2. 设计原则与边界

### 2.1 核心原则

> **ComicAtlas 是媒体管理器，不是媒体处理器。**

- ✅ 负责：导入、管理、阅读、播放。
- ❌ 不负责：转码、修复 FastStart、重新封装、生成多码率、优化视频编码。

### 2.2 边界约定

| 项目 | 决策 |
|---|---|
| 视频转码 | 导入阶段不做，未来可作为独立管理工具提供 |
| FastStart / moov 位置 | 不检测、不修复，依赖浏览器 Range 请求 |
| Poster 封面 | 不生成单独封面文件，视频页播放前由浏览器原生控件渲染 |
| LQ 低质量版本 | 视频不生成 LQ，仅图片保留 LQ 流程 |
| 视频预加载 | 不预加载完整视频，翻到视频页时由浏览器按需加载 |
| 阅读历史 | 视频占一个 `page_number`，本次不记录视频内播放时间点 |
| 浏览器播放能力 | 由前端运行时通过 `<video>` 判断，不硬编码到数据库 |

---

## 3. 领域模型

### 3.1 概念升级

将原有「Page（图片页）」概念升级为「MediaItem（媒体项）」：

```
Comic
 └── Catalog
      └── Chapter
           └── MediaItem
                 ├── IMAGE   （现有图片）
                 ├── VIDEO   （本次新增）
                 └── ...     （未来：GIF_ANIMATION / WEBP_ANIMATION / LIVE_PHOTO / AUDIO）
```

阅读器只关心 `MediaItem[]`，不区分内部类型，仅根据 `mediaType` 选择渲染组件。

### 3.2 数据库表

**策略**：保留现有 `page` 表名（避免数据迁移风险），但语义扩展为「媒体项」；Java 实体改名为 `Media`，通过 `@TableName("page")` 映射。

#### `page` 表新增字段

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `media_type` | VARCHAR(32) | `'IMAGE'` | `IMAGE` / `VIDEO` |
| `duration` | DECIMAL(10,3) | NULL | 视频时长（秒） |
| `container` | VARCHAR(32) | NULL | 容器格式，如 `mp4`、`mkv`、`webm`、`mov`、`avi` |
| `video_codec` | VARCHAR(32) | NULL | 视频编码，如 `h264`、`hevc`、`vp9`、`av1` |
| `audio_codec` | VARCHAR(32) | NULL | 音频编码，如 `aac`、`opus`、`mp3` |

#### `page` 表保留字段语义

| 字段 | 语义 |
|---|---|
| `hq_root` / `hq_path` | 所有媒体类型的 HQ 文件位置 |
| `width` / `height` | 图片直接有；视频由 ffprobe 读取 |
| `file_size` | 所有媒体都有 |
| `lq_status` | 图片：`NOT_GENERATED` / `GENERATED`；视频：`NOT_APPLICABLE` |
| `lq_root` / `lq_path` | 视频保持为 `NULL` |
| `page_number` | 媒体项在章节内的展示顺序 |

#### `chapter` 表

- 字段名 `page_count` 保留，语义改为「章节内媒体项数量」。
- 不新增字段。

#### `reading_history` 表

- 本次不修改。
- 视频占一个 `page_number`，不记录播放时间点（已知限制）。

---

## 4. 存储层

### 4.1 文件布局

视频文件与图片文件按阅读顺序平铺在同一目录：

```
D:/manga/hq/{comicId}/{chapterId}/
  001.webp
  002.webp
  003.mkv
  004.mp4
  005.webp
```

### 4.2 StorageService 职责不变

`LocalStorageService` 继续只提供：

- `store()`
- `resolve()`
- `exists()`
- `delete()`

它不识别 `mediaType`，只操作文件路径。

### 4.3 URL 规范不变

视频 URL 仍统一走 `FileUrlResolver`：

```
/files/hq/{comicId}/{chapterId}/003.mkv
```

### 4.4 StorageLayout 改名

`StorageLayout.forPage()` 改名为 `forMediaItem()`，签名不变：

```java
String forMediaItem(Long comicId, Long chapterId, String fileName);
```

### 4.5 删除逻辑

`DeleteHandler` / `AdminServiceImpl` 删除 `hq/{comicId}` 整个目录即可，无需额外处理 poster 文件。

---

## 5. 导入系统

### 5.1 导入流水线

```
DirectoryParser
    ↓
MediaAnalyzer（新增）
    ↓
MetadataAssembler
    ↓
DirectoryImportHandler
    ↓
metadata.json v3
```

### 5.2 DirectoryParser 扩展

扩展支持的媒体扩展名集合：

- 图片：`.jpg`、`.jpeg`、`.png`、`.webp`、`.gif`、`.bmp`
- 视频：`.mp4`、`.webm`、`.mkv`、`.mov`、`.avi`

注意：

- `.gif`、`.webp` 本次仍归为 `IMAGE`，由浏览器自己决定是否播放动画。
- `DirectoryTree.imageFiles()` 改名为 `mediaFiles()`，返回所有被识别的媒体文件（图片 + 视频）。
- 只按文件名排序，不区分类型。

### 5.3 MediaAnalyzer（新增）

职责：判断媒体类型、读取元数据。

```java
@Component
public class MediaAnalyzer {
    public MediaInfo analyze(Path file);
}
```

#### 与 MetadataAssembler 的集成

`MetadataAssembler` 不再直接调用 `ImageIO`，而是接收 `DirectoryTree` 后，对每个 `mediaFiles()` 中的文件调用 `MediaAnalyzer.analyze()`，将返回的 `MediaInfo` 直接组装进 `ComicMetadata`。

```
DirectoryParser 输出 DirectoryTree
    ↓
MetadataAssembler 遍历 node.mediaFiles()
    ↓
对每个文件调用 MediaAnalyzer.analyze(file) 生成 MediaInfo
    ↓
组装为 ComicMetadata.chapters[].mediaItems
```

#### 与现有代码的字段映射

`ComicMetadata.PageInfo` 演进为 `ComicMetadata.MediaInfo`，字段名变更影响以下组件：

| 旧字段 | 新字段 | 影响组件 |
|---|---|---|
| `imageName` | `fileName` | `DirectoryImportHandler`、`ImportEventHandler`、`MetadataExporter` |
| `PageInfo` | `MediaInfo` | `MetadataAssembler`、`DirectoryImportHandler` |

#### 图片处理

- `mediaType = IMAGE`
- 读取 `width`、`height`（复用现有 `ImageIO` 逻辑）。
- `duration`、`container`、`video_codec`、`audio_codec` 为 `NULL`。

#### 视频处理

- `mediaType = VIDEO`
- 调用 `ffprobe` 读取：
  - `duration`
  - `width`、`height`
  - `container`（format 名称，取第一个）
  - `video_codec`
  - `audio_codec`
- 如果 `ffprobe` 不可用或读取失败：
  - 仍标记为 `VIDEO`。
  - 元数据字段为 `NULL`。
  - 导入继续，不导致整个任务失败。

#### ffprobe 配置

- 新增配置项 `FFPROBE_PATH`，默认指向 `worker-service/ffmpeg/ffprobe.exe`。
- `WorkerConfig` 新增 `ffprobePath` 字段，与 `aria2cPath` 配置方式一致。
- 与现有 `ARIA2C_PATH` 配置方式一致。
- Windows 下可本地打包或让用户自行提供。

### 5.4 MetadataAssembler 输出 MediaInfo

`ComicMetadata.PageInfo` 演进为 `ComicMetadata.MediaInfo`：

```java
public record MediaInfo(
    String fileName,
    int pageNumber,
    String mediaType,      // IMAGE / VIDEO
    String hqStatus,       // READY / MISSING
    String lqStatus,       // NOT_GENERATED / GENERATED / NOT_APPLICABLE
    long fileSize,
    Integer width,
    Integer height,
    BigDecimal duration,   // 视频专用
    String container,      // 视频专用
    String videoCodec,     // 视频专用
    String audioCodec      // 视频专用
) {}
```

### 5.5 metadata.json v3

版本号从 `2` 升级到 `3`。

#### v3 schema

```json
{
  "version": 3,
  "comic": { "title": "...", "author": "...", "tags": [] },
  "catalogs": [...],
  "chapters": [
    {
      "title": "...",
      "chapterNo": "...",
      "sortOrder": 0,
      "globalOrder": 0,
      "catalogIndex": null,
      "sourceDir": "...",
      "mediaItems": [
        {
          "fileName": "001.webp",
          "pageNumber": 1,
          "mediaType": "IMAGE",
          "hqStatus": "READY",
          "lqStatus": "NOT_GENERATED",
          "fileSize": 123456,
          "width": 1920,
          "height": 1080
        },
        {
          "fileName": "003.mkv",
          "pageNumber": 3,
          "mediaType": "VIDEO",
          "hqStatus": "READY",
          "lqStatus": "NOT_APPLICABLE",
          "fileSize": 52428800,
          "width": 1920,
          "height": 1080,
          "duration": 30.5,
          "container": "matroska",
          "videoCodec": "hevc",
          "audioCodec": "aac"
        }
      ]
    }
  ]
}
```

#### v2 兼容

`ImportEventHandler` 必须同时处理：

- `version: 2`：将 `pages` 数组中所有 item 视为 `IMAGE`。
- `version: 3`：读取 `mediaItems` 数组，使用其中的 `mediaType`。

### 5.6 错误处理

- 单个视频 `ffprobe` 失败：以 `VIDEO` 类型无元数据导入（不跳过文件），不导致整个任务失败。
- 单个文件复制失败：按现有逻辑处理。

---

## 6. 阅读器

### 6.1 后端 ReaderDTO

`ReaderDTO.PageDTO` 改名为 `ReaderDTO.MediaItemDTO`，新增字段：

```java
@Data
public static class MediaItemDTO {
    private Long id;
    private int pageNumber;
    private String mediaType;   // IMAGE / VIDEO
    private String hqUrl;
    private String lqUrl;       // 视频为 null
    private String lqStatus;    // 视频为 NOT_APPLICABLE
    private Integer width;
    private Integer height;
    private BigDecimal duration;
    private String container;
    private String videoCodec;
    private String audioCodec;
}
```

### 6.2 前端 MediaItemInfo

`PageInfo` 改名为 `MediaItemInfo`，新增同名字段。

### 6.3 阅读器渲染

`ReaderPagedViewport` 根据 `mediaType` 切换组件：

- `IMAGE`：现有 `ProgressiveImage`。
- `VIDEO`：新增 `VideoPlayer` 组件：
  - 使用 `<video controls preload="metadata">`。
  - 不自动播放（默认点击播放）。
  - 不自动播放（默认点击播放），避免带宽浪费和浏览器自动播放策略拦截。
  - 依赖浏览器原生解码与 HTTP Range。

### 6.4 预加载策略

- 图片：保持现有预加载逻辑。
- 视频：不预加载完整视频，翻到视频页时才发起请求。

### 6.5 浏览器不支持提示

前端通过 `<video>` 的 `error` 事件或 `canPlayType()` 判断：

- 若无法播放，显示：
  - 文件名与路径
  - Container / VideoCodec / AudioCodec
  - 「当前浏览器无法播放此格式」提示
  - 提供文件路径，方便用户用本地播放器打开。

---

## 7. 前端变更

### 7.1 类型定义

`frontend/src/types/index.ts`：

- `PageInfo` → `MediaItemInfo`
- 新增 `mediaType`、`duration`、`container`、`videoCodec`、`audioCodec`

### 7.2 API 层

`readerApi.chapter()` 返回的 `pages` 字段改为 `mediaItems`，但接口路径 `/chapters/{id}` 不变。

为减少前端改动面，后端 ReaderDTO 保留 `pages` 字段名（内部元素类型为 `MediaItemDTO`）。

### 7.3 组件

- 新增 `VideoPlayer.vue`。
- `ReaderPagedViewport.vue` 根据 `mediaType` 渲染不同组件。
- 漫画列表封面 fallback：后端 `ComicServiceImpl.buildFallbackCoverMap()` 需跳过 `media_type = VIDEO` 的项；若章节首项为视频，返回 `null`，前端列表页显示通用视频占位图。

---

## 8. 兼容性与迁移

### 8.1 数据库迁移

- 不删除 `page` 表。
- 新增字段：`media_type`、`duration`、`container`、`video_codec`、`audio_codec`。
- `media_type` 默认 `'IMAGE'`，现有数据自动视为图片。

### 8.2 代码兼容

- `Page` 实体改名为 `Media`，表名仍为 `page`。
- `PageMapper` → `MediaMapper`。
- `PageDTO` / `PageInfo` → `MediaItemDTO` / `MediaItemInfo`。
- 所有按 `mediaType = IMAGE` 过滤的地方需显式处理。

#### API DTO 语义变更

以下字段名保留，语义统一改为「媒体项数量」：

| DTO/Entity | 字段 | 说明 |
|---|---|---|
| `ComicListVO` | `pageCount` | 漫画总媒体项数 |
| `ComicDetailVO` | `pageCount` | 漫画总媒体项数 |
| `ChapterVO` | `pageCount` | 章节内媒体项数 |
| `ChapterRef` | `pageCount` | 章节内媒体项数 |
| `Chapter` | `pageCount` | 章节内媒体项数 |
| `ImportTaskVO` | `totalPages` | 导入任务总媒体项数 |
| `ScanItemVO` | `imageCount` | 扫描到的媒体文件数（含图片和视频）|

### 8.3 LQ 生成器过滤

`LqGenerateHandler` / `LqServiceImpl` 必须增加 `media_type = 'IMAGE'` 过滤，避免对视频生成 LQ。

### 8.4 Admin 工具更新

- `MetadataExporter` 输出 `metadata.json` v3，`chapters[].pages` 节点改为 `chapters[].mediaItems`。
- `rebuildFromHq` / `scanRecover` 读取 v3，兼容 v2；v2 旧文件全部视为 `IMAGE`。
- `AdminServiceImpl.deleteComic` 删除 `hq/{comicId}` 整目录，逻辑不变。

---

## 9. 风险与限制

| 风险 | 说明 | 缓解 |
|---|---|---|
| 无 Poster 体验 | 视频页播放前可能黑屏 | 接受该限制，由浏览器原生控件处理 |
| 浏览器兼容性 | MKV/HEVC 等格式在某些浏览器无法播放 | 前端明确提示，不提供后端转码 |
| ffprobe 依赖 | 需要额外二进制或系统依赖 | 配置化，缺失时降级导入 |
| 大视频文件 | 翻到视频页时才加载，但仍可能瞬间占用带宽 | 预加载策略控制，不主动预加载完整视频 |
| 阅读历史粒度 | 视频只记录到页，不记录时间 | 作为已知限制 |

---

## 10. 后续可扩展

本次设计为以下扩展预留空间，但不在本次实现：

- **GIF / WebP 动画**：可新增 `ANIMATED_IMAGE` 类型。
- **Live Photo**：可新增 `LIVE_PHOTO` 类型，同时包含图片与短视频。
- **纯视频章节**：`Chapter` 内所有 `MediaItem` 均为 `VIDEO`。
- **音频**：可新增 `AUDIO` 类型。
- **独立转码工具**：未来可在管理后台提供「转换为 MP4」按钮，作为独立 Worker 任务，不影响导入流程。
