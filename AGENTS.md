# PROJECT KNOWLEDGE BASE - ComicAtlas

**Updated:** 2026-07-29
**Branch:** main
**语言**: 始终使用中文对话、注释、提交信息。

## OVERVIEW
AI 驱动个人漫画仓库平台。统一接收 ZIP/目录/EHENTAI 来源的漫画，支持图片+视频混排，完成导入、管理和阅读。
Spring Boot 3 + Vue3 + RabbitMQ + MySQL + Redis。
**所有导入统一 MANAGED 存储**——文件搬入 `F:/manga/hq/{comicId}/{chapterId}/`。

## STRUCTURE
```
comic-atlas/
├── api-service/             # 漫画CRUD + 导入 + Catalog + Reader + LQ/HQ删除 + 回收站 + MQ消费（Flyway 迁移在 src/main/resources/db/）
├── worker-service/          # 文件处理 + MQ消费 + 下载 + 解压 + 导入 + LQ/HQ删除 + 回收/恢复/永久清理 + ffprobe（模块化：config/event/command/importer/media/storage/export/file/process/image）
├── comic-common/            # 共享事件 DTO（31 个事件 record + ComicEvent sealed interface + payload 数据载体，Jackson 多态序列化）+ MQ 契约/元数据/工具（constant/dto/event/metadata/mq/util）
├── gateway/                 # Spring Cloud Gateway: 路由 + Nacos发现
├── frontend/                # Vue3/Vite: 列表 + 详情 + 阅读器 + 管理后台 + 存储管理
├── scripts/                 # dev/qa/db/release 开发与运维脚本（入口 scripts/dev/start-dev.ps1）
├── tools/                   # migration/maintenance/vendor 迁移、维护与第三方二进制
├── docs/                    # 文档中心（入口 docs/README.md：api/user-guide/development-guide + architecture/operations/releases 等专题）
├── nginx.conf               # /files/{root}/{path} → alias /storage/{root}/
├── docker-compose.yml       # 项目服务：Gateway + API + Nginx
└── docker-compose.infra.yml # 基础设施：MySQL + Redis + RabbitMQ + Nacos
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
| LQ 完成处理 | `api-service/.../management/event/ManagementCommandResultHandler.java` | 处理 LQ 命令结果，更新 media.lq_status + lq_path |
| HQ 删除完成 | `api-service/.../event/HqDeletedHandler.java` | 更新 media.hq_status=DELETED |
| 回收站/永久清理 | `api-service/.../management/trash/TrashLifecycleController.java` | POST /api/trash/... restore/purge/reconcile（删除=回收，永久删除=purge） |
| 目录扫描 | `api-service/.../importer/controller/DirectoryScanTaskController.java` | POST /api/tasks/directory-scan，漫画集根目录批量发现（直接子目录=候选漫画） |
| 媒体上传（预留能力） | `api-service/.../upload/` | 分块上传后端可用、无前端入口，接口能力预留 |
| 恢复任务 API | `api-service/.../controller/RecoveryTaskController.java` | POST /api/tasks/recovery |
| 恢复任务 Service | `api-service/.../service/impl/RecoveryTaskServiceImpl.java` | 创建/重试/列表 |
| 恢复事件发布 | `api-service/.../event/RecoveryEventPublisher.java` | 发送恢复事件到 MQ |
| 恢复事件处理 | `api-service/.../event/RecoveryEventHandler.java` | 消费 MQ 事件，逐本调用 RecoveryEngine |
| 恢复引擎 | `api-service/.../recovery/RecoveryEngine.java` | 单本漫画的 DB 恢复逻辑 |
| Worker 恢复入口 | `worker-service/.../event/RecoveryTaskHandler.java` | 扫描 HQ 目录，发布 comicId 列表 |
| 事件 DTO | `comic-common/.../event/` | 31 个事件 record + ComicEvent sealed interface + payload/（数据载体） |
| MQ 常量 | `comic-common/.../constant/` | MqExchanges/MqQueues/MqRoutingKeys（exchange/queue/routingKey 契约） |
| 元数据构建 | `comic-common/.../metadata/` | MetadataV3/MetadataJsonBuilder（V3 元数据模型） |
| MQ 消费支持 | `comic-common/.../mq/` | MqConsumerSupport（统一 ACK/Reject/DLQ 策略） |
| 工具类 | `comic-common/.../util/` | ImageDimensionsReader（图片尺寸读取） |
| DTO | `comic-common/.../dto/` | ScanItemDTO/ScanResultDTO/TrashManifestDTO/TrashManifestItemDTO/OutboxStatsDTO 等 |
| 枚举 | `api-service/.../common/enums/` | TaskType/TaskStage/ManagementTaskStatus/TranscodeStatus 等（仅 api 消费） |
| Worker 入口 | `worker-service/.../event/ImportTaskHandler.java` | sourceType 路由到统一 handler |
| 取消任务 | `worker-service/.../event/CancelHandler.java` | ConcurrentHashMap 标记 |
| LQ 生成 | `worker-service/.../command/LqCommandHandler.java` | 消费 management 命令，调用 ImageOptimizer 外部工具 |
| HQ 删除 | `worker-service/.../event/HqDeleteHandler.java` | 按章节删除 HQ 图片 |
| 目录解析 | `worker-service/.../importer/DirectoryParser.java` | 输出 DirectoryTree（纯树，无业务语义） |
| 元数据组装 | `worker-service/.../importer/MetadataAssembler.java` | DirectoryTree → ComicMetadata（注入 Catalog/Chapter） |
| 媒体分析 | `worker-service/.../media/MediaAnalyzer.java` | 图片尺寸 + ffprobe 视频元数据 |
| 统一导入 | `worker-service/.../importer/DirectoryImportHandler.java` | handle() 解析→暂存文件到 HQ→写metadata |
| 导入最终化 | `worker-service/.../event/ImportStorageFinalizeHandler.java` | 两阶段最终化：hq/{comicId}/{globalOrder} → hq/{comicId}/{chapterId} |
| 最终化落库 | `api-service/.../importer/service/impl/ImportPersistenceServiceImpl.java` | 逐章收尾，全 READY → comic READY / task SUCCESS |
| ZIP 导入 | `worker-service/.../importer/ZipImportHandler.java` | 解压→委托 DirectoryImportHandler；入口必须是最后 `.zip`，缺任一卷失败，`.z01` 不可作为入口 |
| 分卷解析 | `worker-service/.../file/archive/ZipVolumeResolver.java` | 最后 `.zip` 为唯一入口 → 有序 `.z01..zNN`+主文件（缺号/重复/非法命名/非普通文件拒绝） |
| ZIP 解压 | `worker-service/.../file/extract/ZipExtractor.java` | Commons ZipFile 随机访问 + 标准分卷 + 安全校验；`.z01` 永不作为入口 |
| 导出编排 | `worker-service/.../export/ExportService.java` | collect → manifest → ZipBuilder → 原子发布 `EXPORT/{taskId}`（本地路径产物，无下载端点） |
| 分卷 ZIP 构建 | `worker-service/.../export/ZipBuilder.java` | `ZipBuildResult(主 .zip, 有序分卷, 总大小)`；manifest 总未压缩 > `zip.splitSize` 才分卷 |
| EHENTAI 导入 | `worker-service/.../file/download/EhentaiDownloadService.java` | 下载(Archiver优先→Torrent兜底)→解压→返回源目录，委托 DirectoryImportHandler |
| 存储服务 | `worker-service/.../storage/StorageService.java` | store/resolve/exists/delete |
| 存储根 | `worker-service/.../storage/StorageRoot.java` | path + resolve() + exists() |
| 文件引用 | `worker-service/.../storage/StorageRef.java` | rootKey + relativePath |
| 图片优化 | `worker-service/.../image/ImageOptimizer.java` | 外部 Go 工具生成 WebP LQ |
| URL 解析 | `api-service/.../storage/FileUrlResolver.java` | Page → /files/{root}/{path} |
| 路径布局 | `api-service/.../storage/StorageLayout.java` | forPage(comicId, chapterId, imageName) |
| 元数据模型 | `worker-service/.../media/ComicMetadata.java` | catalogs + chapters + mediaItems(IMAGE/VIDEO) |
| 导入上下文 | `worker-service/.../importer/ImportContext.java` | sourceType + sourcePath |
| 命令执行器 | `worker-service/.../command/` | TranscodeCommandHandler/TrashCommandHandler 等 8 个（ManagementCommandDispatcher 路由） |
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
  ├─ ZIP → ZipImportHandler → ZipExtractor(标准分卷) → DirectoryImportHandler.handle()
  ├─ REGISTER → DirectoryImportHandler.handle()
  └─ EHENTAI → EhentaiDownloadService → 下载(Archiver优先→Torrent兜底) → 解压 → DirectoryImportHandler.handle()
  ↓
DirectoryImportHandler（两阶段之第一阶段 staging）:
  DirectoryParser → MetadataAssembler → MediaAnalyzer(图片尺寸+ffprobe视频)
  → 文件暂存 HQ hq/{comicId}/{globalOrder}（DB ID 生成前的漫画内暂存键）→ metadata.json
  ↓
MQ task.completed
  ↓
API ImportEventHandler: 读 metadata.json → INSERT catalog+chapter+media(IMAGE/VIDEO)
  → 插入章节取得不可变 chapterId（comic 保持 IMPORTING）→ 逐章发送 finalize.requested
  ↓
Worker ImportStorageFinalizeHandler（两阶段之第二阶段 最终化）:
  逐章把 hq/{comicId}/{globalOrder} 移动到 hq/{comicId}/{chapterId} → 逐章发送 finalize.completed
  ↓
API ImportPersistenceService: 逐章 media/chapter → READY，全部章节完成才 UPDATE comic→READY / task→SUCCESS
```

