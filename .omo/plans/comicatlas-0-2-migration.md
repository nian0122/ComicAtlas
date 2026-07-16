# comicatlas-0-2-migration - Work Plan

## TL;DR (For humans)

**What you'll get:** 一个阅读与管理彻底分离的 ComicAtlas 0.2：阅读首页作为默认入口，管理集中在 `/manage`；原有 Dashboard 和 OperationLog 被清除；新增管理侧漫画列表、设置页、Category 绑定、封面选择。

**Why this approach:** 保留 0.1 已验证的导入、Worker、存储、阅读等基础设施，只重构产品层和前端架构。采用 Shell-first 渐进迁移，每个阶段都保持可运行，避免一次性大爆炸改写。

**What it will NOT do:**
- 不改动已有基础设施的核心 schema（Worker、MQ、导入流程、存储模型、阅读器逻辑保持不变）。
- 允许新增 `category` 表与 `comic.category_id`，但不改动其他已有表结构。
- 不引入多用户、权限、推荐、收藏、排行榜、社区。
- 不在迁移过程中混入新功能开发。
- 不保留 Dashboard、OperationLog 等不符合定位的页面。
- 不引入 Category 树形结构。

**Effort:** Large
**Risk:** Medium - 页面搬迁和路由重构涉及面广，但每个阶段都有 Playwright 兜底
**Decisions to sanity-check:**
- `/library` 独立保留，首页只展示第一页漫画库。
- API 优先保留现有 URL，通过 DTO 按场景拆分。
- Category 永远一级，不支持树形。

Your next move: 确认计划后，使用 `$start-work` 或手动按 Wave 执行。完整执行细节见下文。

---

> TL;DR (machine): Large effort, Medium risk. Deliver: Reading/Management split, new Layouts/Router, legacy cleanup, reading/management store/service split, management comic list/settings/cover, Category support. Approach: Shell-first phased migration with Playwright at each phase and git tags per wave.

## Scope

### Must have

1. **Phase 0 — 架构冻结（已完成）**：`docs/architecture/00-08.md` 已写入并通过自审。
2. **Phase 1 — Shell**：新建 `ReadingLayout` / `ManagementLayout` / `ReaderLayout`，重构 `router/index.ts`，页面先用旧组件 Wrapper 保证可运行。
3. **Phase 2 — 阅读迁移**：Home、Library、Detail、Reader、History 页面迁移到 `views/reading/`，删除阅读详情页中的管理按钮。
4. **Phase 3 — 管理迁移**：ComicEdit、Import、Task 页面迁移到 `views/management/`，建立 5 项管理导航（漫画 / 导入 / 存储 / 元数据 / 设置）。
5. **Phase 4 — Legacy 清理**：删除 Dashboard、OperationLog、旧路由、旧 pages/ 目录、遗留 Store/API/DTO/CSS。
6. **Phase 5 — 新功能**：新增 `category` 表与实体、Category 管理、漫画编辑绑定 Category、Tag 管理迁移到元数据模块、存储页面基础框架。

### Must NOT have (guardrails, anti-slop, scope boundaries)

- 不改动已有基础设施的核心 schema：Worker、MQ、导入流程、存储模型、阅读器逻辑保持不变。
- 允许新增 `category` 表与 `comic.category_id` 字段及数据迁移，但不改动其他已有表结构。
- 不为了 URL 前缀美观而重构 API Controller 路径（只按场景拆分 DTO）。
- 不在迁移阶段混入 UI 优化或新功能（迁移只搬家，不改业务逻辑）。
- 不保留 Dashboard、OperationLog、旧 Home 等不符合 0.2 定位的页面。
- 不引入多用户、权限、推荐、收藏、排行榜、社区。
- 不引入 Category 树形结构（Category 永远一级）。
- 不保留任何 `*_old` / `*_legacy` / 半废弃文件。

## Verification strategy

> 每个 Phase 结束时项目必须可运行、Playwright 通过。零人工干预，全部自动化验证。

- **Test decision**: tests-after + Playwright smoke tests（现有 `frontend/e2e/`）。
- **前端类型检查**: `cd frontend && npm run type-check` 或 `vue-tsc --noEmit`。
- **后端编译**: `cd api-service && ./mvnw compile -q`。
- **证据目录**: `.omo/evidence/task-<N>-comicatlas-0-2-migration.<ext>`。
- **每个 todo 必须包含**: happy path + failure path QA，明确工具与命令。

## Execution strategy

### 总体顺序

```
Phase 0（架构冻结）→ Phase 1（Shell）→ Phase 2（阅读迁移）→ Phase 3（管理迁移）→ Phase 4（清理）→ Phase 5（新功能）
```

每个 Phase 必须完成并通过验证后才能进入下一阶段。Phase 0 已完成，计划从 Phase 1 开始。

### 并行执行策略

- **Phase 1 内部**：Layout、Router、目录结构、Wrapper 页面可以并行。
- **Phase 2 内部**：各阅读页面迁移可以并行，但 Store 重构需要先完成。
- **Phase 3 内部**：各管理页面迁移可以并行，但管理导航需要先完成。
- **Phase 4 必须串行**：按“页面 → 路由 → 组件 → Store → API/DTO → CSS”顺序删除，避免误删。
- **Phase 5 内部**：Category 表 → Category API → Category UI → 漫画绑定 串行；Tag 迁移与 Storage/Settings/ReadingHome/Cover 可并行。Category 功能集群（Todo 29-33）和 ReadingHome/Cover 功能集群（Todo 38-39）完成后各自运行一次 Playwright 子集，避免 11 个 Feature 累积到 Final Verification 才暴露问题。

