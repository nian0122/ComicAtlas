# PROJECT KNOWLEDGE BASE - ComicAtlas

**Updated:** 2026-07-22
**Branch:** main
**语言**: 始终使用中文对话、注释、提交信息。

## OVERVIEW
AI 驱动个人漫画仓库平台。统一接收 ZIP/目录/EHENTAI 来源的漫画，支持图片+视频混排，完成导入、管理和阅读。
Spring Boot 3 + Vue3 + RabbitMQ + MySQL + Redis。
**所有导入统一 MANAGED 存储**——文件搬入 `D:/manga/hq/{comicId}/{chapterId}/`。

## STRUCTURE
```
comic-atlas/
├── api-service/             # 漫画CRUD + 导入 + Catalog + Reader + LQ/HQ删除 + MQ消费
├── worker-service/          # 文件处理 + MQ消费 + 下载 + 解压 + 解析 + LQ/HQ删除 + ffprobe
├── comic-common/            # 共享事件 DTO（11 个 record + ComicEvent sealed interface，Jackson 多态序列化）
├── gateway/                 # Spring Cloud Gateway: 路由 + Nacos发现
├── frontend/                # Vue3/Vite: 列表 + 详情 + 阅读器 + 管理后台 + 存储管理
├── docs/                    # api.md + superpowers/specs|plans
├── nginx.conf               # /files/{root}/{path} → alias /storage/{root}/
└── docker-compose.yml       # MySQL + Redis + RabbitMQ + Nacos + Nginx
```

## WHERE TO LOOK
| 任务 | 位置 | Notes |
|------|------|-------|
| 漫画列表/详情 | `api-service/.../controller/ComicController.java` | list/detail/delete |
| 目录树 | `api-service/.../controller/CatalogController.java` | GET `/comics/{id}/catalog` |
| 章节阅读 | `api-service/.../reader/controller/ReaderController.java` | GET `/chapters/{id}` 返回 pages+prev/next |
| Catalog Service | `api-service/.../service/CatalogService.java` | buildTree 组装 ViewModel |
| Reader Service | `api-service/.../reader/service/ReaderService.java` | 按 global_order 取 prev/next |
| 导入 API | `api-service/.../controller/ImportController.java` | POST sourceType+sourcePath + batch/scan |
| 导入 Service | `api-service/.../service/impl/ImportServiceImpl.java` | 预创建 comic+task → MQ |
| LQ 手动触发 | `api-service/.../controller/LqController.java` | POST /comics/{id}/lq |
| HQ 删除 API | `api-service/.../controller/HqDeleteController.java` | POST /comics/{id}/delete-hq |
| MQ 消费 | `api-service/.../event/ImportEventHandler.java` | 读 metadata.json → INSERT |
| LQ 完成处理 | `api-service/.../event/LqCompletedHandler.java` | 更新 media.lq_status + lq_path |
| HQ 删除完成 | `api-service/.../event/HqDeletedHandler.java` | 更新 media.hq_status=DELETED |
| 删除完成处理 | `api-service/.../event/DeleteEventHandler.java` | DB 级联删除 |
| 事件 DTO | `comic-common/.../event/` | 12 个 record + ComicEvent sealed interface |
| Worker 入口 | `worker-service/.../event/ImportTaskHandler.java` | sourceType 路由到统一 handler |
| 取消任务 | `worker-service/.../event/CancelHandler.java` | ConcurrentHashMap 标记 |
| LQ 生成 | `worker-service/.../event/LqGenerateHandler.java` | 调用 ImageOptimizer 外部工具 |
| HQ 删除 | `worker-service/.../event/HqDeleteHandler.java` | 按章节删除 HQ 图片 |
| 完整删除 | `worker-service/.../event/DeleteHandler.java` | 删除 hq/lq/thumbs 全部文件 |
| 目录解析 | `worker-service/.../parse/DirectoryParser.java` | 输出 DirectoryTree（纯树，无业务语义） |
| 元数据组装 | `worker-service/.../parse/MetadataAssembler.java` | DirectoryTree → ComicMetadata（注入 Catalog/Chapter） |
| 媒体分析 | `worker-service/.../parse/MediaAnalyzer.java` | 图片尺寸 + ffprobe 视频元数据 |
| 统一导入 | `worker-service/.../handler/DirectoryImportHandler.java` | handle() 解析→搬文件→写metadata |
| ZIP 导入 | `worker-service/.../handler/ZipImportHandler.java` | 解压→委托 DirectoryImportHandler |
| EHENTAI 导入 | `worker-service/.../file/FileService.java` | 下载→解压→搬文件→metadata |
| 存储服务 | `worker-service/.../storage/LocalStorageService.java` | store/resolve/exists/delete |
| 存储根 | `worker-service/.../storage/StorageRoot.java` | path + resolve() + exists() |
| 文件引用 | `worker-service/.../storage/StorageRef.java` | rootKey + relativePath |
| 图片优化 | `worker-service/.../image/ImageOptimizer.java` | 外部 Go 工具生成 WebP LQ |
| 路径构建 | `worker-service/.../common/FilePathBuilder.java` | hqDir/lqDir/thumbPath 等 |
| URL 解析 | `api-service/.../storage/FileUrlResolver.java` | Page → /files/{root}/{path} |
| 路径布局 | `api-service/.../storage/StorageLayout.java` | forPage(comicId, chapterId, imageName) |
| 元数据模型 | `worker-service/.../parse/ComicMetadata.java` | catalogs + chapters + mediaItems(IMAGE/VIDEO) |
| 导入上下文 | `worker-service/.../parse/ImportContext.java` | sourceType + sourcePath |
| 存储管理 API | `api-service/.../controller/AdminStorageController.java` | stats/comics/chapters |
| 存储查询 | `api-service/.../service/StorageQueryService.java` | 聚合 HQ/LQ 大小+状态 |
| 前端路由 | `frontend/src/router/index.ts` | 14 routes（reading 6 + management 8） |
| Pinia Store | `frontend/src/stores/` | comic/reader/import/history/tag/app/management-comic/storage/category/reader-settings + reading.ts barrel |
| API 服务 | `frontend/src/services/api.ts` | comic/catalog/reader/import/lq/hq/admin |
| 存储服务 | `frontend/src/services/storage.ts` | fetchComics/fetchSummary/fetchChapters/executeOperation |
| 类型定义 | `frontend/src/types/index.ts` | CatalogNode/ChapterRef/ReaderDTO 等 + 存储类型 |
| 视频播放器 | `frontend/src/views/reading/reader/components/VideoPlayer.vue` | VIDEO 类型播放 |