**分卷 ZIP 导入规则**：
- 导入入口必须是同 basename 分卷组中**最后一个 `.zip`**（`sourcePath` 指向主文件），`.z01..zNN` 与主文件须同目录同 basename。
- 缺任一卷（如存在 `.z01/.z03` 缺 `.z02`）导入直接失败；`.z01` 永不作为入口。
- 导出产物布局：`EXPORT/{taskId}/{base}.z01..zNN + {base}.zip`（总未压缩 > `worker.zip.splitSize` 默认 **2 GiB** 才分卷；单条目与总量上限 `maxEntrySize/maxTotalSize` 默认 **30 GiB**）。

## STORAGE
所有漫画统一 MANAGED，文件搬入 `F:/manga/hq/{comicId}/{chapterId}/`。

**DB 存储**：
- `comic.storage_policy` = `MANAGED`
- `page.hq_root` = `HQ`，`page.hq_path` = `{comicId}/{chapterId}/001.jpg`

**删除语义**：删除 = 回收（软删除，`TRASHED` 生命周期，文件移入 trash 卷），永久删除 = `purge`（仅接受 `TRASHED` + 7 天保留期 + 二次确认 token）。旧的"完整删除"直删链路（`comic.delete` 事件）已移除。

**迁移**：只改 `storage.roots.HQ.path` 配置，不改 DB。

