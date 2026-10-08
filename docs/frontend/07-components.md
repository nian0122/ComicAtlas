# 前端组件归属

更新日期：2026-09-29。本文列出当前常用组件的职责与归属；切片间引用必须经过根 `index.ts`，具体规则见 [前端 FSD 架构](08-frontend-architecture.md)。

| 能力 | 当前归属 | 说明 |
| --- | --- | --- |
| 导航与阅读布局 | `widgets/reading-layout` | `TopNav`、`ReadingLayout` |
| 首页内容 | `widgets/home` | `HomeHero`、`HomeRow`、`HomeActionGrid` |
| 漫画展示与目录 | `entities/comic/ui` | `ComicCard`、`ComicPoster`、`CatalogTree`、`ChapterRow` |
| 移动端漫画详情 | `widgets/comic-detail` | `MobileComicDetail` |
| 阅读器框架 | `widgets/reader` | 视口、工具栏、设置抽屉、底部导航 |
| 图片和视频媒体 | `entities/media/ui` | `ProgressiveImage`、`VideoPlayer` |
| 管理布局 | `widgets/management-layout` | 管理端页面框架 |
| 漫画批量编辑 | `features/comic-batch-edit/ui` | `BatchEditDialog` |
| 存储状态 | `entities/storage/ui` | `StorageStatusTag` |
| 存储页面 | `pages/management/storage/ui` | `StorageSummary`、`StorageTable`、`StorageToolbar` |
| DLQ 页面 | `pages/management/dlq/ui` | `DlqMessageDialog`、`DeadLetterPage` |
| 通用外观 | `shared/ui` | 按钮、状态标签、标题、空态、面板和加载指示器 |

页面私有组件留在对应 `pages/*/ui`；跨页面组合放在 `widgets`；用户动作放在 `features`；稳定业务对象展示放在 `entities`。通用组件的视觉值统一读取 `app/styles/tokens.css`，设计依据见 [设计系统](design-system.md)。

统计卡通过 `StatCard` 的 `compact` 属性表达紧凑、顶部对齐的内容；统计网格通过 `StatGrid.mobileColumns` 指定窄屏一列或两列，默认仍为一列。存储页面使用这些公共属性，不在页面复制统计卡主题。筛选字段继续消费公共控件样式，可通过 `--filter-label-size` 和 `--filter-label-color` 调整标签层级。
