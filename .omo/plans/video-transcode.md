# video-transcode - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**What you'll get:** 为旧导入的非 mp4/webm 视频提供转码补偿——一个 API 接口触发，后端自动调用 ffmpeg 转成浏览器兼容的 H.264 mp4，前端存储管理页面可以看到状态并重新触发。

**Why this approach:** 和现有的 HQ 删除、LQ 生成保持一致的异步事件架构——API 收请求发 MQ，Worker 消费做文件处理，结果通过 MQ 回写 DB。ffmpeg 参数直接复用已验证的 VideoNormalizer 配置。每次只处理一页，安全替换原文件（先转 temp → 成功后才覆盖）。

**What it will NOT do:** 不修复视频元数据（已有 fixVideoMetadata），不重新生成封面或 LQ，不涉及流媒体转码（HLS/DASH）。

**Effort:** Medium (10 todos, 3 waves)
**Risk:** Medium — 文件替换有数据风险，已用原子 move 规避（Metis G2）
**Decisions to sanity-check:** 每页发 1 条 MQ 消息（非批量——防 Worker 崩溃丢页）；转码超时 10 分钟；FAILED 页面需手动重新触发

Your next move: approve plan, then `$start-work` to execute.

---

> TL;DR (machine): <1 line - effort, risk, deliverables>

## Scope
### Must have
### Must NOT have (guardrails, anti-slop, scope boundaries)

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: <TDD | tests-after | none> + framework
- Evidence: .omo/evidence/task-<N>-video-transcode.<ext>

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.

- **Wave 1** (T1-T3): DB + comic-common + MQ — 完全并行，无互相依赖
- **Wave 2** (T4-T7): API Controller + Service + Handlers + Worker — 依赖 Wave 1 的 DTO/MQ，4 个可并行
- **Wave 3** (T8-T10): Frontend types → table + tag → page + polling — 线性依赖但有前后端解耦

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| T1 DB | — | T4,T5,T6 | T2,T3 |
| T2 Events | — | T4,T5,T7 | T1,T3 |
| T3 MQ | — | T4,T5,T6,T7 | T1,T2 |
| T4 API-CTRL | T1,T2,T3 | — | T5,T6,T7 |
| T5 API-SVC | T1 | T8,T9,T10 | T4,T6,T7 |
| T6 API-HDLR | T1,T2,T3 | T10 | T4,T5,T7 |
| T7 Worker | T2,T3 | — | T4,T5,T6 |
| T8 Frontend-types | T4 | T9,T10 | — |
| T9 Frontend-table | T5,T8 | T10 | — |
| T10 Frontend-page | T6,T9 | — | — |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->
- [x] 1. DB: V8 迁移 — page 表加 transcode_status 列 + 历史数据 UPDATE

  What to do:
  - 创建 V8__add_transcode_status.sql：ALTER TABLE page ADD COLUMN transcode_status VARCHAR(16) NOT NULL DEFAULT 'NOT_NEEDED'
  - 历史数据修复：UPDATE page SET transcode_status = 'PENDING' WHERE media_type = 'VIDEO' AND (container IS NULL OR container NOT IN ('mp4', 'webm'))
  - 建索引：ALTER TABLE page ADD INDEX idx_transcode_status (transcode_status)
  - 同步更新 schema.sql 的 page 表 DDL

  Must NOT:
  - 不要修改 V1-V7 已有的迁移文件
  - 不要漏掉 container IS NULL 的页面（Metis G3）
  - 不要给 IMAGE 类型的页面设 PENDING

  Parallelization: Wave 1 | Blocked by: nothing | Blocks: T5,T6,T7,T8,T9
  References: api-service/src/main/resources/db/migration/V7__create_export_task.sql（命名约定）
  Acceptance criteria: migration SQL 语法正确；UPDATE 匹配所有非 mp4/webm 视频；IMAGE 页面保持 NOT_NEEDED
  QA scenarios: docker exec comicatlas-mysql mysql -e "SELECT transcode_status, COUNT(*) FROM page GROUP BY transcode_status" — 验证分布
  Commit: Y | feat(db): add transcode_status column to page table