## URL 规范
```
/files/{rootKey_lc}/{relativePath}
```
- `/files/hq/` → alias `F:/manga/hq/` (60d cache)
- `/files/lq/` → alias `F:/manga/lq/` (30d cache)
- `/files/thumbs/` → alias `F:/manga/thumbs/` (7d cache)

URL 统一由 `FileUrlResolver.resolve(page)` 生成，不手拼。

## RABBITMQ
| Exchange | RoutingKey | Queue | Consumer |
|----------|-----------|-------|----------|
| comic.import | task.created | import.task.queue | Worker ImportTaskHandler |
| comic.import | task.completed | import.result.queue | API ImportEventHandler |
| comic.import | task.failed | import.failed.queue | API ImportEventHandler |
| comic.import | import.storage.finalize.requested | import.storage.finalize.requested.queue | Worker ImportStorageFinalizeHandler |
| comic.import | import.storage.finalize.completed | import.storage.finalize.completed.queue | API ImportStorageFinalizeEventHandler |
| comic.import | import.storage.finalize.failed | import.storage.finalize.failed.queue | API ImportStorageFinalizeEventHandler |
| comic.task | status.changed | task.status.queue | API ImportEventHandler |
| comic.task | cancel.requested | cancel.task.queue | Worker CancelHandler |
| comic.image | hq.delete.requested | hq.delete.queue | Worker HqDeleteHandler |
| comic.image | hq.delete.completed | hq.delete.result.queue | API HqDeletedHandler |
| comic.image | video.metadata.fix.requested | video.metadata.fix.queue | Worker VideoMetadataFixHandler |
| comic.image | video.metadata.fix.completed | video.metadata.fix.result.queue | API VideoMetadataFixCompletedHandler |
| comic.export | task.created | export.task.queue | Worker ExportTaskHandler |
| comic.export | task.started | export.started.result.queue | API ExportStartedHandler |
| comic.export | task.completed | export.completed.result.queue | API ExportCompletedHandler |
| comic.export | task.failed | export.failed.result.queue | API ExportFailedHandler |
| comic.export | metadata.refresh.requested | metadata.refresh.queue | Worker MetadataRefreshHandler |
| comic.recovery | recovery.requested | recovery.task.queue | Worker RecoveryTaskHandler |
| comic.recovery | recovery.progress | recovery.result.queue | API RecoveryEventHandler |
| comic.recovery | recovery.completed | recovery.result.queue | API RecoveryEventHandler |
| comic.recovery | recovery.failed | recovery.result.queue | API RecoveryEventHandler |
| comic.scan | scan.requested | scan.task.queue | Worker DirectoryScanHandler |
| comic.scan | scan.completed | scan.result.queue | API DirectoryScanEventHandler |
| comic.scan | scan.failed | scan.result.queue | API DirectoryScanEventHandler |
| comic.management | command.requested | management.command.queue | Worker ManagementCommandDispatcher |
| comic.management | command.cancel | management.cancel.queue | （未注册消费者） |
| comic.management | command.completed / failed / progress | management.result.queue | API ManagementCommandResultHandler |

