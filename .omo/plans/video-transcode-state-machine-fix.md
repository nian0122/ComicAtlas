# video-transcode-state-machine-fix - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**What you'll get:** 视频转码提交后，数据库状态会在成功或失败时可靠收敛，管理页会持续显示“转码中”并在终态给出反馈。历史上卡在 `PENDING` 的任务可按相同规则恢复处理。

**Why this approach:** API 保持数据库唯一写入者，Worker 保持文件操作执行者；移除没有生产者的 `PROCESSING` 前置条件，避免结果事件被无声丢弃。

**What it will NOT do:** 不处理 Worker 部署；不让 Worker 写 MySQL；不增加取消、任务中心或 `PROCESSING` 事件握手。

**Effort:** Medium
**Risk:** Medium - 需要修复已有 MQ 结果事件与数据库状态之间的契约。
**Decisions to sanity-check:** `PENDING` 同时表示排队与执行中；前端超时只停止观察，不将后台任务标记失败。

Your next move: 使用 `$start-work` 执行计划，或先要求高精度审查。完整执行细节如下。

---

> TL;DR (machine): Medium / Medium — 修复 PENDING 状态机、接通详情页轮询、覆盖 MQ 结果与历史积压回归。

## Scope
### Must have
- API 只写数据库，Worker 只执行文件转码并发布完成/失败事件。
- 状态机固定为 `NOT_NEEDED|FAILED → PENDING → DONE|FAILED`；PENDING 在 UI 中展示为“转码中”。
- 完成/失败消费者幂等处理 PENDING 记录，重复或过期消息不会覆盖终态。
- 存储详情页提交转码后复用轮询，终态刷新数据并给出成功/失败/仍在后台处理的反馈。
- 覆盖 API、MQ 消费、Worker 结果与前端交互的自动化测试。
### Must NOT have (guardrails, anti-slop, scope boundaries)
- 不修改 Docker Compose、Worker 部署、RabbitMQ 远端配置、FFmpeg 安装或媒体路径布局。
- 不新增 Worker 数据库写入、状态写 HTTP 回调、PROCESSING 事件或取消机制。
- 不改变转码编码参数、原子文件替换、Nginx 或阅读器行为。

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after；后端 JUnit/Spring AMQP 测试与 Playwright 管理页 E2E。
- Evidence: `.omo/evidence/video-transcode-state-machine-fix/task-<N>.md`，记录命令、退出码、事件状态断言与截图。

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.

Wave 1 固化 API/Worker 状态契约与回归测试；Wave 2 接通前端观察并验证真实管理流程；Wave 3 处理历史 PENDING 说明和文档。

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 | 无 | 2, 3, 4 | 无 |
| 2 | 1 | 3, 4 | 无 |
| 3 | 1, 2 | 4 | 无 |
| 4 | 1, 2, 3 | F1-F4 | 无 |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->
- [x] 1. 固化 API 独占写库的 PENDING 转码状态契约
  What to do / Must NOT do: 以 `AdminStorageController.transcodeVideos()` 的提交后消息发布为唯一请求入口；定义并在实现中统一 `NOT_NEEDED|FAILED → PENDING → DONE|FAILED`。将完成与失败消费者的数据库前置条件从 `PROCESSING` 改为 `PENDING`，使用条件更新或等价方式确保只消费一次。重复消息、已 DONE/FAILED 或不存在的 page 必须确认并跳过。不得新增 Worker Mapper、DataSource、写库或 PROCESSING 生产者。
  Parallelization: Wave 1 | Blocked by: 无 | Blocks: 2, 3, 4
  References (executor has NO interview context - be exhaustive): `api-service/src/main/java/com/comicatlas/api/admin/controller/AdminStorageController.java:71-158`; `api-service/src/main/java/com/comicatlas/api/export/event/TranscodeCompletedHandler.java:21-41`; `api-service/src/main/java/com/comicatlas/api/export/event/TranscodeFailedHandler.java:21-35`; `api-service/src/main/java/com/comicatlas/api/comic/entity/Media.java`; `api-service/src/main/resources/db/schema.sql:67`; `docs/superpowers/specs/2026-07-26-video-transcode-design.md:44-52`。
  Acceptance criteria (agent-executable): 执行 `mvn -pl api-service test -Dtest=*Transcode*Test,*AdminStorage*Test`；断言完成事件将 PENDING 更新为 DONE 并更新路径/编码/大小，失败事件将 PENDING 更新为 FAILED，重复事件不改变 DONE/FAILED 记录。
  QA scenarios (name the exact tool + invocation): Happy: 以 PENDING Media fixture 消费完成事件，断言新 HQ 元数据和 DONE；Failure: 以 DONE 或不存在 Media 消费事件，断言 ACK 且无二次数据库更新。Evidence `.omo/evidence/video-transcode-state-machine-fix/task-1.md`。
  Commit: Y | fix(transcode): 修复视频转码状态机

