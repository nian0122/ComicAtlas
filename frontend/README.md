# ComicAtlas 前端

基于 Vue 3、TypeScript、Vite、Pinia 和 Element Plus，固定使用 Feature-Sliced Design（FSD）。

[架构规范](../docs/frontend/08-frontend-architecture.md) · [设计系统](../docs/frontend/design-system.md) · [前端工作规则](AGENTS.md) · [测试目录说明](../e2e/README.md)

## 开发与验证

```bash
pnpm install
pnpm dev
pnpm check
```

`pnpm check` 依次执行公共 UI 检查、架构规则回归、类型检查、含 FSD 检查的 ESLint、格式检查、Vitest、Playwright E2E 和生产构建。Playwright 按配置启动或复用本地 Vite 服务。

`pnpm check:fsd` 检查六层目录、依赖方向、同层隔离、public API、相对路径及 Vue 外置样式；不豁免测试。`pnpm lint` 和 `pnpm build` 均前置此检查，违规直接阻断。`pnpm test:architecture` 验证检查器能拦截违规样例。

## 固定目录

```text
src/
├── app/        # 入口、providers、router、根组件、全局样式、跨页面集成测试
├── pages/      # 路由页面及私有 UI、状态、样式
├── widgets/    # 布局、阅读器等组合 UI 与跨 feature 编排
├── features/   # 导入、上传、批量编辑、阅读设置等用户能力
├── entities/   # 稳定实体、基础 API、共享实体状态、实体展示
└── shared/     # HTTP 基础设施、通用 UI、工具与资产
```

依赖只向下；同层切片不互引。实体关系使用限定消费者的 `@x` 入口。跨切片只能引用根 `index.ts`，不能导入内部 `ui/api/model` 的 barrel。

`pages/reading`、`pages/management` 是无代码分组，分组内各页面仍独立；`pages/reader` 是直属切片。页面私有状态和样式不外提到全局目录。

## 复用与样式

- 通用按钮、标题、面板、状态外观和空态使用 [shared/ui](src/shared/ui/README.md)，领域映射留在实体或功能切片。
- 全局设计令牌、基础和主题样式位于 [app/styles](src/app/styles/README.md)，页面 CSS 随各页面放入 `ui/`，组件私有样式就近维护。
- 漫画列表的阅读与管理 Store 保持独立，放在各自页面；分类、标签、历史的共享实体状态放在 `entities/*/model`。
- HTTP 客户端为 `shared/api/http.ts`；基础实体 API 放在 `entities/*/api`，动作编排放在 features。
- 测试随被测模块放置；跨页面 Store 行为对比位于 `app/tests`。
- Vue 组件使用 `<script setup lang="ts">`；Element Plus 按需导入。构建产物与自动生成的 `components.d.ts` 不提交。
