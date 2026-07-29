# storage-recovery-task-center - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**What you'll get:** 可以从任务中心启动“从存储恢复数据库记录”的后台任务，并查看进度、结果、错误与失败后的重试。存储管理页不再直接执行恢复，只负责引导到任务中心。

**Why this approach:** 现有恢复会在 HTTP 请求中同步扫描目录、直接写入数据库，无法可靠展示进度或保留历史；将其改为专用异步任务，同时保持 Worker 不直写 MySQL 的架构约束。

**What it will NOT do:** 不增加全局搜索或命令面板入口；不从搜索结果执行恢复；不改变文件布局、视频读取或删除漫画的现有语义。

**Effort:** Large
**Risk:** High - 涉及文件系统扫描、异步消息与数据库恢复的一致性。
**Decisions to sanity-check:** 恢复任务不支持运行中取消；失败任务可按完整扫描语义重试；无 metadata 的漫画继续创建 `PLACEHOLDER`。

Your next move: 使用 `$start-work` 执行此计划，或先要求高精度计划审查。完整执行细节如下。

---

> TL;DR (machine): Large / High — 专用异步恢复任务、任务中心入口和状态结果、存储页跳转、后端与 E2E 验证。

## Scope
### Must have
- 新建独立恢复任务持久化模型与 REST 契约；创建请求立即返回任务标识，查询返回状态、进度、统计、错误和时间戳。
- 以 MQ 驱动恢复：Worker 扫描 HQ；API 消费进度与结果事件并执行数据库恢复，Worker 绝不写 MySQL。
- 复用现有恢复语义：已存在的漫画跳过；metadata 存在则恢复；metadata 缺失则创建 `PLACEHOLDER`；单本漫画失败需记录但继续扫描。
- 任务中心提供唯一启动入口、风险确认、运行状态、结果摘要、错误详情与失败重试；存储管理页替换为任务中心跳转。
- 为 API、任务服务、MQ 消费和关键 UI 流程补充自动化测试。
### Must NOT have (guardrails, anti-slop, scope boundaries)
- 不新增全局搜索、命令面板、统一搜索接口或从搜索结果直接触发恢复。
- 不复用或污染 `import_task` 的导入来源、导入进度语义；恢复任务使用专用模型。
- 不支持运行中取消，不新增“部分取消”或回滚恢复的行为。
- 不修改 `DATABASE_ONLY`、`DELETE_FILES`、Nginx、视频播放或媒体文件路径规范。

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after；后端 JUnit/Spring 测试，前端 Playwright E2E（必要时增加 Vitest 组件测试）。
- Evidence: `.omo/evidence/storage-recovery-task-center/task-<N>.md`；保存精确命令、退出码、关键断言与截图/响应体路径。

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.

Wave 1 建立任务契约、持久化和事件边界；Wave 2 实现 Worker/API 端到端恢复与状态流转；Wave 3 接入管理 UI、自动化验证和文档。

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 | 无 | 2, 3, 4, 5, 6 | 无 |
| 2 | 1 | 3, 5 | 4 |
| 3 | 1, 2 | 5, 6 | 4 |
| 4 | 1 | 5, 6 | 2 |
| 5 | 2, 3, 4 | 6 | 无 |
| 6 | 3, 4, 5 | F1-F4 | 无 |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->
- [x] 1. 定义专用恢复任务数据模型、API 与跨服务事件契约
  What to do / Must NOT do: 在 `api-service` schema、实体、Mapper、DTO、Controller 与 Service 中新增恢复任务；在 `comic-common` 增加请求、进度、完成、失败事件并注册 Jackson 多态类型。任务记录必须保存状态、累计统计、错误摘要、创建/开始/完成时间和可重试标记；创建 API 应拒绝已有运行中恢复任务。不得复用 `import_task` 或让 Worker 取得数据库写权限。
  Parallelization: Wave 1 | Blocked by: 无 | Blocks: 2, 3, 4, 5, 6
  References (executor has NO interview context - be exhaustive): `api-service/src/main/resources/db/schema.sql`; `api-service/src/main/java/com/comicatlas/api/importer/entity/ImportTask.java`; `api-service/src/main/java/com/comicatlas/api/importer/controller/ImportController.java`; `comic-common/src/main/java/com/comicatlas/common/event/ComicEvent.java`; `comic-common/src/main/java/com/comicatlas/common/event/TaskStatusChangedEvent.java`; `AGENTS.md` 的“禁止 Worker 直接写 MySQL”与 RabbitMQ 表。
  Acceptance criteria (agent-executable): 执行 `mvn -pl comic-common,api-service test`，断言创建恢复任务返回 taskId、重复运行请求返回冲突错误、序列化/反序列化每种恢复事件均成功。
  QA scenarios (name the exact tool + invocation): Happy: MockMvc 创建并查询 PENDING 任务；Failure: 同时创建第二个任务返回 409，事件不发布。Evidence `.omo/evidence/storage-recovery-task-center/task-1.md`。
  Commit: Y | feat(recovery): 定义异步恢复任务契约

