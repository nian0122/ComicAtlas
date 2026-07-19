# batch-import - Work Plan

## TL;DR (For humans)

**What you'll get:** ImportPage 新增"批量导入"标签页——输入漫画父目录、扫描预览子目录列表、勾选确认后一次性创建 N 个导入任务，跳转任务中心按批次跟踪进度。

**Why this approach:** 每个路径独立事务（`TransactionTemplate`），一个导入失败不影响其他。后端复用现有 `createImportTask` 核心逻辑，Worker 层零改动。前端扫描预览让你在确认前看到目录名和页数、排除不想导入的。

**What it will NOT do:**
- 不自动重试失败的条目（手动勾选重新提交）
- 不加并发限流（Worker 按 RabbitMQ 队列自然消费）
- 不同时支持 ZIP 批量（本次仅 DIRECTORY 类型）

**Effort:** Medium
**Risk:** Low — 不改 Worker 层，不改现有单次导入，只新增端点
**Decisions to sanity-check:** 扫描端点无路径遍历检查（只验证父目录存在/可读，不限白名单）—— 内部工具可接受

Your next move: approve, or run a high-accuracy review first. Full execution detail follows below.

---

> TL;DR (machine): Medium effort, Low risk — 8 todos, 5 waves, 4 backend + 3 frontend + 1 test. Adds scan+batch endpoints to ImportController, batch mode panel to ImportPage, batchId filter to TaskPage.

## Scope
### Must have
- DB: `import_task.batch_id VARCHAR(36) NULL` + index
- 后端: 5 DTO + 2 Service 方法 + 2 Controller 端点 + Mapper batchId 查询 + entity/VO batchId
- 后端: scan 端点的 parentPath 验证（存在/是目录/可读 → 否则 400）+ 空 sourcePaths 验证（400）
- 后端: createBatchImportTasks 使用 TransactionTemplate 逐项独立事务
- 前端: ImportPage 批量模式面板（tab 切换 + 扫描 + 勾选 + 确认）
- 前端: ImportStore scan + createBatch action
- 前端: TaskPage batchId URL 参数筛选
- 前端: 扫描/批量提交的 loading + empty + error 三种状态
- 测试: ImportServiceTest 覆盖事务隔离 + 失败不回滚 + scan 验证

### Must NOT have (guardrails, anti-slop, scope boundaries)
- 不修改 Worker 层（ImportTaskHandler / DirectoryImportHandler / ZipImportHandler）
- 不修改现有单次导入逻辑
- 不引入线程池 / 限流 / 并发控制
- 不修改 RabbitMQ 配置
- 不用 `@Transactional` 自调用（用 TransactionTemplate）
- 不实现 scan 的 sizeBytes 字段
- 不引入失败项自动重试（用户手动重新提交失败路径）
- 不加 TaskPage 分页（当前 50 条上限足够）

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after + framework JUnit 5 + Spring Boot Test + MyBatis Plus Test
- Evidence: .omo/evidence/task-<N>-batch-import.md

## Execution strategy
### Parallel execution waves
- Wave 1: T1 DB+Entity, T2 DTO, T3 Frontend Types（3 个 todo 全部独立，并行执行）
- Wave 2: T4 ServiceImpl（依赖 T2 的 DTO）
- Wave 3: T5 Controller+Mapper（依赖 T4 ServiceImpl）
- Wave 4: T6 ImportPage, T7 TaskPage（依赖 T3 Frontend Types，可并行执行）
- Wave 5: T8 Tests（依赖 T4+T5 后端实现完成）

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| T1 DB+Entity | — | T4, T5 | T2, T3 |
| T2 DTOs | — | T4 | T1, T3 |
| T3 Frontend Types | — | T6, T7 | T1, T2 |
| T4 ServiceImpl | T1, T2 | T5, T8 | — |
| T5 Controller+Mapper | T4 | T8 | — |
| T6 ImportPage | T3 | — | T7 |
| T7 TaskPage | T3 | — | T6 |
| T8 Tests | T4, T5 | — | — |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->

### Wave 1 — DB + DTO + Types（全部独立，并行执行）

