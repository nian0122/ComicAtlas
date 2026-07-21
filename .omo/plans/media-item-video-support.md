# media-item-video-support - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**What you'll get:** ComicAtlas 从纯图片漫画库升级为支持章节内图片+视频混排的媒体漫画库，视频文件原样导入、原样存储、浏览器原生播放。

**Why this approach:** 渐进式演进保留 `page` 表名避免数据迁移风险；Storage 层零改动保持职责纯粹；导入阶段只分析不转码，复杂度可控。

**What it will NOT do:** 不转码视频、不生成 Poster、不修复 FastStart、不为视频生成 LQ、不修改阅读历史表。

**Effort:** Large
**Risk:** Medium - 实体重命名（Page→Media）和 metadata.json 版本升级影响面广，但设计文档已明确每个文件的改动点
**Decisions to sanity-check:**
- 是否接受无 Poster（视频页播放前可能黑屏）
- 是否接受 `page_count`/`pageCount` 字段名保留但语义变为「媒体项数量」
- 是否接受 `ffprobe.exe` 作为外部依赖（Windows 需自行提供）

Your next move: approve 后开始执行，或提出调整。Full execution detail follows below.

---

> TL;DR (machine): Large effort, Medium risk. Deliverables: DB migration + Page→Media domain rename + DirectoryParser video extension + MediaAnalyzer (ImageIO+ffprobe) + metadata.json v3 with v2 compat + ReaderDTO MediaItemDTO + VideoPlayer.vue + LQ image-only filtering. No transcoding, no poster, no FastStart fix.

## Scope
### Must have
- 数据库 `page` 表新增 `media_type`/`duration`/`container`/`video_codec`/`audio_codec` 字段，现有数据兼容。
- Java 实体 `Page` 改名为 `Media`（`@TableName("page")`），`PageMapper` → `MediaMapper`。
- `DirectoryParser` 扩展视频扩展名（`.mp4`/`.mkv`/`.webm`/`.mov`/`.avi`），`imageFiles()` → `mediaFiles()`。
- 新增 `MediaAnalyzer` 组件：图片用 `ImageIO`，视频用 `ffprobe` 读取元数据。
- `MetadataAssembler` 输出 `MediaInfo`（替代 `PageInfo`）。
- `DirectoryImportHandler` 写入 `metadata.json` v3，字段 `pages` → `mediaItems`。
- `ImportEventHandler` 兼容 `metadata.json` v2（全部视为 IMAGE）和 v3。
- `ReaderDTO` 保留 `pages` 字段名，内部元素 `PageDTO` → `MediaItemDTO`，新增视频字段。
- `ReaderService` 使用 `MediaMapper`，填充 `mediaType` 等字段。
- `LqServiceImpl` 增加 `media_type = 'IMAGE'` 过滤。
- `MetadataExporter` / `rebuildFromHq` / `scanRecover` 支持 v3 格式。
- `ComicServiceImpl` 封面相关方法（`buildFallbackCoverMap`/`resolveFirstPageCoverUrl`/`listCoverCandidates`）跳过视频首项。
- 前端 `PageInfo` → `MediaItemInfo`，新增 `mediaType`/`duration`/`container`/`videoCodec`/`audioCodec`。
- 新增 `VideoPlayer.vue`：`<video controls preload="metadata">`，不自动播放。
- `ReaderPagedViewport.vue` 按 `mediaType` 切换 `ProgressiveImage` / `VideoPlayer`。
- `WorkerConfig` 新增 `ffprobePath` 配置项。

### Must NOT have (guardrails, anti-slop, scope boundaries)
- 不在导入阶段转码视频或修复 FastStart。
- 不生成 Poster/封面文件。
- 不为视频生成 LQ 版本。
- 不改 `chapter` 表字段名（`page_count` 保留）。
- 不改 `reading_history` 表。
- 不改 StorageService 的职责（不识别 mediaType）。
- 不改 FileUrlResolver 的 URL 格式。
- 不改 RabbitMQ 队列/Exchange 配置。
- 不引入前端视频预加载逻辑（翻到才加载）。

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after + Spring Boot Test (backend) + manual browser QA (frontend)
- Evidence: .omo/evidence/task-<N>-media-item-video-support.<ext>