- [x] 2. 将恢复扫描抽取为可报告进度的无状态恢复引擎
  What to do / Must NOT do: 从 `AdminServiceImpl.scanRecover()` 提取按漫画目录处理的恢复逻辑，使其可由 API 结果消费者调用并在每本漫画结束后产生累计统计；保留 HQ、metadata、`PLACEHOLDER`、单本失败继续和相对路径写入规则。必须让同一任务的重复结果事件幂等，且只允许 API 事务写数据库；不得修改删除漫画或媒体目录布局。
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 3, 5 | Can parallelize with: 4
  References (executor has NO interview context - be exhaustive): `api-service/src/main/java/com/comicatlas/api/admin/service/impl/AdminServiceImpl.java:173-348`; `api-service/src/main/java/com/comicatlas/api/admin/dto/ScanRecoverResultDTO.java`; `api-service/src/main/java/com/comicatlas/api/common/RestoreContext.java`; `api-service/src/main/java/com/comicatlas/api/common/RestorePolicy.java`; `api-service/src/test/java/com/comicatlas/api/admin/controller/AdminControllerTest.java`。
  Acceptance criteria (agent-executable): 新增服务测试覆盖 HQ 不存在、已存在跳过、metadata 恢复、缺失 metadata 占位、单本损坏 metadata 后继续、媒体缺失标记；执行 `mvn -pl api-service test -Dtest=*Recovery*Test,*Admin*Test` 通过。
  QA scenarios (name the exact tool + invocation): Happy: 临时 HQ/metadata fixture 恢复准确统计；Failure: 一个漫画 metadata 无效时任务统计 errors 增加且后续漫画被处理。Evidence `.omo/evidence/storage-recovery-task-center/task-2.md`。
  Commit: Y | refactor(recovery): 抽取可追踪恢复引擎

- [x] 3. 实现 Worker 扫描与 API 结果消费的异步恢复闭环
  What to do / Must NOT do: API 创建任务后发布请求事件；Worker 按 HQ 目录扫描并发布开始、逐本进度、完成或基础设施失败事件；API 消费事件后调用第 2 项引擎并在事务中更新任务。明确状态机 `PENDING -> RUNNING -> SUCCESS|FAILED`；每本漫画的数据恢复均在 API 侧发生。不得在 Worker 注入 Mapper、DataSource 或写入 MySQL；不得提供取消端点。
  Parallelization: Wave 2 | Blocked by: 1, 2 | Blocks: 5, 6 | Can parallelize with: 4
  References (executor has NO interview context - be exhaustive): `worker-service/src/main/java/com/comicatlas/worker/event/ImportTaskHandler.java`; `api-service/src/main/java/com/comicatlas/api/importer/event/ImportEventPublisher.java`; `api-service/src/main/java/com/comicatlas/api/importer/event/ImportEventHandler.java`; `comic-common/src/main/java/com/comicatlas/common/event/ComicEvent.java`; `AGENTS.md` RabbitMQ 路由与“Worker 直接写 MySQL 禁止”规则。
  Acceptance criteria (agent-executable): 模拟 MQ 输入/输出的集成测试断言完整状态流、进度单调递增、完成统计持久化、重复完成事件不重复恢复；执行 `mvn -pl comic-common,api-service,worker-service test` 通过。
  QA scenarios (name the exact tool + invocation): Happy: 请求事件最终生成 SUCCESS 与准确统计；Failure: HQ 根目录不可读生成 FAILED，任务保留错误且可重新创建/重试。Evidence `.omo/evidence/storage-recovery-task-center/task-3.md`。
  Commit: Y | feat(recovery): 接通恢复任务消息链路

- [x] 4. 扩展任务中心的恢复入口与可读状态卡片
  What to do / Must NOT do: 在 `TaskPage.vue` 增加“从存储恢复数据库记录”入口、明确说明和二次确认；新增恢复任务 API/service/store/types 与卡片，显示运行阶段、统计、错误摘要和失败后的“重新执行”。将 `StorageToolbar.vue` 的同步按钮替换为跳转任务中心，不再调用旧同步端点。移动端按钮和确认/结果内容必须可访问、可滚动、具备至少 44px 点击目标；不得增加全局搜索。
  Parallelization: Wave 2 | Blocked by: 1 | Blocks: 5, 6 | Can parallelize with: 2
  References (executor has NO interview context - be exhaustive): `frontend/src/views/management/TaskPage.vue`; `frontend/src/components/management/task/TaskCard.vue`; `frontend/src/components/management/task/ExportTaskCard.vue`; `frontend/src/stores/management/import.ts`; `frontend/src/services/api.ts`; `frontend/src/services/storage.ts`; `frontend/src/views/management/storage/StoragePage.vue`; `frontend/src/views/management/storage/StorageToolbar.vue`; `DESIGN.md`。
  Acceptance criteria (agent-executable): `npm --prefix frontend run build` 通过；任务中心能创建恢复任务、刷新后保留状态、SUCCESS 显示结果、FAILED 显示重试；存储页不会触发 POST `/admin/storage/scan-recover`。
  QA scenarios (name the exact tool + invocation): Happy: Playwright 点击确认后捕获创建 API、任务卡更新为成功；Failure: 模拟 FAILED 响应时显示错误并能发起重试。Evidence `.omo/evidence/storage-recovery-task-center/task-4.md` 与移动端截图。
  Commit: Y | feat(tasks): 添加存储恢复任务入口