## IMPORT FLOW
```
POST /api/tasks/import { sourceType:"ZIP"|"REGISTER"|"EHENTAI", sourcePath:"D:/..." }
  ↓
ImportServiceImpl: INSERT comic(IMPORTING) + import_task(PENDING) → MQ
  ↓
Worker ImportTaskHandler: sourceType 路由
  ├─ ZIP → ZipImportHandler → extract → DirectoryImportHandler.handle()
  ├─ REGISTER → DirectoryImportHandler.handle()
  └─ EHENTAI → FileService → 下载(Archiver优先→Torrent兜底) → 解压 → DirectoryImportHandler.handle()
  ↓
DirectoryImportHandler: DirectoryParser → MetadataAssembler → MediaAnalyzer(图片尺寸+ffprobe视频) → 搬文件到 HQ → metadata.json
  ↓
MQ task.completed
  ↓
API ImportEventHandler: 读 metadata.json → INSERT catalog+chapter+media(IMAGE/VIDEO), comic→READY
```

## STORAGE
所有漫画统一 MANAGED，文件搬入 `D:/manga/hq/{comicId}/{chapterId}/`。

**DB 存储**：
- `comic.storage_policy` = `MANAGED`
- `page.hq_root` = `HQ`，`page.hq_path` = `{comicId}/{chapterId}/001.jpg`

**迁移**：只改 `storage.roots.HQ.path` 配置，不改 DB。

## URL 规范
```
/files/{rootKey_lc}/{relativePath}
```
- `/files/hq/` → alias `D:/manga/hq/` (60d cache)
- `/files/lq/` → alias `D:/manga/lq/` (30d cache)
- `/files/thumbs/` → alias `D:/manga/thumbs/` (7d cache)

URL 统一由 `FileUrlResolver.resolve(page)` 生成，不手拼。

