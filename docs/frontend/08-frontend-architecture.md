# 08 — 前端技术架构

> Vue3 项目的技术层设计。确定 Router、Pinia、API、Types、组件层级、目录结构。

---

## 目录结构（目标）

```
frontend/src/
├── App.vue                  # 根组件：全局 Layout
├── main.ts                  # 入口
├── style.css                # CSS 变量
├── router/
│   └── index.ts             # 路由定义
├── stores/                  # Pinia
│   ├── comic-store.ts       # 漫画列表
│   ├── reader-store.ts      # 阅读器状态
│   ├── import-store.ts      # 导入任务
│   ├── history-store.ts     # 阅读记录
│   ├── dashboard-store.ts   # 仪表盘
│   ├── tag-store.ts         # 标签
│   └── app-store.ts         # 全局状态
├── services/
│   └── api.ts               # Axios + 所有 API
├── types/
│   └── index.ts             # 接口定义
├── components/              # 可复用组件
│   ├── layout/
│   │   └── TopNav.vue       # 全局导航栏
│   ├── comic/
│   │   ├── ComicCard.vue
│   │   ├── CatalogTree.vue
│   │   └── SearchBar.vue
│   ├── reader/
│   │   ├── ReaderToolbar.vue
│   │   └── ImageViewer.vue
│   ├── task/
│   │   └── TaskCard.vue
│   └── common/
│       ├── PageHeader.vue
│       └── EmptyState.vue
└── pages/                   # 页面（路由级组件）
    ├── ComicListPage.vue
    ├── ComicDetailPage.vue
    ├── ReaderPage.vue
    ├── ImportPage.vue
    ├── TaskCenterPage.vue
    ├── HistoryPage.vue
    ├── DashboardPage.vue
    └── OperationLogPage.vue
```

---

## Router

当前 8 条路由。需新增 AppLayout 包裹：

```typescript
const routes = [
  {
    path: '/',
    component: AppLayout,       // TopNav + <router-view>
    children: [
      { path: '/', redirect: '/comics' },
      { path: '/comics', ... },
      { path: '/comics/:id', ... },
      { path: '/comics/:id/read', ... },
      { path: '/tasks', ... },
      { path: '/history', ... },
      { path: '/dashboard', ... },
      { path: '/import', ... },     // 不需要在 TopNav 中显示
      { path: '/operations', ... },  // 不需要在 TopNav 中显示
    ]
  }
]
```

---

## Pinia Stores

| Store | 职责 | 状态 |
|-------|------|------|
| `comic-store` | 漫画列表、搜索、分页 | ✅ 已有 |
| `reader-store` | 当前章节、页码、prev/next、HQ 模式 | ✅ 已有 |
| `import-store` | 导入任务列表、创建、取消、重试 | ✅ 已有 |
| `history-store` | 阅读记录列表 | ✅ 已有 |
| `dashboard-store` | 统计数据 | ✅ 已有 |
| `tag-store` | 标签列表 | ✅ 已有 |
| `app-store` | 全局状态（侧边栏等） | ✅ 已有 |

**无需新增 Store**。只新增 Layout 组件和可复用组件。

---

## API 服务层

当前 `api.ts` 结构：

```typescript
export const comicApi   = { list, detail, delete }
export const catalogApi = { tree }
export const readerApi  = { chapter }
export const importApi  = { create, list, detail, status, cancel, retry }
export const historyApi = { list, get, update }
export const lqApi      = { generateComic, generateChapter }
export const dashboardApi = { statistics }
export const operationApi = { list }
export const tagApi     = { list }
```

**无需新增**。完整覆盖当前所有后端接口。

---

## Types

当前 `index.ts` 类型定义：

```typescript
ComicListVO, ComicDetailVO, ChapterVO
CatalogNode, ChapterRef
PageInfo, ChapterPageVO, ReaderDTO
ImportTaskVO, ImportStatusVO
HistoryVO, TagRef
StatisticsVO, OperationLogVO
STATUS_COLOR_MAP
```

**需新增**：
- 无需新增。当前类型完整覆盖。

---

## 组件层级

```
AppLayout
├── TopNav（全局导航）
│   ├── Logo + 标题
│   ├── NavLink: 漫画库
│   ├── NavLink: 任务
│   ├── NavLink: 历史
│   └── NavLink: 仪表盘
└── <router-view>
    ├── ComicListPage
    │   ├── SearchBar
    │   └── ComicCard[]
    ├── ComicDetailPage
    │   └── CatalogTree（递归）
    ├── ReaderPage
    │   ├── ReaderToolbar
    │   └── ImageViewer
    ├── ImportPage
    ├── TaskCenterPage
    │   └── TaskCard[]
    ├── HistoryPage
    │   └── ComicCard[]
    └── DashboardPage
```

---

## 技术栈

| 层 | 技术 |
|----|------|
| 框架 | Vue 3 + Composition API |
| 构建 | Vite |
| 路由 | Vue Router 4 |
| 状态 | Pinia |
| UI 库 | Element Plus |
| HTTP | Axios |
| 语言 | TypeScript strict |

---

## 当前状态 vs 目标

| 项 | 当前 | 目标 |
|----|------|------|
| Layout | 无全局布局，页面独立渲染 | AppLayout + TopNav |
| 组件 | 页面内联，无独立组件文件 | 提取 8 个可复用组件 |
| 导航 | 无顶部导航 | TopNav 4 个主要入口 |
| 响应式 | 部分支持 | 移动端友好（TopNav 折叠 + 卡片网格） |
