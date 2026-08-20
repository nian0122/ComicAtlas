# 07 — 组件设计

**更新日期：** 2026-08-16
**状态：** 与 v2.0 组件清单同步
**维护者：** ComicAtlas 前端组

> 定义可复用组件的职责、位置和接口契约。不涉及 API，只描述组件层。
> 组件实际源码以 `frontend/src/components/` 与 `frontend/src/views/**/components/` 为准。

---

## 组件清单

### 通用组件（`components/`）

| 组件 | 位置 | 复用 |
|------|------|------|
| TopNav | `components/layout/TopNav.vue` | 管理端布局（全局导航） |
| MaterialSymbolIcon | `components/icons/MaterialSymbolIcon.vue` | 全局图标 |
| HeroBanner | `components/reading/HeroBanner.vue` | 阅读端横幅 |
| HomeHero | `components/reading/home/HomeHero.vue` | 首页 Hero |
| HomeRow | `components/reading/home/HomeRow.vue` | 首页内容行（最近阅读/最近导入） |
| HomeActionGrid | `components/reading/home/HomeActionGrid.vue` | 首页操作入口网格 |
| ComicCard | `components/reading/comic/ComicCard.vue` | Library / History |
| ComicPoster | `components/reading/comic/ComicPoster.vue` | 封面海报 |
| CatalogTree | `components/reading/comic/CatalogTree.vue` | Comic Detail（递归目录树） |
| CatalogTreeNode | `components/reading/comic/CatalogTreeNode.vue` | CatalogTree 递归节点 |
| ChapterRow | `components/reading/comic/ChapterRow.vue` | 章节行（非目录树场景） |
| MobileComicDetail | `components/reading/comic/MobileComicDetail.vue` | 移动端详情 |
| TaskCard | `components/management/task/TaskCard.vue` | 导入任务中心 |
| ExportTaskCard | `components/management/task/ExportTaskCard.vue` | 导出任务 |
| RecoveryTaskCard | `components/management/task/RecoveryTaskCard.vue` | 恢复任务 |

### 阅读器组件（`views/reading/reader/components/`）

| 组件 | 职责 |
|------|------|
| ReaderViewport | 阅读器内容视口（滚动模式） |
| ReaderPagedViewport | 翻页模式视口 |
| ReaderImageItem | 单页图片项 |
| ProgressiveImage | 渐进加载图片（HQ/LQ 切换、骨架屏、错误占位） |
| VideoPlayer | 视频页播放（VIDEO 类型媒体） |
| ReaderToolbar / ReaderToolbarDesktop / ReaderToolbarMobile | 工具栏（返回/标题/页码/章节导航/设置），桌面与移动变体 |
| ReaderSettingsDrawer | 阅读设置抽屉（画质/适配/缩放/方向/预加载） |
| ReaderBottomNav | 底部导航 |

### 管理端子页组件（`views/management/`）

| 组件 | 职责 |
|------|------|
| `storage/StorageSummary.vue` | 存储统计概览 |
| `storage/StorageTable.vue` | 漫画级 HQ/LQ 状态表 |
| `storage/StorageToolbar.vue` | 存储列表工具栏（筛选/排序） |
| `storage/StorageBatchBar.vue` | 批量操作栏 |
| `storage/StorageDetailPage.vue` | 章节明细 |
| `storage/StorageChapterDrawer.vue` | 章节抽屉 |
| `storage/StorageStatusTag.vue` | HQ/LQ 状态标签 |
| `dlq/DlqAccessPanel.vue` | DLQ 队列访问面板 |
| `dlq/DlqMessageDialog.vue` | DLQ 消息查看对话框 |
| `BatchEditDialog.vue` | 批量编辑对话框（漫画工作区） |

---

## ComicCard

漫画卡片，用于 Library 列表。

**职责**：
- 渲染封面图（3:4 比例）
- 显示标题、作者
- 显示阅读进度（百分比 + 进度条）
- 点击跳转 Comic Detail

**文件**：`components/reading/comic/ComicCard.vue`

---

## CatalogTree

递归目录树组件（`components/reading/comic/CatalogTree.vue` + `CatalogTreeNode.vue`）。

**职责**：
- 递归渲染 `CatalogNode` 树
- 目录节点可折叠/展开
- 章节节点可点击跳转 Reader

---

## ProgressiveImage

阅读器图片渐进加载组件（`views/reading/reader/components/ProgressiveImage.vue`）。

**职责**：
- HQ/LQ 图片源选择（`qualityMode`：AUTO / HQ_ONLY / LQ_ONLY）
- 加载态骨架屏（保持 aspect-ratio）
- 错误态显示占位 / 重试
- HQ 已删除时提示（HQ_ONLY 模式下 HQ 缺失且存在 LQ 时提示"HQ 已删除，当前为 LQ"）

---

## VideoPlayer

视频页播放组件（`views/reading/reader/components/VideoPlayer.vue`）。

**职责**：渲染 VIDEO 类型媒体项并播放，支持进度记录。

---

## TaskCard

任务卡片（`components/management/task/TaskCard.vue`）。

**职责**：
- 状态标签 + 进度条
- 错误信息（失败时）
- 操作按钮（取消/重试）

---

## TopNav

全局导航（`components/layout/TopNav.vue`）。

**职责**：
- 管理端主导航（漫画工作区 / 导入 / 任务中心 / 存储 / DLQ 等）
- 当前页面高亮
- 与各 Layout（ReadingLayout / ReaderLayout / ManagementLayout）配合渲染 `<router-view>`