每完成一个 Wave 后，由执行 Agent 运行：
1. `mvn compile`（backend）验证编译通过。
2. `npm run build`（frontend）验证编译通过。
3. 端到端验证：用测试目录（含 `.jpg` + `.mp4`）执行导入，确认 `metadata.json` v3 正确，数据库记录 `media_type = VIDEO`，阅读器返回 `MediaItemDTO`。

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.

- **Wave 1 — Foundation**：数据库迁移 + 领域模型重构。所有后续工作依赖此波。
- **Wave 2 — Worker 导入流水线**：DirectoryParser 扩展、MediaAnalyzer 新增、MetadataAssembler 改造、DirectoryImportHandler 升级、ComicMetadata 记录改名。
- **Wave 3 — API 导入与管理层**：ImportEventHandler v2/v3 兼容、MetadataExporter v3、rebuildFromHq/scanRecover 兼容、LqServiceImpl 过滤、AdminServiceImpl 确认无改、WorkerConfig 新增 ffprobePath。
- **Wave 4 — API 阅读器层**：ReaderDTO/ReaderService/ReaderController 改造、ComicServiceImpl 封面 fallback、FileUrlResolver 确认无改、PageInfo DTO 更新。
- **Wave 5 — 前端**：类型定义、VideoPlayer.vue、ReaderPagedViewport 改造、漫画列表封面占位。
- **Wave 6 — 集成验证**：端到端测试、编译验证、回归测试。

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| W1 (DB + Domain) | — | W2, W3, W4 | — |
| W2 (Worker Import) | W1 | W6 | W3, W4 |
| W3 (API Import/Admin) | W1 | W6 | W2, W4 |
| W4 (API Reader) | W1 | W5, W6 | W2, W3 |
| W5 (Frontend) | W4 | W6 | — |
| W6 (Verification) | W2, W3, W4, W5 | — | — |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->

- [x] 1. DB Migration: page 表新增媒体类型与视频元数据字段
  What to do / Must NOT do: 新增 `media_type`/`duration`/`container`/`video_codec`/`audio_codec` 字段；`media_type` 默认 `'IMAGE'`；不删表、不改现有字段类型、不迁移数据。新增 Flyway 迁移脚本。
  Parallelization: Wave 1 | Blocked by: — | Blocks: 2
  References: `api-service/.../db/migration/` 或 MyBatis schema；`api-service/.../entity/Page.java`
  Acceptance: 迁移脚本运行后 `page` 表包含新字段；`SELECT media_type FROM page LIMIT 1` 返回 `IMAGE`。
  QA: `mvn flyway:migrate` + `mvn test` 中 schema 校验测试。Evidence `.omo/evidence/task-1-media-item-video-support.sql`
  Commit: N | —

- [x] 2. 领域模型重构: Page → Media 实体与 Mapper 重命名
  What to do / Must NOT do: 将 `Page` 实体改名为 `Media`（保留 `@TableName("page")`），新增 `mediaType`/`duration`/`container`/`videoCodec`/`audioCodec` 字段；`PageMapper` → `MediaMapper`；更新所有注入 `PageMapper` 的 Service。不改 `Chapter.pageCount` 字段名。
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 8, 10, 12, 13, 14
  References: `api-service/.../entity/Page.java`; `api-service/.../mapper/PageMapper.java`; `api-service/.../service/ReaderService.java`; `api-service/.../service/impl/LqServiceImpl.java`; `api-service/.../service/impl/ComicServiceImpl.java`（大量 Page/PageMapper 引用）; `api-service/.../importer/event/ImportEventHandler.java`; `api-service/.../admin/service/MetadataExporter.java`
  Acceptance: `mvn compile` 通过，无 `Page` 残留引用（测试文件除外）。
  QA: `grep -r "PageMapper" api-service/src/main/java/` 返回空；`grep -r "class Page " api-service/src/main/java/` 返回空。Evidence `.omo/evidence/task-2-media-item-video-support.txt`
  Commit: N | —

- [x] 3. DirectoryParser 扩展: 识别视频文件并统一为 mediaFiles()
  What to do / Must NOT do: 新增 `VIDEO_EXT` 集合（`.mp4` `.webm` `.mkv` `.mov` `.avi`）；`imageFiles()` → `mediaFiles()`；`hasImages()` → `hasMedia()`；`.gif`/`.webp` 仍归为 IMAGE。不引入新依赖。
  Parallelization: Wave 2 | Blocked by: 1 | Blocks: 5, 6
  References: `worker-service/.../parse/DirectoryParser.java`; `worker-service/.../parse/DirectoryTree.java`
  Acceptance: `DirectoryParser.parse()` 对含 `.mp4` 的目录返回 `mediaFiles()` 包含该文件；对纯 `.jpg` 目录仍正常工作。
  QA: 单元测试：目录含 `001.jpg`+`002.mp4` → `mediaFiles().size() == 2`；纯图片目录 → 行为不变。Evidence `.omo/evidence/task-3-media-item-video-support.log`
  Commit: N | —