- [x] 2. comic-common: 3 个 event DTO + ComicEvent sealed interface 更新

  What to do:
  - 创建 VideoTranscodeRequestedEvent: record(UUID eventId, Instant occurredAt, Long comicId, Long pageId, String hqRoot, String hqPath, String container)
  - 创建 VideoTranscodeCompletedEvent: record(UUID eventId, Instant occurredAt, Long pageId, Long comicId, String newHqPath, String container, String videoCodec, String audioCodec, Long fileSize)
  - 创建 VideoTranscodeFailedEvent: record(UUID eventId, Instant occurredAt, Long pageId, Long comicId, String errorMessage)
  - 更新 ComicEvent.java: permits 加 3 个 + @JsonSubTypes 加 3 个 @Type
  - 每页发 1 条 MQ 消息（Metis G1 修正：非批量）

  Must NOT:
  - 不要省略 eventId/occurredAt（Metis G4）
  - 不要使用 List<Long> pageIds（批量改单页）

  Parallelization: Wave 1 | Blocked by: nothing | Blocks: T3,T4,T5,T9
  References: comic-common/src/main/java/com/comicatlas/common/event/DeleteHqRequestedEvent.java, LqCompletedEvent.java
  Acceptance criteria: mvn compile 通过；3 个 record 都可 JSON 序列化/反序列化
  QA scenarios: 写 @Test 验证 Jackson 序列化 VideoTranscodeRequestedEvent → JSON → 反序列化 → eventId 一致
  Commit: Y | feat(events): add video transcode event DTOs

- [x] 3. MQ: 两侧 RabbitMqConfig 声明 exchange/queue/DLX

  What to do:
  - Worker 侧 RabbitMqConfig: 声明 comic.video exchange + video.transcode.queue (DLX comic.video.dlx) + binding routingKey: video.transcode.requested
  - API 侧 RabbitMqConfig: 声明 comic.video exchange + video.transcode.result.queue (DLX comic.video.dlx) + binding routingKey: video.transcode.completed, video.transcode.failed
  - 声明 DLX comic.video.dlx + DLQ video.transcode.dlq + video.transcode.result.dlq
  - 声明 comic.video exchange（Fanout → Topic 取决于是否需要 routingKey 过滤）

  Must NOT:
  - 不要复用 comic.image exchange（独立领域）
  - 不要遗漏 DLX/DLQ 声明

  Parallelization: Wave 1 | Blocked by: nothing | Blocks: T7,T8,T9
  References: worker-service/.../config/RabbitMqConfig.java（comic.image 段）, api-service/.../config/RabbitMqConfig.java
  Acceptance criteria: 应用启动后 RabbitMQ 管理面板可见 exchange/queue/binding
  QA scenarios: 启动 Worker + API，curl RabbitMQ API 验证 queue 已声明且 DLX 绑定正确
  Commit: Y | feat(mq): add comic.video exchange and transcode queues

