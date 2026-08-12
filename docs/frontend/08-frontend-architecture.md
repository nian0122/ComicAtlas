# 08 — 前端技术架构

**更新日期：** 2026-08-12
**状态：** 与 v1.5 源码结构同步
**维护者：** ComicAtlas 前端组

> Vue3 项目的技术层设计：Router、Pinia、API、Types、组件层级、目录结构。

---

## 目录结构（当前）

```
frontend/src/
├── App.vue                  # 根组件
├── main.ts                  # 入口
├── style.css                # CSS 变量 / 设计 Token
├── router/
│   └── index.ts             # 路由定义（阅读端 6 + 管理端 8 主路由）
├── layouts/                 # 布局
│   ├── ReadingLayout.vue    # 阅读端（Home/Library/Detail/History）
│   ├── ReaderLayout.vue     # 阅读器（全屏）
│   └── ManagementLayout.vue # 管理端（TopNav + <router-view>）
├── stores/                  # Pinia
│   ├── comic-store.ts       # 漫画列表 / 搜索 / 分页
│   ├── reader-store.ts      # 阅读器状态
│   ├── history-store.ts     # 阅读记录
│   ├── tag-store.ts         # 标签
│   ├── app-store.ts         # 全局状态
│   ├── reader-settings-store.ts  # 阅读偏好（localStorage 持久化）
│   ├── reading.ts           # 阅读端 store barrel
│   └── management/          # 管理端 store
│       ├── comic.ts         # management-comic（漫画工作区）
│       ├── import.ts        # 导入任务
│       ├── storage.ts       # 存储管理
│       ├── category.ts      # 分类
│       └── recovery.ts      # 恢复任务
├── services/                # API 服务层
│   ├── api.ts               # axios 实例 + 全部领域 API + DLQ 类型
│   ├── storage.ts           # storageService / exportService（存储域封装）
│   ├── recovery.ts          # recoveryApi（恢复任务）
│   ├── reading.ts           # 阅读端 API barrel
│   ├── management.ts        # 管理端 API barrel
│   └── media-url.ts         # 媒体 URL 解析
├── types/
│   └── index.ts             # 接口定义（阅读 + 管理 + 存储类型）
├── components/              # 可复用组件
│   ├── layout/TopNav.vue    # 全局导航
│   ├── reading/             # 阅读端组件（home / comic / HeroBanner）
│   ├── management/task/     # TaskCard / ExportTaskCard / RecoveryTaskCard
│   ├── history/             # 阅读记录组件
│   └── icons/               # MaterialSymbolIcon
├── views/                   # 页面（路由级组件）
│   ├── reading/             # HomePage / LibraryPage / DetailPage / HistoryPage / ReaderPage / PosterTestPage
│   │   └── reader/components/  # ReaderViewport / ProgressiveImage / VideoPlayer 等
│   └── management/          # ComicListPage / ComicEditPage / ImportPage / TaskPage /
│                            # storage/ / dlq/ / MetadataPage / SettingsPage / InterceptPage
└── utils/
    ├── device.ts            # 移动阅读设备判定（isMobileReadingDevice）
    └── preload-engine.ts    # 阅读器预加载引擎
```

---

## Router

路由定义在 `frontend/src/router/index.ts`，共 14 条主页面路由（阅读端 6 + 管理端 8），另含管理端移动拦截页 `/manage/intercept` 与存储详情子页 `/manage/storage/:id`：

```typescript
// 阅读端
{ path: '/',              name: 'home',            ReadingLayout  }
{ path: '/library',       name: 'library',         ReadingLayout  }
{ path: '/history',       name: 'history',         ReadingLayout  }
{ path: '/comic/:id',     name: 'comic-detail',    ReadingLayout  }
{ path: '/reader/:chapterId', name: 'reader',      ReaderLayout   }
{ path: '/poster-test',   name: 'poster-test' }        // 测试页
// 管理端
{ path: '/manage/intercept', name: 'manage-intercept' }  // 移动端拦截
{ path: '/manage',        ManagementLayout
  ├── /manage/comics           manage-comics
  ├── /manage/comics/:id/edit  manage-comic-edit
  ├── /manage/import           manage-import
  ├── /manage/import/tasks     manage-import-tasks
  ├── /manage/storage          manage-storage
  ├── /manage/storage/:id      manage-storage-detail
  ├── /manage/metadata         manage-metadata
  ├── /manage/dlq              manage-dlq
  └── /manage/settings         manage-settings }
```

移动端守卫：`router.beforeEach` 对 `/manage/*` 前缀做移动阅读设备判定（`isMobileReadingDevice`），命中则重定向到 `/manage/intercept`；DEV 下可带 `?force-desktop=1` 旁路。

---

## Pinia Stores