- [x] 4. MediaAnalyzer 新增: 图片 ImageIO + 视频 ffprobe 分析
  What to do / Must NOT do: 新建 `MediaAnalyzer` 组件；图片路径复用现有 `ImageIO` 读取宽高；视频路径调用 `ffprobe` 读取 `duration`/`width`/`height`/`container`/`video_codec`/`audio_codec`；`ffprobe` 失败时返回 `VIDEO` 但字段为 `null`。不转码、不生成 poster。
  Parallelization: Wave 2 | Blocked by: 1 | Blocks: 5, 6
  References: `worker-service/.../parse/MediaAnalyzer.java`（新建）; `worker-service/.../config/WorkerConfig.java`; `worker-service/.../parse/ComicMetadata.java`
  Acceptance: `MediaAnalyzer.analyze(jpgPath).mediaType()` 返回 `IMAGE`；`MediaAnalyzer.analyze(mp4Path).mediaType()` 返回 `VIDEO` 且 `duration() > 0`；`MediaAnalyzer.analyze(mkvPath)` 在 ffprobe 缺失时仍返回 `VIDEO` 且字段为 `null`。
  QA: 单元测试覆盖三种场景。Evidence `.omo/evidence/task-4-media-item-video-support.log`
  Commit: N | —

- [x] 5. ComicMetadata + MetadataAssembler 改造: PageInfo → MediaInfo
  What to do / Must NOT do: `ComicMetadata.PageInfo` → `MediaInfo`，字段 `imageName` → `fileName`，新增 `mediaType`/`duration`/`container`/`videoCodec`/`audioCodec`；`MetadataAssembler.scanPages()` → `scanMediaItems()`，对每个文件调用 `MediaAnalyzer.analyze()`；`chapters[].pages` → `chapters[].mediaItems`。不删除旧字段的 backward compatibility 构造函数。
  Parallelization: Wave 2 | Blocked by: 3, 4 | Blocks: 6
  References: `worker-service/.../parse/ComicMetadata.java`; `worker-service/.../parse/MetadataAssembler.java`; `worker-service/.../parse/MediaAnalyzer.java`
  Acceptance: `MetadataAssembler.assemble(tree, ctx)` 返回的 `ComicMetadata` 中，`chapters().get(0).mediaItems()` 包含 `mediaType` 字段。
  QA: 对混合 `jpg`+`mp4` 的目录执行 assemble → 验证 MediaInfo 列表长度和字段。Evidence `.omo/evidence/task-5-media-item-video-support.log`
  Commit: N | —

- [x] 6. DirectoryImportHandler 升级: 写入 metadata.json v3
  What to do / Must NOT do: 搬运逻辑对 `mediaItems()` 迭代，不区分类型；封面逻辑遇到视频首项时跳过（不生成封面）；`writeMetadata()` 输出 `version: 3`，字段 `mediaItems` 替代 `pages`，子项字段 `fileName` 替代 `imageName`，包含 `mediaType`/`duration`/`container`/`videoCodec`/`audioCodec`。保持 `version: 2` 的旧代码不删除（ImportEventHandler 兼容需要）。
  Parallelization: Wave 2 | Blocked by: 3, 4, 5 | Blocks: 8
  References: `worker-service/.../handler/DirectoryImportHandler.java`
  Acceptance: 导入含 `001.jpg`+`002.mp4` 的目录后，生成的 `metadata/{taskId}.json` 中 `version == 3`，`chapters[0].mediaItems[1].mediaType == "VIDEO"`。
  QA: 读取生成的 JSON 文件验证 schema。Evidence `.omo/evidence/task-6-media-item-video-support.json`
  Commit: N | —

