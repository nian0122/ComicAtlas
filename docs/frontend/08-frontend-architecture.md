# 08 — 前端 FSD 架构

更新日期：2026-09-29。本文为当前架构约束；[原迁移清单](fsd-refactoring-todo.md)仅作为历史记录。

ComicAtlas 前端固定采用 Feature-Sliced Design 六层。不得恢复 `views`、`components`、`layouts`、`services` 等顶层目录。规范依据：[FSD 层级依赖](https://fsd.how/docs/reference/layers/)、[public API 与实体交叉入口](https://fsd.how/docs/reference/public-api/)。

## 层级与职责

| 层 | 职责 | 当前示例 |
| --- | --- | --- |
| app | 启动、插件、路由、全局样式、跨页面集成测试 | `app/main.ts`、`app/providers`、`app/router`、`app/styles` |
| pages | 路由页面、页面私有模型与样式 | `pages/reading/library`、`pages/management/comic-workspace`、`pages/reader` |
| widgets | 大块 UI、多个 feature 的组合与协调 | `widgets/reader`、`widgets/reading-layout`、`widgets/management-layout` |
| features | 用户动作和能力 | `features/import`、`features/comic-batch-edit`、`features/reader-settings` |
| entities | 稳定实体、基础 API、共享实体状态与展示 | `entities/comic`、`entities/media`、`entities/history` |
| shared | 无业务含义的基础设施、工具和 UI | `shared/api/http.ts`、`shared/lib`、`shared/ui` |

依赖方向为 `app → pages → widgets → features → entities → shared`，可以跳层向下依赖。禁止向上依赖和同层跨切片依赖。app/shared 没有业务切片，层内按职责分段。

pages 的 reading、management 是无代码的组织分组，不构成切片；分组下的每个页面相互独立。pages/reader 是直属切片。新切片使用 `ui/model/api/lib/config` 等职责分段，避免 components/composables/services 桶目录。

## 公开入口

切片必须有根 `index.ts`，只导出外部需要的能力。切片外禁止导入内部文件，包括内部 `model/index.ts`、`ui/index.ts` 和 `api/index.ts`。内部依赖直接引用实现，不从自己的根 barrel 绕回。

```typescript
import { ComicCard, comicApi } from '@/entities/comic'
import { useReaderSettingsStore } from '@/features/reader-settings'
```

实体之间确有稳定业务关系时，提供限定消费者的 `entities/<提供方>/@x/<消费方>.ts`，说明原因并最小化导出。当前关系包括章节载荷的媒体信息、漫画的反馈和标签类型、任务的漫画查询条件。其他层没有同层互引例外。

shared 的组件经 `shared/ui` 或具体组件目录的 index 引用。单文件 HTTP、格式化、日志等模块本身可以作为公开入口，无需建立聚合整个 shared 的 barrel。

## 状态与业务编排

- 阅读库、管理漫画列表与首页的查询状态属于各自 pages/model，实例独立。
- 阅读与管理使用独立筛选组件、状态模型与后端查询范围，管理列表及批量快照共用管理谓词；详见[漫画列表筛选](../architecture/comic-list-filtering.md)。
- 分类、标签、历史 Store 属于 entities/model，用于维护共享实体数据；reader-navigation 保存进度后通过历史实体同步已加载记录。
- reader-settings 与 reader-navigation 是独立能力。阅读器状态与快捷键的协调放在 widgets/reader/model；纯手势与交互模式保留在 reader-interaction。跨页面的阅读会话导航归 features/reading-navigation，由 app/router 安装，详情页、阅读器和沉浸阅读共用。
- 批量编辑依赖 category/tag/comic 实体，不能通过另一个 feature 获取实体状态。
- 导入、上传、恢复等业务动作在 features 中封装，页面负责路由与组合。
- 阅读端喜欢页面属于 `pages/reading/favorites`，分页与撤销状态放在本切片 model；`features/favorites` 封装阅读查询和标记动作。漫画、章节、媒体三个层级独立，阅读会话将喜欢页记录为返回来源。

## 路由、API 与样式

路由唯一装配点为 `src/app/router/index.ts`，懒加载引用 pages/widgets 根入口；移动端管理拦截继续由路由守卫执行。页面移动不得改变现有路径或跳转行为。

阅读会话通过浏览器每条历史记录中的 `comicAtlasReadingNavigation` 保存来源完整路径、来源位置及漫画详情位置，不使用全局固定返回地址。详情返回首页、漫画库或历史页等实际来源，保留来源查询参数；直达链接缺少来源时回到漫画库。普通阅读和沉浸阅读切换、章节切换使用同一个历史项。阅读器返回已有详情时按历史位置回退，缺少详情入口时替换当前历史项；详情返回来源时跨过阅读会话中的中间记录。历史状态随刷新保留，浏览器前进、后退恢复对应历史项的会话；失败或取消的导航不更新会话。

`shared/api/http.ts` 提供 Axios 客户端与错误解包，阅读接口使用 `/api/**`，管理接口使用 `/api/manage/**`；目录调整不改变端点和数据协议。

全局样式只从 `app/styles/index.scss` 装配。页面 CSS 放在所属切片 ui 下，通过 `<style scoped src="…">` 引入，不允许在 pages 分组下散落共用样式。组件私有样式就近维护，设计值使用全局令牌。

## 自动门禁

- `pnpm check:fsd`：检查六层目录、切片入口、上下层及同层依赖、根 public API、具名实体 @x 和无法解析的本地导入。
- 检查范围包含 TS/JS/Vue、测试、CSS/SCSS；支持静态导入、重导出、类型导入、字面量动态导入、require、Vue 脚本和外置样式。使用语法树避免把注释和普通字符串当作导入。
- `pnpm test:architecture`：用违规夹具验证规则，防止放宽检查器导致架构回退。
- `pnpm lint` 和 `pnpm build` 强制前置 FSD 检查；`pnpm check` 包括规则回归、类型、格式、单元测试、E2E 和构建。

自动检查确保静态依赖边界；业务职责、动态计算的模块路径和样式语义仍需评审。不得使用动态拼接导入绕过边界，也不得用整层忽略或迁移白名单消除错误。

## 本次冻结验证（2026-09-29）

- 通过：`pnpm check:fsd`、`pnpm test:architecture`（7 项）、`pnpm typecheck`、`pnpm lint`、`pnpm test:unit`（53 项）、`pnpm build`、`git diff --check`。
- 完整开发服务 E2E 执行 42 项，36 项通过、6 项失败。随后修正视频用例对一次性 page 参数的时序断言；导入测试拦截器限定实际 `/api/` 请求，避免拦截 FSD 源模块。
- 生产构建预览对导入与视频阅读 7 项专项回归全部通过。开发服务下批量导入跳转仍遇到 Vite 动态模块加载/重载错误，不报告完整 E2E 全绿。
- 其余 4 项失败（桌面/移动目录树、管理任务空态、回收站按钮颜色）在迁移前的 `develop` 基线 `a95d148c` 独立检出后同样复现，保留为已有回归问题。
- `check:ui` 的 3 处原生按钮违规在基线中已存在；全库格式检查也仍有存量问题。因此 `pnpm check` 整体尚不能通过，未执行合并或发布。