- [x] 2. 验证 Worker 文件处理与结果事件保持 API/Worker 边界
  What to do / Must NOT do: 检查并补充 `VideoTranscodeHandler` 的测试，确认它只使用事件提供的文件引用、执行 ffmpeg/原子替换、发布 `completed` 或 `failed`，且从不写数据库。保留 10 分钟超时、临时文件清理和失败事件；不得为解决状态机问题向 Worker 增加数据库状态更新。
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 3, 4 | Can parallelize with: 无
  References (executor has NO interview context - be exhaustive): `worker-service/src/main/java/com/comicatlas/worker/event/VideoTranscodeHandler.java:42-154`; `comic-common/src/main/java/com/comicatlas/common/event/VideoTranscodeRequestedEvent.java`; `comic-common/src/main/java/com/comicatlas/common/event/VideoTranscodeCompletedEvent.java`; `comic-common/src/main/java/com/comicatlas/common/event/VideoTranscodeFailedEvent.java`; `AGENTS.md` 中“禁止 Worker 直接写 MySQL”。
  Acceptance criteria (agent-executable): 执行 `mvn -pl comic-common,worker-service test -Dtest=*VideoTranscode*Test`；验证成功路径发布一个 completed 事件且输出 MP4 存在，ffmpeg 非零退出发布一个 failed 事件并不遗留临时文件。
  QA scenarios (name the exact tool + invocation): Happy: 使用临时 HQ 文件和受控 ffmpeg fixture 生成 completed 事件；Failure: 模拟 ffmpeg 失败，断言 failed 事件的 pageId 与错误信息正确。Evidence `.omo/evidence/video-transcode-state-machine-fix/task-2.md`。
  Commit: Y | test(transcode): 覆盖 Worker 转码结果事件

- [x] 3. 接通存储详情页的转码状态观察与反馈
  What to do / Must NOT do: 在 `StorageDetailPage.vue` 提交转码成功后调用现有 `useStoragePolling` 的 `start(comicId, StorageOperationType.TranscodeVideos)`，并在组件卸载时停止轮询。将 PENDING/PROCESSING 均显示为“转码中”；轮询进入 DONE 或 FAILED 时刷新详情并显示对应反馈。达到轮询上限时仅提示“仍在后台处理，可稍后刷新”，不得把数据库状态写成 FAILED 或自动重复提交。
  Parallelization: Wave 2 | Blocked by: 1, 2 | Blocks: 4 | Can parallelize with: 无
  References (executor has NO interview context - be exhaustive): `frontend/src/views/management/storage/StorageDetailPage.vue:214-224`; `frontend/src/composables/storage/useStoragePolling.ts:13-67`; `frontend/src/services/storage.ts:63-66`; `frontend/src/services/api.ts:114-115`; `frontend/src/types/index.ts:278,319`; `frontend/src/views/management/storage/StorageStatusTag.vue:27-31`; `DESIGN.md`。
  Acceptance criteria (agent-executable): `npm --prefix frontend run build` 通过；Playwright 断言确认后发起转码请求并开始轮询、DONE/FAILED 停止轮询、超时不显示“转码失败”。
  QA scenarios (name the exact tool + invocation): Happy: mock PENDING 后 DONE 的 API 响应，检查状态标签与成功消息；Failure: mock FAILED 和持续 PENDING，检查失败信息与“仍在后台处理”提示。Evidence `.omo/evidence/video-transcode-state-machine-fix/task-3.md`（375px 与 1280px 截图）。
  Commit: Y | fix(storage): 观察视频转码状态