- [ ] 1. DB 迁移 + ImportTask 实体/VO 增加 batchId
  What to do: 创建 SQL migration 文件，ImportTask entity 加 batchId 字段，ImportTaskVO 加 batchId 字段，toVO() 映射 batchId
  Must NOT do: 不碰现有字段，不修改 comic/ catalog/ chapter/ page 表
  Parallelization: Wave 1 | Blocked by: — | Blocks: T4, T5
  References: api-service/.../entity/ImportTask.java:7, api-service/.../dto/ImportTaskVO.java:6, api-service/.../service/impl/ImportServiceImpl.java:225(toVO)
  Acceptance criteria: SELECT batch_id FROM import_task WHERE batch_id IS NOT NULL 不报错；ImportTaskVO JSON 含 batchId 字段
  QA: Happy — INSERT 带 batchId 的任务，GET /api/tasks/import/{id} 返回 batchId。Failure — 不传 batchId 的旧任务返回 null。Evidence .omo/evidence/task-1-batch-import.md
  Commit: Y | feat(db): import_task 增加 batch_id 字段

- [ ] 2. 后端 5 个新 DTO + ImportService 接口签名
  What to do: 创建 ScanItemVO, ScanResultVO, BatchImportRequest, BatchImportResultVO, FailedItem；ImportService 接口新增 scanDirectories() 和 createBatchImportTasks() 方法签名
  Must NOT do: DTO 不用 Lombok @Data（项目用的是什么 pattern 就用什么，先检查现有 DTO），不写实现
  Parallelization: Wave 1 | Blocked by: — | Blocks: T4
  References: api-service/.../dto/ImportRequest.java:5, api-service/.../service/ImportService.java:9, spec §4 DTO 表
  Acceptance criteria: 编译通过（mvn compile -pl api-service -q），DTO 字段与 spec 一致
  QA: Happy — mvn compile 退出码 0。Evidence .omo/evidence/task-2-batch-import.md
  Commit: Y | feat(api): 批量导入 DTO + Service 接口定义

- [ ] 3. 前端 Types + API Service
  What to do: types/index.ts 新增 BatchImportRequest, BatchImportResultVO, FailedItem, ScanItemVO, ScanResultVO 接口；ImportTaskVO 加 batchId?；api.ts importApi 新增 scan(parentPath, sourceType) 和 createBatch(data) 方法
  Must NOT do: 不动 store 和组件，不修改现有 importApi 方法签名
  Parallelization: Wave 1 | Blocked by: — | Blocks: T6, T7
  References: frontend/src/types/index.ts:131-156, frontend/src/services/api.ts:45-53
  Acceptance criteria: TypeScript 编译无类型错误（vue-tsc --noEmit）
  QA: Happy — vue-tsc 退出码 0，importApi.scan 类型推导正确。Evidence .omo/evidence/task-3-batch-import.md
  Commit: Y | feat(frontend): 批量导入 Types + API 方法

### Wave 2 — 后端实现（依赖 Wave 1 的 T1, T2）

- [ ] 4. ImportServiceImpl 实现 scanDirectories + createBatchImportTasks
  What to do:
    - scanDirectories: Files.list(parentPath) → 过滤子目录 → 每个统计 imageCount（Files.list(dir).filter(jpg/jpeg/png/webp/bmp/gif).count()，全量不设上限）→ 按 name 排序 → 返回 ScanResultVO
    - 验证: parentPath 存在/是目录/可读 → 否则 BusinessException(400)
    - createBatchImportTasks: 生成 UUID batchId → 遍历 sourcePaths → TransactionTemplate.execute() 开启独立事务 → 事务内预创建 Comic + ImportTask + setBatchId → execute() 返回后（事务已提交）手动调用 eventPublisher.publishImportTaskCreated() → 单个失败 catch 记录到 failed 列表 continue → 返回 BatchImportResultVO
    - ⚠️ 关键：用 TransactionTemplate.execute()（非 executeWithoutResult），execute 返回即事务已提交，之后发 MQ。不要用 TransactionSynchronizationManager（TransactionTemplate 没有 @Transactional 上下文，registerSynchronization 会立即执行）
    - 验证: sourcePaths 空 → BusinessException(400)
  Must NOT do: 不引入线程池，不用 @Transactional 自调用，不修改 MQ 配置，不碰 Worker
  Parallelization: Wave 2 | Blocked by: T1 (entity), T2 (DTO) | Blocks: T5, T8
  References: ImportServiceImpl.java:54-130 (createImportTask 完整逻辑), ImportEventPublisher (MQ 发布), spec §5.2
  Acceptance criteria:
    - scan 父目录存在 → 返回 total>=0, items 已排序
    - scan 父目录不存在 → 返回 code=400
    - batch 传入 2 个路径 → batchId 非空, succeeded.length=2, failed.length=0
    - batch 传入空数组 → 返回 code=400
    - batch 传入 1 个有效+1 个无效路径 → succeeded.length=1, failed.length=1
  QA: Happy — 用 curl 调用 scan 和 batch 端点验证。Failure — path 不存在 → 400, 空 sourcePaths → 400, 部分失败 → failed 列表非空。Evidence .omo/evidence/task-4-batch-import.md
  Commit: Y | feat(api): 批量导入 Service 实现

