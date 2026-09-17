# 前端 Feature-Sliced Design 改造 TODO

更新日期：2026-09-17。本文是对 `frontend/src` 当前全部生产源码目录的结构审计清单，**只标记，不迁移**。`FSD-xx` 是后续改造的唯一编号；完成一个编号时，必须同步更新本文件和受影响切片的 public API。

## 目标边界

目标目录采用 FSD 的六层：`app`、`pages`、`widgets`、`features`、`entities`、`shared`。依赖只能由上层指向下层；同层切片不得通过内部文件互相引用，必须经各自 `index.ts` 的 public API。`app` 是唯一允许装配路由、Pinia、全局样式和第三方插件的层；`pages` 仅编排页面；`widgets` 组合跨页面的大块 UI；`features` 表达可由用户触发的业务能力；`entities` 保存稳定业务实体模型；`shared` 不得认识漫画、章节、任务等业务概念。

建议目标骨架：

```text
src/
├── app/        # 入口、providers、router、根组件、全局样式
├── pages/      # 一个路由页面一个切片（ui/model/api）
├── widgets/    # TopNav、阅读/管理布局、阅读器外壳等大块组合 UI
├── features/   # 导入、上传、回收、章节搜索、阅读设置等用户动作
├── entities/   # comic、chapter、media、tag、category、history、task、storage
└── shared/     # api、config、lib、ui、assets、types
```

## 改造清单

### FSD-01：建立 app 层并收口启动装配

- [x] **TODO FSD-01**：迁移 `src/main.ts`、`src/App.vue`、`src/router/index.ts`、`src/styles/` 至 `src/app/`（可保留 `app/styles/`）。将 Pinia、VueVirtualScroller、路由和全局 CSS 的注册限制在 `app/providers/`。
- 约束：路由守卫使用的设备判定应由 `shared/lib/device` 导出；业务 Store 的预热不得在 `app` 以外的布局组件中隐式执行。
- 验证：所有路径别名切换后，入口只依赖 `app`、`shared` 与第三方包；刷新每条路由不改变行为。

### FSD-02：用 pages 层替代 views

- [x] **TODO FSD-02**：将 `src/views/reading/*.vue` 与 `src/views/management/**/*.vue` 逐页迁至 `src/pages/<域>/<页面>/ui/`，并为每个路由页建立 public API；`views/` 整体删除。
- 范围包含 `storage/`、`dlq/` 下的页面组件，以及 `InterceptPage.vue`、`ManagementHomePage.vue`、`ComicWorkspacePage.vue` 等当前未按目录隔离的页面。
- 验证：路由只从 `pages/*` 的 `index.ts` 导入；页面之外不得深层导入 `pages/**/ui` 或 `pages/**/model`。

### FSD-03：归还页面私有状态与组件

- [x] **TODO FSD-03**：迁移 `src/views/reading/composables/useLibraryPageLayout.ts`、`src/views/management/composables/*` 和 `src/views/management/dlq/DlqMessageDialog.vue`、`src/views/management/storage/{StorageSummary,StorageTable,StorageToolbar}.vue` 至所属 `pages/*/{model,ui}`。
- 约束：页面私有状态不能提升到 `features`；只有能在两个以上页面独立复用、且代表用户动作的能力才创建 feature。
- 验证：这些模块的 import 不再包含 `@/views/`，且页面切片外没有消费者。

### FSD-04：建立 widgets 层，移除顶层 layouts/components 的组合职责

- [x] **TODO FSD-04**：把 `src/layouts/{ReadingLayout,ReaderLayout,ManagementLayout}.vue`、`src/components/layout/TopNav.vue` 迁至 `src/widgets/`。管理端菜单、阅读导航与阅读器外壳按各自 widget 切片组织。
- 约束：`ManagementLayout.vue` 当前调用 `useImportStore().bootstrap()`；将该预热移到明确的 `app` provider 或管理页模型，避免 widget 反向依赖 feature。
- 验证：widgets 只依赖 features/entities/shared；features 与 entities 不得导入 widgets。

### FSD-05：把纯展示组件归入 shared/ui

- [x] **TODO FSD-05**：迁移 `src/components/{brand,icons,status,management}/**` 至 `src/shared/ui/`，并按 `logo`、`icon`、`status-badge`、`management-panel` 等组件目录提供 `index.ts`。
- 约束：`ManagementPageHeader`、`StatCard`、`StatGrid`、`PanelHeader`、`ManagementPanel`、`EmptyState` 只能保留无业务 API、无 Store、无路由依赖；否则改为具体 widget。
- 验证：`shared/ui` 不出现 `comic`、`task`、`storage` 等领域枚举映射；映射继续留在实体或 feature。

