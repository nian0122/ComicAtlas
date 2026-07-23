# comic-export - Work Plan

## TL;DR (For humans)

**What you'll get:** 漫画一键导出 ZIP 功能——在 StoragePage 点击"导出 ZIP"，Worker 后台打包 HQ 图片+视频（已删除的自动降级 LQ）+ metadata.json，完成后 TaskPage 显示状态、可下载或打开目录。

**Why this approach:** 完全复用现有 Import 的 MQ 异步流水线（API→MQ→Worker→MQ→API），Worker 新增只读 MyBatis Plus 访问 comic/chapter/media 表，ZipBuilder 流式写入避免 OOM。

**What it will NOT do:** 不支持单章导出、不实现实时进度推送、不统一 FileTask 框架（不碰 import_task）、不在 DetailPage 放置导出按钮。

**Effort:** Medium（17 个新文件，8 个修改文件，~1500 LOC）
**Risk:** Medium — Worker 首次接入数据库，需配置数据源和连接池，MQ 链路多需逐一验证
**Decisions to sanity-check:** Worker 实体用 `Export*` 前缀隔离、metadata.json 保持 v2、逐页 HQ→LQ 降级策略

Your next move: approve the plan, or run a high-accuracy dual Momus review before execution. Full execution detail follows below.

---

> TL;DR (machine): Medium effort, Medium risk — 17 new + 8 mod files, MQ async mirroring Import, Worker read-only MyBatis Plus, streaming ZipBuilder

## Scope
### Must have
- 整本漫画 ZIP 导出（API→MQ→Worker→MQ→API 异步流水线）
- HQ 优先，hqStatus=DELETED 自动降级 LQ
- ZIP 内含 metadata.json(v2) + catalog 目录结构
- `export_task` 表 + 5 个 API 端点 + 4 个 MQ 事件 + DLX/DLQ
- Worker 侧新增 MyBatis Plus 只读访问 comic/chapter/catalog/media
- StoragePage 入口按钮 + TaskPage 任务跟踪 + Toast 通知
- 下载/复制路径/打开目录功能
- Worker Entity 使用 `Export` 前缀避免与 API Entity 冲突
- ZipBuilder 流式写入（零拷贝），失败时清理不完整文件
- application.yml 新增 EXPORT storage root
- DB 索引：comic_id、status、created_at
- `GET /api/comics/{comicId}/exports` 列表端点
- `export.result.queue` 使用独立 binding（`task.started` / `task.completed` / `task.failed`）

### Must NOT have (guardrails, anti-slop, scope boundaries)
- MUST NOT 实现单章导出（Phase II）
- MUST NOT 修改 `StorageLayout`（与导出无关）
- MUST NOT 修改 `import_task` 表或 Import 事件流
- MUST NOT Worker 写入业务数据库（仅 CPUPDATE export_task 状态——状态更新通过 MQ 事件回 API）
- MUST NOT 使用 `Files.readAllBytes()` 读取源文件（必须流式）
- MUST NOT 在 DetailPage 放置导出入口
- MUST NOT 覆盖已有导出文件（时间戳命名）
- MUST NOT 忽略 `progress = -1`（ExportFailedHandler 必须设置）
- MUST NOT 在 `export.result.queue` 使用 `task.*` binding（会泄露 task.created）

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after（先完成全部实现，再集中验证）
- Evidence: .omo/evidence/task-<N>-comic-export.txt

## Execution strategy
### Parallel execution waves
- **Wave 1**（5 todos，0 dependencies）：comic-common 事件 + DB migration + Worker MyBatis Plus + 前端类型/API
- **Wave 2**（5 todos，depends Wave 1）：API/Worker MQ config + API entity/mapper + Worker entities/mappers
- **Wave 3**（5 todos，depends Wave 2）：API handlers + API controller/service + Worker handler + Worker 5 components
- **Wave 4**（3 todos，depends Wave 3）：Frontend StorageTable + TaskPage + Config(yaml)
- **Wave 5**（1 todo，depends Wave 1-4）：Integration verification

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
|------|-----------|--------|---------------------|
| T1 (4 events) | — | T6,T7,T12 | T2,T3,T4,T5 |
| T2 (DB migration) | — | T7,T9 | T1,T3,T4,T5 |
| T3 (Worker MyBatis) | — | T8 | T1,T2,T4,T5 |
| T4 (Frontend types) | — | T15 | T1,T2,T3,T5 |
| T5 (Frontend api.ts) | — | T15,T16 | T1,T2,T3,T4 |
| T6 (API MQ config) | T1 | T9,T10,T11 | T7,T8 |
| T7 (Worker MQ config) | T1 | T12 | T6,T8 |
| T8 (Worker entities/mappers) | T3 | T12 | T6,T7 |
| T9 (API entity/mapper) | T2 | T10,T11 | — |
| T10 (API handlers) | T6,T9 | T17 | T11,T12,T13 |
| T11 (API controller/service) | T6,T9 | T17 | T10,T12,T13 |
| T12 (Worker handler) | T1,T7,T8 | T17 | T10,T11,T13 |
| T13 (Worker 5 components) | T8 | T17 | T10,T11,T12 |
| T14 (Config yamls) | — | T17 | T15,T16 |
| T15 (Frontend StorageTable) | T4,T5,T14 | T17 | T16 |
| T16 (Frontend TaskPage) | T4,T5,T14 | T17 | T15 |
| T17 (Integration verify) | T10,T11,T12,T13,T14,T15,T16 | — | — |

