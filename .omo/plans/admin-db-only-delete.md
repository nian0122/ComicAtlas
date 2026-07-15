# admin-db-only-delete - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**What you'll get:** 一个后台管理接口 `DELETE /api/admin/comics/{id}?mode=DATABASE_ONLY`，只清数据库里的漫画记录，保留本地文件；同时 Dashboard 页面新增一个「数据库维护」区域，输入 Comic ID 即可执行并看到删除了多少条关联数据。

**Why this approach:** 独立 admin 接口与现有 `DELETE /api/comics/{id}` 完全隔离，避免误删文件；直接走 api-service 事务删除，不经过 RabbitMQ/Worker，逻辑简单且与文件删除链路互不干扰。

**What it will NOT do:**
- 不删除任何本地文件（HQ/LQ/缩略图/原档）
- 不修改现有普通删除接口的行为
- 不引入权限/鉴权体系
- 不在普通漫画详情页添加删除入口

**Effort:** Short
**Risk:** Medium - 涉及跨表删除和事务边界，需确保不误删文件、不误删 import_task 日志
**Decisions to sanity-check:**
- 删除顺序是否满足当前外键依赖
- import_task「未结束状态」枚举集合是否正确
- Dashboard UI 是否把危险操作与普通统计明确分隔

Your next move: 批准计划后开始执行，或先运行高准确度审查。完整执行细节见下文。

---

> TL;DR (machine): Short effort, medium risk — add admin-only DELETE /api/admin/comics/{id}?mode=DATABASE_ONLY for database-only comic deletion with running-task guard, deletion stats, and a Dashboard maintenance UI; no file deletion, no MQ, no auth.

## Scope
### Must have
- 新增 `DELETE /api/admin/comics/{id}?mode=DATABASE_ONLY` 接口，仅删除数据库记录，不删除本地文件
- 校验漫画存在，不存在返回 `404`
- 检查该漫画是否存在未结束的导入任务，存在返回 `409`
- 在单个事务内删除漫画及其业务关联数据（page / chapter / catalog / comic_tag / reading_history / comic）
- 保留 `import_task` 导入日志
- 返回 `ComicDeleteStats` 删除统计
- 前端 Dashboard 页面新增「数据库维护」区域，支持输入 Comic ID、展示漫画信息、二次确认、调用接口、展示统计
- 前端在漫画状态为未结束导入时禁用删除按钮
- 不经过 RabbitMQ，不调用 Worker

### Must NOT have (guardrails, anti-slop, scope boundaries)
- 不得删除任何本地文件（HQ / LQ / thumbs / raw / metadata）
- 不得修改现有 `DELETE /api/comics/{id}` 行为
- 不得删除 `import_task` 记录
- 不得引入权限/鉴权体系
- 不得在普通漫画详情页（ComicDetailPage）添加删除按钮
- 不得修改 Dashboard 现有统计功能（只在底部新增区域）

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after + Spring Boot 测试 + Playwright 端到端测试
- Evidence: `.omo/evidence/task-<N>-admin-db-only-delete.<ext>`
- Every code change must pass `mvn test` (api-service) and `npm run build` / `vue-tsc --noEmit` (frontend) before commit
- Backend tests: `@SpringBootTest` 或 `@DataJpaTest` + MyBatis Plus 验证删除范围和统计
- Frontend tests: Playwright 验证 Dashboard 新增区域、输入框、二次确认、删除后统计展示

## Execution strategy
### Parallel execution waves

**Wave 1: Backend DTO + AdminService** — 建立数据模型和业务逻辑
**Wave 2: Backend API + Controller** — 暴露接口并接入测试
**Wave 3: Frontend API + Dashboard UI** — 前端调用和界面
**Wave 4: Final QA** — 全链路验证

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1.1 ComicDeleteStats DTO | — | 1.2, 1.3 | — |
| 1.2 AdminService.deleteComic | 1.1 | 1.3 | — |
| 1.3 AdminController endpoint | 1.2 | 2.1 | — |
| 2.1 Backend tests | 1.3 | 3.1 | — |
| 3.1 Frontend adminApi | 1.3 | 3.2 | — |
| 3.2 Dashboard UI | 3.1 | 4.1 | — |
| 4.1 Final verification | 全部 | — | — |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->

- [x] 1. 创建 ComicDeleteStats DTO
  What to do / Must NOT do: 在 `api-service` 中创建 `ComicDeleteStats` DTO 类（`com.comicatlas.api.admin.dto` 包），包含 `comic`、`catalog`、`chapter`、`page`、`tag`、`history` 6 个 int 字段。Must NOT 引入业务实体或依赖。
  Parallelization: Wave 1 | Blocked by: — | Blocks: 2
  References (executor has NO interview context - be exhaustive): docs/superpowers/specs/2026-07-15-admin-db-only-delete-design.md:3.2
  Acceptance criteria (agent-executable): `mvn -pl api-service compile` 通过；类存在且字段与 Spec 一致。
  QA scenarios: happy: DTO 字段完整。failure: 编译失败或字段缺失。Evidence .omo/evidence/task-1-admin-db-only-delete.log
  Commit: Y | feat(api): add ComicDeleteStats DTO

