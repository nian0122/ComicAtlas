# ComicAtlas 前端

基于 Vue 3、TypeScript、Vite、Pinia 和 Element Plus 的漫画阅读与管理前端。

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
├── components/     # 可复用展示组件，按阅读端/管理端分组
├── views/          # 路由页面与页面编排
├── layouts/        # 阅读端、管理端布局
├── router/         # 路由配置
├── shared/         # 与业务无关的基础工具、响应式工具和通用类型
├── services/       # HTTP 客户端等基础设施
├── styles/         # 全局样式与设计令牌
└── utils/          # 仅保留尚未归属业务域的通用工具
```

业务代码优先从 `entities` 或 `features` 直接引用；新增类型、API 和状态不再集中放入全局聚合文件。

## 约定

- Vue 组件使用 `<script setup lang="ts">`。
- API 按业务域放在对应 feature，HTTP 基础客户端位于 `services/http.ts`。
- Element Plus 组件按需自动导入，避免全量注册造成首屏包体膨胀。
- 生产构建产物位于 `dist/`，自动生成的 `components.d.ts` 不提交。
