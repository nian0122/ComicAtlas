# lq-migration - Work Plan

## TL;DR (For humans)

**What you'll get:** 一个临时后端 API，让旧系统中"HQ 为空、只有 LQ"的漫画能直接导入新系统，LQ 文件进入系统 LQ 存储，数据库诚实记录 HQ 为空、LQ 就绪。

**Why this approach:** 
1. **诚实数据模型**：不将 LQ 伪装成 HQ，数据库直接记录 `hqRoot=null, lqRoot="LQ"`，前端阅读器利用已有的 fallback 机制自动加载 LQ。
2. **最小侵入核心链路**：只修改 `ImportEventHandler` 这一个 DB 写入点，不动 `DirectoryImportHandler` 这个核心导入引擎。

**What it will NOT do:** 
- 不会修改前端界面（临时功能，手动调用 API 即可）。
- 不会处理 HQ+LQ 同时存在的漫画（这些走正常 DIRECTORY 导入）。
- 不会自动生成或重新压缩 LQ（旧系统 LQ 已优化，直接搬运）。

**Effort:** Medium
**Risk:** Low - 修改范围小（1 个新类 + 2 个现有类条件分支），前端零改动，阅读器已有 fallback 兼容。
**Decisions to sanity-check:** 
1. 是否接受 `ImportEventHandler` 的轻微修改（增加 sourceType 条件分支）？
2. 是否接受 `HqStatus.EMPTY` 这个新枚举值？
3. 冒烟测试是否需要真实旧系统目录，还是临时构造的 test 目录即可？

Your next move: 审阅本计划，确认无遗漏后执行 `$start-work`。详细执行步骤见下方。

---

> TL;DR (machine): Medium effort, Low risk, 7 implementation tasks across 3 waves + final verification, delivers honest LQ-only migration with zero frontend changes.

## Scope
### Must have
1. **API 层**: `ImportServiceImpl.createImportTask()` 支持 `sourceType="MIGRATE_LQ"`（并入现有 DIRECTORY case，验证 sourcePath 存在即可）。
2. **Worker 路由**: `ImportTaskHandler.handle()` 增加 `case "MIGRATE_LQ"` 路由到新增 Handler。
3. **核心 Handler**: 新增 `LqMigrationHandler`（Worker 层），完成：
   - 从 sourcePath 推断 LQ 路径（`h_*` → `l_*`）
   - 用现有 `DirectoryParser` + `MetadataAssembler` 扫描 LQ 目录
   - 搬运 LQ 文件到**系统 LQ 目录**（`storageService.store(src, "LQ", ...)`）
   - 生成 metadata.json（version=3，标记 `sourceType="MIGRATE_LQ"`）
   - 封面：从系统 LQ 复制到 thumbs 目录
4. **DB 写入层**: `ImportEventHandler.insertChapter()` 根据 metadata.sourceType 区分：
   - `MIGRATE_LQ`: `hqRoot=null, hqPath=null, hqStatus="EMPTY"`, `lqRoot="LQ", lqPath=path, lqStatus="READY"`
   - 其他: 保持现有硬编码 HQ 逻辑
5. **枚举扩展**: `HqStatus` 增加 `EMPTY` 状态；`EnumTypeHandlers` 自动兼容（VARCHAR 映射）。
6. **测试**: `LqMigrationHandler` 单元测试（路径推断、搬运逻辑、metadata 生成）。
7. **冒烟测试**: 用临时目录验证完整导入流程（API → MQ → Worker → DB → 阅读器）。

### Must NOT have (guardrails, anti-slop, scope boundaries)
- **禁止修改 `DirectoryImportHandler`**：核心导入链路不得改动。
- **禁止修改前端**：临时功能只提供后端 API。
- **禁止自动生成 LQ**：`lqStatus=READY` 表示已优化，系统不应再压缩。
- **禁止伪装**：不能把 LQ 文件搬运到系统 HQ 目录并记录为 HQ。
- **MIGRATE_LQ 只处理 HQ 为空的漫画**：HQ 有内容的漫画（状态 1 和 2）走正常 `DIRECTORY` sourceType，这是用户的选择策略，不是技术限制。

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after + JUnit 5（Worker 层单元测试）+ 冒烟测试（端到端）
- Evidence: `.omo/evidence/task-<N>-lq-migration.<ext>`