**死信**: 主队列除 comic.task（task.status.queue / cancel.task.queue 无 DLX）外均配置 DLX + DLQ（comic.import.dlx / comic.image.dlx / comic.export.dlx / comic.recovery.dlx / comic.scan.dlx / comic.management.dlx）

**Broker 遗留实体清理**: 代码已不再声明旧完整删除（comic.delete）的 exchange/queue/DLQ（`delete.task.queue` / `delete.result.queue` / `comic.delete.dlx` 等）。但已运行 Broker 中残留的 durable 实体不会被 Spring 自动删除，需用户在停服且确认无消息后单独人工清理（RabbitMQ 管理台或 `rabbitmqctl`）；本计划不执行 Broker 删除。

**序列化**: Jackson2JsonMessageConverter

**事件命名规范（冻结）**:
| Event | RoutingKey | DTO |
|-------|-----------|----------------|
| ImportTaskCreated | comic.import.task.created | ImportTaskCreatedEvent |
| ImportTaskCompleted | comic.import.task.completed | ImportTaskCompletedEvent |
| ImportTaskFailed | comic.import.task.failed | ImportTaskFailedEvent |
| ImportStorageFinalizeRequested | comic.import.import.storage.finalize.requested | ImportStorageFinalizeRequestedEvent |
| ImportStorageFinalizeCompleted | comic.import.import.storage.finalize.completed | ImportStorageFinalizeCompletedEvent |
| ImportStorageFinalizeFailed | comic.import.import.storage.finalize.failed | ImportStorageFinalizeFailedEvent |
| TaskStatusChanged | comic.task.status.changed | TaskStatusChangedEvent |
| CancelTask | comic.task.cancel.requested | CancelTaskEvent |
| DeleteHqRequested | comic.image.hq.delete.requested | DeleteHqRequestedEvent |
| HqDeleted | comic.image.hq.delete.completed | HqDeletedEvent |
| VideoMetadataFixRequested | comic.image.video.metadata.fix.requested | VideoMetadataFixRequestedEvent |
| VideoMetadataFixCompleted | comic.image.video.metadata.fix.completed | VideoMetadataFixCompletedEvent |
| ExportTaskCreated | comic.export.task.created | ExportTaskCreatedEvent |
| ExportTaskStarted | comic.export.task.started | ExportTaskStartedEvent |
| ExportTaskCompleted | comic.export.task.completed | ExportTaskCompletedEvent |
| ExportTaskFailed | comic.export.task.failed | ExportTaskFailedEvent |
| MetadataRefresh | comic.export.metadata.refresh.requested | MetadataRefreshEvent |
| RecoveryRequested | comic.recovery.requested | RecoveryRequestedEvent |
| RecoveryScanCompleted | comic.recovery.progress | RecoveryScanCompletedEvent |
| RecoveryProgress | comic.recovery.progress | RecoveryProgressEvent |
| RecoveryCompleted | comic.recovery.completed | RecoveryCompletedEvent |
| RecoveryFailed | comic.recovery.failed | RecoveryFailedEvent |
| DirectoryScanRequested | comic.scan.requested | DirectoryScanRequestedEvent |
| DirectoryScanCompleted | comic.scan.completed | DirectoryScanCompletedEvent |
| DirectoryScanFailed | comic.scan.failed | DirectoryScanFailedEvent |
| ManagementCommandRequested | comic.management.command.requested | ManagementCommandRequestedEvent |
| ManagementCommandProgress | comic.management.command.progress | ManagementCommandProgressEvent |
| ManagementCommandCompleted | comic.management.command.completed | ManagementCommandCompletedEvent |
| ManagementCommandFailed | comic.management.command.failed | ManagementCommandFailedEvent |
| ManagementCommandCancelRequested | comic.management.command.cancel | ManagementCommandCancelRequestedEvent |
| MediaUploadCompleted | comic.management.command.completed | MediaUploadCompletedEvent |