### 实施纪律

> **任何新增 Feature 必须归属于已有 Wave；不允许在 Wave 1–4 中临时加入与迁移无关的新功能。**

例如：不得在 Wave 2 中突然加入阅读统计、OCR、AI 标签、新搜索、动画效果等。这些应放到后续版本，不打断迁移节奏。

### Dependency matrix

| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1-3 | — | 4 | 相互并行 |
| 4 | 1, 2, 3 | 8-21, 24 | 与 5, 6 并行 |
| 5 | — | 8-19 | 与 1-3 并行 |
| 6 | 1 | 7 | 与 2, 3, 5 并行 |
| 7 | 4, 6 | Wave 2 | — |
| 8-12 | 4, 5 | 14 | 相互并行 |
| 13 | 8-12 | 14 | — |
| 14 | 13 | Wave 3 | — |
| 15-18 | 4, 5 | 19, 22 | 相互并行 |
| 19 | 2, 15 | 22 | 与 16-18 并行 |
| 20-21 | 15-19 | 22 | 相互并行 |
| 22 | 20, 21 | Wave 4 | — |
| 23-28 | 14, 22 | Wave 5 | Phase 4 内部串行 |
| 29-39 | 28 | Phase 5 完成 | 按各自依赖并行 |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->

### Wave 1 — Phase 1: Shell

- [x] 1. 创建 ReadingLayout
  What to do / Must NOT do: 在 `frontend/src/layouts/ReadingLayout.vue` 创建阅读侧 Layout，包含顶部导航（首页 / 历史 / 管理）和 `<router-view>`。不要在此阶段修改任何页面组件内容。
  Parallelization: Wave 1 | Blocked by: — | Blocks: 4, 8-12
  References: `frontend/src/components/layout/AppLayout.vue`, `frontend/src/components/layout/TopNav.vue`, `docs/architecture/07-frontend.md`
  Acceptance criteria: 文件存在，`npm run type-check` 无错误。
  QA scenarios: happy: `cd frontend && npm run type-check` 成功；failure: 若导航缺少管理入口，失败。Evidence `.omo/evidence/task-1-comicatlas-0-2-migration.txt`
  Commit: Y | feat(frontend): 新增 ReadingLayout

- [x] 2. 创建 ManagementLayout
  What to do / Must NOT do: 在 `frontend/src/layouts/ManagementLayout.vue` 创建管理侧 Layout，包含左侧 5 项导航（漫画 / 导入 / 存储 / 元数据 / 设置）和返回阅读链接。不要实现具体管理页面。
  Parallelization: Wave 1 | Blocked by: — | Blocks: 4, 13-19
  References: `docs/architecture/04-management.md`, `docs/architecture/07-frontend.md`
  Acceptance criteria: 文件存在，包含 SideNav，类型检查通过。
  QA scenarios: happy: `cd frontend && npm run type-check` 成功；failure: 若缺少任一导航项，失败。Evidence `.omo/evidence/task-2-comicatlas-0-2-migration.txt`
  Commit: Y | feat(frontend): 新增 ManagementLayout

- [x] 3. 创建 ReaderLayout
  What to do / Must NOT do: 在 `frontend/src/layouts/ReaderLayout.vue` 创建沉浸式阅读器 Layout，近全屏，隐藏顶部导航。旧组件 `components/layout/ReaderLayout.vue` 仅作参考，不直接复用。不要修改 ReaderPage 业务逻辑。
  Parallelization: Wave 1 | Blocked by: — | Blocks: 4, 11
  References: `frontend/src/layouts/ReaderLayout.vue`（新）, `frontend/src/components/layout/ReaderLayout.vue`（旧，仅参考）, `docs/architecture/02-navigation.md`
  Acceptance criteria: 新 Layout 文件存在，类型检查通过，ReaderLayout 不显示顶部导航。
  QA scenarios: happy: `cd frontend && npm run type-check` 成功；failure: 若顶部导航仍显示或引用旧 Layout，失败。Evidence `.omo/evidence/task-3-comicatlas-0-2-migration.txt`
  Commit: Y | feat(frontend): 新增 ReaderLayout