## Todos
> Implementation + Test = ONE todo. Never separate.

- [ ] 1. comic-common: 新增 4 个 export 事件 DTO + 修改 ComicEvent sealed interface
  What to do: 在 `comic-common/src/main/java/com/comicatlas/common/event/` 下创建 ExportTaskCreatedEvent、ExportTaskStartedEvent、ExportTaskCompletedEvent、ExportTaskFailedEvent 四个 Java record。每个 record 实现 ComicEvent，首两个字段固定 UUID eventId + Instant occurredAt。在 ComicEvent.java 的 @JsonSubTypes 和 permits 中注册 4 个新子类型（当前11个）。
  Must NOT do: 不要创建超过4个事件DTO、不要修改现有11个事件的字段、不要忘记 permits 子句。
  Parallelization: Wave 1 | Blocked by: — | Blocks: T6,T7,T12
  References:
    - `comic-common/src/main/java/com/comicatlas/common/event/ComicEvent.java`（sealed interface 当前 11 个子类型）
    - `comic-common/src/main/java/com/comicatlas/common/event/ImportTaskCreatedEvent.java`（record 模板）
    - `comic-common/src/main/java/com/comicatlas/common/event/DeleteHqRequestedEvent.java`（含 routing key 注释）
    - spec `docs/superpowers/specs/2026-07-23-comic-export-design.md` §6.1-6.5
  Acceptance criteria:
    - ExportTaskCreatedEvent: fields eventId(UUID), occurredAt(Instant), taskId(Long), comicId(Long)
    - ExportTaskStartedEvent: fields eventId, occurredAt, taskId, comicId
    - ExportTaskCompletedEvent: fields eventId, occurredAt, taskId, comicId, outputRoot(String), outputPath(String), outputSize(Long)
    - ExportTaskFailedEvent: fields eventId, occurredAt, taskId, comicId, errorCode(String), errorMessage(String)
    - ComicEvent.java permits 子句包含这 4 个新类名，@JsonSubTypes 中包含对应 4 个 @Type
    - `mvn compile -pl comic-common` 编译通过
  QA scenarios:
    - Happy: `mvn test -pl comic-common` → 编译通过，无类型错误
    - Failure: 故意在 permits 子句中遗漏一个类 → 编译失败
  Commit: Y | feat(comic-common): 新增导出事件 DTO（ExportTaskCreated/Started/Completed/Failed）

- [ ] 2. DB: 创建 export_task 表（含索引）
  What to do: 在 api-service 的 Flyway 迁移中添加 V 版本 SQL，创建 export_task 表。字段：id(BIGINT AUTO_INCREMENT PK), comic_id(BIGINT NOT NULL), status(VARCHAR(20) DEFAULT 'PENDING'), progress(SMALLINT DEFAULT 0), output_root(VARCHAR(20)), output_path(VARCHAR(500)), output_size(BIGINT DEFAULT 0), error_msg(VARCHAR(500)), created_at(DATETIME DEFAULT CURRENT_TIMESTAMP), completed_at(DATETIME)。索引：comic_id、status、created_at。
  Must NOT do: 不要修改 import_task 表、不要添加 source_type/source_path 等导入专用字段、不要遗漏索引。
  Parallelization: Wave 1 | Blocked by: — | Blocks: T7,T9
  References:
    - `api-service/src/main/resources/db/migration/`（现有 Flyway 迁移文件）
    - spec §3.1（完整 DDL + 索引）
  Acceptance criteria:
    - 迁移文件命名符合 Flyway 规范（如 V1x__create_export_task.sql）
    - DDL 执行成功：`mvn flyway:migrate -pl api-service`
    - `DESC export_task` 显示全部 9 个字段 + 3 个索引
  QA scenarios:
    - Happy: Flyway 迁移成功，INSERT 一条记录后 SELECT 返回正确数据
    - Failure: 重复执行迁移 → Flyway 幂等跳过
  Commit: Y | feat(db): 创建 export_task 表（含 comic_id/status/created_at 索引）