- [x] 2. 实现 AdminService.deleteComic
  What to do / Must NOT do: 在 `AdminService` 接口新增 `ComicDeleteStats deleteComic(Long comicId, String mode)`；在 `AdminServiceImpl` 实现：校验 `mode` 参数（当前仅支持 `DATABASE_ONLY`，其他值抛 400），校验 comic 存在（404），检查未结束 import_task（409），在 `@Transactional` 内按 `page → chapter → catalog → comic_tag → reading_history → comic` 顺序删除，返回统计。Must NOT 删除本地文件或发送 MQ。Must NOT 删除 import_task。
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 3
  References (executor has NO interview context - be exhaustive): api-service/src/main/java/com/comicatlas/api/admin/service/AdminService.java:1-12, api-service/src/main/java/com/comicatlas/api/admin/service/impl/AdminServiceImpl.java:1-39, api-service/src/main/java/com/comicatlas/api/comic/mapper/ComicMapper.java, api-service/src/main/java/com/comicatlas/api/comic/mapper/ChapterMapper.java, api-service/src/main/java/com/comicatlas/api/comic/mapper/PageMapper.java, api-service/src/main/java/com/comicatlas/api/comic/mapper/CatalogMapper.java, api-service/src/main/java/com/comicatlas/api/reader/mapper/ReadingHistoryMapper.java, api-service/src/main/java/com/comicatlas/api/comic/mapper/ComicTagMapper.java, api-service/src/main/java/com/comicatlas/api/importer/mapper/ImportTaskMapper.java, docs/superpowers/specs/2026-07-15-admin-db-only-delete-design.md:4.2-4.4
  Acceptance criteria (agent-executable): `mvn -pl api-service test` 通过；新增针对 `AdminServiceImpl.deleteComic` 的单元/集成测试覆盖：mode 参数校验、正常删除、漫画不存在、运行中任务、统计正确、import_task 保留。
  QA scenarios: happy: 删除后所有关联表清空，import_task 保留，本地文件仍在。failure: 删除文件、删除 import_task、未返回 409、事务未回滚。Evidence .omo/evidence/task-2-admin-db-only-delete.log
  Commit: Y | feat(api): implement deleteComic service with mode

- [x] 3. 新增 AdminController 删除接口
  What to do / Must NOT do: 在 `AdminController` 新增 `@DeleteMapping("/comics/{id}")` 方法，接收 `@RequestParam String mode`，返回 `Result<ComicDeleteStats>`。调用 `adminService.deleteComic(id, mode)`。Must NOT 复用或修改现有 `ComicController` 的删除接口。
  Parallelization: Wave 2 | Blocked by: 2 | Blocks: 4, 5
  References (executor has NO interview context - be exhaustive): api-service/src/main/java/com/comicatlas/api/admin/controller/AdminController.java:1-22, docs/superpowers/specs/2026-07-15-admin-db-only-delete-design.md:4.1
  Acceptance criteria (agent-executable): 启动 api-service 后，`curl -X DELETE "http://localhost:8080/api/admin/comics/{id}?mode=DATABASE_ONLY"` 返回正确 JSON 和删除统计；400/404/409 场景返回正确状态码。
  QA scenarios: happy: 接口返回删除统计。failure: 接口不存在或返回错误状态码。Evidence .omo/evidence/task-3-admin-db-only-delete.log
  Commit: Y | feat(api): add DELETE /api/admin/comics/{id}?mode=DATABASE_ONLY endpoint

- [x] 4. 后端集成测试补充
  What to do / Must NOT do: 在 `api-service/src/test/java/.../admin/service/` 下新增测试类，验证完整删除链路：预置 comic + chapter + page + catalog + comic_tag + reading_history + import_task，调用 service 后断言结果。Must NOT 修改现有测试。
  Parallelization: Wave 2 | Blocked by: 3 | Blocks: 5
  References (executor has NO interview context - be exhaustive): task-2 测试用例
  Acceptance criteria (agent-executable): `mvn -pl api-service test` 全部通过，新增测试覆盖率达标。
  QA scenarios: happy: 所有断言通过。failure: 测试失败或覆盖率不足。Evidence .omo/evidence/task-4-admin-db-only-delete.log
  Commit: Y | test(api): add integration test for db-only comic deletion