- [x] 4. 重构 Router 为双树结构
  What to do / Must NOT do: 重写 `frontend/src/router/index.ts`，建立 ReadingLayout、ReaderLayout、ManagementLayout 三棵路由树。旧路由路径（`/home`、`/comics`、`/comics/:id/edit`、`/tasks`、`/import`、`/dashboard`、`/operations` 等）在 Phase 1-3 以 `<Redirect>` 或别名方式保留，确保旧书签可用；Phase 4 统一清理。暂时用旧页面组件作为 Wrapper。
  Parallelization: Wave 1 | Blocked by: 1, 2, 3 | Blocks: 8-18, 23
  References: `frontend/src/router/index.ts`, `docs/architecture/02-navigation.md`
  Acceptance criteria: 所有新路由可访问，旧路由路径不重定向到 404，类型检查通过。
  QA scenarios: happy: `cd frontend && npm run type-check && npm run dev` 后 `/`、`/library`、`/manage`、`/reader/:chapterId` 可访问，旧 `/home` 重定向到 `/`；failure: 新路由 404 或旧路由直接 404（非重定向），失败。Evidence `.omo/evidence/task-4-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 重构 Router 为 Reading/Management/Reader 三树并保留旧路由别名

- [x] 5. 创建视图目录结构
  What to do / Must NOT do: 创建 `frontend/src/views/reading/` 和 `frontend/src/views/management/`。暂时只放指向旧页面的 index 文件或 re-export。不要移动实际页面代码。
  Parallelization: Wave 1 | Blocked by: — | Blocks: 8-19
  References: `docs/architecture/07-frontend.md`
  Acceptance criteria: 目录存在，至少包含占位文件。
  QA scenarios: happy: 目录结构符合 `docs/architecture/07-frontend.md`；failure: 缺少任一目录，失败。Evidence `.omo/evidence/task-5-comicatlas-0-2-migration.txt`
  Commit: Y | chore(frontend): 创建 views/reading 与 views/management 目录

- [x] 6. 更新全局 TopNav
  What to do / Must NOT do: 更新 `frontend/src/components/layout/TopNav.vue`，只保留 首页 / 历史 / 管理 三个入口。删除 Dashboard、Task、Import 等旧入口。
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 7
  References: `frontend/src/components/layout/TopNav.vue`, `docs/architecture/02-navigation.md`
  Acceptance criteria: 顶部导航只有 3 项，点击可跳转。
  QA scenarios: happy: Playwright 现有导航测试通过；failure: 出现已删除入口，失败。Evidence `.omo/evidence/task-6-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 简化 TopNav 为阅读导向导航

- [x] 7. 验证 Phase 1 Shell 可运行
  What to do / Must NOT do: 启动前后端，运行 Playwright smoke tests。不要进入 Phase 2 直到本 todo 通过。
  Parallelization: Wave 1 | Blocked by: 4, 6 | Blocks: Wave 2
  References: `package.json`, `frontend/package.json`, `frontend/e2e/`
  Acceptance criteria: `cd frontend && npx playwright test` 全部通过。
  QA scenarios: happy: Playwright 全部 pass；failure: 任一 test fail，失败。Evidence `.omo/evidence/task-7-comicatlas-0-2-migration.txt`
  Commit: N | —

### Wave 2 — Phase 2: Reading Migration

- [x] 8. 迁移 HomePage 到 views/reading/
  What to do / Must NOT do: 将 `frontend/src/pages/HomePage.vue` 移动到 `frontend/src/views/reading/HomePage.vue`，更新路由引用。第一步只搬家，不改 UI。
  Parallelization: Wave 2 | Blocked by: 4, 5 | Blocks: 14
  References: `frontend/src/pages/HomePage.vue`, `frontend/src/router/index.ts`, `docs/architecture/03-reading.md`
  Acceptance criteria: `/` 能正常显示，类型检查通过。
  QA scenarios: happy: Playwright 首页测试通过；failure: 首页白屏或 404，失败。Evidence `.omo/evidence/task-8-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 迁移 HomePage 到 views/reading

- [x] 9. 迁移 LibraryPage 到 views/reading/
  What to do / Must NOT do: 将 `frontend/src/pages/ComicListPage.vue` 移动到 `frontend/src/views/reading/LibraryPage.vue`。保留现有功能，不改 UI。
  Parallelization: Wave 2 | Blocked by: 4, 5 | Blocks: 14
  References: `frontend/src/pages/ComicListPage.vue`, `frontend/src/router/index.ts`, `docs/architecture/03-reading.md`
  Acceptance criteria: `/library` 能正常显示。
  QA scenarios: happy: Playwright 漫画库测试通过；failure: 列表不渲染，失败。Evidence `.omo/evidence/task-9-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 迁移 LibraryPage 到 views/reading

- [x] 10. 迁移 ComicDetailPage 到 views/reading/ 并移除管理按钮
  What to do / Must NOT do: 将 `frontend/src/pages/ComicDetailPage.vue` 移动到 `frontend/src/views/reading/DetailPage.vue`，删除编辑、删除等管理按钮。不要修改业务逻辑。
  Parallelization: Wave 2 | Blocked by: 4, 5 | Blocks: 14
  References: `frontend/src/pages/ComicDetailPage.vue`, `docs/architecture/03-reading.md`
  Acceptance criteria: `/comic/:id` 正常显示且无管理按钮。
  QA scenarios: happy: 详情页只有阅读相关按钮；failure: 仍存在编辑/删除入口，失败。Evidence `.omo/evidence/task-10-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 迁移 ComicDetailPage 并移除管理按钮

- [x] 11. 迁移 ReaderPage 到 views/reading/ 并适配新路由参数
  What to do / Must NOT do: 将 `frontend/src/pages/ReaderPage.vue` 移动到 `frontend/src/views/reading/ReaderPage.vue`。新路由 `/reader/:chapterId` 不再提供 `comicId`，需要后端 `ReaderDTO` 增加 `comicId` 字段（或使用已有 `ChapterPageVO`），并在 `reader-store.ts` 的 `loadChapter()` 中设置 `state.comicId`。同步更新 ComicDetailPage、HistoryPage 中跳转到阅读器的链接格式。不要修改翻页/阅读核心逻辑。
  Parallelization: Wave 2 | Blocked by: 3, 4, 5 | Blocks: 14
  References: `frontend/src/pages/ReaderPage.vue`, `frontend/src/stores/reader.ts`, `api-service/src/main/java/com/comicatlas/api/reader/dto/ReaderDTO.java`, `api-service/src/main/java/com/comicatlas/api/comic/dto/ChapterPageVO.java`, `docs/architecture/03-reading.md`
  Acceptance criteria: `/reader/:chapterId` 正常阅读，翻页后 `saveProgress()` 能正确保存 `(comicId, chapterId, pageNumber)`。
  QA scenarios: happy: 从 ComicDetail 点击"继续阅读"进入 `/reader/:chapterId`，翻页后刷新页面可恢复进度；failure: 进度保存缺失 comicId 或阅读器 404，失败。Evidence `.omo/evidence/task-11-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 迁移 ReaderPage 并适配 /reader/:chapterId 路由

