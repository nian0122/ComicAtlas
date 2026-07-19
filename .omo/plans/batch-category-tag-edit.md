# batch-category-tag-edit - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**What you'll get:** 漫画管理列表支持勾选多本漫画，通过弹窗统一设置分类和追加标签，一次保存批量生效。

**Why this approach:** 复用现有 Service 的逐条处理逻辑做批量 best-effort 更新（部分失败不影响其他），前端仿照导入页已有的复选框模式。后端只新增 1 个端点，改动最小。

**What it will NOT do:** 不会批量修改标题/作者/描述；不会覆盖式替换已有标签（只追加）；不会改动现有单漫画编辑流程。

**Effort:** Short（8 个 todo，3-4 波并行）
**Risk:** Low - 新增端点独立，不触碰现有编辑流程
**Decisions to sanity-check:** 标签只追加不替换是否够用？分类覆盖式设置是否接受？

Your next move: approve to generate plan, or run a high-accuracy review. Full execution detail follows below.

---

> TL;DR (machine): Short effort, Low risk, 4 waves: 8 todos — 4 backend + 4 frontend, deliver batch category/tag editing via ComicListPage checkboxes + dialog

## Scope
### Must have
- Backend: `POST /api/comics/batch/update` — 接收 comicIds + categoryId? + addTagIds?，逐条 best-effort 更新，返回 succeeded/failed
- Backend DTOs: `BatchComicUpdateDTO`（校验 comicIds 非空 ≤100，categoryId/addTagIds 至少一个非空），`BatchUpdateResultVO`（total + succeeded + failed[]）
- Backend Service: `ComicServiceImpl.batchUpdate()` — 遍历 comicIds，只处理 READY 状态；categoryId 直接设 comic entity；addTagIds 去重追加（不删已有）
- Frontend: `ComicListPage.vue` 加复选框列 + 全选 + 底部浮动「批量编辑」按钮（选中 ≥1 时显示）
- Frontend: 新建 `BatchEditDialog.vue` — 分类下拉（含"不修改"）+ 标签多选 + 确认按钮
- Frontend: API 服务 `comicApi.batchUpdate()` + TypeScript 类型扩展
- 校验：后端验证 comicIds 非空 ≤100、至少一个修改项；前端弹窗确认前检查至少选了一个字段

### Must NOT have (guardrails, anti-slop, scope boundaries)
- 不修改标题/作者/描述
- 不支持标签覆盖式替换——只做追加
- 不新增数据库表或字段
- 不改动现有单漫画编辑流程（ComicEditPage, ComicController 现有端点）
- 不在导入完成后自动弹窗
- 不做批量删除标签

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after — 后端复用现有 Service 模式（MyBatis Plus + LambdaQueryWrapper），手工验证；前端用 Playwright 端到端
- Framework: 后端 Spring Boot Test + MockMvc；前端 Playwright
- Evidence: .omo/evidence/task-<N>-batch-category-tag-edit.<ext>

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.

**Wave 1** — Backend（无内部依赖，4 个 todo 可并行）
- T1: 新建 BatchComicUpdateDTO.java
- T2: 新建 BatchUpdateResultVO.java
- T3: 新建 ComicService.batchUpdate() 接口 + ComicServiceImpl 实现
- T4: ComicController 新增 POST /comics/batch/update

**Wave 2** — Frontend 基础层（依赖 Wave 1，T5+T6 可并行）
- T5: TypeScript 类型扩展
- T6: API 服务 comicApi.batchUpdate()

**Wave 3** — Frontend 组件层（依赖 Wave 2，1 个 todo）
- T7: 新建 BatchEditDialog.vue

**Wave 4** — Frontend 集成（依赖 Wave 3，1 个 todo）
- T8: ComicListPage.vue 加复选框 + 全选 + 批量编辑按钮 + 集成弹窗

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| T1 | — | T3, T5, T6 | T2, T4 |
| T2 | — | T3, T5 | T1, T4 |
| T3 | T1, T2 | T4 | — |
| T4 | T3 | T5, T6 | — |
| T5 | T1, T2 | T7, T8 | T6 |
| T6 | T1, T2 | T7, T8 | T5 |
| T7 | T5, T6 | T8 | — |
| T8 | T7 | — | — |

## Todos
> Implementation + Test = ONE todo. Never separate.