- [x] 7. WorkerConfig 新增 ffprobePath 配置
  What to do / Must NOT do: `WorkerConfig` 新增 `ffprobePath` 字段；`application.yml` / `application.properties` 新增默认值 `worker-service/ffmpeg/ffprobe.exe`；与 `aria2cPath` 配置风格一致。不捆绑二进制。
  Parallelization: Wave 2 | Blocked by: 1 | Blocks: —
  References: `worker-service/.../config/WorkerConfig.java`; `worker-service/src/main/resources/application.yml`
  Acceptance: `WorkerConfig.getFfprobePath()` 返回配置值；无配置时返回默认路径。
  QA: Spring Context 加载测试验证配置绑定。Evidence `.omo/evidence/task-7-media-item-video-support.log`
  Commit: N | —

- [x] 8. ImportEventHandler 兼容: metadata.json v2 与 v3
  What to do / Must NOT do: 解析 `metadata.json` 时读取 `version` 字段；v2 时读取 `pages` 数组，字段名 `imageName`，所有 item 视为 `IMAGE`；v3 时读取 `mediaItems` 数组，字段名 `fileName`，使用其中的 `mediaType`。`insertChapter()` 中 `hqPath` 构造需兼容 `imageName`（v2）和 `fileName`（v3）。插入 `Media` 实体（原 `Page`），v3 时填充 `duration`/`container`/`videoCodec`/`audioCodec`。`chapter.pageCount` 和 `comic.totalPages` 按数组长度统计，自然包含视频，无需额外改造。不删除 v2 处理逻辑。
  Parallelization: Wave 3 | Blocked by: 2, 6 | Blocks: 10
  References: `api-service/.../importer/event/ImportEventHandler.java`; `api-service/.../entity/Media.java`
  Acceptance: v2 旧任务导入后所有记录 `media_type == IMAGE`；v3 新任务导入后视频记录 `media_type == VIDEO`。
  QA: 分别用 v2 和 v3 的 metadata.json 调用 ImportEventHandler，验证数据库记录。Evidence `.omo/evidence/task-8-media-item-video-support.log`
  Commit: N | —

- [x] 9. MetadataExporter 输出 metadata.json v3
  What to do / Must NOT do: `MetadataExporter.export()` 输出 `version: 3`，`chapters[].mediaItems` 替代 `pages`；每个 mediaItem 包含 `mediaType`/`duration`/`container`/`videoCodec`/`audioCodec`。保留 v2 读取能力（用于 rebuildFromHq）。
  Parallelization: Wave 3 | Blocked by: 2 | Blocks: 10
  References: `api-service/.../admin/service/MetadataExporter.java`
  Acceptance: 导出任意漫画后，`metadata/{comicId}.json` 中 `version == 3`，图片 item 的 `mediaType == IMAGE`，视频 item 的 `mediaType == VIDEO`。
  QA: 对比导出 JSON 的 schema。Evidence `.omo/evidence/task-9-media-item-video-support.json`
  Commit: N | —

- [x] 10. rebuildFromHq / scanRecover 兼容 v2/v3
  What to do / Must NOT do: `restoreComicInternal()` 读取 `version` 字段；v2 时将 `pages` 数组全部视为 `IMAGE`；v3 时读取 `mediaItems` 数组及其 `mediaType`。不删除 v2 兼容逻辑。
  Parallelization: Wave 3 | Blocked by: 8, 9 | Blocks: —
  References: `api-service/.../admin/service/impl/AdminServiceImpl.java`
  Acceptance: 对 v2 metadata 文件执行 rebuild → 所有记录 `media_type == IMAGE`；对 v3 metadata 文件执行 rebuild → 视频记录 `media_type == VIDEO`。
  QA: 单元测试覆盖两种版本 metadata 的恢复。Evidence `.omo/evidence/task-10-media-item-video-support.log`
  Commit: N | —

- [x] 11. LQ 生成器过滤: 仅对 IMAGE 类型生成 LQ
  What to do / Must NOT do: `LqServiceImpl` 和 `LqGenerateHandler` 中所有查询 `Media`（原 `Page`）的地方增加 `.eq(Media::getMediaType, "IMAGE")` 过滤；视频记录的 `lq_status` 保持 `NOT_APPLICABLE`。不删除 LQ 生成逻辑。
  Parallelization: Wave 3 | Blocked by: 2 | Blocks: —
  References: `api-service/.../importer/service/impl/LqServiceImpl.java`; `worker-service/.../event/LqGenerateHandler.java`
  Acceptance: 章节含视频时，LQ 生成任务只处理图片，视频记录的 `lq_status` 不变。
  QA: 对含 `jpg`+`mp4` 的章节触发 LQ → 验证只有 `jpg` 被处理。Evidence `.omo/evidence/task-11-media-item-video-support.log`
  Commit: N | —

