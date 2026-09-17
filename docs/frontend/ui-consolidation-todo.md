# 前端公共 UI 收敛 TODO

更新日期：2026-09-17。本清单以当前 `frontend/src` 为准，目标是把跨页面重复的 UI 外观和交互抽到 FSD 的 `shared/ui`，使每一种按钮、状态、页面状态与基础容器只有一个实现。本轮已完成代码迁移、公共入口收敛和自动检查。

## 收敛规则

- `shared/ui` 只提供无领域语义的外观和基础交互，不请求 API、不读 Store、不认识漫画、任务、存储等业务状态。
- `entities/*/ui` 负责实体特有展示与状态映射；`features/*/ui` 负责一个用户动作；`widgets` 与 `pages` 只组合公共 UI，不能复制其基础 CSS。
- 保留 Element Plus 作为表单、弹框、表格的底层实现。禁止在同一语义上混用 `<button>` 自定义样式与 `el-button`；完成迁移后，以 `AppButton` 为统一业务按钮入口，只有 Element Plus 专有能力才可直接使用 `el-button`。
- 所有公共组件必须暴露 `index.ts`；业务页只从 `@/shared/ui/<slice>` 的 public API 引用，不导入其内部 Vue 文件。

## 待办清单

### UI-01：建立唯一按钮组件

- [x] **TODO UI-01**：新增 `shared/ui/button/`，以 Element Plus 为基础实现 `AppButton`，统一 `primary`、`secondary`、`ghost`、`danger`、`text`、`overlay` 六种 variant，以及 `sm/default/lg` 尺寸、loading、disabled、icon-only 和键盘焦点样式。
- 替换范围：`pages/management/comics/ui/ComicListPage.vue`、`pages/reading/history/ui/HistoryPage.vue` 中重复的 `.primary-btn/.ghost-btn`；`pages/management/import/ui/ImportPage.vue`、`widgets/home/HeroBanner.vue`、`entities/comic/ui/{ComicCard,ComicPoster}.vue` 的同类原生按钮。
- 约束：导航仍使用 `RouterLink`；必要时由 `AppButton` 提供 `as`/`to` 适配，不能用点击回调模拟链接。卡片整块点击和列表行点击保持其实体交互，不强行替换为按钮。
- 验证：`rg -n '(^|[\\s{])\\.(primary-btn|ghost-btn|poster-btn|overlay-btn|hero-btn)' frontend/src` 不再发现可替换的通用按钮实现；hover、disabled、焦点和移动端触控尺寸一致。

### UI-02：收口图标按钮与文字操作

- [x] **TODO UI-02**：在 `shared/ui/button/` 为图标按钮和文字操作定义 `icon`、`text` variant，统一可访问名称、最小点击区、danger/warning 色阶与 loading 行为。
- 替换范围：`pages/reading/history/ui/HistoryPage.vue` 的 `history-play/history-end-retry`，`features/chapter-search/ui/ChapterSearchBox.vue` 的清空按钮，以及各管理页对“刷新、重试、清空、编辑、删除”的本地按钮实现。
- 约束：表格行内的 Element Plus `link` 可以由 `AppButton variant="text"` 适配，但不能改变确认对话框、禁用条件或事件传播语义。
- 验证：所有无文字图标操作都有 `aria-label`；没有为同一操作保留两套颜色、圆角或 hover 规则。

### UI-03：统一页面标题栏

- [x] **TODO UI-03**：在 `shared/ui/page-header/` 提供由标题、eyebrow、description、actions 插槽组成的无业务 `PageHeader`；`ManagementPageHeader` 改为薄包装或删除。
- 替换范围：`pages/reading/history/ui/HistoryPage.vue`、`pages/reading/library/ui/LibraryPage.vue` 的 `.page-header`，以及所有使用 `ManagementPageHeader` 或本地 `.workspace-header/.structure-header` 的管理页。
- 约束：阅读端移动端隐藏标题栏的策略留在页面或 widget；组件只负责桌面基础布局和插槽，不绑定路由或断点。
- 验证：标题字号、下边框、actions 间距和窄屏换行由组件单点定义；页面不再复制 `.header-actions` 等基础规则。

### UI-04：统一加载、错误与空态

- [x] **TODO UI-04**：扩展现有 `shared/ui/management-panel/EmptyState.vue` 或迁为 `shared/ui/content-state/`，提供 `loading`、`error`、`empty` 三种语义状态及 action 插槽；spinner 也由该切片唯一实现。
- 替换范围：`pages/management/comics/ui/ComicListPage.vue`、`pages/reading/history/ui/HistoryPage.vue`、`pages/reading/library/ui/LibraryPage.vue`、`pages/management/dlq/ui/DeadLetterPage.vue` 及其余含 `.state/.spinner/.empty-*` 的页面。
- 约束：空态文案、图标和重试动作属于调用方；公共组件不吞掉错误信息，也不发起重试请求。
- 验证：`rg -n '\\.(state|spinner|empty-title|empty-desc)' frontend/src/pages` 只保留页面布局特例，不再保留重复的状态容器和旋转动画。