- [ ] 1. 新建 BatchComicUpdateDTO.java
  What to do: 在 `api-service/.../dto/` 下创建 `BatchComicUpdateDTO`，三个字段：`@NotEmpty @Size(max=100) List<Long> comicIds`、`Long categoryId`（可选）、`List<Long> addTagIds`（可选）。在 Controller 层加手动校验：categoryId 和 addTagIds 不能同时为空（跨字段校验，Bean Validation 不支持直接表达），返回 400 "至少需要提供 categoryId 或 addTagIds"。
  Must NOT do: 不加 title/author/description 字段；不修改现有 DTO。
  Parallelization: Wave 1 | Blocked by: — | Blocks: T3, T5, T6
  References: `api-service/.../dto/ComicMetadataUpdateDTO.java`（参考现有 DTO 风格）、`api-service/.../dto/BatchImportRequest.java`（参考 @Size 校验）、设计文档 §4.1
  Acceptance criteria (agent-executable): `javac` 编译通过；controller 能接收 JSON `{"comicIds":[1,2],"categoryId":5,"addTagIds":[10,11]}`；comicIds 为空返回 400；comicIds >100 返回 400；categoryId 和 addTagIds 均为空返回 400
  QA scenarios: happy — 正常 JSON 反序列化；failure — comicIds=[] 校验失败、comicIds 101个 校验失败、两个字段都 null 校验失败。Evidence: 编译日志 + curl 测试
  Commit: Y | feat(api): 新增 BatchComicUpdateDTO 批量更新请求 DTO

- [ ] 2. 新建 BatchUpdateResultVO.java
  What to do: 在 `api-service/.../dto/` 下创建 `BatchUpdateResultVO`，含 `int total`、`int succeeded`、`List<FailedItem> failed`，内部类 `FailedItem` 含 `Long comicId`、`String title`、`String reason`。
  Must NOT do: 不加 Lombok @Builder（保持与项目风格一致的 @Data）；不引入额外依赖。
  Parallelization: Wave 1 | Blocked by: — | Blocks: T3, T5
  References: `api-service/.../dto/BatchImportResultVO.java`（仿照其 succeed/fail 结构）、设计文档 §4.1
  Acceptance criteria: 编译通过；controller 中能被 `Result<BatchUpdateResultVO>` 包装返回
  QA scenarios: happy — new 对象设值后 getter 正确；failure — N/A（纯数据类）。Evidence: 编译日志
  Commit: Y | feat(api): 新增 BatchUpdateResultVO 批量更新响应 VO

- [ ] 3. 实现 ComicService.batchUpdate() + ComicServiceImpl
  What to do: 
    1. `ComicService.java` 新增 `BatchUpdateResultVO batchUpdate(BatchComicUpdateDTO dto);`
    2. `ComicServiceImpl.java` 实现：
       a. **去重 comicIds**：`new LinkedHashSet<>(dto.getComicIds())` 去重，防止同一漫画被计数多次
       b. 遍历去重后的 comicIds → 查 comic → 校验 status=="READY"（否则记录 failed "漫画状态为 X，无法编辑" → continue）
       c. 有 categoryId 时：先 `categoryMapper.selectById()` 校验分类存在 → 不存在则记录 failed "分类不存在" → continue（跳过本漫画的标签处理）；存在则设 `comic.categoryId` 和 `comic.category` → `comicMapper.updateById(comic)`
       d. 有 addTagIds 时：先 `tagMapper.selectBatchIds()` 校验所有标签 → 不存在的 tagId **单独跳过并记录日志**（不阻止本漫画，因为个别标签不存在不影响其他标签的追加）；查已有 comic_tag 取 tagId 列表 → 过滤去重 → 逐个 insert ComicTag（不删已有的）
       e. 每条异常被 catch 记录为 failed（含具体原因），不中断其他漫画
  Must NOT do: 不调 updateMetadata()（它要求 @NotBlank title）；不包 @Transactional（best-effort 逐条）；不调 updateComicTags()（它是全量替换）；不静默接受无效 categoryId/tagId。
  Parallelization: Wave 1 | Blocked by: T1, T2 | Blocks: T4
  References: `ComicServiceImpl.java:275-299`（updateMetadata 的分目录逻辑）、`:323-347`（updateComicTags 的 comicTagMapper 用法）、`:30-40`（注入的 mapper 列表：comicMapper, categoryMapper, comicTagMapper）、设计文档 §5.2
  Acceptance criteria: comicIds=[1,1,2]（含重复）→ 去重后仅处理 1 和 2，total=2；categoryId=999（不存在）→ 该 comic 记录 failed "分类不存在"，该 comic 的 addTagIds 不处理；addTagIds=[10,999]，其中 999 不存在 → 999 跳过（日志记录），10 正常追加，该 comic 仍计为 succeeded；漫画状态 IMPORTING → failed "漫画状态为 IMPORTING，无法编辑"；全部成功 → succeeded=2, total=2
  QA scenarios: happy — 2本READY漫画设分类+标签，验证 DB categoryId/category/comic_tag；failure — 重复 comicIds 不膨胀 succeeded、无效 categoryId 记录具体原因、无效 tagId 记录具体原因、IMPORTING 状态跳过、异常被 catch 记录 "系统错误"。Evidence: comicMapper + comicTagMapper + categoryMapper 查询结果
  Commit: Y | feat(api): 实现 ComicService.batchUpdate 批量更新分类和标签