- [x] 12. ReaderDTO/ReaderService 改造: MediaItemDTO 与 mediaType 填充
  What to do / Must NOT do: `ReaderDTO.PageDTO` → `MediaItemDTO`，保留 `pages` 字段名（List<MediaItemDTO>），新增 `mediaType`/`duration`/`container`/`videoCodec`/`audioCodec`；`ReaderService.getChapter()` 使用 `MediaMapper`（原 `PageMapper`），为每个 item 填充新字段；视频 item 的 `lqUrl` 为 `null`，`lqStatus` 为 `NOT_APPLICABLE`。不改 API 路径。
  Parallelization: Wave 4 | Blocked by: 2, 14 | Blocks: 15
  References: `api-service/.../reader/dto/ReaderDTO.java`; `api-service/.../reader/service/ReaderService.java`; `api-service/.../mapper/MediaMapper.java`
  Acceptance: `GET /api/chapters/{id}` 返回的 JSON 中，`pages[0].mediaType` 存在且为 `"IMAGE"` 或 `"VIDEO"`；视频 item 的 `lqUrl == null`。
  QA: 调用 API 对含视频的章节验证响应结构。Evidence `.omo/evidence/task-12-media-item-video-support.json`
  Commit: N | —

- [x] 13. ComicServiceImpl 封面 fallback: 跳过视频首项
  What to do / Must NOT do: `buildFallbackCoverMap()` 遇到 `media_type == VIDEO` 的 item 时跳过，继续向后查找第一张图片；`resolveFirstPageCoverUrl()` 同样跳过视频首项；`listCoverCandidates()` 遇到视频首项的章节时跳过该候选；`updateCover()` 允许用户手动选视频作为封面（设置 `coverPath` 为视频路径，前端自行处理展示）。若章节全为视频，返回 `null`。`FileUrlResolver` 和 `StorageLayout` 不改。
  Parallelization: Wave 4 | Blocked by: 2 | Blocks: —
  References: `api-service/.../comic/service/impl/ComicServiceImpl.java`; `api-service/.../common/storage/FileUrlResolver.java`
  Acceptance: 漫画第一章第一页为视频时，`listComics` 返回的 `coverUrl` 为 `null` 或后续图片的 URL。
  QA: 构造首项为视频的漫画 → 验证列表 API 的 `coverUrl`。Evidence `.omo/evidence/task-13-media-item-video-support.json`
  Commit: N | —

- [x] 14. 后端 DTO 重命名: PageInfo → MediaItemInfo, PageDTO → MediaItemDTO
  What to do / Must NOT do: `api-service/.../comic/dto/PageInfo.java` → `MediaItemInfo.java`，新增 `mediaType`/`duration`/`container`/`videoCodec`/`audioCodec`；`ReaderDTO.PageDTO` → `ReaderDTO.MediaItemDTO`；同步更新所有引用 `PageInfo` / `PageDTO` 的 Service 和 Controller。`ChapterPageVO.pages` 字段保留，元素类型改为 `MediaItemInfo`。不改 `pageCount` / `totalPages` 字段名。
  Parallelization: Wave 4 | Blocked by: 2 | Blocks: 12, 15
  References: `api-service/.../comic/dto/PageInfo.java`; `api-service/.../comic/dto/ChapterPageVO.java`; `api-service/.../reader/dto/ReaderDTO.java`; `api-service/.../comic/service/ComicService.java`; `api-service/.../comic/service/impl/ComicServiceImpl.java`（getChapterPages/listCoverCandidates/updateCover）; `api-service/.../comic/controller/ComicController.java`
  Acceptance: `mvn compile` 通过；无 `PageInfo` / `PageDTO` 残留引用（测试文件除外）。
  QA: `grep -r "PageInfo" api-service/src/main/java/` 返回空；`grep -r "PageDTO" api-service/src/main/java/` 返回空。Evidence `.omo/evidence/task-14-media-item-video-support.txt`
  Commit: N | —