## CONFIG / ENV
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MANGA_ROOT` | `F:/manga` | 存储根目录，Worker 写 / Nginx 读 |
| `PROXY_HOST` | `127.0.0.1` | HTTP 代理 |
| `PROXY_PORT` | `7897` | HTTP 代理端口 |
| `ARIA2C_PATH` | `tools/aria2c/aria2c.exe` | aria2c 路径 |
| `FFMPEG_PATH` | `tools/ffmpeg/ffmpeg.exe` | ffmpeg 路径（视频封面抽取） |
| `FFPROBE_PATH` | `tools/ffmpeg/ffprobe.exe` | ffprobe 路径（视频元数据提取） |
| `IMAGE_OPTIMIZER_PATH` | `tools/image-optimizer/image-optimizer.exe` | LQ 图片优化工具路径 |
| `MYSQL_PASS` | 无默认值（必填） | Worker 只读 MySQL 密码（仅 GRANT SELECT） |

## DB SCHEMA 要点
- `catalog` 表：comic_id, parent_id, title, sort_order（可选目录树）
- `chapter` 表：catalog_id(nullable), sort_order, global_order（全书阅读顺序）
- `chapter.chapter_no` = 原始编号，不参与排序。排序只用 `global_order`
- `page` 表：hq_root, hq_path（替代旧 image_name）
- `page.hq_status` = PENDING（文件复制前），→ READY（复制成功），→ DELETED（HQ 删除后），MISSING（文件丢失），另有 DELETE_QUEUED/DELETING/FAILED（HqStatus 枚举）
- `page.lq_status` = NOT_GENERATED（不自动生成 LQ），另有 QUEUED/GENERATING/READY/MISSING/FAILED（LqStatus 枚举）
- `page.status` / `chapter.status` = MediaLifecycleStatus 生命周期（STAGING→READY→TRASHED→DELETED，含 DELETING/TRASHING/RESTORING/PURGING）
- `page` 视频字段：media_type, duration, container, video_codec, audio_codec（ffprobe 提取）
- `import_task` 表：source_type, source_path（修复 retry 硬编码问题）
- `comic.category_id` 替代旧 `category` VARCHAR 列
- 新增表：`management_task`/`management_task_item`（管理任务）、`outbox_message`/`inbox_receipt`（Outbox 发件箱）、`upload_session`/`upload_file`（分块上传）、`recovery_task`、`directory_scan_task`、`export_task`（回收清单以 TrashManifest DTO + resultRef 存储，非表）
- comic/chapter/page 均含 `version` 乐观锁列（管理端编辑）
- **已清理死字段**：comic(root_key, relative_path, lq_status)、catalog(path, level)、import_task(current_page, downloaded_bytes)

## CONVENTIONS
- Java: Lombok, NIO Path/Files, MyBatis Plus LambdaQueryWrapper
- Vue: Composition API + `<script setup lang="ts">` + Element Plus
- 枚举: Java `enum` + DB `VARCHAR`，禁止 MySQL `ENUM`
- 提交: 中文 commit message
- 包名: `com.comicatlas.api.importer`（非 `import`，关键字冲突）

## 阿里 Java 开发规范

后端 Java 代码遵循《阿里巴巴 Java 开发手册》的强制规则，并结合本项目“本地个人应用、无需鉴权、Worker 只读 MySQL”的边界执行。规范优先级高于个人编码习惯；与现有架构约束冲突时，以本文件的架构约束为准。

### 命名与结构

- 类、接口、枚举使用 UpperCamelCase；方法、参数、局部变量使用 lowerCamelCase；常量使用 `UPPER_SNAKE_CASE`。
- 布尔字段和方法使用 `is`、`has`、`can` 等明确前缀，禁止使用含糊的 `flag`、`status` 作为布尔值名称。
- 禁止使用单字母、拼音、无意义缩写和魔法数字；跨方法复用的值必须提取为有语义的常量。
- 包名全部小写，按业务边界分包；Controller、Service、Mapper、EventHandler 等职责不得混放。
- 一个类只负责一个清晰职责；公共方法参数和返回值优先使用领域 DTO/record，避免直接暴露数据库实体。

### 类型、集合与对象使用

- 数值比较使用 `equals`/安全比较方法，禁止对包装类型使用 `==`；字符串常量放在左侧调用 `equals`。
- 禁止使用 `null` 作为集合返回值；无结果返回空集合或明确的 Optional/领域结果。
- 集合初始化时按预期容量设置初始大小；遍历删除使用迭代器或批量操作，禁止在增强 `for` 中直接修改集合。
- 时间统一使用 `java.time`，禁止使用 `Date`、`Calendar` 和无时区字符串表达时间。
- Lombok 仅用于减少样板代码；实体、DTO 的 `equals`、`hashCode`、`toString` 不得包含敏感信息或造成循环引用。

### 异常、日志与资源

- 禁止捕获后静默忽略异常、直接 `printStackTrace` 或只记录异常字符串；必须保留原始异常作为 cause，并转换为项目统一的业务异常或明确的任务结果。
- 不得用异常控制正常业务分支；参数校验在边界完成，失败信息包含可定位的业务上下文。
- 日志使用项目日志框架，禁止输出密码、令牌、完整本地敏感路径和漫画内容；异常日志使用占位符，不拼接字符串。
- 外部进程、文件、网络、线程池和 MQ Channel 必须在所有分支释放或回收；中断异常必须恢复线程中断标志并结束当前任务流程。
- 禁止在循环中打印高频日志；批处理使用开始、进度、结束和失败摘要，避免日志污染。

### 分层、数据库与 MQ

- Controller 只负责协议适配和参数校验；业务编排放在 Service；持久化放在 Mapper；禁止 Controller 直接访问 Mapper 或拼装 SQL。
- API 是唯一业务 HTTP 入口。Worker 不提供业务 HTTP 接口，只通过 MQ 接收任务和回传结果；Worker 可读 MySQL，但禁止执行任何 INSERT、UPDATE、DELETE、DDL 或事务写操作。
- SQL 必须使用参数绑定，禁止字符串拼接；查询明确列名，禁止生产代码使用 `SELECT *`；批量操作控制单批大小并关注索引与 N+1 查询。
- 涉及多个写操作的 Service 方法必须明确事务边界；事务内不得执行长时间外部 IO、下载、解压或进程调用。
- MQ 消费必须明确 ACK、重试、幂等和死信策略；不得把取消/中断误报为普通业务失败，状态转换必须使用已定义的枚举。
- 数据库枚举字段使用 Java `enum` + `VARCHAR`；新增状态必须同步更新枚举、迁移、事件 DTO、状态机和测试。

### 并发、外部进程与文件安全

- 线程池必须有明确的核心/最大线程数、队列容量、拒绝策略和关闭方式，禁止无界队列和临时创建大量线程。
- 外部进程必须设置有限超时，超时、取消和中断时终止完整进程树、关闭流并回收任务；不得留下后台进程或长期占用 IO 线程。
- 文件路径必须通过 `Path.resolve` 和规范化校验限制在配置根目录内，禁止信任用户输入拼接绝对路径或目录穿越路径。
- 读写大文件使用流式处理和大小上限；临时文件、解压目录和失败产物必须有清理策略。

### 注释、测试与提交门禁

- 注释解释业务原因、约束和状态转换，不重复代码；公共 API、枚举状态和 MQ 事件必须有简明 Javadoc/说明。
- 修改后端代码至少运行对应模块测试；合并前运行 `./mvnw verify`、Checkstyle 和 `git diff --check`。新增缺陷必须补充回归测试，测试名称描述行为而非实现细节。
- 禁止提交调试代码、死代码、未使用导入、构建产物、日志、`.env`、凭据和宿主机个人路径；代码格式化不得夹带无关改动。
- Review 时同时检查变量命名、异常链、资源释放、事务边界、SQL 安全、Worker 只读边界和 MQ 状态一致性。

## GIT 工作流

### 分支职责

- `main`：用户稳定版本，只允许合并已验证的发布内容；当前 1.0 版本使用标签 `v1.0.0`。
- `develop`：日常开发和下一版本集成，必须保持可构建。
- `feature/<名称>`：从 `develop` 创建的短期功能分支。
- `fix/<名称>`：从 `develop` 创建的普通缺陷修复分支。
- `hotfix/<名称>`：从 `main` 创建的线上紧急修复分支，修复后必须同时合并回 `main` 和 `develop`。

禁止直接在 `main` 上开发。开始工作前先同步目标分支：

```bash
git switch develop
git pull --rebase origin develop
git switch -c feature/<功能名称>
```

### 提交规范

- 对话、注释、提交信息始终使用中文。
- 一个提交只解决一个完整问题，避免把功能、格式化、无关清理混在一起。
- 提交信息使用“动作 + 内容”，例如：`修复阅读器章节切换`、`新增漫画批量导入`、`完善 1.0 用户指南`。
- 提交前必须检查 `git status`、`git diff` 和 `git diff --cached`，确认没有 `.env`、日志、构建产物、漫画文件或无关修改。
- 不提交宿主机绝对路径、数据库密码、远程服务凭据和个人漫画文件。

### 合并和发布

功能完成后先在功能分支验证，再合并到 `develop`：

```bash
git add <相关文件>
git commit -m "完成 <功能名称>"
git switch develop
git merge --no-ff feature/<功能名称> -m "合入 <功能名称>"
```

发布流程：

1. 在 `develop` 完成前端构建、后端测试和真实导入—阅读链路验证。
2. 将 `develop` 合并到 `main`，提交信息使用 `发布 X.Y.Z`。
3. 在 `main` 创建带注释标签：`git tag -a vX.Y.Z -m "ComicAtlas X.Y.Z 稳定版本"`。
4. 发布后推送分支和标签：`git push origin main --follow-tags`、`git push origin develop`。
5. 发布说明放在 `docs/releases/vX.Y.Z.md`，用户操作说明维护在 `README.md` 和 `docs/user-guide.md`。

### 安全和回滚

- 禁止使用 `git push --force` 覆盖共享分支；确需改写历史时必须先确认。
- 禁止未经明确授权执行 `git reset --hard`、批量删除或清理用户文件。
- 撤销未提交的单个文件使用 `git restore <文件>`，执行前必须确认目标文件不含用户改动。
- 发布问题优先从 `main` 创建 `hotfix/<名称>`，修复并验证后同时合并回 `main` 和 `develop`。
- 合并前检查工作区干净；保留与任务无关的用户修改，不得擅自覆盖或丢弃。

## ANTI-PATTERNS
- 禁止 Worker 直接写 MySQL → 全部通过 MQ 事件回 API
- 禁止 LQ 自动生成 → 手动触发
- 禁止 URL 手拼 → 统一走 `FileUrlResolver`
- 禁止 `spring.cloud.nacos.config` 强制 import → 已 disabled
- 禁止 `page` 表存绝对路径 → 用 `hq_root` + `hq_path` 相对路径
