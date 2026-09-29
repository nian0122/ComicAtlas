# 前端 FSD 工作规则

前端架构固定为 Feature-Sliced Design，不能改回按技术类型平铺的目录。

- 六层：`app → pages → widgets → features → entities → shared`。跨层只能向下；同层切片禁止互引，包括通过 public API。
- 切片对外入口只能是根 `index.ts`；内部导入使用相对路径，避免绕回自己的 barrel 形成循环。
- 实体确有稳定业务关系时，可提供 `entities/<提供方>/@x/<消费方>.ts`，只导出消费者需要的类型或组件，并说明业务原因。其他层不得使用该例外。
- `reading`、`management` 只是 pages 分组，不共享模块；`pages/reader` 是独立页面切片。跨页面组合测试放在 `app/tests`，不为测试建立切片间依赖。
- 页面状态放在 page/model；共享实体缓存与基础 CRUD 放在 entity/model、entity/api；用户动作放在 features；多个 feature 的协调放在 pages/widgets。
- shared 不认识业务实体，通用组件经 `shared/ui` 或组件目录的 index 引用；通用 API 和工具可以使用单文件公开模块。
- 全局样式只由 `app/styles/index.scss` 装配；页面样式放在本切片 `ui/`，通过 scoped src 引入。
- 架构检查使用 `pnpm check:fsd`，规则回归使用 `pnpm test:architecture`。`pnpm lint` 和 `pnpm build` 强制前置检查，提交前执行 `pnpm check` 与 `git diff --check`。
- 结构调整保持路由、API 协议和用户行为稳定，同步更新 public API、相关测试和架构文档。