- [x] 12. 迁移 HistoryPage 到 views/reading/
  What to do / Must NOT do: 将 `frontend/src/pages/HistoryPage.vue` 移动到 `frontend/src/views/reading/HistoryPage.vue`。
  Parallelization: Wave 2 | Blocked by: 4, 5 | Blocks: 14
  References: `frontend/src/pages/HistoryPage.vue`, `docs/architecture/03-reading.md`
  Acceptance criteria: `/history` 正常显示。
  QA scenarios: happy: 历史记录渲染正确；failure: 历史为空或不渲染，失败。Evidence `.omo/evidence/task-12-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 迁移 HistoryPage 到 views/reading

- [x] 13. 重组阅读组件到 components/reading/
  What to do / Must NOT do: 将阅读相关组件从 `frontend/src/components/` 移动到 `frontend/src/components/reading/`。只移动，不改组件内部。
  Parallelization: Wave 2 | Blocked by: 8-12 | Blocks: 14
  References: `frontend/src/components/comic/`, `frontend/src/components/reader/`, `docs/architecture/07-frontend.md`
  Acceptance criteria: 组件路径符合 07-frontend.md，类型检查通过。
  QA scenarios: happy: `npm run type-check` 通过；failure: 组件引用报错，失败。Evidence `.omo/evidence/task-13-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 重组阅读组件到 components/reading

- [x] 14. 验证 Phase 2 阅读链路
  What to do / Must NOT do: 运行 Playwright 阅读链路测试（Home → Library → Detail → Reader → History）。不要进入 Phase 3 直到通过。
  Parallelization: Wave 2 | Blocked by: 8-13 | Blocks: Wave 3
  References: `frontend/e2e/`, `docs/architecture/03-reading.md`
  Acceptance criteria: `cd frontend && npx playwright test` 全部通过。
  QA scenarios: happy: 阅读链路测试 pass；failure: 任一测试 fail，失败。Evidence `.omo/evidence/task-14-comicatlas-0-2-migration.txt`
  Commit: N | —

### Wave 3 — Phase 3: Management Migration

- [x] 15. 创建管理侧 ComicListPage
  What to do / Must NOT do: 在 `frontend/src/views/management/ComicListPage.vue` 创建管理侧漫画列表页，路由 `/manage/comics`，作为 `/manage` 的 redirect 目标。列表展示漫画、来源、状态、操作按钮，点击进入编辑页。
  Parallelization: Wave 3 | Blocked by: 4, 5 | Blocks: 19, 22
  References: `frontend/src/pages/ComicListPage.vue`, `frontend/src/views/management/`, `docs/architecture/02-navigation.md`, `docs/architecture/04-management.md`
  Acceptance criteria: `/manage/comics` 可访问，点击进入 `/manage/comics/:id/edit`。
  QA scenarios: happy: 管理漫画列表渲染，点击编辑跳转正确；failure: `/manage` 重定向 404 或列表不渲染，失败。Evidence `.omo/evidence/task-15-comicatlas-0-2-migration.txt`
  Commit: Y | feat(frontend): 新增管理侧 ComicListPage

- [x] 16. 迁移 ComicEditPage 到 views/management/
  What to do / Must NOT do: 将 `frontend/src/pages/ComicEditPage.vue` 移动到 `frontend/src/views/management/ComicEditPage.vue`，路由改为 `/manage/comics/:id/edit`。保持现有编辑功能。
  Parallelization: Wave 3 | Blocked by: 4, 5 | Blocks: 23
  References: `frontend/src/pages/ComicEditPage.vue`, `frontend/src/router/index.ts`, `docs/architecture/04-management.md`
  Acceptance criteria: `/manage/comics/:id/edit` 正常显示且可保存。
  QA scenarios: happy: 编辑页可修改标题/作者；failure: 保存失败或 404，失败。Evidence `.omo/evidence/task-16-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 迁移 ComicEditPage 到 views/management

- [x] 17. 迁移 ImportPage 到 views/management/
  What to do / Must NOT do: 将 `frontend/src/pages/ImportPage.vue` 移动到 `frontend/src/views/management/ImportPage.vue`，路由改为 `/manage/import`。
  Parallelization: Wave 3 | Blocked by: 4, 5 | Blocks: 23
  References: `frontend/src/pages/ImportPage.vue`, `docs/architecture/04-management.md`
  Acceptance criteria: `/manage/import` 正常显示且可创建导入任务。
  QA scenarios: happy: 可提交 ZIP/目录导入；failure: 导入请求失败，失败。Evidence `.omo/evidence/task-17-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 迁移 ImportPage 到 views/management