| Store | 文件 | 职责 |
|-------|------|------|
| `comic` | `stores/comic-store.ts` | 漫画列表、搜索、筛选、分页 |
| `reader` | `stores/reader-store.ts` | 当前章节、页码、prev/next |
| `reader-settings` | `stores/reader-settings-store.ts` | 阅读偏好（画质/适配/缩放/方向/预加载） |
| `history` | `stores/history-store.ts` | 阅读记录 |
| `tag` | `stores/tag-store.ts` | 标签 |
| `app` | `stores/app-store.ts` | 全局状态 |
| `management-comic` | `stores/management/comic.ts` | 漫画工作区（列表/编辑/批量） |
| `import` | `stores/management/import.ts` | 导入任务 |
| `storage` | `stores/management/storage.ts` | 存储管理 |
| `category` | `stores/management/category.ts` | 分类 |
| `recovery` | `stores/management/recovery.ts` | 恢复任务 |

`stores/reading.ts` 为阅读端 store barrel（统一导出阅读端 stores）。

---

## API 服务层

`services/api.ts` 创建 axios 实例（`baseURL: '/api'`，响应拦截器统一解包 `{ code, data }`），按域导出：

| API 对象 | 接口域 |
|----------|--------|
| `comicApi` | `/comics`（list/detail/delete/metadata/tags/batch） |
| `catalogApi` | `/comics/{id}/catalog` |
| `readerApi` | `/chapters/{id}` |
| `importApi` | `/tasks/import`（create/list/detail/status/cancel/retry/batch） |
| `directoryScanApi` | `/tasks/directory-scan` |
| `historyApi` | `/history` |
| `tagApi` | `/tags` |
| `categoryApi` | `/categories` |
| `lqApi` | `/storage/lq/*`（generateComic / generateChapter） |
| `hqApi` | `/storage/delete-hq/*`（deleteComic / deleteChapter） |
| `exportApi` | `/storage/export/*`（create/list/get/download/open） |
| `adminApi` | `/storage/stats`、`/admin/storage/*`、`/storage/transcode/*`、`/admin/dlq/*` |
| `settingsApi` | `/settings` |

存储域封装在 `services/storage.ts`：`storageService`（fetchComics / fetchSummary / fetchComic / fetchChapters / executeOperation / transcodeVideos）+ `exportService`。恢复任务在 `services/recovery.ts`（`recoveryApi`）。`services/reading.ts` 与 `services/management.ts` 分别为阅读端、管理端 API barrel。

---

## Types

`types/index.ts` 覆盖阅读与管理全部 DTO：

```typescript
// 阅读端
ComicListQuery, ComicListVO, ComicDetailVO, ChapterVO, TagRef
CatalogNode, ChapterRef, MediaType('IMAGE'|'VIDEO'), MediaItemInfo
ReaderDTO, ChapterPageVO
HistoryVO
// 导入/任务
ImportTaskVO, ImportStatusVO, ScanItemVO, ScanResultVO, BatchImportRequest, BatchImportResultVO
// 管理端
ComicMetadataDTO, ComicMetadataUpdateDTO, TagDTO, TagCreateDTO, ComicTagUpdateDTO
BatchComicUpdateDTO, BatchUpdateResultVO, FailedItem
// 存储域
HqStatus, LqStatus, ComicStorageItem, ChapterStorageItem, StorageStats, ComicStorageQuery
StorageOperationType, StorageOperation, ExportTaskVO, OperationSubmitResult
RecoveryTaskVO, DirectoryScanTaskVO
// 展示辅助
STATUS_COLOR_MAP, EXPORT_STATUS_COLOR_MAP, DEFAULT_ASPECT_RATIO
```

---

## 组件层级

```
ReadingLayout
├── HomePage（HomeHero / HomeRow / HomeActionGrid）
├── LibraryPage（ComicCard[] / ComicPoster）
├── HistoryPage
└── DetailPage（CatalogTree → CatalogTreeNode[]，ChapterRow，MobileComicDetail）

ReaderLayout
└── ReaderPage
    ├── ReaderViewport / ReaderPagedViewport
    │   ├── ReaderImageItem → ProgressiveImage
    │   └── VideoPlayer（VIDEO 类型）
    ├── ReaderToolbar（Desktop/Mobile 变体）
    └── ReaderSettingsDrawer / ReaderBottomNav

ManagementLayout
├── ComicListPage（BatchEditDialog）
├── ComicEditPage
├── ImportPage → TaskPage（TaskCard[] / ExportTaskCard / RecoveryTaskCard）
├── StoragePage / StorageDetailPage（storage/ 子组件）
├── MetadataPage
├── DeadLetterPage（dlq/ 子组件）
└── SettingsPage
```

---

## 技术栈

| 层 | 技术 |
|----|------|
| 框架 | Vue 3 + Composition API（`<script setup lang="ts">`） |
| 构建 | Vite |
| 路由 | Vue Router 4 |
| 状态 | Pinia |
| UI 库 | Element Plus |
| HTTP | Axios |
| 语言 | TypeScript strict |