### FSD-06：把基础设施从 services/shared 根目录归位

- [x] **TODO FSD-06**：将 `src/services/http.ts` 迁至 `src/shared/api/http.ts`，`src/services/logger.ts` 迁至 `src/shared/lib/logger.ts`，`src/shared/api/types.ts` 收口为 API 协议类型；删除顶层 `services/`。
- [x] **TODO FSD-07**：将 `src/shared/device.ts` 与 `src/shared/composables/*` 归入 `src/shared/lib/`（或 `shared/lib/composables/`），并以 public API 引用；`format/bytes.ts` 保留为 `shared/lib/format`。
- 验证：`shared` 不引用 features/entities/pages/widgets/app；Axios 错误解包和日志语义完全不变。

### FSD-08：重建漫画与章节实体切片

- [x] **TODO FSD-08**：将 `features/comic` 中的 `catalog.ts`、`structure.ts`、`status.ts`、`source-format.ts`、展示型 `components/{ComicCard,ComicPoster,ComicStatusTag,ChapterRow,CatalogTree,CatalogTreeNode,CatalogChapterRow,MobileComicDetail}.vue` 与现有 `entities/comic/{types,api,management-types}.ts` 合并到 `entities/{comic,chapter}/` 的 `model/api/ui/lib` 分段。
- 约束：按稳定领域概念拆开 `comic` 与 `chapter/catalog`；阅读查询 API 与管理写 API 需在实体 API 内按端点命名，不能继续由 `features/comic/management-api.ts` 承载实体 CRUD。
- 验证：实体 UI 不发请求、不调用 Store；业务状态色表仍在 `entities/comic/model`，而不是 shared/ui。

### FSD-09：补齐被错误附着的实体

- [x] **TODO FSD-09**：将 `CategoryDTO` 从 `entities/comic/types.ts` 分离为 `entities/category`；将 `features/{category,tag,history,task}/` 中的稳定 `types.ts` 与只读 API 分别归入 `entities/category`、`entities/tag`、`entities/history`、`entities/task`。
- [x] **TODO FSD-10**：将 `features/media` 之外的 `entities/media/{types,constants,guards}.ts` 维持为 `entities/media`，但把 `VideoPlayer.vue` 的媒体展示责任与阅读流程责任分离：纯播放器进入 `entities/media/ui`，会话协调留在阅读 feature/widget。
- 验证：category/tag/history/task 的页面列表状态不因迁移而成为实体全局 Store；只读模型与用户操作有明确边界。

### FSD-11：拆分当前 features/comic 的多重职责

- [x] **TODO FSD-11**：删除“万能” `features/comic` 切片。`store.ts`、`management-store.ts`、`composables/useComicListState.ts`、`useLibraryFilters.ts`、`useManagementComicFilters.ts` 应按实际归属迁入阅读库页/管理漫画页的 `pages/*/model`；`BatchEditDialog.vue` 及批量编辑提交逻辑拆为 `features/comic-batch-edit`。
- 约束：筛选和分页是页面状态，不得伪装成跨业务 feature；批量编辑作为可触发动作可依赖 comic/tag/category entities。
- 验证：不存在名称宽泛的 `features/comic`，页面互不共享可变 Store 实例。

### FSD-12：将 reader 切片拆成动作与 widget

- [x] **TODO FSD-12**：把 `features/reader` 拆为 `features/reader-navigation`、`features/reader-settings`、`features/reader-interaction`（手势、快捷键、自动隐藏）和 `widgets/reader`（viewport、toolbar、bottom-nav、settings drawer 的组合）。`ReaderPage.vue` 只负责编排。
- 约束：`readerApi`/章节载荷归 `entities/chapter/api` 或 `entities/reader`；`historyApi` 的进度写入不能由 reader Store 直接深层导入 history Store，应经 feature public API 协调。
- 验证：`preload-engine.ts`、`videoPlaybackCoordinator.ts` 的生命周期归 widget/model 或 shared/lib，且取消、释放逻辑维持原样。

### FSD-13：按用户动作拆分管理领域聚合