- [x] 18. 迁移 TaskPage 到 views/management/
  What to do / Must NOT do: 将 `frontend/src/pages/TaskCenterPage.vue` 移动到 `frontend/src/views/management/TaskPage.vue`，路由改为 `/manage/import/tasks`。
  Parallelization: Wave 3 | Blocked by: 4, 5 | Blocks: 23
  References: `frontend/src/pages/TaskCenterPage.vue`, `docs/architecture/04-management.md`
  Acceptance criteria: `/manage/import/tasks` 正常显示任务列表。
  QA scenarios: happy: 任务列表渲染正确；failure: 任务为空或不渲染，失败。Evidence `.omo/evidence/task-18-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 迁移 TaskCenterPage 到 views/management

- [x] 19. 创建管理侧 SideNav
  What to do / Must NOT do: 在 ManagementLayout 中实现左侧导航：漫画 / 导入 / 存储 / 元数据 / 设置。漫画入口指向 ComicListPage；导入/存储/元数据/设置可先 placeholder 或真实页面。
  Parallelization: Wave 3 | Blocked by: 2 | Blocks: 23
  References: `frontend/src/layouts/ManagementLayout.vue`, `docs/architecture/04-management.md`
  Acceptance criteria: 5 项导航全部可点击，漫画入口跳转 `/manage/comics`，其他入口至少显示占位页面。
  QA scenarios: happy: 导航点击后 URL 正确；failure: 导航项缺失或 404，失败。Evidence `.omo/evidence/task-19-comicatlas-0-2-migration.txt`
  Commit: Y | feat(frontend): 实现管理侧 5 项 SideNav

- [x] 20. 重组管理组件到 components/management/
  What to do / Must NOT do: 将管理相关组件移动到 `frontend/src/components/management/`。只移动，不改逻辑。
  Parallelization: Wave 3 | Blocked by: 16-19 | Blocks: 23
  References: `frontend/src/components/task/`, `docs/architecture/07-frontend.md`
  Acceptance criteria: 组件路径符合 07-frontend.md，类型检查通过。
  QA scenarios: happy: `npm run type-check` 通过；failure: 组件引用报错，失败。Evidence `.omo/evidence/task-20-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 重组管理组件到 components/management

- [x] 21. 重组管理 Store
  What to do / Must NOT do: 将管理相关 store 拆分为 `frontend/src/stores/management/comic.ts`、`import.ts` 等。不修改 API 调用逻辑。
  Parallelization: Wave 3 | Blocked by: 16-19 | Blocks: 23
  References: `frontend/src/stores/`, `docs/architecture/07-frontend.md`
  Acceptance criteria: Store 拆分完成，类型检查通过。
  QA scenarios: happy: 管理页面状态正常；failure: store 引用报错，失败。Evidence `.omo/evidence/task-21-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 拆分管理模块 Store

- [x] 22. 验证 Phase 3 管理链路
  What to do / Must NOT do: 运行 Playwright 管理链路测试（管理首页 → 漫画列表 → 漫画编辑 → 导入 → 任务）。不要进入 Phase 4 直到通过。
  Parallelization: Wave 3 | Blocked by: 16-21 | Blocks: Wave 4
  References: `frontend/e2e/`, `docs/architecture/04-management.md`
  Acceptance criteria: `cd frontend && npx playwright test` 全部通过。
  QA scenarios: happy: 管理链路测试 pass；failure: 任一测试 fail，失败。Evidence `.omo/evidence/task-22-comicatlas-0-2-migration.txt`
  Commit: N | —

### Wave 4 — Phase 4: Legacy Cleanup

- [x] 23. 删除 DashboardPage 与 OperationLogPage
  What to do / Must NOT do: 删除 `frontend/src/pages/DashboardPage.vue` 和 `frontend/src/pages/OperationLogPage.vue`。同时删除相关路由、组件、store。不要误删其他页面。
  Parallelization: Wave 4 | Blocked by: 14, 22 | Blocks: 24, 27
  References: `frontend/src/pages/DashboardPage.vue`, `frontend/src/pages/OperationLogPage.vue`, `frontend/src/router/index.ts`
  Acceptance criteria: `/dashboard` 和 `/operations` 返回 404。
  QA scenarios: happy: 旧路由不再存在；failure: 旧路由仍能访问，失败。Evidence `.omo/evidence/task-23-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 删除 Dashboard 与 OperationLog 页面

- [x] 24. 删除旧 Router 与旧 pages/ 目录
  What to do / Must NOT do: 当所有页面迁移完成后，删除 `frontend/src/pages/` 目录。删除旧路由定义（`/comics`、`/comics/:id`、`/comics/:id/edit` 等）。
  Parallelization: Wave 4 | Blocked by: 14, 22, 23 | Blocks: 25, 27
  References: `frontend/src/pages/`, `frontend/src/router/index.ts`, `docs/architecture/02-navigation.md`
  Acceptance criteria: `frontend/src/pages/` 不存在，旧路由全部 404。
  QA scenarios: happy: 新路由正常，旧路由 404；failure: 仍有旧路由可访问，失败。Evidence `.omo/evidence/task-24-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 删除旧 pages 目录与旧路由

- [x] 25. 清理废弃组件
  What to do / Must NOT do: 删除不再使用的管理/统计组件（如 `HeroBanner.vue`）以及旧 Layout 组件。保留 `common/` 中真正通用的组件。
  Parallelization: Wave 4 | Blocked by: 23 | Blocks: 26, 27
  References: `frontend/src/components/`, `frontend/src/components/layout/HeroBanner.vue`
  Acceptance criteria: 类型检查通过，无未引用组件。
  QA scenarios: happy: `npm run type-check` 通过；failure: 引用已删除组件报错，失败。Evidence `.omo/evidence/task-25-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 清理废弃组件