- [ ] 4. ComicController 新增 POST /comics/batch/update
  What to do: `ComicController.java` 新增 `@PostMapping("/comics/batch/update") public Result<BatchUpdateResultVO> batchUpdate(@Valid @RequestBody BatchComicUpdateDTO dto)`。先手动校验 categoryId 和 addTagIds 不能同时为空（返回 400），再调 `comicService.batchUpdate(dto)` 返回。
  Must NOT do: 不加 GET/PUT/DELETE 端点；不修改现有 controller 方法。
  Parallelization: Wave 1 | Blocked by: T3 | Blocks: T5, T6
  References: `ComicController.java:48-66`（参考 @PutMapping 的 @Valid + Result 模式）、设计文档 §5.1
  Acceptance criteria: POST /api/comics/batch/update with valid JSON → 200 + BatchUpdateResultVO；invalid JSON（comicIds 空）→ 400；categoryId 和 addTagIds 均为空 → 400 "至少需要提供 categoryId 或 addTagIds"
  QA scenarios: happy — curl POST 正常请求返回 200；failure — curl POST 空 comicIds 返回 400、两个字段都空返回 400。Evidence: curl 输出
  Commit: Y | feat(api): ComicController 新增批量更新分类标签端点

- [ ] 5. 扩展 Frontend TypeScript 类型
  What to do: `frontend/src/types/index.ts` 新增 `BatchComicUpdateDTO`（comicIds: number[], categoryId?: number | null, addTagIds?: number[]）、`BatchUpdateResultVO`（total, succeeded, failed: FailedItem[]）、`FailedItem`（comicId, title: string | null, reason）。
  Must NOT do: 不修改现有类型定义；不引入新依赖。
  Parallelization: Wave 2 | Blocked by: T1, T2 | Blocks: T7, T8
  References: `frontend/src/types/index.ts:210-228`（现有 ComicMetadataUpdateDTO、ComicTagUpdateDTO 风格）、设计文档 §6.4
  Acceptance criteria: TypeScript 编译通过（`npx vue-tsc --noEmit`）
  QA scenarios: happy — import 新类型无报错；failure — 类型与后端 DTO 不匹配时的编译错误。Evidence: tsc 输出
  Commit: Y | feat(frontend): 新增批量编辑相关 TypeScript 类型

- [ ] 6. 扩展 API 服务 comicApi.batchUpdate()
  What to do: `frontend/src/services/api.ts` 在 `comicApi` 对象中新增 `batchUpdate: (data: BatchComicUpdateDTO) => api.post('/comics/batch/update', data)`。
  Must NOT do: 不修改现有 comicApi 方法；不改变 api 实例的配置。
  Parallelization: Wave 2 | Blocked by: T1, T2 | Blocks: T7, T8
  References: `frontend/src/services/api.ts:22-35`（现有 comicApi 风格）、设计文档 §6.3
  Acceptance criteria: 调用 `comicApi.batchUpdate({ comicIds: [1], categoryId: 5 })` 发送 POST 到 `/comics/batch/update`
  QA scenarios: happy — 函数存在且签名正确；failure — N/A。Evidence: 代码检查
  Commit: Y | feat(frontend): API 服务新增 comicApi.batchUpdate()

