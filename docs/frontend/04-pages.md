# 前端页面与路由

更新日期：2026-09-29。路由事实来源为 `frontend/src/app/router/index.ts`；页面职责和组件归属遵循 [前端 FSD 架构](08-frontend-architecture.md)。

## 阅读端

| 路由 | 页面切片 | 主要职责 |
| --- | --- | --- |
| `/` | `pages/reading/home` | 继续阅读、最近内容和操作入口 |
| `/library` | `pages/reading/library` | 浏览、搜索、筛选与排序 |
| `/history` | `pages/reading/history` | 阅读记录与继续阅读 |
| `/comic/:id` | `pages/reading/detail` | 漫画信息、目录与章节入口 |
| `/reader/:chapterId` | `pages/reader` | 图片与视频混排阅读、进度和阅读设置 |
| `/videos/:chapterId` | `pages/reader` | 章节视频页 |

阅读页面使用 `widgets/reading-layout`，阅读器使用 `widgets/reader`。漫画卡片与目录树属于 `entities/comic`，视频播放与渐进加载属于 `entities/media`。

## 管理端

| 路由 | 页面切片 | 主要职责 |
| --- | --- | --- |
| `/manage` | `pages/management/home` | 管理首页 |
| `/manage/comics` | `pages/management/comics` | 漫画列表与批量操作入口 |
| `/manage/comics/:id` | `pages/management/comic-workspace` | 单本漫画的编辑、结构和存储工作台 |
| `/manage/import` | `pages/management/import` | 单项与批量导入 |
| `/manage/upload` | `pages/management/upload` | 媒体上传和替换 |
| `/manage/tasks` | `pages/management/tasks` | 管理任务进度、取消与重试 |
| `/manage/trash` | `pages/management/trash` | 回收、恢复与永久清理 |
| `/manage/storage` | `pages/management/storage` | 存储统计、筛选和漫画存储明细入口 |
| `/manage/metadata` | `pages/management/metadata` | 分类和标签维护 |
| `/manage/media-reactions` | `pages/management/media-reactions` | 媒体反馈管理 |
| `/manage/ai-analysis` | `pages/management/ai-analysis` | AI 分析 |
| `/manage/dlq` | `pages/management/dlq` | 死信查看、重放和清空 |
| `/manage/settings` | `pages/management/settings` | 阅读与应用设置 |
| `/manage/intercept` | `pages/management/intercept` | 移动设备管理端提示 |

管理页面使用 `widgets/management-layout`。`/manage/comics/:id/edit` 和 `/manage/storage/:id` 重定向到漫画工作台的相应标签；旧的 `/manage/import/tasks` 重定向到统一任务中心。`/manage/workbench`、`/manage/operations` 和 `/manage/status` 保留为列表页兼容跳转。移动阅读设备访问管理路由时由路由守卫转到 `/manage/intercept`。

存储统计页使用完整管理内容区：全库、HQ、LQ 与缩略图为四张对齐的紧凑统计卡，窄屏保留两列。状态和更新时间独立显示，统计刷新仍异步核对容量。筛选在桌面排列为两行，字段、条件清除与排序保留原有行为，条件只影响漫画列表。表格将容量与媒体数量分列展示，点击记录进入漫画存储工作台。