- [ ] 3. Worker: 添加 MyBatis Plus 依赖 + 数据源配置
  What to do: 在 worker-service/pom.xml 中添加 mybatis-plus-spring-boot3-starter 和 mysql-connector-j 依赖（版本从 api-service/pom.xml 复制）。在 worker-service/application.yml 中添加 spring.datasource（同 api-service 配置）和 mybatis-plus 基础配置（map-underscore-to-camel-case: true, id-type: auto）。设置 HikariCP maximum-pool-size=2（Worker 单线程消费，2 足够）。在 Worker 启动类添加 @MapperScan("com.comicatlas.worker.mapper")。
  Must NOT do: 不要添加 spring-boot-starter-web、不要设置过大的连接池（>5）、不要遗漏 @MapperScan。
  Parallelization: Wave 1 | Blocked by: — | Blocks: T8
  References:
    - `worker-service/pom.xml`（当前依赖列表，确认 mybatis-plus 不在其中）
    - `api-service/pom.xml`（mybatis-plus 和 mysql-connector-j 版本号）
    - `api-service/src/main/resources/application.yml`（datasource + mybatis-plus 配置）
    - `worker-service/src/main/java/com/comicatlas/worker/WorkerApplication.java`（启动类）
  Acceptance criteria:
    - worker-service/pom.xml 包含 mybatis-plus-spring-boot3-starter（版本与 api-service 一致）
    - worker-service/application.yml 包含 spring.datasource.url/username/password/driver-class-name
    - HikariCP maximum-pool-size=2
    - Worker 启动类有 `@MapperScan("com.comicatlas.worker.mapper")`
    - `mvn compile -pl worker-service` 编译通过
  QA scenarios:
    - Happy: Worker 启动日志显示 HikariPool 初始化成功
    - Failure: 错误的数据源配置 → Worker 启动失败，日志显示 SQLException
  Commit: Y | feat(worker): 添加 MyBatis Plus + MySQL 数据源只读访问

- [ ] 4. Frontend: 新增 exportApi + ExportTaskVO 类型定义
  What to do: 在 frontend/src/types/index.ts 中新增 ExportTaskVO 接口（字段：taskId, comicId, status, outputPath, outputSize, physicalPath, errorMessage, createdAt, completedAt）。新增 STATUS_COLOR_MAP 条目（PENDING: 'info', RUNNING: 'warning', SUCCESS: 'success', FAILED: 'danger'）。在 frontend/src/services/api.ts 中新增 exportApi 对象：createExport(comicId) → POST /api/comics/{comicId}/export, getTask(taskId) → GET /api/export/{taskId}, listExports(comicId) → GET /api/comics/{comicId}/exports, download(taskId) → GET /api/export/{taskId}/download, openDir(taskId) → POST /api/export/{taskId}/open。
  Must NOT do: 不要修改现有 ImportTaskVO 类型、不要遗漏物理路径字段 physicalPath。
  Parallelization: Wave 1 | Blocked by: — | Blocks: T15,T16
  References:
    - `frontend/src/types/index.ts`（ImportTaskVO、STATUS_COLOR_MAP）
    - `frontend/src/services/api.ts`（lqApi/hqApi 模式）
    - spec §8.1-8.5（API 端点）、§10.3（前端类型字段）
  Acceptance criteria:
    - ExportTaskVO 接口编译无类型错误
    - exportApi 包含 5 个方法，URL 路径与 spec 一致
    - `npx tsc --noEmit` 在 frontend 目录通过
  QA scenarios:
    - Happy: TypeScript 编译通过
    - Failure: 故意调用不存在的端点 → TypeScript 编译时错误
  Commit: Y | feat(frontend): 新增 exportApi + ExportTaskVO 类型

- [ ] 5. API: RabbitMQ 配置 — comic.export exchange + queues + DLX
  What to do: 在 api-service/.../config/RabbitMqConfig.java 中新增 comic.export exchange（TopicExchange）、export.result.queue（含 DLX → comic.export.dlx）、export.result.dlq、3 个独立 binding（task.started / task.completed / task.failed）、DLX exchange + DLQ binding。遵循现有 import/image/delete exchange 的声明模式（QueueBuilder.durable、deadLetterExchange、deadLetterRoutingKey）。
  Must NOT do: 不要用 task.* 做 result queue binding（会泄露 task.created）、不要忘记声明 DLQ + DLX。
  Parallelization: Wave 2 | Blocked by: T1 | Blocks: T10,T11
  References:
    - `api-service/src/main/java/com/comicatlas/api/config/RabbitMqConfig.java`（现有 5 个 exchange 模式）
    - spec §7（MQ 配置 + 修正后的 3 个独立 binding）
  Acceptance criteria:
    - exportExchange() Bean 存在，name="comic.export"
    - exportResultQueue() 含 deadLetterExchange("comic.export.dlx") 和 deadLetterRoutingKey("export.result.dlq")
    - 3 个 binding Bean：exportResultStartedBinding(task.started)、exportResultCompletedBinding(task.completed)、exportResultFailedBinding(task.failed)
    - DLX exchange + DLQ queue + DLQ binding 完整
  QA scenarios:
    - Happy: API 启动后 RabbitMQ 管理界面显示 comic.export exchange + export.result.queue + export.result.dlq
    - Failure: 绑定错误 → RabbitMQ 启动时 channel 异常
  Commit: Y | feat(api): 新增 comic.export MQ 配置（exchange/queue/DLX）