### Wave 3 — 后端 Controller + Mapper（依赖 Wave 2 的 T4）

- [ ] 5. ImportController 新端点 + ImportTaskMapper batchId 支持
  What to do:
    - ImportController: @GetMapping("/scan") → scan(), @PostMapping("/batch") → createBatch()
    - listTasks 方法增加 @RequestParam(required=false) String batchId 参数 → 传至 Service → Mapper
    - ImportTaskMapper: LambdaQueryWrapper .eq(batchId != null, ImportTask::getBatchId, batchId)
  Must NOT do: 不修改其他现有端点签名（cancel/retry/status/detail 不变）
  Parallelization: Wave 3 | Blocked by: T4 | Blocks: T8
  References: ImportController.java:17-50, ImportTaskMapper.java, spec §5.3
  Acceptance criteria:
    - GET /api/tasks/import/scan?parentPath=... 返回 JSON 含 parentPath + total + items
    - POST /api/tasks/import/batch 返回 JSON 含 batchId + succeeded + failed
    - GET /api/tasks/import?batchId=xxx 只返回该批次任务
  QA: Happy — 同上 T4 QA + listTasks batchId 筛选。Evidence .omo/evidence/task-5-batch-import.md
  Commit: Y | feat(api): 批量导入 Controller + batchId 筛选

### Wave 4 — 前端实现（依赖 Wave 1 的 T3，T6+T7 并行）

- [ ] 6. ImportStore 新 action + ImportPage.vue 批量模式面板
  What to do:
    - ImportStore: scan(parentPath, sourceType) → 调用 importApi.scan → 存 scanResult ref；createBatch(sourceType, paths) → 调用 importApi.createBatch → 返回 batchId
    - ImportPage: 新增 el-tab 切换"单个导入" / "批量导入"
    - 批量 tab: el-input 输入父目录 → el-button "扫描" → 加载中 disabled + loading icon
    - 扫描成功: el-checkbox-group 展示列表（checkbox + 名称 + 图片数），顶部 [全选] [取消全选]，底部 "已选 X/Total" + [确认导入] 按钮
    - canSubmit: selectedPaths.length > 0
    - 扫描失败: el-alert 显示错误信息（"父目录不存在" / "无权访问"）
    - 扫描结果为空: el-empty "此目录下未发现漫画子目录"
    - 确认导入 → loading → 成功 → router.push(`/manage/import/tasks?batchId=${batchId}`)
    - 单个 tab 保持现有逻辑不变
  Must NOT do: 不修改单个导入的表单和逻辑，不修改 element-plus 主题
  Parallelization: Wave 4 | Blocked by: T3 | Blocks: —
  References: ImportPage.vue:85-157, stores/management/import.ts: create(), types/index.ts, api.ts: importApi
  Acceptance criteria:
    - 切换到批量 tab → 输入有效 parentPath → 点击扫描 → 列表渲染
    - 扫描无效路径 → 显示错误提示
    - 全选 → 取消 2 个 → 已选 N-2
    - 点确认 → loading → 跳转到 /manage/import/tasks?batchId=xxx
    - 空路径 → 确认按钮 disabled
  QA: Happy — Playwright 测试：导航 → 批量模式 → 扫描 → 勾选 → 确认导入 → 验证跳转。Failure — 无效路径 error 提示、空结果 empty 提示。Evidence .omo/evidence/task-6-batch-import.md
  Commit: Y | feat(frontend): ImportPage 批量导入模式