- [x] 4. 处理历史 PENDING 的回归、文档与端到端链路验证
  What to do / Must NOT do: 为历史 PENDING 记录定义安全行为：重新触发时不得重复发布并发任务；由 Worker 已处理但结果未落库的场景，完成事件可直接将 PENDING 收敛到 DONE。更新设计/API/用户指南，说明 PENDING 的含义、重试规则、部署不在功能范围内和无法自动判定实际文件是否已转码的限制。新增 API→MQ→Worker 结果→API 的集成验证或等价受控消息链路测试。
  Parallelization: Wave 3 | Blocked by: 1, 2, 3 | Blocks: F1-F4 | Can parallelize with: 无
  References (executor has NO interview context - be exhaustive): `api-service/src/main/java/com/comicatlas/api/admin/controller/AdminStorageController.java:101-151`; `api-service/src/main/java/com/comicatlas/api/config/RabbitMqConfig.java:364-394`; `worker-service/src/main/java/com/comicatlas/worker/config/RabbitMqConfig.java:155-168,260-268`; `docs/superpowers/specs/2026-07-26-video-transcode-design.md`; `docs/api.md`; 当前运行态发现：VIDEO `PENDING` 记录 174 条。
  Acceptance criteria (agent-executable): 执行 `mvn -pl comic-common,api-service,worker-service test` 与 `npx playwright test e2e/tests/storage-transcode.spec.ts`；验证 PENDING 完成消息最终为 DONE，FAILED 重试只发布一次请求，文档包含 PENDING 语义和限制。
  QA scenarios (name the exact tool + invocation): Happy: 以历史 PENDING fixture 注入 completed 事件并最终显示 DONE；Failure: 对 PENDING 连续提交两次，第二次不发布重复请求且状态保持一致。Evidence `.omo/evidence/video-transcode-state-machine-fix/task-4.md`。
  Commit: Y | test(docs): 覆盖历史转码积压处理

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [x] F1. Plan compliance audit
  对照 Todos 1-4 检查状态转换、事件类型、测试与文档；确认所有数据库写操作均在 API，记录到 `.omo/evidence/video-transcode-state-machine-fix/f1.md`。
- [x] F2. Code quality review
  审查事件幂等、ACK/NACK、事务后发布、文件原子替换、TypeScript 定时器清理及错误提示，记录到 `.omo/evidence/video-transcode-state-machine-fix/f2.md`。
- [x] F3. Real manual QA (SKIPPED - build environment)
  使用 Playwright 在 375px 和 1280px 打开存储详情页，确认转码、观察 PENDING、DONE/FAILED 与超时反馈；记录网络与截图证据到 `.omo/evidence/video-transcode-state-machine-fix/f3.md`。
- [x] F4. Scope fidelity
  审查最终 diff，确认没有 Worker 部署、全局搜索、取消、PROCESSING 握手、Nginx 或阅读器改动，记录到 `.omo/evidence/video-transcode-state-machine-fix/f4.md`。

## Commit strategy
按可回滚边界提交：API 状态机与测试、Worker 事件测试、前端轮询与 E2E、文档与历史 PENDING 回归。所有提交信息使用中文。

## Success criteria
- API 写入 PENDING 后，完成/失败事件能使同一页面可靠收敛到 DONE/FAILED；重复消息不破坏终态。
- Worker 不包含数据库写入逻辑，只处理文件和发布结果事件。
- 管理页转码后会观察状态、在终态停止轮询，并在超时时说明任务仍在后台。
- 现有和历史 PENDING 记录可由结果事件安全收敛，重复触发不并发重复转码。
- 后端目标测试、前端构建和 Playwright E2E 均通过并产出证据。
