# 08 — 前端技术架构

**更新日期：** 2026-09-16
**状态：** 与 v2.1 源码结构同步
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
├── entities/                # 跨页面共享实体类型与 API（comic / media / tag）
├── features/                # 按业务能力组织 store、API、composable 与组件
│   ├── comic/、import/、reader/、storage/
│   └── task/、trash/、recovery/、upload/、category/、tag/
├── shared/                  # HTTP 类型、格式化、设备与通用组合逻辑
│   ├── api/types.ts、composables/、format/
├── services/                # 跨领域服务
│   ├── http.ts              # axios 实例、响应解包与统一错误处理
│   └── media-url.ts         # 媒体 URL 解析
├── components/              # 可复用组件
│   ├── layout/TopNav.vue    # 全局导航
│   ├── reading/             # 阅读端组件（home / comic / HeroBanner）
│   ├── management/task/     # TaskCard / ExportTaskCard / RecoveryTaskCard
│   ├── history/             # 阅读记录组件
│   └── icons/               # MaterialSymbolIcon
├── views/                   # 页面（路由级组件）
│   ├── reading/             # HomePage / LibraryPage / DetailPage / HistoryPage / ReaderPage / PosterTestPage
│   │   └── composables/     # 页面级布局与生命周期编排
│   │   └── reader/components/  # ReaderViewport / ProgressiveImage / VideoPlayer 等
│   └── management/          # ComicListPage / ComicEditPage / ImportPage / ComicStructurePage / TaskPage /
│       └── composables/     # 页面级表单、媒体排序与工作区状态
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
  ├── /manage/tasks            manage-tasks
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
| `comic` | `features/comic/store.ts` | 漫画列表、搜索、筛选、分页 |
| `reader` | `features/reader/store.ts` | 当前章节、页码、prev/next |
| `reader-settings` | `features/reader/settings-store.ts` | 阅读偏好（画质/适配/缩放/方向/预加载） |
| `history` | `features/history/store.ts` | 阅读记录 |
| `tag` | `features/tag/store.ts` | 标签 |
| `management-comic` | `features/comic/management-store.ts` | 漫画工作区（列表/编辑/批量） |
| `import` | `features/import/store.ts` | 导入任务 |
| `storage` | `features/storage/store.ts` | 存储管理 |
| `category` | `features/category/store.ts` | 分类 |
| `recovery` | `features/recovery/store.ts` | 恢复任务 |

页面级复杂交互下沉到 `views/**/composables` 或对应 feature composable；页面仅保留路由、数据加载编排和模板组合。当前页面组合逻辑包括 `useLibraryPageLayout`（筛选栏滚动与海报断点）、`useComicEditTags`（标签选择/创建）、`useImportPageForm`（导入表单派生状态）和 `useMediaOrder`（章节媒体排序）。

---

## API 服务层

`services/http.ts` 创建 axios 实例（`baseURL: '/api'`，响应拦截器统一解包 `{ code, data }`），领域 API 位于 `entities/*/api.ts` 和 `features/*/api.ts`：

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

类型按实体、领域和共享协议拆分到 `entities/*`、`features/*/types.ts` 与 `shared/api/types.ts`，不再集中于 `types/index.ts`：

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
├── ComicListPage → features/comic/components/BatchEditDialog.vue
├── ComicEditPage（useComicEditTags）
├── ImportPage（useImportPageForm + useImportScan + PreviewNode） → TaskPage
├── ComicStructurePage（目录结构 + 媒体工作区 + useMediaOrder + 上传对话框）
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
