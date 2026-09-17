# 公共 UI 约定

> **TODO UI-01～UI-11**：公共 UI 收敛计划与完整替换范围见[前端公共 UI 收敛 TODO](../../../../docs/frontend/ui-consolidation-todo.md)。迁移期间，禁止在 `pages/`、`widgets/`、`entities/`、`features/` 新增第二套通用按钮、状态标签、页面标题、空态或面板样式。

此目录只放与业务无关的 UI 原语。组件不调用 API、不读取 Pinia Store、不依赖路由，也不包含漫画、章节、任务或存储状态的文案和映射。

- `button/`：唯一的通用按钮、图标按钮、文字操作入口。
- `page-header/`：标题、说明和操作区的通用布局。
- `content-state/`：加载、错误、空态的通用容器。
- `status-badge/`：无领域状态外观；领域切片只提供状态到 `label/tone` 的映射。
- `management-panel/`：当前的面板、标题和统计卡基础组件，后续按 UI-07 统一命名与 variant。
- `icon/`、`logo/`：应用通用图标与品牌展示。

消费方必须经各切片的 `index.ts` public API 引用。若某个组件需要业务 Store、接口或业务状态机，它不属于 `shared/ui`。