### UI-05：合并漫画封面、悬浮动作与状态角标

- [x] **TODO UI-05**：评估并合并 `entities/comic/ui/ComicCard.vue` 与 `ComicPoster.vue` 的封面框、占位图、进度条、悬浮遮罩、继续阅读/详情按钮和状态角标；目标是一个可配置的 `ComicPoster` 基础展示组件，列表密度差异由 slots/variant 表达。
- [x] **TODO UI-06**：封面上的业务状态映射继续位于 `entities/comic/model/status.ts`，视觉角标改复用 `shared/ui/status-badge`（必要时增加 overlay appearance），不得在 `ComicCard.vue` 与 `ComicPoster.vue` 各写 `.status-badge/.status--*`。
- 约束：不要为了“唯一组件”抹平阅读首页、漫画库与管理列表的不同信息密度；只合并相同的外观和交互原语。
- 验证：封面缺图、进度、状态、键盘激活、悬浮遮罩均有实体 UI 测试；不再存在两套“继续阅读/查看详情”按钮外观。

### UI-07：收口面板、卡片和统计容器

- [x] **TODO UI-07**：以 `shared/ui/management-panel/{ManagementPanel,PanelHeader,StatCard,StatGrid}.vue` 为唯一基础容器，补齐 padding、flush、标题、actions、响应式栅格 variant；命名去除仅限管理端的误导性时一并迁移。
- 替换范围：`pages/management/comic-workspace/ui/{ComicOperationsPage,ComicStructurePage}.vue` 的本地 `.panel/.tree-panel/.detail-panel/.action-card` 中可泛化的边框、背景、圆角、标题区域，以及其他管理页的重复卡片外观。
- 约束：目录树、媒体工作台和操作表单的三栏布局仍是页面专有样式；只抽取视觉容器，不把页面业务结构移入 shared。
- 验证：通用容器的 shadow、border、radius、padding 只在 shared/ui 定义一次。

### UI-08：规范状态展示的唯一入口

- [x] **TODO UI-08**：保持 `shared/ui/status-badge/StatusBadge.vue` 为唯一无业务状态外观；`entities/comic/ui/ComicStatusTag.vue`、`features/task/ui/TaskStatusTag.vue`、`features/storage/ui/StorageStatusTag.vue` 只负责把领域状态映射为 `label/tone/appearance`。
- 替换范围：管理任务页的 `.task-card-accent.status-*`、工作台内 `.media-status`、导入与回收页面的裸状态文本，分别判断是否为实体状态标签后复用领域 adapter。
- 约束：时间线色条、进度条等结构性状态提示不应伪装成 badge；仅合并标签外观，不丢失任务/媒体的状态机语义。
- 验证：不在页面内新增状态颜色映射；所有标签的颜色 token 来自 `StatusBadge`。

### UI-09：统一样式令牌和禁止页面级组件复制

- [x] **TODO UI-09**：将按钮、状态、容器、标题、空态所需的尺寸、边框、过渡、焦点 token 集中到 `app/styles/tokens.css`；公共组件只能消费 token，页面不能硬编码同类视觉值。
- [x] **TODO UI-10**：在 ESLint/Stylelint（若引入）或自定义 CI 检查中禁止 `pages/**` 定义 `.primary-btn`、`.ghost-btn`、`.status-badge`、`.spinner` 等公共类名，并禁止 pages 深层导入 `shared/ui/**/<Component>.vue`。
- 验证：新增页面只能组合 `shared/ui`、entities、features、widgets；PR 检查能阻止相同按钮出现第二份 CSS。

### UI-11：公共 UI 的迁移顺序与回归门禁

- [x] **TODO UI-11**：按 UI-01 → UI-02 → UI-04 → UI-03 → UI-08 → UI-05 → UI-07 → UI-09/10 的顺序迁移；每次只替换一个原语并删除被替代的 CSS，禁止保留永久兼容样式。
- 验证：每个组件补充 unit test（variant、disabled、slot、可访问性属性），关键页面补充 Playwright 断言；每批运行 `pnpm typecheck`、`pnpm lint`、`pnpm test:unit`、`pnpm build` 和 `git diff --check`。

## 已有可复用基础

`shared/ui/status-badge/StatusBadge.vue`、`shared/ui/management-panel/*`、`shared/ui/icon/MaterialSymbolIcon.vue`、`shared/ui/logo/ComicAtlasLogo.vue` 已具备公共 UI 候选资格。后续迁移应优先增强这些组件或在其相邻切片补充新原语，不能重新在页面目录创建平行基础组件。

本清单与 [FSD 改造 TODO](fsd-refactoring-todo.md) 互补：前者解决层级和切片边界，本文解决跨页面的 UI 原语重复。完成 UI 收敛不意味着把所有视觉结构抽成全局组件；只抽取已被多个页面重复实现且不会携带业务语义的部分。