- [ ] 7. 新建 BatchEditDialog.vue 弹窗组件
  What to do: 创建 `frontend/src/views/management/BatchEditDialog.vue`。结构：`el-dialog`（title="批量编辑", width=480px, destroy-on-close）→ `el-form` → 分类 `el-select`（placeholder="不修改", clearable, 数据源 categoryStore.list）→ 标签 `el-select`（multiple, filterable, placeholder="选择要追加的标签", 数据源 tagStore.list）→ 底部提示"将为选中的 N 本漫画统一设置" → footer 取消/确认按钮。确认前校验至少选了一个字段。关键行为：
    - `destroy-on-close` 确保每次打开弹窗时 ref 重置（categoryId=null, addTagIds=[]）
    - 确认前校验 `categoryId === null && addTagIds.length === 0` → warning
    - API 返回后：succeeded > 0 && failed.length > 0 → warning "N 本成功，M 本失败"；succeeded === 0 → error "所有漫画更新失败"；全成功 → success
  Must NOT do: 不引入覆盖式标签替换逻辑；不引入标题/作者编辑；不加 emoji。
  Parallelization: Wave 3 | Blocked by: T5, T6 | Blocks: T8
  References: `ComicEditPage.vue`（参考分类 el-select + 标签多选组件用法）、`ComicListPage.vue`（pinia store 引用方式）、设计文档 §6.2
  Acceptance criteria: 弹窗打开时 categoryId=null, addTagIds=[]（每次打开都重置）；分类下拉显示 categoryStore.list；标签多选显示 tagStore.list；确认按钮在未选择任何字段时提示"请至少选择分类或标签"；succeeded > 0 && failed > 0 → warning toast；succeeded === 0 → error toast；全成功 → success toast；确认后调用 comicApi.batchUpdate()
  QA scenarios: happy — 选分类+标签点确认 → API 调用 → 提示成功 → emit('saved')；failure — 未选字段点确认 → warning；关闭再打开弹窗 → 状态已重置；API 返回 succeeded=0 → error toast。Evidence: Playwright 截图 + API mock 日志
  Commit: Y | feat(frontend): 新建 BatchEditDialog 批量编辑弹窗组件

- [ ] 8. ComicListPage.vue 集成批量选择 + 弹窗
  What to do:
    1. 添加 `selectedIds: ref<number[]>([])` + `showBatchDialog: ref(false)`
    2. filter-toolbar 下方或上方插入 batch-toolbar（v-if="selectedIds.length > 0"）：全选 checkbox（含 indeterminate 状态）+ 「批量编辑」button
    3. 每行 comic-row 的封面左侧加 `<el-checkbox :model-value="selectedIds.includes(comic.id)" @change="() => toggleSelect(comic.id)" @click.stop>` — 阻止冒泡不跳转编辑页
    4. `toggleSelect(id)`、`handleSelectAll(val)`、`onBatchSaved()` 方法
    5. 模板底部引入 `<BatchEditDialog v-model:visible="showBatchDialog" :comic-ids="selectedIds" @saved="onBatchSaved" />`
  Must NOT do: 不修改 comic-row 的点击跳转行为（点击封面/标题仍跳转编辑页）；不修改 filter-toolbar；不改 store 结构；不引入新的 pinia store。
  Parallelization: Wave 4 | Blocked by: T7 | Blocks: —
  References: `ImportPage.vue:209,277-291`（selectedPaths + togglePath + selectAll 模式）、`ComicListPage.vue:76-98`（comic-row 结构 + @click="goEdit"）、设计文档 §6.1
  Acceptance criteria: 列表每行显示复选框；勾选后底部出现「已选 N / M」+「批量编辑」按钮；全选/取消全选正常工作；点复选框不跳转编辑页；点封面/标题仍跳转；弹窗确认后 selectedIds 清空、列表刷新
  QA scenarios: happy — 勾选3本 → 点批量编辑 → 弹窗选分类 → 确认 → 提示成功 → 列表刷新；failure — 未勾选时无批量编辑按钮；弹窗未选字段点确认报错。Evidence: Playwright 端到端截图
  Commit: Y | feat(frontend): ComicListPage 集成批量选择与批量编辑功能

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit — 检查所有 todo 的 Acceptance criteria 是否满足、文件是否全部存在、API 是否可调用
- [ ] F2. Code quality review — LSP diagnostics 无新增错误、代码风格与现有文件一致
- [ ] F3. Real manual QA — Playwright 端到端：列表页勾选 → 弹窗编辑 → API 返回 → 列表刷新
- [ ] F4. Scope fidelity — 确认未改动的文件（ComicEditPage、CatalogController、ImportController 等）保持原样

## Commit strategy
- 每个 todo 独立 commit，commit message 格式：`<type>(<scope>): <中文描述>`
- Wave 1（后端）commit 顺序：T1 → T2 → T3 → T4
- Wave 2（前端基础）commit 顺序：T5 → T6 → T7
- Wave 3（前端集成）commit：T8
- 不做 squash —— 保留每个独立变更的可追溯性

## Success criteria
- [ ] `POST /api/comics/batch/update` 能接受合法 JSON 并返回 BatchUpdateResultVO
- [ ] 批量设分类：选中漫画的 `comic.category_id` 更新为指定值
- [ ] 批量加标签：选中漫画的 `comic_tag` 表新增指定关联，已有标签不重复
- [ ] 非 READY 状态漫画被跳过，出现在 failed 列表中
- [ ] 前端 ComicListPage 可多选漫画并弹出批量编辑弹窗
- [ ] 弹窗确认后列表刷新，显示更新后的分类
- [ ] ComicListPage 其他功能（过滤、分页、单本编辑）不受影响