## RABBITMQ
| Exchange | RoutingKey | Queue | Consumer |
|----------|-----------|-------|----------|
| comic.import | task.created | import.task.queue | Worker ImportTaskHandler |
| comic.import | task.completed | import.result.queue | API ImportEventHandler |
| comic.import | task.failed | import.result.queue | API ImportEventHandler |
| comic.task | status.changed | task.status.queue | API ImportEventHandler |
| comic.task | cancel.requested | cancel.task.queue | Worker CancelHandler |
| comic.image | lq.generate | lq.generate.queue | Worker LqGenerateHandler |
| comic.image | lq.completed | lq.result.queue | API LqCompletedHandler |
| comic.image | hq.delete.requested | hq.delete.queue | Worker HqDeleteHandler |
| comic.image | hq.delete.completed | hq.delete.result.queue | API HqDeletedHandler |
| comic.delete | delete.requested | delete.task.queue | Worker DeleteHandler |
| comic.delete | delete.completed | delete.result.queue | API DeleteEventHandler |

**死信**: 所有主队列配置 DLX + DLQ（comic.import.dlx / comic.image.dlx / comic.delete.dlx）

**序列化**: Jackson2JsonMessageConverter

**事件命名规范（冻结）**:
| Event | RoutingKey | DTO（Phase B） |
|-------|-----------|----------------|
| ImportTaskCreated | comic.import.task.created | ImportTaskCreatedEvent |
| ImportTaskCompleted | comic.import.task.completed | ImportTaskCompletedEvent |
| ImportTaskFailed | comic.import.task.failed | ImportTaskFailedEvent |
| LqGenerate | comic.image.lq.generate | LqGenerateEvent |
| LqCompleted | comic.image.lq.completed | LqCompletedEvent |
| DeleteRequested | comic.delete.requested | DeleteRequestedEvent |
| DeleteCompleted | comic.delete.completed | DeleteCompletedEvent |
| DeleteHqRequested | comic.image.hq.delete.requested | DeleteHqRequestedEvent |
| HqDeleted | comic.image.hq.delete.completed | HqDeletedEvent |
| CancelTask | comic.task.cancel.requested | CancelTaskEvent |
| TaskStatusChanged | comic.task.status.changed | TaskStatusChangedEvent |

## CONFIG / ENV
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MANGA_ROOT` | `D:/manga` | 存储根目录，Worker 写 / Nginx 读 |
| `PROXY_HOST` | `127.0.0.1` | HTTP 代理 |
| `PROXY_PORT` | `7897` | HTTP 代理端口 |
| `ARIA2C_PATH` | `worker-service/aria2-.../aria2c.exe` | aria2c 路径 |

## DB SCHEMA 要点
- `catalog` 表：comic_id, parent_id, title, sort_order（可选目录树）
- `chapter` 表：catalog_id(nullable), sort_order, global_order（全书阅读顺序）
- `chapter.chapter_no` = 原始编号，不参与排序。排序只用 `global_order`
- `page` 表：hq_root, hq_path（替代旧 image_name）
- `page.hq_status` = PENDING（文件复制前），→ READY（复制成功），→ DELETED（HQ 删除后），MISSING（文件丢失）
- `page.lq_status` = NOT_GENERATED（不自动生成 LQ）
- `page` 视频字段：media_type, duration, container, video_codec, audio_codec（ffprobe 提取）
- `import_task` 表：source_type, source_path（修复 retry 硬编码问题）
- `comic.category_id` 替代旧 `category` VARCHAR 列
- **已清理死字段**：comic(root_key, relative_path, lq_status)、catalog(path, level)、import_task(current_page, downloaded_bytes)

## CONVENTIONS
- Java: Lombok, NIO Path/Files, MyBatis Plus LambdaQueryWrapper
- Vue: Composition API + `<script setup lang="ts">` + Element Plus
- 枚举: Java `enum` + DB `VARCHAR`，禁止 MySQL `ENUM`
- 提交: 中文 commit message
- 包名: `com.comicatlas.api.importer`（非 `import`，关键字冲突）

## ANTI-PATTERNS
- 禁止 Worker 直接写 MySQL → 全部通过 MQ 事件回 API
- 禁止 LQ 自动生成 → 手动触发
- 禁止 URL 手拼 → 统一走 `FileUrlResolver`
- 禁止 `spring.cloud.nacos.config` 强制 import → 已 disabled
- 禁止 `page` 表存绝对路径 → 用 `hq_root` + `hq_path` 相对路径