- [ ] 6. Worker: RabbitMQ 配置 — comic.export exchange + export.task.queue + DLX
  What to do: 在 worker-service/.../config/RabbitMqConfig.java 中新增 comic.export exchange（TopicExchange，与 API 侧完全一致）、export.task.queue（含 DLX）、export.task.dlq、task.created binding、DLX exchange + DLQ binding。声明参数必须与 API 侧完全一致（durable, DLX, DLQ name）。
  Must NOT do: 不要修改 exchange 的 durable 属性（必须 true）、不要遗漏 DLX/DLQ、不要新增不属于 Worker 的 result queue。
  Parallelization: Wave 2 | Blocked by: T1 | Blocks: T12
  References:
    - `worker-service/src/main/java/com/comicatlas/worker/config/RabbitMqConfig.java`（现有 6 个 exchange）
    - spec §7（MQ 配置，Worker 侧对称声明）
  Acceptance criteria:
    - exportExchange() Bean 声明与 API 完全一致
    - exportTaskQueue() 含 deadLetterExchange("comic.export.dlx") + deadLetterRoutingKey("export.task.dlq")
    - exportTaskBinding() → exchange="comic.export", routingKey="task.created"
    - DLX + DLQ 完整
  QA scenarios:
    - Happy: Worker 启动后 RabbitMQ 管理界面确认 export.task.queue 就绪
    - Failure: 与 API 侧声明不一致 → RabbitMQ PRECONDITION_FAILED
  Commit: Y | feat(worker): 新增 comic.export MQ 配置（exchange/task.queue/DLX）

- [ ] 7. API: export_task Entity + Mapper + Flyway 迁移验证
  What to do: 在 api-service 创建 ExportTask entity 类（@TableName("export_task")，字段对应 DDL），创建 ExportTaskMapper 接口（extends BaseMapper<ExportTask>，使用 @Mapper 注解）。在 ExportTask 中提供便利方法 isPending()/isRunning()/isSuccess()/isFailed()。
  Must NOT do: 不要放在 import_task 的包路径下（新建 `api/export/entity/` 和 `api/export/mapper/`）、不要使用 MyBatis XML（用注解/BaseMapper 默认方法）。
  Parallelization: Wave 2 | Blocked by: T2 | Blocks: T10,T11
  References:
    - `api-service/src/main/java/com/comicatlas/api/comic/entity/Media.java`（@TableName + @TableId 模式）
    - `api-service/src/main/java/com/comicatlas/api/comic/mapper/ComicMapper.java`（Mapper 接口模式）
  Acceptance criteria:
    - ExportTask entity @TableName("export_task")，字段与 DDL 一致
    - ExportTaskMapper extends BaseMapper<ExportTask>
    - `mvn compile -pl api-service` 编译通过
  QA scenarios:
    - Happy: Spring 启动时 MyBatis Plus 扫描到 ExportTaskMapper
    - Failure: @TableName 拼写错误 → 运行时 TableNotFoundException
  Commit: Y | feat(api): 新增 ExportTask Entity + Mapper

- [ ] 8. Worker: 创建只读 Entity 类（ExportComic/Chapter/Catalog/Media）+ Mapper
  What to do: 在 worker-service 创建 4 个只读 entity（ExportComic、ExportChapter、ExportCatalog、ExportMedia），使用 Export 前缀避免与 API Entity 冲突。每个 entity 仅含导出所需字段。ExportMedia 必须包含 lqRoot/lqPath/lqStatus（降级逻辑依赖）。创建对应 4 个 Mapper 接口。Worker Media 字段：id, chapterId, pageNumber, mediaType, fileName, hqRoot, hqPath, hqStatus, lqRoot, lqPath, lqStatus, fileSize, width, height, duration, container, videoCodec, audioCodec。
  Must NOT do: 不要在 Worker entity 中定义 API 侧才用的字段（如 comic.category、chapter.catalogId 详细）、不要遗漏 lqRoot/lqPath/lqStatus（降级关键）、不要使用与 API Entity 相同的类名（必须 Export 前缀）。
  Parallelization: Wave 2 | Blocked by: T3 | Blocks: T12,T13
  References:
    - `api-service/src/main/java/com/comicatlas/api/comic/entity/Comic.java`（字段参考，仅取 title）
    - `api-service/src/main/java/com/comicatlas/api/comic/entity/Chapter.java`（取 id, comicId, title, chapterNo, globalOrder）
    - `api-service/src/main/java/com/comicatlas/api/comic/entity/Catalog.java`（取 id, comicId, parentId, title, sortOrder）
    - `api-service/src/main/java/com/comicatlas/api/comic/entity/Media.java`（取上述字段）
    - spec §5.2-5.3（Collector 和 FileResolver 字段需求）
  Acceptance criteria:
    - ExportComic(comicId, title, status, coverPath)
    - ExportChapter(id, comicId, catalogId, title, chapterNo, globalOrder)
    - ExportCatalog(id, comicId, parentId, title, sortOrder)
    - ExportMedia(包含 lqRoot/lqPath/lqStatus)
    - 4 个 Mapper 接口编译通过
  QA scenarios:
    - Happy: `mvn compile -pl worker-service` 编译通过
    - Failure: 与 API Entity 同名 → IDE 导入歧义（验证无同名类在 classpath 上）
  Commit: Y | feat(worker): 新增只读 Export Entity（Comic/Chapter/Catalog/Media）+ Mapper

