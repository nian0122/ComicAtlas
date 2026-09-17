# ComicAtlas 前端

基于 Vue 3、TypeScript、Vite、Pinia 和 Element Plus 的漫画阅读与管理前端。

[设计系统](../docs/frontend/design-system.md) · [前端文档导航](../docs/frontend/README.md) · [Feature-Sliced Design 改造 TODO](../docs/frontend/fsd-refactoring-todo.md) · [测试目录说明](../e2e/README.md)

## 开发命令

```bash
pnpm install
pnpm dev
pnpm check
```

`pnpm check` 是提交前统一门禁，会依次执行 TypeScript 类型检查、ESLint、全源码 Prettier 格式检查、Vitest 单元测试、Playwright Chromium E2E 测试和生产构建。E2E 脚本会按 `playwright.config.ts` 自动启动或复用本地 Vite 服务；CI 环境设置 `CI` 后会等待独立服务并启用失败重试。

单独执行 `pnpm format:check` 会检查整个前端源码树，并自动兼容仓库现有的 LF/CRLF 换行风格；`node_modules`、构建产物和测试产物由 `.prettierignore` 排除。需要自动修复格式时执行 `pnpm format`。

## FSD 目录

前端采用六层 Feature-Sliced Design，依赖方向只能由上层指向下层。所有切片通过 `index.ts` 暴露 public API，跨切片不得深层引用内部 `model`、`api`、`ui` 文件。

[样式目录规范](src/styles/README.md) · [公共组件归属](src/components/README.md)

保留 `entities` 和 `features` 的业务聚合结构。全局样式统一从 `styles/index.scss` 加载，抽离的页面 CSS 集中在 `styles/pages/` 并由页面以 `scoped src` 引用；组件私有样式就近维护。公共组件集中在 `components/`，业务专属组件保留在对应 feature。

```text
src/
├── app/            # 入口、providers、router、根组件、全局样式
├── pages/          # 路由页面及页面私有编排
├── widgets/        # 阅读/管理布局、导航、阅读器等大块 UI
├── features/       # 导入、上传、回收、阅读设置等用户能力
├── entities/       # comic、chapter、media、tag、category 等稳定实体
└── shared/         # API 基础设施、通用 UI、工具与资产
```

业务代码优先从 `entities` 或 `features` 直接引用；新增类型、API 和状态不再集中放入全局聚合文件。

## 复用边界

- 管理页头、面板、面板标题、统计卡片/栅格和空态统一使用 `components/management/`。组件只接收展示属性与插槽，页面负责请求、权限条件和操作回调；嵌入工作区的标题保留隐藏规则。
- 状态外观统一使用 `components/status/StatusBadge.vue`；漫画、任务、存储的枚举映射分别留在 `features/comic`、`features/task`、`features/storage`。页面使用领域状态组件，不重复维护颜色与文案。
- 公用组件内收口边框、背景、间距、字体和响应式栅格，全部引用 `styles/` 的设计令牌。树结构、阅读器、表格列宽等业务布局仍由所属页面负责，不通过全局覆盖强行统一。
- 漫画列表分页、查询快照、加载状态和请求竞态处理统一放在 `features/comic/composables/useComicListState.ts`。阅读与管理 Store 保持独立实例，分别指定 API；阅读端强制 `READY` 并回填服务端页码，管理端保留筛选与请求页码。
- 通用文件大小换算位于 `shared/format/bytes.ts`，页面只负责占位文案与精度选择。管理首页的汇总展示仍保留其 MB 起步的业务规则。
- 跨页面的漫画业务规则留在 `features/comic/`，不提升为无业务边界的通用 Store 工厂。`shared/` 只放与业务无关的工具。
- 单元测试与被测能力就近放置；列表 Store 的行为回归集中在 `features/comic/list-store.test.ts`。

## 约定

- Vue 组件使用 `<script setup lang="ts">`。
- API 按业务域放在对应 feature，HTTP 基础客户端位于 `services/http.ts`。
- Element Plus 组件按需自动导入，避免全量注册造成首屏包体膨胀。
- 生产构建产物位于 `dist/`，自动生成的 `components.d.ts` 不提交。
