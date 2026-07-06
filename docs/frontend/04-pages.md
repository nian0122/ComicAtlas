# 04 — 页面规划

> 确定每个页面的路由、职责、核心组件和当前状态。

---

## 页面清单

| 路由 | 页面 | 当前状态 |
|------|------|----------|
| `/comics` | Library | ⚠️ 基础可用，需优化 |
| `/comics/:id` | Comic Detail | ⚠️ 可用，CatalogTree 已接 |
| `/comics/:id/read` | Reader | ⚠️ 基本可用，缺交互 |
| `/import` | Import | ✅ 可用 |
| `/tasks` | Task Center | ✅ 可用 |
| `/history` | History | ⚠️ 基础可用 |
| `/dashboard` | Dashboard | ✅ 可用 |
| `/operations` | Operations | ✅ 可用 |

---

## Library（漫画列表）

**路由**：`/comics`（默认首页）

**职责**：
- 展示所有漫画（封面网格）
- 搜索（标题/作者/标签，300ms 即时搜索）
- 标签筛选
- 排序切换
- 分页
- 点击封面进入详情
- 显示阅读进度

**当前状态**：⚠️ 基础功能可用，但缺少：
- 响应式布局优化
- 封面加载骨架屏
- 空状态引导（"还没有漫画，去导入"）
- 进度条在卡片上的可视化

**关键组件**：
- SearchBar（搜索 + 标签筛选 + 排序）
- ComicCard（封面 + 标题 + 作者 + 进度）
- Pagination

---

## Comic Detail（漫画详情）

**路由**：`/comics/:id`

**职责**：
- 展示漫画封面 + 基本信息
- 展示标签
- 展示阅读进度
- 操作按钮：继续阅读 / 从头开始 / 生成LQ / 删除
- 展示 Catalog 目录树
- 点击章节 → Reader

**当前状态**：⚠️ 可用，CatalogTree 已递归渲染。待改进：
- 移动端折叠目录
- 已读/未读状态标识
- 删除确认更友好

**关键组件**：
- CoverImage
- ComicMeta（标题/作者/信息）
- TagList
- ActionButtons
- CatalogTree（递归组件）
- ProgressBar

---

## Reader（阅读器）

**路由**：`/comics/:id/read?chapterId=&page=`

**职责**：
- 展示漫画图片（HQ/LQ 切换）
- 页码显示 + 总页数
- 上一章 / 下一章导航
- 自动记录阅读进度（间隔 5 页同步）
- 返回漫画详情
- 阅读设置抽屉（HQ/LQ 切换）

**当前状态**：⚠️ 核心阅读可用，但交互待加强：
- 无键盘快捷键
- 无预加载下一页
- 无连续滚动模式
- 移动端无触摸翻页

**关键组件**：
- ReaderToolbar（返回/标题/页码/章节导航/设置）
- ImageViewer（图片展示 + 骨架屏）
- SettingsDrawer

---

## Import（导入）

**路由**：`/import`

**职责**：
- 选择来源类型（ZIP / DIRECTORY）
- 输入文件路径
- 提交导入任务
- 引导用户去 Task Center 查看进度

**当前状态**：✅ 可用

**关键组件**：
- SourceTypeSelector
- PathInput
- SubmitButton

---

## Task Center（任务中心）

**路由**：`/tasks`

**职责**：
- 展示所有导入任务
- 按状态分组：进行中 / 失败 / 已完成
- 实时进度条
- 失败任务重试
- 进行中任务取消
- 自动 3 秒轮询

**当前状态**：✅ 可用

**关键组件**：
- TaskGroup（按状态分组）
- TaskCard（进度条 + 状态标签 + 操作按钮）

---

## History（阅读记录）

**路由**：`/history`

**职责**：
- 展示最近阅读的漫画
- 点击直接进入 Reader 恢复上次位置

**当前状态**：⚠️ 基础可用

**关键组件**：
- HistoryList

---

## Dashboard（仪表盘）

**路由**：`/dashboard`

**职责**：统计概览（漫画数、页数、标签数、存储占用、今日导入）

**当前状态**：✅ 可用

---

## Operations（操作日志）

**路由**：`/operations`

**职责**：运维查询日志

**当前状态**：✅ 可用

---

## 全局 Layout 需求

当前无全局布局组件，所有页面独立。需新增：

```
AppLayout
├── TopNav     # 导航栏：Logo + 搜索 + 页面链接
└── <router-view>
```

**导航栏链接**：
- 漫画库（/comics）
- 任务（/tasks）
- 历史（/history）
- 仪表盘（/dashboard）

Import 和 Operations 不需要在主导航中，通过 Task Center 和 Dashboard 间接访问即可。