## Execution strategy
### Parallel execution waves
- **Wave 1** (独立并行): 1. API 层改动 + 2. 枚举扩展 + 3. Worker 路由
- **Wave 2** (依赖 Wave 1): 4. 新增 LqMigrationHandler + 5. ImportEventHandler 修改
- **Wave 3** (依赖 Wave 2): 6. 单元测试 + 7. 冒烟测试
- **Wave 4** (最终验证): F1-F4 并行执行

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1. API 层 | — | 5 | 2, 3 |
| 2. 枚举扩展 | — | 5 | 1, 3 |
| 3. Worker 路由 | — | 4 | 1, 2 |
| 4. LqMigrationHandler | 3 | 6, 7 | 5 |
| 5. ImportEventHandler | 1, 2 | 6, 7 | 4 |
| 6. 单元测试 | 4, 5 | — | 7 |
| 7. 冒烟测试 | 4, 5 | — | 6 |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->
- [ ] 1. API 层: ImportServiceImpl 扩展 sourceType 支持 MIGRATE_LQ
  What to do / Must NOT do: 在 `createImportTask()` 的 switch 中，将 `case "ZIP", "REGISTER", "DIRECTORY"` 扩展为包含 `"MIGRATE_LQ"`，共用同一验证逻辑（检查 sourcePath 存在、创建 comic + import_task）。Must NOT: 新增独立 case 块，避免代码重复。
  Parallelization: Wave 1 | Blocked by: — | Blocks: 5
  References: `api-service/src/main/java/com/comicatlas/api/importer/service/impl/ImportServiceImpl.java:73-113`
  Acceptance criteria: `lsp_diagnostics` 在 ImportServiceImpl 上无错误；代码审查确认 `MIGRATE_LQ` 与 `DIRECTORY` 共用同一创建逻辑。
  QA scenarios: 调用 `importApi.create("MIGRATE_LQ", "F:/test/path")`，验证 task 创建成功且 sourceType="MIGRATE_LQ"。Evidence: `.omo/evidence/task-1-lq-migration.md`
  Commit: Y | feat(api): ImportServiceImpl 支持 MIGRATE_LQ sourceType

- [ ] 2. 枚举扩展: HqStatus 增加 EMPTY 状态
  What to do / Must NOT do: 在 `HqStatus.java` 中增加 `EMPTY` 枚举值。`EnumTypeHandlers.HqStatusHandler` 基于 `safeValueOf`，新增枚举值自动兼容，无需改动。Must NOT: 修改 EnumTypeHandlers。
  Parallelization: Wave 1 | Blocked by: — | Blocks: 5
  References: `api-service/src/main/java/com/comicatlas/api/common/enums/HqStatus.java:3`
  Acceptance criteria: 编译通过；`HqStatus.EMPTY` 可在代码中引用。
  QA scenarios: 编写临时测试验证 `HqStatus.valueOf("EMPTY")` 不抛异常。Evidence: `.omo/evidence/task-2-lq-migration.md`
  Commit: Y | feat(api): HqStatus 增加 EMPTY 枚举值

- [ ] 3. Worker 路由: ImportTaskHandler 增加 MIGRATE_LQ 分支
  What to do / Must NOT do: 在 `handle()` 的 switch 中增加 `case "MIGRATE_LQ"`，调用 `lqMigrationHandler.handle(...)`。Must NOT: 修改 ZIP/DIRECTORY/EHENTAI 分支。
  Parallelization: Wave 1 | Blocked by: — | Blocks: 4
  References: `worker-service/src/main/java/com/comicatlas/worker/event/ImportTaskHandler.java:55-67`
  Acceptance criteria: `lsp_diagnostics` 无错误；编译通过。
  QA scenarios: 代码审查确认路由分支正确，无遗漏。Evidence: `.omo/evidence/task-3-lq-migration.md`
  Commit: Y | feat(worker): ImportTaskHandler 增加 MIGRATE_LQ 路由

- [ ] 4. 核心 Handler: 新增 LqMigrationHandler
  What to do / Must NOT do: 在 `worker-service/.../handler/` 下新建 `LqMigrationHandler.java`，实现：
  1. `inferLqPath(Path hqPath)` - 正则推断 LQ 路径，无 `h_` 前缀时拒绝
  2. `handle(Long taskId, Long comicId, Path hqPath, Path mangaRoot)` - 扫描 LQ、搬运到系统 LQ、生成 metadata、封面
  3. 复用 `DirectoryParser` + `MetadataAssembler` + `LocalStorageService`
  Must NOT: 修改 DirectoryImportHandler；不能把文件搬运到 HQ 目录。
  Parallelization: Wave 2 | Blocked by: 3 | Blocks: 6, 7
  References: `worker-service/src/main/java/com/comicatlas/worker/file/handler/DirectoryImportHandler.java`（参考模式，不修改）
  Acceptance criteria: `lsp_diagnostics` 无错误；新类编译通过；方法签名符合 Spring DI 要求。
  QA scenarios: 单元测试验证 inferLqPath 的正确路径推断和拒绝非法路径。Evidence: `.omo/evidence/task-4-lq-migration.md`
  Commit: Y | feat(worker): 新增 LqMigrationHandler 处理 MIGRATE_LQ 导入

