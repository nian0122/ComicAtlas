# 04 — 页面规划

**更新日期：** 2026-08-12
**状态：** 与 v1.5 路由结构同步
**维护者：** ComicAtlas 前端组

> 确定每个页面的路由、职责、核心组件和当前状态。路由定义见 `frontend/src/router/index.ts`（阅读端 6 条 + 管理端 8 条主页面路由）。

---

## 页面清单

### 阅读端

| 路由 | 页面 | 说明 |
|------|------|------|
| `/` | Home（首页） | 最近阅读 / 最近导入 / 操作入口 |
| `/library` | Library（漫画库） | 浏览、搜索、筛选、排序 |
| `/comic/:id` | Comic Detail（漫画详情） | 信息、目录树、阅读/操作入口 |
| `/reader/:chapterId` | Reader（阅读器） | 图片/视频混排阅读 |
| `/history` | History（阅读记录） | 继续阅读入口 |
| `/poster-test` | PosterTest（测试页） | 海报/封面渲染测试，非用户功能 |

### 管理端

| 路由 | 页面 | 说明 |
|------|------|------|
| `/manage/comics` | ComicListPage（漫画工作区） | 管理端列表、筛选、批量选择 |
| `/manage/comics/:id/edit` | ComicEditPage（漫画编辑） | 元数据 / 标签 / 分类 / 封面（乐观锁） |
| `/manage/import` | ImportPage（导入） | ZIP / DIRECTORY 导入（批量仅 DIRECTORY；EHENTAI 由 API 支持） |
| `/manage/import/tasks` | TaskPage（任务中心） | 导入任务进度、取消、重试 |
| `/manage/storage` | StoragePage（存储管理） | 存储统计、HQ/LQ 状态、批量操作 |
| `/manage/storage/:id` | StorageDetailPage（章节明细） | 单本漫画的章节级 HQ/LQ 状态与操作 |
| `/manage/metadata` | MetadataPage（元数据管理） | 分类与标签维护 |
| `/manage/dlq` | DeadLetterPage（DLQ 管理） | 死信队列查看 / 重放 / 清空 |
| `/manage/settings` | SettingsPage（设置） | 阅读默认画质等偏好 |
| `/manage/intercept` | InterceptPage（拦截页） | 移动阅读设备访问管理端的提示页 |

---

## Home（首页）

**路由**：`/`

**职责**：
- Hero 展示继续阅读入口
- 最近阅读（HomeRow → `/history`）
- 最近导入（HomeRow → `/library`）
- 操作入口网格（HomeActionGrid）

**关键组件**：`components/reading/home/HomeHero.vue`、`HomeRow.vue`、`HomeActionGrid.vue`

---

## Library（漫画库）

**路由**：`/library`

**职责**：
- 展示所有漫画（封面网格）
- 搜索（keyword，防抖即时搜索）
- 筛选（分类 / 多标签 AND-OR / 状态 / 来源类型）
- 排序切换（createdAt / updatedAt / title / pageCount / lastReadTime）
- 分页
- 点击封面进入详情

**关键组件**：`components/reading/comic/ComicCard.vue`、`ComicPoster.vue`

---

## Comic Detail（漫画详情）

**路由**：`/comic/:id`

**职责**：
- 展示漫画封面 + 基本信息
- 展示标签
- 操作按钮：继续阅读 / 从头开始 / 生成 LQ / 删除
- 展示 Catalog 目录树
- 点击章节 → Reader

**关键组件**：`components/reading/comic/CatalogTree.vue`（+ `CatalogTreeNode.vue` 递归）、`ChapterRow.vue`、`MobileComicDetail.vue`

---

## Reader（阅读器）

**路由**：`/reader/:chapterId`

**职责**：
- 展示漫画内容（图片 HQ/LQ 渐进加载，视频 VideoPlayer 播放）
- 页码显示 + 总页数
- 上一章 / 下一章导航（按 global_order）
- 自动记录阅读进度（chapterId + pageNumber）
- 阅读设置抽屉（画质 HQ/LQ、滚动/翻页模式）
- 双端工具栏（桌面 / 移动）

**关键组件**：`views/reading/reader/components/`：`ReaderViewport.vue`、`ReaderPagedViewport.vue`、`ProgressiveImage.vue`、`ReaderImageItem.vue`、`VideoPlayer.vue`、`ReaderToolbar.vue`（+ Desktop/Mobile 变体）、`ReaderSettingsDrawer.vue`、`ReaderBottomNav.vue`

---

## History（阅读记录）

**路由**：`/history`

**职责**：
- 展示最近阅读的漫画（按时间分组）
- 点击直接进入 Reader 恢复上次位置

**关键组件**：`components/history/`

---

## Import（导入，管理端）

**路由**：`/manage/import`

**职责**：
- 选择来源类型（ZIP 文件 / DIRECTORY 本地目录；EHENTAI 由 API 支持，前端暂未提供选项）
- 输入文件路径（ZIP 文件路径或本地目录路径）
- 批量导入（多条本地目录路径）
- 提交后跳转任务中心

---

## 任务中心（管理端）

**路由**：`/manage/import/tasks`

**职责**：
- 展示所有导入任务
- 按状态分组：进行中 / 失败 / 已完成
- 实时进度条
- 失败任务重试
- 进行中任务取消

**关键组件**：`components/management/task/TaskCard.vue`

---

## 存储管理（管理端）

**路由**：`/manage/storage`、`/manage/storage/:id`

**职责**：
- 存储统计概览（HQ/LQ 总大小与状态）
- 漫画级 HQ/LQ 状态列表、排序与筛选
- 批量操作（LQ 生成 / HQ 删除）
- 章节明细：逐章查看 HQ/LQ 状态、执行操作
- 视频转码、导出、危险区

**关键组件**：`views/management/storage/`：`StorageSummary.vue`、`StorageTable.vue`、`StorageToolbar.vue`、`StorageBatchBar.vue`、`StorageDetailPage.vue`、`StorageChapterDrawer.vue`、`StorageStatusTag.vue`

---

## DLQ 管理（管理端）

**路由**：`/manage/dlq`

**职责**：查看死信队列积压，查看消息 payload，重放（replay）或清空（purge）。

**关键组件**：`views/management/dlq/DlqAccessPanel.vue`、`DlqMessageDialog.vue`

---

## 全局 Layout

现有布局组件（`frontend/src/layouts/`）：

```
ReadingLayout       # 阅读端（Home/Library/Detail/History）
ReaderLayout        # 阅读器（全屏）
ManagementLayout    # 管理端（TopNav + 侧边栏 + <router-view>）
```

导航：`components/layout/TopNav.vue`。移动阅读设备访问 `/manage/*` 时由路由守卫重定向到 `/manage/intercept`（见 `router/index.ts` 的 `beforeEach`）。