- [ ] 9. API: ExportStartedHandler + ExportCompletedHandler + ExportFailedHandler
  What to do: 在 api-service/.../export/event/ 下创建 3 个 @RabbitListener handler。ExportStartedHandler：消费 task.started → UPDATE export_task SET status='RUNNING'。ExportCompletedHandler：消费 task.completed → UPDATE status='SUCCESS', output_root, output_path, output_size, progress=100, completed_at=NOW()。ExportFailedHandler：消费 task.failed → UPDATE status='FAILED', error_msg, progress=-1。均使用 manual ACK（basicAck/basicReject），遵循 LqCompletedHandler/HqDeletedHandler 模式。
  Must NOT do: 不要忘记设置 progress=-1（ExportFailedHandler）、不要忘记 basicAck、不要使用 auto ACK。
  Parallelization: Wave 3 | Blocked by: T6,T9 | Blocks: T17
  References:
    - `api-service/src/main/java/com/comicatlas/api/importer/event/LqCompletedHandler.java`（@RabbitListener + Channel + basicAck 模式）
    - `api-service/src/main/java/com/comicatlas/api/importer/event/HqDeletedHandler.java`（UPDATE 模式）
    - spec §4（数据流）、§6（事件字段映射到 DB）
  Acceptance criteria:
    - 3 个 Handler 类，@RabbitListener 分别监听 export.result.queue
    - ExportStartedHandler: UPDATE status=RUNNING
    - ExportCompletedHandler: UPDATE status=SUCCESS, output_root, output_path, output_size, progress=100
    - ExportFailedHandler: UPDATE status=FAILED, error_msg, progress=-1
    - 均使用 channel.basicAck / basicReject
  QA scenarios:
    - Happy: 手动发送 ExportTaskCompletedEvent → DB 中 export_task 状态更新为 SUCCESS
    - Failure: 事件缺少字段 → 反序列化失败，进入 DLQ
  Commit: Y | feat(api): 新增导出事件处理器（Started/Completed/Failed）

- [ ] 10. API: ExportController + ExportService
  What to do: 创建 ExportController（5 个端点）和 ExportService（校验+创建任务+发MQ）。POST /api/comics/{comicId}/export：校验 comic.status==READY、幂等检查 PENDING/RUNNING 任务 → INSERT export_task(PENDING) → afterCommit 发送 ExportTaskCreatedEvent → 202。GET /api/comics/{comicId}/exports：列表查询（按 created_at 倒序）→ physicalPath 由 StorageProperties 计算。GET /api/export/{taskId}：单任务查询 → 含 physicalPath。GET /api/export/{taskId}/download：StreamingResponseBody → Content-Type: application/zip。POST /api/export/{taskId}/open：Desktop.open() → 无桌面环境返回 501。
  Must NOT do: 不要忘记 afterCommit 事务同步、不要遗漏幂等检查、不要缺失 physicalPath 计算。
  Parallelization: Wave 3 | Blocked by: T6,T9 | Blocks: T17
  References:
    - `api-service/src/main/java/com/comicatlas/api/importer/controller/ImportController.java`（Controller 模式）
    - `api-service/src/main/java/com/comicatlas/api/importer/service/impl/ImportServiceImpl.java`（afterCommit + TransactionSynchronization 模式，lines 124-131）
    - `api-service/src/main/java/com/comicatlas/api/importer/event/ImportEventPublisher.java`（RabbitTemplate.convertAndSend 模式）
    - `worker-service/src/main/java/com/comicatlas/worker/file/storage/StorageProperties.java`（resolve 物理路径）
    - spec §8.1-8.5（5 个端点定义）
  Acceptance criteria:
    - 5 个端点全部实现，路径与 spec 一致
    - 创建端点返回 202 + {taskId, status}
    - 列表端点返回 ExportTaskVO[]，含 physicalPath
    - 下载端点返回 StreamingResponseBody + application/zip
    - 打开目录端点 Desktop.open() 或 501
    - afterCommit 正确发布 ExportTaskCreatedEvent
  QA scenarios:
    - Happy: curl POST /api/comics/1/export → 202
    - Happy: curl GET /api/comics/1/exports → [ExportTaskVO...]
    - Happy: curl GET /api/export/1 → {taskId:1, status:"SUCCESS", ...}
    - Failure: comic not READY → 409
    - Failure: 重复创建 → 409
    - Failure: comicId 不存在 → 404
  Commit: Y | feat(api): 新增 ExportController + ExportService（5 端点 + MQ 发布）