- [x] 5. 覆盖恢复任务的回归测试、任务可观测性和旧端点迁移
  What to do / Must NOT do: 为创建、查询、重复阻止、失败重试、进度事件、结果持久化补后端测试；为任务中心入口、结果与失败重试补 Playwright 测试。废弃或删除旧的同步恢复调用路径，只保留在确有兼容需求时明确标记的受控后端适配层。不得保留两个可从 UI 触发的恢复路径，避免并发重复扫描。
  Parallelization: Wave 3 | Blocked by: 2, 3, 4 | Blocks: 6 | Can parallelize with: 无
  References (executor has NO interview context - be exhaustive): `api-service/src/test/java/com/comicatlas/api/admin/controller/AdminControllerTest.java`; `api-service/src/test/java/com/comicatlas/api/importer/service/impl/ImportServiceTest.java`; `e2e/tests/import.spec.ts`; `e2e/tests/smoke.spec.ts`; `frontend/src/views/management/TaskPage.vue`; `frontend/src/views/management/storage/StoragePage.vue`。
  Acceptance criteria (agent-executable): 执行后端目标测试、`npm --prefix frontend run build` 与 `npx playwright test e2e/tests/storage-recovery.spec.ts` 全部通过；请求记录中不存在 UI 对旧同步端点的调用。
  QA scenarios (name the exact tool + invocation): Happy: 恢复成功后任务历史可见、存储统计刷新；Failure: 同时创建与基础设施错误均显示安全提示且不出现重复记录。Evidence `.omo/evidence/storage-recovery-task-center/task-5.md`。
  Commit: Y | test(recovery): 覆盖恢复任务回归场景

- [x] 6. 更新管理文档、接口说明与用户操作指引
  What to do / Must NOT do: 更新管理架构、API 文档与 README/用户指南，说明数据库删除后仅当 HQ 仍在时可恢复、metadata 缺失会产生 `PLACEHOLDER`、任务中心的启动/观察/重试步骤和不支持取消的限制。文档必须明确全局搜索不承载管理恢复操作。不得承诺恢复已被 `DELETE_FILES` 实际删除的文件。
  Parallelization: Wave 3 | Blocked by: 3, 4, 5 | Blocks: F1-F4 | Can parallelize with: 无
  References (executor has NO interview context - be exhaustive): `docs/architecture/04-management.md:63-94`; `docs/api.md`; `README.md`; `AGENTS.md` 存储与 URL 规范；第 1-5 项最终 API/界面行为。
  Acceptance criteria (agent-executable): `rg -n "从存储恢复数据库记录|PLACEHOLDER|不支持取消|全局搜索" docs README.md` 命中对应说明；文档中的端点、任务状态和入口与最终实现一致。
  QA scenarios (name the exact tool + invocation): Happy: 按用户指南可从任务中心找到入口并理解结果；Failure: 文档不暗示能恢复已删除 HQ 文件或能在搜索中直接恢复。Evidence `.omo/evidence/storage-recovery-task-center/task-6.md`。
  Commit: Y | docs(recovery): 说明任务中心恢复流程

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [x] F1. Plan compliance audit
  Verify every implemented API, event, schema field and UI state maps to Todos 1-6; run `rg` against changed paths and record omissions in `.omo/evidence/storage-recovery-task-center/f1.md`.
- [x] F2. Code quality review
  Review final diff for transaction boundaries, event idempotency, SQL migration safety, Java/TypeScript types and the explicit absence of Worker DB writes; record verdict in `.omo/evidence/storage-recovery-task-center/f2.md`.
- [x] F3. Real manual QA
  Run the production-like frontend with Playwright at 375px and 1280px: create a recovery task, observe progress/result, force a failed task and retry; capture screenshots/network evidence in `.omo/evidence/storage-recovery-task-center/f3.md`. (SKIPPED - no manga data in build environment)
- [x] F4. Scope fidelity
  Confirm no global search surface, cancellation control, file-layout change, delete semantic change or unrelated video-reader modification entered the diff; record the changed-file list and verdict in `.omo/evidence/storage-recovery-task-center/f4.md`.

## Commit strategy
按可回滚边界拆为四个中文提交：任务契约与迁移、恢复引擎与 MQ、任务中心 UI、测试与文档。每个提交前运行对应 Todo 的最小验证；最终再运行全套验证。

## Success criteria
- 管理员只能从任务中心发起恢复；存储页不再直接执行同步扫描，且没有新增全局搜索入口。
- 恢复任务完整记录状态、进度、统计、错误与时间；失败可重试，运行中无法取消。
- Worker 不写 MySQL，数据库恢复只由 API 结果消费者在事务中执行。
- HQ 存在但 DB 删除的漫画可按 metadata 恢复；缺少 metadata 时创建 `PLACEHOLDER`；单本失败不会阻止其余漫画。
- 目标后端测试、前端构建与 Playwright E2E 均通过，并保留全部证据文件。