- [ ] 5. DB 写入层: ImportEventHandler 支持 MIGRATE_LQ sourceType
  What to do / Must NOT do: 
  1. `persistComicImported()` 读取 metadata.sourceType
  2. `insertChapter()` 增加 `boolean isMigrateLq` 参数
  3. 当 `isMigrateLq=true`：写入 `hqRoot=null, hqPath=null, hqStatus="EMPTY"`, `lqRoot="LQ", lqPath=path, lqStatus="READY"`
  4. 当 `isMigrateLq=false`：保持现有逻辑
  5. `comic.hqSize` 在 MIGRATE_LQ 时设为 0
  Must NOT: 修改非 MIGRATE_LQ 的现有写入逻辑（避免回归）。
  Parallelization: Wave 2 | Blocked by: 1, 2 | Blocks: 6, 7
  References: `api-service/src/main/java/com/comicatlas/api/importer/event/ImportEventHandler.java:247-289`
  Acceptance criteria: `lsp_diagnostics` 无错误；编译通过；现有测试不失败。
  QA scenarios: 代码审查确认条件分支覆盖所有字段；HQ/LQ 字段互斥（MIGRATE_LQ 时 hqRoot 必为 null）。Evidence: `.omo/evidence/task-5-lq-migration.md`
  Commit: Y | feat(api): ImportEventHandler 根据 sourceType 诚实写入 HQ/LQ 状态

- [ ] 6. 单元测试: LqMigrationHandler 测试覆盖
  What to do / Must NOT do: 在 `worker-service/src/test/java/.../handler/` 下新建 `LqMigrationHandlerTest.java`，覆盖：
  1. `inferLqPath` - 正常推断、大小写不敏感、无 h_ 前缀时抛异常
  2. `mergeDirectories` / handle 的搬运逻辑 - 验证 `storageService.store` 被调用且 rootKey="LQ"
  3. metadata 生成 - 验证包含 `sourceType="MIGRATE_LQ"`
  Must NOT: 测试 DirectoryImportHandler 的行为。
  Parallelization: Wave 3 | Blocked by: 4, 5 | Blocks: —
  References: 同 4
  Acceptance criteria: `mvn test -Dtest=LqMigrationHandlerTest` 全部通过（3-5 个测试方法）。
  QA scenarios: 运行测试命令，验证输出 `Tests run: X, Failures: 0`。Evidence: `.omo/evidence/task-6-lq-migration.md`
  Commit: Y | test(worker): LqMigrationHandler 单元测试

- [ ] 7. 冒烟测试: 端到端 MIGRATE_LQ 导入验证
  What to do / Must NOT do: 
  1. 准备临时目录 `test_l/` 放正常图片文件
  2. 调用 API 创建 MIGRATE_LQ 导入任务
  3. 等待 Worker 处理完成（检查 MQ 或轮询 task 状态）
  4. 验证系统 LQ 目录下文件存在且大小 > 0
  5. 验证 DB：`page.hq_root=null`, `page.lq_root="LQ"`, `page.lq_status="READY"`, `page.hq_status="EMPTY"`
  6. 验证阅读器 API 返回的 `lqUrl` 不为 null
  Must NOT: 测试正常 DIRECTORY 导入（已有测试覆盖）。
  Parallelization: Wave 3 | Blocked by: 4, 5 | Blocks: —
  References: 设计文档 `docs/superpowers/specs/2026-07-21-lq-migration-design.md`
  Acceptance criteria: DB 查询结果与预期一致；阅读器 API 返回非空 lqUrl。
  QA scenarios: 手动执行或通过脚本验证完整流程。Evidence: `.omo/evidence/task-7-lq-migration.md`
  Commit: N | —

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit: 确认所有 Must have 已完成，Must NOT have 未违反（DirectoryImportHandler 零改动、前端零改动、无伪装）。
- [ ] F2. Code quality review: `lsp_diagnostics` 在全部修改文件上零错误；无类型安全问题；无空 catch。
- [ ] F3. Real manual QA: 用真实旧系统目录（如 `F:/games/comics/h_photograph/写真/陆萱萱`）执行一次完整导入，验证阅读器正常加载。
- [ ] F4. Scope fidelity: 确认未修改 DIRECTORY/ZIP/EHENTAI 的现有导入逻辑，无回归。

## Commit strategy
- 每个 Wave 的任务完成后分别提交（T1-T3 可独立提交，T4-T5 可独立提交，T6-T7 可合并提交）。
- 提交信息使用中文，符合项目规范：`feat(api): ...`, `feat(worker): ...`, `test(worker): ...`
- 冒烟测试证据不提交到 git，保留在 `.omo/evidence/`。

## Success criteria
1. `sourceType="MIGRATE_LQ"` 的导入任务能成功创建、执行、完成。
2. 导入完成后，系统 LQ 目录包含所有旧系统 LQ 文件。
3. DB 中 `page` 记录诚实：`hq_root=null, lq_root="LQ", lq_status="READY", hq_status="EMPTY"`。
4. 阅读器 API 返回有效的 `lqUrl`，前端能正常加载图片。
5. 现有 `DIRECTORY` / `ZIP` / `EHENTAI` 导入流程无回归。