- [ ] 11. Worker: ExportTaskHandler（MQ 消费入口，路由编排）
  What to do: 创建 ExportTaskHandler，@RabbitListener(queues="export.task.queue")。处理流程：①Publish ExportTaskStartedEvent → ②ExportCollector.collect() → ③ZipBuilder.build() → ④Publish ExportTaskCompletedEvent（含 outputPath+outputSize）→ ⑤basicAck。异常时 publish ExportTaskFailedEvent + basicReject。注入 RabbitTemplate（用于发布回复事件）。
  Must NOT do: 不要在 Handler 中包含业务逻辑（仅路由编排）、不要忘记 basicAck/basicReject、不要直接在 Handler 中写 ZIP 代码。
  Parallelization: Wave 3 | Blocked by: T1,T7,T8 | Blocks: T17
  References:
    - `worker-service/src/main/java/com/comicatlas/worker/event/HqDeleteHandler.java`（@RabbitListener + RabbitTemplate 回复模式）
    - `worker-service/src/main/java/com/comicatlas/worker/event/ImportTaskHandler.java`（sourceType 路由模式参考）
    - spec §4（数据流）、§5.1（Handler 职责）
  Acceptance criteria:
    - @RabbitListener(queues="export.task.queue") 注解正确
    - 成功路径：publish StartedEvent → collect → build → publish CompletedEvent → basicAck
    - 失败路径：catch Exception → publish FailedEvent → basicReject
    - 注入 RabbitTemplate, ExportCollector, ZipBuilder, StorageProperties
  QA scenarios:
    - Happy: 手动发送 ExportTaskCreatedEvent → Handler 处理完成 → export.task.queue 消息 ACK
    - Failure: 导出过程中 IO 异常 → ExportTaskFailedEvent 发布 → 消息 reject（进 DLQ）
  Commit: Y | feat(worker): 新增 ExportTaskHandler（MQ 消费+路由编排）