- [x] 15. 前端类型重构: PageInfo → MediaItemInfo
  What to do / Must NOT do: `frontend/src/types/index.ts` 中 `PageInfo` → `MediaItemInfo`，新增 `mediaType`/`duration`/`container`/`videoCodec`/`audioCodec`；同步更新所有引用 `PageInfo` 的文件。暂时保留 `ReaderDTO.pages` 字段名（元素类型改为 `MediaItemInfo[]`）。
  Parallelization: Wave 5 | Blocked by: 12, 14 | Blocks: 16, 17
  References: `frontend/src/types/index.ts`; `frontend/src/views/reading/reader/components/ReaderPagedViewport.vue`; `frontend/src/views/reading/reader/components/ReaderViewport.vue`; `frontend/src/views/reading/reader/components/ReaderImageItem.vue`; `frontend/src/stores/reader-store.ts`; `frontend/src/services/api.ts`
  Acceptance: `npm run build` 通过，无 TypeScript 类型错误。
  QA: `npm run build` 输出无 error。Evidence `.omo/evidence/task-15-media-item-video-support.log`
  Commit: N | —

- [x] 16. VideoPlayer.vue 新增: 视频渲染组件
  What to do / Must NOT do: 新建 `VideoPlayer.vue`，接收 `hqUrl`/`mediaType`/`width`/`height`/`duration`/`container`/`videoCodec`/`audioCodec` props；使用 `<video controls preload="metadata">`；不自动播放；若 `<video>` 触发 error 事件，显示「浏览器无法播放」提示 + 文件信息。不引入第三方播放器。
  Parallelization: Wave 5 | Blocked by: 15 | Blocks: 17
  References: `frontend/src/views/reading/reader/components/VideoPlayer.vue`（新建）
  Acceptance: 组件编译通过；传入 `mediaType == VIDEO` 时渲染 `<video>`；传入 `mediaType == IMAGE` 时不使用。
  QA: 单元测试/Storybook 验证组件渲染。Evidence `.omo/evidence/task-16-media-item-video-support.log`
  Commit: N | —

- [x] 17. ReaderPagedViewport 改造: 按 mediaType 切换组件
  What to do / Must NOT do: 模板中根据 `page.mediaType` 条件渲染：`IMAGE` → `ProgressiveImage`，`VIDEO` → `VideoPlayer`；aspect ratio 计算兼容视频宽高；翻页逻辑（wheel、page-request）完全不变。不改滚动阅读器（如存在）。
  Parallelization: Wave 5 | Blocked by: 15, 16 | Blocks: —
  References: `frontend/src/views/reading/reader/components/ReaderPagedViewport.vue`
  Acceptance: 混合 `IMAGE`+`VIDEO` 的章节在阅读器中正确渲染；翻到视频页时显示 VideoPlayer；滚轮翻页继续工作。
  QA: Playwright 或手动浏览器测试：混合章节翻页验证。Evidence `.omo/evidence/task-17-media-item-video-support.png`
  Commit: N | —

- [x] 18. 前端封面与列表: 视频占位图
  What to do / Must NOT do: 漫画列表页对 `coverUrl == null` 显示通用视频占位图（Element Plus placeholder 或自定义图标）；漫画详情页封面区域同理。不修改后端 API 路径。
  Parallelization: Wave 5 | Blocked by: 15 | Blocks: —
  References: `frontend/src/views/.../ComicList.vue`; `frontend/src/views/.../ComicDetail.vue`
  Acceptance: 视频首项漫画在列表中显示占位图而非空白。
  QA: 浏览器截图验证列表渲染。Evidence `.omo/evidence/task-18-media-item-video-support.png`
  Commit: N | —

- [x] 19. 集成验证: 端到端导入 + 阅读器测试
  What to do / Must NOT do: 准备测试目录（`001.jpg` + `002.mp4`）；执行完整导入流程（DIRECTORY 类型）；验证：① `metadata.json` v3 正确 ② 数据库 `media_type` 区分 IMAGE/VIDEO ③ 阅读器 API 返回 `MediaItemDTO` ④ 前端正确渲染。不测试转码或 LQ。
  Parallelization: Wave 6 | Blocked by: 6, 8, 11, 12, 17 | Blocks: —
  References: `api-service/.../controller/ImportController.java`; `api-service/.../reader/controller/ReaderController.java`
  Acceptance: 导入后数据库 `page` 表包含 `media_type == VIDEO` 的记录；阅读器 API 返回的视频 item 包含 `duration`/`container`/`videoCodec`/`audioCodec`。
  QA: 手动或自动化端到端测试。Evidence `.omo/evidence/task-19-media-item-video-support.log`
  Commit: N | —