- [ ] 7. TaskPage.vue batchId 筛选 + TaskCard 批量上下文
  What to do:
    - TaskPage: useRoute() 读取 batchId query 参数 → 传给 store.fetchList({ batchId })
    - 筛选模式下：页面标题显示"批次导入 {batchId}" + [返回全部任务] 链接
    - 返回全部: router.replace('/manage/import/tasks') 清除 query
    - TaskCard: 可选——若有 batchId，显示小标签
  Must NOT do: 不修改轮询逻辑，不加分页（当前 50 条上限足够）
  Parallelization: Wave 4 | Blocked by: T3 | Blocks: —
  References: TaskPage.vue:27-134, stores/management/import.ts: fetchList(), api.ts: importApi.list
  Acceptance criteria:
    - URL ?batchId=xxx → 只显示该批次任务
    - "返回全部任务"链接 → 清除筛选 → 显示全部
    - 无 batchId → 行为与当前一致
  QA: Happy — Playwright 测试：访问 /manage/import/tasks?batchId=xxx → 验证列表仅含该批次。Evidence .omo/evidence/task-7-batch-import.md
  Commit: Y | feat(frontend): TaskPage 支持 batchId 筛选

### Wave 5 — 测试验证（依赖 Wave 2+3 的 T4+T5）

- [ ] 8. ImportServiceTest 事务隔离测试 + 全链路验收
  What to do:
    - ImportServiceTest: @SpringBootTest + 注入 ImportService + ImportTaskMapper
    - 测试 1: 批量创建 2 个任务 → 验证两个 task 均有 batchId 且相同
    - 测试 2: 1 个有效 + 1 个无效路径 → 验证 succeeded=1, failed=1，成功的不回滚
    - 测试 3: 空 sourcePaths → 验证 BusinessException
    - 测试 4: 不存在的 parentPath 调用 scan → 验证 BusinessException
    - 全链路: 启动服务 → curl scan → 选 2 个 curl batch → 等 Worker 完成 → curl listTasks batchId → 验证 2 个 comic 状态 READY
  Must NOT do: 不删除已有测试，不 mock MQ（让 RabbitMQ 真实消费），不修改 Worker 代码
  Parallelization: Wave 5 | Blocked by: T4, T5 | Blocks: —
  References: AdminServiceImplTest.java (现有测试模式), GlobalExceptionHandler.java (异常处理)
  Acceptance criteria:
    - mvn test -pl api-service -Dtest=ImportServiceTest 全部通过
    - 全链路验收: 2 个测试漫画成功导入 → READY 状态
  QA: Happy — 4 个单元测试通过 + 1 个集成测试通过。Evidence .omo/evidence/task-8-batch-import.md
  Commit: Y | test(api): 批量导入事务隔离 + 端到端验收测试

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit — 对比 spec 文件逐项检查：7 个变更文件、batch_id 列、API 响应格式与 spec 一致
- [ ] F2. Code quality review — Oracle 检查：事务隔离正确性、路径验证完整性、前端错误状态覆盖
- [ ] F3. Real manual QA — curl scan + curl batch + 浏览器确认 TaskPage batchId 筛选
- [ ] F4. Scope fidelity — 确认未修改 Worker 层、现有导入逻辑、RabbitMQ 配置

## Commit strategy
- 每个 todo 独立 commit，中文 commit message
- T1: `feat(db): import_task 增加 batch_id 字段`
- T2: `feat(api): 批量导入 DTO + Service 接口定义`
- T3: `feat(frontend): 批量导入 Types + API 方法`
- T4: `feat(api): 批量导入 Service 实现`
- T5: `feat(api): 批量导入 Controller + batchId 筛选`
- T6: `feat(frontend): ImportPage 批量导入模式`
- T7: `feat(frontend): TaskPage 支持 batchId 筛选`
- T8: `test(api): 批量导入事务隔离 + 端到端验收测试`

## Success criteria
1. POST /api/tasks/import/batch 35 个路径 → 35 个 task 创建成功，都有相同 batchId
2. 其中 1 个路径无效 → 34 succeeded + 1 failed，34 个全部走上 Worker 正常完成
3. GET /api/tasks/import?batchId=xxx → 返回该批次全部 35 个任务
4. 前端: 扫描 → 勾选 → 导入 → 跳转任务中心 → 只显示该批次，全程无 JS error