- [ ] 12. Worker: ExportCollector + ExportFileResolver + ExportManifest + ZipBuilder + ComicTitleSanitizer（5 个组件）
  What to do: 一次性实现 Worker 的 5 个导出核心组件：
    - **ExportCollector**：只读查询 comic/chapter/catalog/media（按 globalOrder 排序），组装原始数据。调用 MetadataExporter 生成 metadata.json 字符串（v2 格式）。
    - **ExportFileResolver**：逐页决策 HQ/LQ，返回 StorageRef。逻辑：VIDEO → 始终 HQ；IMAGE + hqStatus==READY → HQ；IMAGE + hqStatus==DELETED + lqStatus==READY → LQ；否则抛 ExportFileNotFoundException。
    - **ExportManifest**：纯数据 record(rootDirName, metadataJson, List<Entry>)，Entry(targetPath, sourceFile)。组装逻辑：按 catalog 树构建 targetPath，无 catalog 时按 chapter.title 建一级文件夹。文件名冲突时追加序号去重。目录名用 ComicTitleSanitizer 处理。
    - **ZipBuilder**：流式 ZIP 写入。build(manifest, outputPath) → Files.createDirectories(outputPath.getParent()) → ZipOutputStream → 逐文件 Files.copy(source, zos)（流式，零拷贝）。失败时 Files.deleteIfExists(outputPath)。metadata.json 用 writeStringEntry 写入。
    - **ComicTitleSanitizer**：过滤 [<>:\"/\\|?*] 正则，空字符串默认 "comic_export"。
  Must NOT do: 不要使用 readAllBytes（必须流式）、ZipBuilder 不要有任何业务依赖（只接受 ExportManifest）、ExportFileResolver 不要检查文件系统（只检查 DB 状态字段）、ExportCollector 不要写 DB。
  Parallelization: Wave 3 | Blocked by: T8 | Blocks: T17
  References:
    - `api-service/src/main/java/com/comicatlas/api/admin/service/MetadataExporter.java`（metadata.json 生成逻辑）
    - `worker-service/src/main/java/com/comicatlas/worker/file/storage/StorageProperties.java`（resolve 物理路径）
    - `worker-service/src/main/java/com/comicatlas/worker/common/FilePathBuilder.java`（hqDir/lqDir 路径模式）
    - spec §5.2-5.6（5 个组件详设 + 流式 ZipBuilder 修正）
  Acceptance criteria:
    - ExportCollector: 4 个 Mapper 查询结果正确组合
    - ExportFileResolver: resolve(media) 正确返回 HQ/LQ StorageRef
    - ExportManifest: rootDirName 经 ComicTitleSanitizer 处理，entries 按 catalog 树排列，冲突去重
    - ZipBuilder: 流式写入，不 OOM，失败时清理文件
    - ComicTitleSanitizer: "葬送の芙莉蓮" → "葬送の芙莉蓮"（日文OK），"A:B" → "AB"，空字符串 → "comic_export"
    - `mvn compile -pl worker-service` 编译通过
  QA scenarios:
    - Happy: 单元测试 ComicTitleSanitizer.sanitize("test<>.txt") → "test.txt"
    - Happy: 单元测试 ExportFileResolver 对 IMAGE+READY 返回 HQ StorageRef
    - Happy: 单元测试 ExportFileResolver 对 IMAGE+DELETED+LQ_READY 返回 LQ StorageRef
    - Happy: 单元测试 ExportFileResolver 对 IMAGE+DELETED+LQ_FAILED 抛 ExportFileNotFoundException
    - Happy: 单元测试 ZipBuilder.build 生成有效 ZIP → `unzip -l` 条目数匹配
    - Happy: 单元测试 ExportManifest 去重 → "番外篇" 重复 → "番外篇(1)"
    - Failure: ZipBuilder 磁盘满 → 异常 + 部分文件已删除
  Commit: Y | feat(worker): 新增导出核心组件（Collector/FileResolver/Manifest/ZipBuilder/Sanitizer）

- [ ] 13. Config: application.yml 新增 EXPORT storage root（API + Worker 两侧）
  What to do: 在 worker-service/application.yml 的 storage.roots 下新增 EXPORT root：{ type: FILESYSTEM, path: ${MANGA_ROOT:D:/manga}/export }。在 api-service/application.yml 中新增对应的 StorageProperties 配置（如 api 侧无 storage roots 则需确认 api 侧是否需要——仅当 ExportController 的 physicalPath 计算需要）。Worker 侧 StorageProperties 自动加载。
  Must NOT do: 不要修改 StorageLayout 类、不要修改现有 HQ/LQ root 配置。
  Parallelization: Wave 4 | Blocked by: — | Blocks: T17
  References:
    - `worker-service/src/main/resources/application.yml` lines 52-59（storage.roots 配置）
    - `worker-service/src/main/java/com/comicatlas/worker/file/storage/StorageProperties.java`（自动加载 roots）
    - spec §3.2（EXPORT root 配置）
  Acceptance criteria:
    - Worker application.yml storage.roots 包含 EXPORT key
    - StorageProperties.getRoots().get("EXPORT") 不为 null
    - EXPORT root path 解析为 ${MANGA_ROOT}/export
  QA scenarios:
    - Happy: Worker 启动后日志显示 StorageProperties 加载 3 个 root（HQ/LQ/EXPORT）
    - Failure: 路径不存在 → Worker 启动不报错（运行时 resolve 时检查）
  Commit: Y | feat(config): 新增 EXPORT storage root（双方 application.yml）

- [ ] 14. Frontend: StorageTable.vue 新增"导出 ZIP"按钮
  What to do: 在 StorageTable.vue 操作列（"删HQ"/"生LQ"旁边）新增"导出 ZIP"按钮。按钮始终显示（所有漫画均可导出），点击 emit('exportZip', row.comicId)。当 busyState[row.comicId] 为 true 时 disabled。StoragePage 接收 exportZip 事件 → ElMessageBox.confirm 确认弹窗（显示漫画标题、章节数、预估大小）→ 调用 exportApi.createExport(comicId) → ElMessage.success('导出任务已提交') → 开始 useStoragePolling 轮询导出状态。确认弹窗数据复用已有的 ComicStorageItem 信息（chapterCount, pageCount, hqSize + lqSize 估算）。
  Must NOT do: 不要在 DetailPage 放导出按钮、不要用 v-if 条件隐藏导出按钮（始终显示，仅 disabled 控制）。
  Parallelization: Wave 4 | Blocked by: T4,T5 | Blocks: T17
  References:
    - `frontend/src/views/management/storage/StorageTable.vue` lines 74-80（操作列按钮模板）
    - `frontend/src/views/management/storage/StoragePage.vue`（handleDeleteHQ/handleGenerateLQ 流程）
    - `frontend/src/composables/storage/useStoragePolling.ts`（5s×12 轮询模式）
    - spec §10.1-10.2（入口 + 确认弹窗）
  Acceptance criteria:
    - 操作列新增 el-button "导出 ZIP"
    - 点击 → emit('exportZip', comicId)
    - 确认弹窗显示漫画标题、章节数、预估大小
    - 确认后调用 exportApi.createExport → Toast "导出任务已提交"
    - busyState 为 true 时按钮 disabled
  QA scenarios:
    - Happy: Playwright 验证按钮可见、点击弹出确认弹窗、确认后 Toast 显示
    - Failure: 网络错误 → ElMessage.error 显示错误信息
  Commit: Y | feat(frontend): StorageTable 新增导出 ZIP 按钮 + 确认弹窗

- [ ] 15. Frontend: TaskPage.vue 支持 EXPORT 任务类型
  What to do: 扩展 TaskPage.vue 显示导出任务。在 onMounted 中同时 fetch 导入任务和导出任务。exportApi.listExports() 合并到任务列表。EXPORT 类型任务行：PENDING → 灰色"等待中"、RUNNING → 旋转"导出中..."、SUCCESS → ✅ + [下载][复制路径][打开目录]、FAILED → ❌ + 错误信息。下载/复制路径/打开目录分别调用 exportApi.download/openDir 和 clipboard。Toast：导出完成 → "✅ 导出完成：xxx.zip · 324 MB"，导出失败 → "❌ 导出失败：xxx"。TaskCard 组件需扩展 variant 或新增 ExportTaskCard。
  Must NOT do: 不要修改 ImportTaskVO/ImportTaskCard、不要删除现有导入任务功能。
  Parallelization: Wave 4 | Blocked by: T4,T5 | Blocks: T17
  References:
    - `frontend/src/views/management/TaskPage.vue`（现有 3 个 Section 结构）
    - `frontend/src/components/management/task/TaskCard.vue`（现有 variant 模式：active/failed/done）
    - `frontend/src/stores/management/import.ts`（轮询模式参考）
    - spec §10.3-10.6（TaskPage + Toast + 按钮）
  Acceptance criteria:
    - TaskPage 显示 EXPORT 类型任务行
    - SUCCESS 行有 [下载][复制路径][打开目录] 按钮
    - 点击下载 → 浏览器下载 ZIP
    - 点击复制路径 → navigator.clipboard.writeText
    - 点击打开目录 → POST /api/export/{taskId}/open
    - Toast 显示成功/失败消息（不含完整路径）
  QA scenarios:
    - Happy: Playwright 验证 EXPORT 任务行渲染、按钮可点击
    - Happy: Playwright 验证 Toast 显示 "✅ 导出完成：test.zip · 10 MB"
    - Failure: 下载时文件不存在 → 显示错误 Toast
    - Failure: 打开目录返回 501 → 显示"当前环境不支持打开目录"
  Commit: Y | feat(frontend): TaskPage 支持 EXPORT 任务类型（下载/复制路径/打开目录）

- [ ] 16. Integration: 端到端验证（MQ 链路 + API 端点 + ZIP 正确性）
  What to do: 启动全部服务（API + Worker + RabbitMQ + MySQL），执行端到端验证。①POST /api/comics/{id}/export → 202 → 轮询 GET /api/comics/{id}/exports 直到 status=SUCCESS。②GET /api/export/{taskId}/download → 验证 ZIP 可下载且不为空。③解压 ZIP 验证：根目录 metadata.json 存在（v2 格式），页面文件数量匹配 DB 记录，目录结构遵循 catalog 树。④HQ 降级场景：手动将某页 hqStatus 设为 DELETED → 导出 → 验证 ZIP 中该页为 LQ 格式（.webp）。⑤错误路径：对 IMPORTING 状态的漫画导出 → 409。⑥错误路径：某页 HQ MISSING 且 LQ NOT_GENERATED → Worker 发送 ExportTaskFailedEvent → export_task FAILED。
  Must NOT do: 不要跳过任何场景（6 个全部验证）、不要仅验证"看起来没错"（必须用具体命令和断言）。
  Parallelization: Wave 5 | Blocked by: T10,T11,T12,T13,T14,T15,T16 | Blocks: —
  References: spec §4（完整数据流）、§9（错误处理表）
  Acceptance criteria:
    - 6 个验证场景全部 PASS
    - ZIP 解压后条目数 = DB page 数 + 1（metadata.json）
    - 降级场景 ZIP 中包含 .webp 文件
    - 409 和 FAILED 场景行为正确
  QA scenarios:
    - Happy: curl POST export → poll list → download → unzip -l 验证
    - Failure: 409 → response body 含错误信息
    - Failure: MISSING_FILE → export_task.status='FAILED', error_msg 含 'MISSING_FILE'
  Commit: N（验证不产生代码变更）

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit: 逐条检查 16 个 todo 的 Acceptance criteria 是否全部满足，未完成的记录原因
- [ ] F2. Code quality review: 检查新增代码是否遵循现有模式（Lombok、@Slf4j、afterCommit、basicAck）、无 `as any`/@ts-ignore、无 readAllBytes、无 Worker 写 DB
- [ ] F3. Real manual QA: 在真实环境启动全部服务，执行至少一次完整导出流程，验证 ZIP 可解压可用
- [ ] F4. Scope fidelity: 确认未实现 Phase II 功能（单章导出、实时进度、FileTask 统一）、DetailPage 无导出按钮、StorageLayout 未被修改

## Commit strategy
- 每个 todo 独立 commit，使用中文 commit message
- Commit 格式：`<type>(<scope>): <summary>`
- 不 squash，保留独立 commit 便于 review 和回滚

## Success criteria
- [x] comic-common: 4 个导出事件 DTO 编译通过，ComicEvent permits 完整
- [x] DB: export_task 表创建成功，3 个索引生效
- [x] Worker: MyBatis Plus 依赖添加，数据源配置正确，启动成功
- [x] API + Worker: MQ exchange/queue/binding 声明一致，RabbitMQ 管理界面确认
- [x] API: 5 个端点全部可用，幂等检查正确，409/404/501 错误码正确
- [x] Worker: ExportTaskHandler 消费正确，5 个组件单元测试通过
- [x] Worker: ZipBuilder 流式写入，大文件不 OOM，失败清理残留
- [x] Frontend: StorageTable 导出按钮 + 确认弹窗可用
- [x] Frontend: TaskPage 显示 EXPORT 任务，下载/复制路径/打开目录可用
- [x] Integration: 端到端 6 个场景全部 PASS
- [x] 降级：HQ DELETED → 导出 ZIP 包含 LQ 文件
- [x] 再导入：导出的 ZIP 可通过现有 Import 流程重新导入