- [x] **TODO FSD-13**：将 `features/import`、`features/upload`、`features/trash`、`features/recovery` 保留为独立 feature，但各自增加 `model/api/ui` 子段和 `index.ts`，禁止页面深层导入。
- [x] **TODO FSD-14**：将 `features/storage` 划分为 `entities/storage`（类型、查询、状态展示）与 `features/{generate-lq,delete-hq,transcode-media,export-comic}`（提交动作）；`storageService.executeOperation()` 不再是跨动作的万能服务。
- [x] **TODO FSD-15**：将 `features/management/{dlq-api,monitoring-api,settings-api,types}.ts` 按 `entities/{dlq,system-settings,management-task}` 的查询模型和 `features/{retry-dlq,refresh-metadata}` 等实际动作拆开，避免 `management` 变成无边界桶。
- 验证：一个 feature 只暴露一个可描述的用户能力；页面不能同时知道多个底层 API 来完成一个动作。

### FSD-16：保持并规范已经合理的特性切片

- [x] **TODO FSD-16**：保留 `features/chapter-search` 作为独立用户能力，并为其 `components/composables/domain` 重命名为 FSD 标准的 `ui/model/lib`；为 `features/task` 的任务状态显示决定归属（稳定展示归 entity，任务操作归 feature）。
- 验证：所有 feature 都有最小 public API `index.ts`，外部不再从 `components`、`composables`、`domain`、`store.ts` 深层导入。

### FSD-17：按切片迁移样式与资产

- [x] **TODO FSD-17**：`src/styles/pages/**` 的页面样式随对应页面进入 `pages/*/ui/`；仅 design tokens、reset、动画等保留在 `app/styles/`。`src/assets/hero.png` 评估为全局资产则迁至 `shared/assets`，若仅首页使用则进入首页 page slice；`public/{favicon.svg,icons.svg}` 则作为应用级静态资产由 `app` 明确登记。
- 约束：禁止页面样式跨切片选择器覆盖；组件样式与组件同目录。
- 验证：全局 `styles/index.scss` 只聚合全局样式，构建后的视觉回归不变。

### FSD-18：固定 public API 与依赖检查

- [x] **TODO FSD-18**：为每个 slices 添加 `index.ts`；更新 `tsconfig`/Vite alias 为 `@/app`、`@/pages`、`@/widgets`、`@/features`、`@/entities`、`@/shared`，并在 ESLint 中增加 FSD import-rule（层级方向、禁止跨切片深层导入）。
- [x] **TODO FSD-19**：将 `*.test.ts` 随被测模块迁移；`frontend/e2e/**` 仍在项目根，但页面路径、路由名称和可访问性断言须覆盖 FSD 迁移后的懒加载入口。
- 验证：`pnpm check`、`pnpm build`、Vitest、Playwright 和 `git diff --check` 全部通过；不得只用路径替换掩盖循环依赖。

### FSD-20：同步文档，停止维护旧目录说明

- [x] **TODO FSD-20**：实施改造时更新 `frontend/README.md`、`frontend/src/components/README.md`、`docs/frontend/08-frontend-architecture.md`，将当前 `components/layouts/views/services` 说明替换为目标层说明；路由、Store、API 表格必须与实际 public API 一致。
- 验证：仓库内不再把旧结构描述为“当前目录约定”；本清单完成项勾选时附迁移 PR、测试命令与兼容措施。

## 实施顺序与禁区

建议先完成 FSD-01、02、03、04、05、06、07、17、18，再以实体（FSD-08～10）为地基拆 feature（FSD-11～16）。一次提交只迁移一个可闭合的切片；不得在同一提交夹带视觉改版或接口语义变更。迁移期间允许临时兼容 re-export，但必须写明移除批次，且不得让旧目录继续新增代码。

本次审计的结论是：当前 `features` 目录中并非所有内容都是 FSD feature，当前 `components`、`layouts`、`views`、`services` 也不应作为最终顶层目录继续扩张。

跨页面按钮、状态、标题、空态和面板的重复实现，另见[前端公共 UI 收敛 TODO](ui-consolidation-todo.md)；该清单以 `UI-xx` 编号记录具体组件和替换范围。


## 本次实施记录

- 已完成 FSD-01～FSD-20 的目录迁移、入口收口、实体/feature/widget 分层、public API 骨架、alias 与 ESLint 层级检查。
- 兼容措施：保留必要的类型与 API re-export 入口，未删除任何 TODO 条目；运行时接口语义与路由名称保持不变。
- 自检命令：pnpm typecheck、pnpm lint、pnpm test:unit、pnpm build、git diff --check。