- [x] 20. 回归测试: 现有图片漫画零影响
  What to do / Must NOT do: 用纯图片目录执行导入，验证行为与修改前完全一致：`media_type == IMAGE`，LQ 生成正常，阅读器正常，封面正常。不引入任何图片行为变化。
  Parallelization: Wave 6 | Blocked by: 6, 8, 11, 12, 17 | Blocks: —
  References: 现有测试用例、纯图片导入目录
  Acceptance: 纯图片漫画导入后 `media_type` 全为 `IMAGE`；LQ 生成成功；阅读器正常显示；封面正常生成。
  QA: 端到端回归测试。Evidence `.omo/evidence/task-20-media-item-video-support.log`
  Commit: N | —

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.

- [x] F1. Plan compliance audit
  由 Oracle Agent 审查：所有设计文档中的决策是否在代码中落实；`MediaItemDTO` 字段是否完整；`metadata.json` v3 schema 是否匹配；LQ 过滤是否生效。输出审计报告。
  Evidence: `.omo/evidence/f1-plan-compliance-media-item-video-support.md`

- [x] F2. Code quality review
  运行 `mvn compile`（api-service + worker-service）和 `npm run build`（frontend），确认零错误。检查是否有 `Page` 残留引用、`ImageIO` 误用于视频、`lq_status` 对视频填错等问题。
  Evidence: `.omo/evidence/f2-code-quality-media-item-video-support.log`

- [x] F3. Real manual QA
  准备测试目录（001.jpg + 002.mp4 + 003.webp），执行 DIRECTORY 导入，确认：
  - metadata.json v3 正确生成
  - 数据库三条记录 media_type 分别为 IMAGE/VIDEO/IMAGE
  - 阅读器 API 返回的 pages[1] 包含 mediaType=VIDEO、duration>0、container="mp4"
  - 前端翻到视频页显示 VideoPlayer，翻到图片页显示 ProgressiveImage
  - 纯图片目录导入后行为与修改前完全一致
  Evidence: `.omo/evidence/f3-manual-qa-media-item-video-support.log`

- [x] F4. Scope fidelity
  确认未实现任何 Must NOT have 项目：无转码、无 Poster 生成、无视频 LQ、无 reading_history 修改、无 StorageService 职责变化。
  Evidence: `.omo/evidence/f4-scope-fidelity-media-item-video-support.md`

## Commit strategy

本次改动量大、跨服务、跨前后端，采用**单一批次合并**策略：

- 所有 20 个 todo 完成后，统一走 `git diff --stat` 确认改动范围。
- 分三个 commit：
  1. `feat(backend): Page → Media 领域模型重构与视频导入支持`
     - DB migration、Page→Media、DirectoryParser/MediaAnalyzer/MetadataAssembler、DirectoryImportHandler v3、ImportEventHandler v2/v3 兼容、MetadataExporter/Admin v3、LQ 过滤、WorkerConfig ffprobePath。
  2. `feat(backend): Reader 与 Comic API 支持 MediaItemDTO`
     - ReaderDTO/ReaderService 改造、ComicServiceImpl 封面 fallback、PageInfo DTO 更新。
  3. `feat(frontend): 阅读器支持视频混排`
     - types 重构、VideoPlayer.vue、ReaderPagedViewport 改造、封面占位图。
- 不逐 todo commit，避免中间状态破坏编译。
- 合并前必须通过 F1-F4 最终验证波。

## Success criteria

1. **编译零错误**：`mvn compile`（api-service + worker-service）通过，`npm run build`（frontend）通过。
2. **导入兼容**：纯图片目录导入后行为与修改前完全一致（回归测试通过）。
3. **视频混排导入**：含 `.jpg` + `.mp4` 的目录导入后，`metadata.json` 为 v3，数据库 `media_type` 正确区分 IMAGE/VIDEO。
4. **阅读器渲染**：阅读器 API 返回 `MediaItemDTO`，前端按 `mediaType` 正确渲染 `ProgressiveImage` 或 `VideoPlayer`。
5. **LQ 不处理视频**：`LqServiceImpl` 对视频记录零操作，视频 `lq_status` 保持 `NOT_APPLICABLE`。
6. **Admin 兼容**：`MetadataExporter` 输出 v3，`rebuildFromHq`/`scanRecover` 同时兼容 v2 和 v3。
7. **封面 fallback**：视频首项漫画的列表封面显示占位图，不崩溃、不空白。
