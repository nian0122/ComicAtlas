# ComicAtlas 前端

基于 Vue 3、TypeScript、Vite、Pinia 和 Element Plus 的漫画阅读与管理前端。

[设计系统](../docs/frontend/design-system.md) · [前端文档导航](../docs/frontend/README.md) · [测试目录说明](../e2e/README.md)

## 开发命令

```bash
pnpm install
pnpm dev
pnpm check
```

`pnpm check` 会依次执行类型检查、ESLint、配置文件格式检查和生产构建。

## 目录约定

```text
src/
├── entities/       # 漫画、媒体、标签等稳定领域模型与实体 API
├── features/       # 按业务能力组织的 API、Store、Composables 和类型
├── components/     # 跨业务共用的品牌、图标和导航组件
├── views/          # 路由页面与页面编排
├── layouts/        # 阅读端、管理端布局
├── router/         # 路由配置
├── shared/         # 与业务无关的基础工具、响应式工具和通用类型
├── services/       # HTTP 客户端等基础设施
└── styles/         # 全局样式与设计令牌
```

业务代码优先从 `entities` 或 `features` 直接引用；新增类型、API 和状态不再集中放入全局聚合文件。

## 复用边界

- 漫画列表分页、查询快照、加载状态和请求竞态处理统一放在 `features/comic/composables/useComicListState.ts`。阅读与管理 Store 保持独立实例，分别指定 API；阅读端强制 `READY` 并回填服务端页码，管理端保留筛选与请求页码。
- 通用文件大小换算位于 `shared/format/bytes.ts`，页面只负责占位文案与精度选择。管理首页的汇总展示仍保留其 MB 起步的业务规则。
- 跨页面的漫画业务规则留在 `features/comic/`，不提升为无业务边界的通用 Store 工厂。`shared/` 只放与业务无关的工具。
- 单元测试与被测能力就近放置；列表 Store 的行为回归集中在 `features/comic/list-store.test.ts`。

## 约定

- Vue 组件使用 `<script setup lang="ts">`。
- API 按业务域放在对应 feature，HTTP 基础客户端位于 `services/http.ts`。
- Element Plus 组件按需自动导入，避免全量注册造成首屏包体膨胀。
- 生产构建产物位于 `dist/`，自动生成的 `components.d.ts` 不提交。