- [x] 26. 清理 Legacy Store、API、DTO、CSS
  What to do / Must NOT do: 删除旧的 dashboard store、operation store、未使用的 API 方法、废弃 DTO、未引用的 CSS。不要删除仍在使用的 API。
  Parallelization: Wave 4 | Blocked by: 23-25 | Blocks: 27
  References: `frontend/src/stores/`, `frontend/src/services/`, `frontend/src/assets/`
  Acceptance criteria: 无 `*_old` / `*_legacy` 文件，类型检查通过。
  QA scenarios: happy: `npm run type-check` 通过，全局搜索 `legacy|_old` 无结果；failure: 存在遗留文件，失败。Evidence `.omo/evidence/task-26-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 清理 Legacy Store、API、DTO、CSS

- [x] 28. 运行全量 Playwright 验证 0.2 架构
  What to do / Must NOT do: 运行完整 Playwright 测试套件，确认 0.2 架构成型。不要进入 Phase 5 直到通过。
  Parallelization: Wave 4 | Blocked by: 23-27 | Blocks: Wave 5
  References: `frontend/e2e/`, `frontend/playwright.config.ts`
  Acceptance criteria: `cd frontend && npx playwright test` 全部通过。
  QA scenarios: happy: 全部测试 pass；failure: 任一 fail，失败。Evidence `.omo/evidence/task-28-comicatlas-0-2-migration.txt`
  Commit: N | —

- [x] 27. 重组阅读侧 Store 与 API Service
  What to do / Must NOT do: 按 `docs/architecture/07-frontend.md` 创建 `frontend/src/stores/reading.ts`（合并现有阅读相关 store）和 `frontend/src/services/reading.ts`、`frontend/src/services/management.ts`。不修改 API 调用逻辑，只重组文件归属。
  Parallelization: Wave 4 | Blocked by: 25, 26 | Blocks: 28
  References: `frontend/src/stores/`, `frontend/src/services/api.ts`, `docs/architecture/07-frontend.md`
  Acceptance criteria: 阅读相关 store 合并到 `stores/reading.ts`，管理相关 API 方法拆到 `services/management.ts`，类型检查通过。
  QA scenarios: happy: `cd frontend && npm run type-check` 通过，所有阅读/管理页面状态正常；failure: store/service 引用报错，失败。Evidence `.omo/evidence/task-27-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 重组阅读与管理 Store/Service

### Wave 5 — Phase 5: New Features

- [x] 29. 新增 Category 表与实体
  What to do / Must NOT do: 在 `api-service` 中新增 `category` 表、实体 `Category`、Mapper、Service。插入默认 5 条分类。不要建 `parent_id` 或树形结构。
  Parallelization: Wave 5 | Blocked by: 28 | Blocks: 30, 31
  References: `docs/architecture/05-domain.md`, `api-service/src/main/java/com/comicatlas/api/comic/entity/`
  Acceptance criteria: 后端编译通过，数据库有默认分类。
  QA scenarios: happy: `cd api-service && ./mvnw compile -q` 成功；failure: 编译失败，失败。Evidence `.omo/evidence/task-29-comicatlas-0-2-migration.txt`
  Commit: Y | feat(api): 新增 Category 表与实体

- [x] 30. 新增 CategoryController 与 Category API
  What to do / Must NOT do: 新增 `CategoryController` 提供 CRUD 接口。Comic 详情/列表返回 `categoryName` 或 `categoryId`。
  Parallelization: Wave 5 | Blocked by: 29 | Blocks: 32
  References: `api-service/src/main/java/com/comicatlas/api/comic/controller/`, `docs/architecture/06-api.md`
  Acceptance criteria: API 可返回分类列表，Comic 接口包含分类信息。
  QA scenarios: happy: `GET /api/categories` 返回 5 条默认分类；failure: 接口 500，失败。Evidence `.omo/evidence/task-30-comicatlas-0-2-migration.txt`
  Commit: Y | feat(api): 新增 CategoryController