- [x] 4. API Controller: AdminStorageController 加 transcodeVideos 端点

  What to do:
  - 在 AdminStorageController 加 POST /api/admin/comics/{comicId}/transcode-videos
  - 查询 logic: SELECT id, hq_root, hq_path, container FROM page WHERE media_type = 'VIDEO' AND (container IS NULL OR container NOT IN ('mp4', 'webm')) AND chapter_id IN (SELECT id FROM chapter WHERE comic_id = ?) AND transcode_status NOT IN ('DONE', 'PROCESSING')
  - 标记 PENDING: UPDATE page SET transcode_status = 'PENDING' WHERE id IN (?) AND transcode_status IN ('NOT_NEEDED', 'FAILED')
  - 为每个 page 发 1 条 MQ 消息 → rabbitTemplate.convertAndSend("comic.video","video.transcode.requested", event)
  - 返回 { comicId, totalVideoPages, pendingCount, alreadyDone, processingCount, failedCount }

  Must NOT:
  - 不要批量发一条消息（每页一条，Metis G1）
  - 不要漏掉 container IS NULL（Metis G3）
  - 不要用 SELECT FOR UPDATE 锁（并发安全靠 transcode_status IN (...）乐观锁

  Parallelization: Wave 2 | Blocked by: T1,T2,T3 | Blocks: nothing
  References: AdminStorageController.java:15, StorageQueryService.java, LqController.java
  Acceptance criteria: curl POST → 200 + pendingCount > 0；重复 POST 返回 0 pending（已标记）
  QA scenarios: happy: POST comic with 3 pending videos → pendingCount=3；failure: POST comic with no videos → pendingCount=0；concurrent: 两个窗口同时 POST → 第二个 pendingCount=0
  Commit: Y | feat(api): add transcode-videos endpoint

- [x] 5. API Service: StorageQueryService + StorageMapper 聚合 transcodeStatus

  What to do:
  - ComicStorageDTO 加 transcodeStatus 字段
  - StorageMapper.xml 新增 selectTranscodeStatus: SELECT GROUP_CONCAT(DISTINCT m.transcode_status) FROM page m JOIN chapter ch ON m.chapter_id = ch.id WHERE ch.comic_id = #{comicId} AND m.media_type = 'VIDEO'
  - StorageQueryServiceImpl.listComics: 对每个 ComicStorageDTO 调用 aggregateTranscodeStatus（复用 aggregateHqStatus 的去重逻辑）
  - 聚合优先级: PROCESSING > PENDING > FAILED > DONE > NOT_NEEDED（Metis G18）
  - 若 comic 无 VIDEO 页面 → transcodeStatus = 'NOT_NEEDED'

  Must NOT:
  - 不要把 IMAGE 页面纳入 transcodeStatus 聚合（Metis G6）
  - 不要修改现有的 image-only 查询逻辑

  Parallelization: Wave 2 | Blocked by: T1 | Blocks: T10,T11
  References: StorageQueryServiceImpl.java:aggregateHqStatus, StorageMapper.xml（GROUP_CONCAT 模式）, ComicStorageDTO.java
  Acceptance criteria: curl GET /api/admin/storage/comics — ComicStorageDTO 含 transcodeStatus；有 VIDEO 待转码的 comic 显示 PENDING
  QA scenarios: happy: comic 92 (664 IMAGE + 4 VIDEO PENDING) → hqStatus=READY, transcodeStatus=PENDING；failure: comic 无 VIDEO 页面 → transcodeStatus=NOT_NEEDED
  Commit: Y | feat(api): add transcodeStatus aggregation to storage query

- [x] 6. API Handler: TranscodeCompletedHandler + TranscodeFailedHandler

  What to do:
  - TranscodeCompletedHandler: @RabbitListener(queues="video.transcode.result.queue", routingKey="video.transcode.completed")
  - 幂等更新: UPDATE page SET hq_path=?, container=?, video_codec=?, audio_codec=?, file_size=?, transcode_status='DONE' WHERE id=? AND transcode_status='PROCESSING'（Metis G12）
  - TranscodeFailedHandler: UPDATE page SET transcode_status='FAILED' WHERE id=? AND transcode_status='PROCESSING'
  - 手动 ACK: channel.basicAck(tag, false) / channel.basicReject(tag, false)

  Must NOT:
  - 不要信任事件中的 transcode_status——必须在 UPDATE 中加 WHERE transcode_status='PROCESSING' 防重复
  - 不要忘记更新 hq_path（转码后扩展名可能变化）

  Parallelization: Wave 2 | Blocked by: T2,T3 | Blocks: T11
  References: LqCompletedHandler.java, HqDeletedHandler.java
  Acceptance criteria: MQ 发 VideoTranscodeCompletedEvent → page 表 transcode_status 变为 DONE，hq_path/codec 更新
  QA scenarios: happy: 发 completed → DB 更新 DONE；idempotency: 发两次 completed → 第二次 UPDATE 影响 0 行（transcode_status 已非 PROCESSING）
  Commit: Y | feat(api): add transcode event handlers

- [x] 7. Worker: VideoTranscodeHandler (ffmpeg → temp → atomic replace → event)

  What to do:
  - @RabbitListener(queues="video.transcode.queue")
  - 从事件读取 comicId, pageId, hqRoot, hqPath, container
  - 构建 HQ 路径: Path.of(mangaRoot, hqRoot, hqPath)
  - ffmpeg 转码到 temp: -c:v libx264 -crf 23 -preset medium -c:a aac -b:a 128k -movflags +faststart -y → tempDir/{pageId}.mp4
  - 超时控制: proc.waitFor(10, TimeUnit.MINUTES) — 超时则 destroyForcibly (Metis G9)
  - 原子替换: Files.move(tempFile, hqTarget, ATOMIC_MOVE, REPLACE_EXISTING)（Metis G2）
  - 若原扩展名 != mp4: 删除旧文件
  - 构建新 hqPath（{comicId}/{chapterId}/{basename}.mp4）
  - 发 completed: rabbitTemplate.convertAndSend("comic.video","video.transcode.completed", event)
  - 失败: 保留原文件，发 failed，reject 消息

  Must NOT:
  - 不要先删原文件再搬 temp（Metis G2）
  - 不要让 proc.waitFor() 无限阻塞（Metis G9）
  - 不要向 DB 写 transcode_status——由 API Handler 更新

  Parallelization: Wave 2 | Blocked by: T2,T3 | Blocks: nothing
  References: VideoNormalizer.java:212 (ffmpeg 参数), LqGenerateHandler.java (MQ 消费模板), HqDeleteHandler.java (HQ 路径构建)
  Acceptance criteria: 消费 VideoTranscodeRequestedEvent → temp 有 mp4 → HQ 原文件被替换 → completed 事件发出
  QA scenarios: happy: 转码 wmv→mp4，hq_path 更新为 .mp4；failure: 损坏视频 → ffmpeg 失败 → failed 事件，原文件保留；timeout: 卡住的视频 → 10min 超时 → failed
  Commit: Y | feat(worker): add VideoTranscodeHandler

- [x] 8. Frontend types + API service

  What to do:
  - types/index.ts: ComicStorageItem 加 transcodeStatus: 'NOT_NEEDED'|'PENDING'|'PROCESSING'|'DONE'|'FAILED'
  - types/index.ts: StorageOperationType 加 TranscodeVideos = 'TRANSCODE_VIDEOS'
  - services/api.ts: adminApi 加 transcodeVideos: (comicId) => api.post(`/admin/comics/${comicId}/transcode-videos`)
  - services/storage.ts: storageService 加 transcodeVideos(comicId) 方法

  Must NOT:
  - 不要创建新的 store 或 service 文件

  Parallelization: Wave 3 | Blocked by: T5 | Blocks: T10,T11
  References: types/index.ts:ComicStorageItem, types/index.ts:StorageOperationType, services/api.ts:adminApi
  Acceptance criteria: npx vue-tsc --noEmit 零错误
  QA scenarios: TypeScript 编译验证 transcodeStatus 字段存在且类型正确
  Commit: Y | feat(frontend): add transcode types and API

- [x] 9. Frontend: StorageTable + StorageStatusTag

  What to do:
  - StorageStatusTag.vue: STATUS_MAP 加 transcode 类型：NOT_NEEDED(''), PENDING('warning','待转码'), PROCESSING('warning','转码中'), DONE('success','已转码'), FAILED('danger','失败')
  - StorageTable.vue: 操作列加 "转码" 按钮：transcodeStatus === 'PENDING' || transcodeStatus === 'FAILED' 时显示，busyState 禁用
  - StorageTable.vue: 新增 "转码" 列：transcodeStatus 非 NOT_NEEDED 时显示 StorageStatusTag(type="transcode")
  - emit 'transcodeVideos' 事件

  Must NOT:
  - 不要给非 VIDEO 漫画显示转码列/按钮（transcodeStatus='NOT_NEEDED' 时隐藏）

  Parallelization: Wave 3 | Blocked by: T8 | Blocks: T11
  References: StorageTable.vue:94-99（DeleteHQ/GenerateLQ 按钮模板）, StorageStatusTag.vue:9-27（hq/lq 类型映射）
  Acceptance criteria: 有 PENDING 视频的漫画行显示 "待转码" 标签 + "转码" 按钮；NOT_NEEDED 漫画不显示
  QA scenarios: happy: 漫画 transcodeStatus=PENDING → 显示黄色"待转码"标签 + 可点击"转码"按钮；boundary: 漫画 NOT_NEEDED → 转码列不显示标签，无按钮
  Commit: Y | feat(frontend): add transcode column and button to storage table

- [x] 10. Frontend: StoragePage + polling

  What to do:
  - StoragePage.vue: 加 handleTranscodeVideos(comicId) → storageService.transcodeVideos → ElMessage.success → polling.start
  - StoragePage.vue: StorageTable 监听 @transcode-videos
  - useStoragePolling.ts: 加 TRANSCODE_VIDEOS 分支 → 轮询至 transcodeStatus === 'DONE' || transcodeStatus === 'NOT_NEEDED'（加 FAILED 为终端状态，Metis G17）
  - StorageBatchBar.vue: 加批量转码按钮（可选，后续）

  Must NOT:
  - 不要漏掉 FAILED 作为轮询停止条件

  Parallelization: Wave 3 | Blocked by: T8,T9,T10 | Blocks: nothing
  References: StoragePage.vue:105-111 (handleDeleteHQ), useStoragePolling.ts:40-48 (轮询停止条件)
  Acceptance criteria: 点击"转码"按钮 → loading → API 调用 → polling 启动 → transcodeStatus 变为 DONE 时停止
  QA scenarios: happy: 点转码→pending→done→标签变绿"已转码"；failure: 点转码→pending→failed→标签变红"失败"
  Commit: Y | feat(frontend): add transcode page integration and polling

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit — 对照 design spec 逐条核对
- [ ] F2. Code quality review — Oracle 审查全部新增代码
- [ ] F3. Real manual QA — curl API + 前端页面验证完整流程
- [ ] F4. Scope fidelity — 确认未实现元数据修复/封面重生成等排外项

## Commit strategy
每完成一个 todo 提交一次，commit message 格式: `feat|fix(<scope>): <中文描述>`。全部通过后 squash 或 rebase 为单个 commit。

## Success criteria
- [ ] POST /api/admin/comics/{id}/transcode-videos 返回 200 并触发转码
- [ ] Worker 成功将 wmv/flv/avi 等非标准格式转码为 mp4
- [ ] 前端存储管理页面可以触发转码并查看状态
- [ ] 转码完成后 hq_path 更新，reader 可以正常播放
- [ ] FAILED 页面标记清晰，可重新触发