- [x] 5. 前端 API 层添加 adminApi
  What to do / Must NOT do: 在 `frontend/src/services/api.ts` 新增 `adminApi.deleteComic(id: number, mode: string): Promise<ComicDeleteStats>`，默认 mode 为 `'DATABASE_ONLY'`。Must NOT 修改现有 comicApi.delete。
  Parallelization: Wave 3 | Blocked by: 3 | Blocks: 6
  References (executor has NO interview context - be exhaustive): frontend/src/services/api.ts:1-57, docs/superpowers/specs/2026-07-15-admin-db-only-delete-design.md:5.1
  Acceptance criteria (agent-executable): `cd frontend && npx vue-tsc --noEmit` 通过；`adminApi` 导出存在且类型正确。
  QA scenarios: happy: 类型检查通过。failure: 类型错误。Evidence .omo/evidence/task-5-admin-db-only-delete.log
  Commit: Y | feat(frontend): add adminApi for db-only delete

- [x] 6. Dashboard 新增数据库维护区域
  What to do / Must NOT do: 在 `frontend/src/pages/DashboardPage.vue` 底部新增「数据库维护」区域：Comic ID 输入框（`<el-input-number>` 仅正整数）、漫画信息展示区（调用 `GET /api/comics/{id}`）、「删除数据库记录」按钮（未结束状态禁用）、二次确认弹窗、删除统计展示。Must NOT 在 Dashboard 顶部或现有统计区域改动。Must NOT 修改 ComicDetailPage。
  Parallelization: Wave 3 | Blocked by: 5 | Blocks: 7
  References (executor has NO interview context - be exhaustive): frontend/src/pages/DashboardPage.vue:1-200, frontend/src/services/api.ts:1-57, frontend/src/types/index.ts, docs/superpowers/specs/2026-07-15-admin-db-only-delete-design.md:5.2-5.4
  Acceptance criteria (agent-executable): `cd frontend && npm run build && npx vue-tsc --noEmit` 通过；Playwright 访问 `/dashboard` 断言「数据库维护」区域存在，输入 ID 后展示漫画信息，点击删除后二次确认弹窗出现，确认后展示统计。
  QA scenarios: happy: UI 完整、交互正确、删除统计展示。failure: 输入非整数、未结束状态未禁用按钮、未展示统计。Evidence .omo/evidence/task-6-admin-db-only-delete.png
  Commit: Y | feat(frontend): add database maintenance section on Dashboard

- [x] 7. 最终验证
  What to do / Must NOT do: 运行 `mvn -pl api-service test` 和 `cd frontend && npm run build && npx vue-tsc --noEmit`；运行 Playwright 端到端测试覆盖后端接口和前端交互。Must NOT 跳过任何失败测试。
  Parallelization: Wave 4 | Blocked by: 1-6 | Blocks: —
  References (executor has NO interview context - be exhaustive): 全部任务
  Acceptance criteria (agent-executable): `mvn -pl api-service test` 通过；`npm run build` 和 `vue-tsc --noEmit` 通过；Playwright 测试通过。
  QA scenarios: happy: 所有验证通过。failure: 任何构建/测试失败。Evidence .omo/evidence/task-7-admin-db-only-delete.log
  Commit: Y | chore: final verification for db-only delete

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [x] F1. Plan compliance audit — verify every todo completed and evidence files exist in `.omo/evidence/`.
- [x] F2. Code quality review — verify no `any`, no `@ts-ignore`, no empty catch blocks, no oversized files, no logic leaks to file deletion.
- [x] F3. Real manual QA — run Playwright end-to-end on Dashboard → input ID → delete → assert stats.
- [x] F4. Scope fidelity — confirm no local file deletion, no import_task deletion, no ComicDetailPage changes, existing delete endpoint unchanged.

## Commit strategy
- 每个 todo 完成后单独 commit，使用中文提交信息（项目约定）。
- 后端 DTO/Service/Controller 分阶段提交，保持测试随代码一起提交。
- 前端 api 和 UI 分阶段提交。
- 禁止 commit 未经 `mvn test` 或 `npm run build` / `vue-tsc --noEmit` 通过的代码。
- 本次实现仅涉及 api-service 和 frontend，不修改 worker-service。

## Success criteria
- `DELETE /api/admin/comics/{id}?mode=DATABASE_ONLY` 可用，返回 `ComicDeleteStats`
- 删除后 `page`、`chapter`、`catalog`、`comic_tag`、`reading_history`、`comic` 表记录清空
- `import_task` 记录保留
- 本地 `D:/manga/hq/{id}`、`D:/manga/lq/{id}`、`D:/manga/thumbs/{id}` 目录保留
- 存在未结束导入任务时返回 `409`
- Dashboard 新增「数据库维护」区域，输入 Comic ID 后展示信息，删除按钮在未结束状态禁用，删除后展示统计
- 不修改现有 `DELETE /api/comics/{id}` 行为
- `mvn -pl api-service test`、`npm run build`、`vue-tsc --noEmit` 全部通过