- [x] 31. 迁移 comic.category 到 category_id
  What to do / Must NOT do: 修改 `Comic` 实体，新增 `categoryId`，废弃 `category` 字段。提供数据库迁移脚本。
  Parallelization: Wave 5 | Blocked by: 29 | Blocks: 32
  References: `api-service/src/main/java/com/comicatlas/api/comic/entity/Comic.java`, `docs/architecture/05-domain.md`
  Acceptance criteria: 数据库迁移成功，Comic 表有 `category_id`。
  QA scenarios: happy: 旧 category 值映射到新分类；failure: 迁移后数据丢失，失败。Evidence `.omo/evidence/task-31-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(api): 迁移 comic.category 到 category_id

- [x] 32. 创建 MetadataPage（Category / Tag 双 Tab）
  What to do / Must NOT do: 在 `frontend/src/views/management/MetadataPage.vue` 创建元数据管理页，包含 Category 和 Tag 两个 Tab。Category 支持 CRUD，Tag 复用现有功能。
  Parallelization: Wave 5 | Blocked by: 30, 31 | Blocks: 34
  References: `docs/architecture/04-management.md`, `frontend/src/views/management/`
  Acceptance criteria: `/manage/metadata` 可管理 Category 和 Tag。
  QA scenarios: happy: 可新增/重命名/删除 Category；failure: Tab 切换或 CRUD 报错，失败。Evidence `.omo/evidence/task-32-comicatlas-0-2-migration.txt`
  Commit: Y | feat(frontend): 新增 MetadataPage 管理 Category/Tag

- [x] 33. 更新 ComicEditPage 绑定 Category
  What to do / Must NOT do: 在漫画编辑页增加 Category 单选器。保存时更新 `categoryId`。不要在此阶段增加新功能。
  Parallelization: Wave 5 | Blocked by: 31 | Blocks: 35
  References: `frontend/src/views/management/ComicEditPage.vue`, `docs/architecture/05-domain.md`
  Acceptance criteria: 编辑页可修改漫画 Category 并保存。
  QA scenarios: happy: 保存后 Category 正确更新；failure: 保存失败，失败。Evidence `.omo/evidence/task-33-comicatlas-0-2-migration.txt`
  Commit: Y | feat(frontend): 漫画编辑页支持 Category 绑定

- [x] 34. 迁移 Tag 管理到 Metadata 模块
  What to do / Must NOT do: 将现有 Tag 管理入口从旧位置迁移到 `/manage/metadata` 的 Tag Tab。保持现有 Tag CRUD 功能。
  Parallelization: Wave 5 | Blocked by: 32 | Blocks: 35
  References: `frontend/src/views/management/MetadataPage.vue`, `frontend/src/services/api.ts`, `docs/architecture/04-management.md`
  Acceptance criteria: Tag 管理在 `/manage/metadata` 下可用。
  QA scenarios: happy: Tag 增删改查正常；failure: Tag 功能丢失，失败。Evidence `.omo/evidence/task-34-comicatlas-0-2-migration.txt`
  Commit: Y | refactor(frontend): 将 Tag 管理迁移到 Metadata 模块

- [x] 35. 创建 StoragePage 基础框架
  What to do / Must NOT do: 在 `frontend/src/views/management/StoragePage.vue` 创建存储管理页，包含统计、扫描、恢复、清理占位。本期只做框架和统计，具体功能后续迭代。
  Parallelization: Wave 5 | Blocked by: 28 | Blocks: Phase 5 完成
  References: `docs/architecture/04-management.md`, `frontend/src/views/management/`
  Acceptance criteria: `/manage/storage` 可访问，展示存储统计占位。
  QA scenarios: happy: 页面渲染正常，导航可进入；failure: 页面 404 或报错，失败。Evidence `.omo/evidence/task-35-comicatlas-0-2-migration.txt`
  Commit: Y | feat(frontend): 新增 StoragePage 基础框架

- [x] 36. 创建 SettingsPage 基础框架
  What to do / Must NOT do: 在 `frontend/src/views/management/SettingsPage.vue` 创建设置页，路由 `/manage/settings`。本期只做基础框架和占位，具体配置项后续迭代。
  Parallelization: Wave 5 | Blocked by: 28 | Blocks: Phase 5 完成
  References: `frontend/src/views/management/`, `docs/architecture/02-navigation.md`, `docs/architecture/04-management.md`
  Acceptance criteria: `/manage/settings` 可访问，SideNav "设置" 入口可点击。
  QA scenarios: happy: 设置页渲染正常；failure: 设置页 404，失败。Evidence `.omo/evidence/task-36-comicatlas-0-2-migration.txt`
  Commit: Y | feat(frontend): 新增 SettingsPage 基础框架

- [x] 37. 验证并确保 tag/comic_tag 表存在
  What to do / Must NOT do: 检查数据库中 `tag` 和 `comic_tag` 表是否存在；若不存在，按 `docs/architecture/05-domain.md` 创建。不要修改已有 tag 数据。
  Parallelization: Wave 5 | Blocked by: 28 | Blocks: 38
  References: `docs/architecture/05-domain.md`, `api-service/src/main/java/com/comicatlas/api/comic/entity/`
  Acceptance criteria: `tag` 与 `comic_tag` 表存在且与实体一致，后端编译通过。
  QA scenarios: happy: `./mvnw compile -q` 通过，SQL `SHOW TABLES LIKE 'tag'`/`'comic_tag'` 返回存在；failure: 表缺失或实体不一致，失败。Evidence `.omo/evidence/task-37-comicatlas-0-2-migration.txt`
  Commit: Y | chore(api): 确保 tag/comic_tag 表与实体存在

- [x] 38. ReadingHome 增强：继续阅读/最近阅读/最近加入
  What to do / Must NOT do: 在 `views/reading/HomePage.vue` 增加继续阅读、最近阅读、最近加入区块，符合 `docs/architecture/03-reading.md`。不要添加推荐或社交功能。
  Parallelization: Wave 5 | Blocked by: 8, 28 | Blocks: Phase 5 完成
  References: `frontend/src/views/reading/HomePage.vue`, `docs/architecture/03-reading.md`
  Acceptance criteria: `/` 展示继续阅读（有历史时）、最近阅读、最近加入、漫画库第一页。
  QA scenarios: happy: Playwright 断言首页存在"继续阅读"/"最近阅读"/"最近加入"区块；failure: 首页仍为旧 Netflix 首页，失败。Evidence `.omo/evidence/task-38-comicatlas-0-2-migration.txt`
  Commit: Y | feat(frontend): ReadingHome 增加阅读上下文区块

- [x] 39. 漫画编辑封面管理：从已有 page 选择封面
  What to do / Must NOT do: 在漫画编辑页增加"封面"区块，支持从该漫画已有 page 中选择封面。不上传新图片、不裁切。
  Parallelization: Wave 5 | Blocked by: 16, 28 | Blocks: Phase 5 完成
  References: `frontend/src/views/management/ComicEditPage.vue`, `docs/architecture/04-management.md`
  Acceptance criteria: 漫画编辑页可选择 page 作为封面并保存。
  QA scenarios: happy: 选择新封面后 ComicDetail 显示新封面；failure: 封面不更新或选择弹窗报错，失败。Evidence `.omo/evidence/task-39-comicatlas-0-2-migration.txt`
  Commit: Y | feat(frontend): 漫画编辑支持从已有 page 选择封面

## Final verification wave
> 所有 todo 完成后并行运行；必须全部通过，才能向用户报告完成。

- [x] F1. Plan compliance audit
  检查：每个 todo 是否完成、是否有遗留 Legacy 文件、路由是否与 `docs/architecture/02-navigation.md` 一致。
  工具：`grep -r "legacy\|_old\|pages/Dashboard\|pages/OperationLog" frontend/src` 应无结果。
  证据：`.omo/evidence/f1-comicatlas-0-2-migration.txt`

- [x] F2. Code quality review
  检查：前端类型检查通过、后端编译通过、无未使用 import。
  工具：`cd frontend && npm run type-check`；`cd api-service && ./mvnw compile -q`。
  证据：`.omo/evidence/f2-comicatlas-0-2-migration.txt`

- [x] F3. Real manual QA
  检查：Playwright 全量测试通过。
  工具：`cd frontend && npx playwright test`。
  证据：`.omo/evidence/f3-comicatlas-0-2-migration.txt`

- [x] F4. Scope fidelity
  检查：0.2 范围边界是否被遵守（无 Dashboard、无 OperationLog、阅读与管理分离、Category 为一级）。
  工具：
    - `grep -rE "DashboardPage|OperationLogPage" frontend/src/views frontend/src/router frontend/src/components` 应无结果。
    - `grep -rE "legacy|_old|_legacy" frontend/src` 应无结果。
    - Playwright 断言 `/dashboard`、`/operations` 返回 404。
    - Playwright 断言 `/comic/:id` 无编辑/删除按钮。
  证据：`.omo/evidence/f4-comicatlas-0-2-migration.txt`

## Commit strategy

- **每个 todo 尽量独立 commit**：每个实现 + 测试完成后即可提交，方便回滚。
- **Phase 边界处做阶段性提交**：每个 Wave 的验证 todo 通过后，可以打一个 summary commit。
- **每个 Wave 验证通过后打 Git Tag**：便于快速回滚到已知良好状态。建议 Tag 命名：`v0.2-wave1`、`v0.2-wave2`、`v0.2-wave3`、`v0.2-wave4`、`v0.2-wave5`。
- **Commit message 使用中文**：符合项目约定，例如 `feat(frontend): 新增 ReadingLayout`。
- **不提交未通过测试的代码**：每个 commit 前必须跑通当前已有测试。
- **Phase 0 文档已写入**：作为计划基线，本次不单独 commit；后续实施时与 Phase 1 一起提交。

## Success criteria

1. **架构层面**：
   - `docs/architecture/00-08.md` 完整且一致。
   - 前端存在 `ReadingLayout`、`ReaderLayout`、`ManagementLayout` 三个 Layout。
   - 路由与 `docs/architecture/02-navigation.md` 完全一致。

2. **阅读层面**：
   - `/` 显示继续阅读、最近阅读、最近加入、漫画库。
   - `/library` 可完整浏览/搜索/筛选。
   - `/comic/:id` 无管理按钮。
   - `/reader/:chapterId` 沉浸式阅读。
   - `/history` 显示阅读历史。

3. **管理层面**：
   - `/manage/*` 下只有 5 项导航：漫画 / 导入 / 存储 / 元数据 / 设置。
   - `/manage/comics` 管理漫画列表可正常使用，点击进入编辑。
   - 漫画编辑、导入、任务可正常使用。
   - 漫画编辑支持从已有 page 选择封面。
   - Category 表与实体存在，Comic 可绑定 Category。
   - Tag 管理位于 Metadata 模块。
   - `/manage/settings` 设置页可访问。

4. **清理层面**：
   - 无 `frontend/src/pages/` 目录。
   - 无 Dashboard、OperationLog、旧路由。
   - 无 `*_old` / `*_legacy` 文件。

5. **测试层面**：
   - 每个 Phase 结束时 Playwright 通过。
   - 最终 `npx playwright test` 全部通过。
   - 前端类型检查无错误。
   - 后端编译通过。
