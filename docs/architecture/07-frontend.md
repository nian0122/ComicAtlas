# ComicAtlas 0.2 前端结构

**版本**: 0.3
**日期**: 2026-07-22
**状态**: Canonical

---

## 目录结构

``` 
frontend/src/
├── layouts/
│   ├── ReadingLayout.vue
│   ├── ReaderLayout.vue
│   └── ManagementLayout.vue
├── views/
│   ├── reading/
│   │   ├── HomePage.vue
│   │   ├── LibraryPage.vue
│   │   ├── DetailPage.vue
│   │   ├── ReaderPage.vue
│   │   ├── HistoryPage.vue
│   │   ├── PosterTestPage.vue
│   │   └── reader/
│   │       └── components/
│   │           ├── ReaderViewport.vue
│   │           ├── ReaderPagedViewport.vue
│   │           ├── ReaderImageItem.vue
│   │           ├── ProgressiveImage.vue
│   │           ├── VideoPlayer.vue
│   │           ├── ReaderToolbar.vue
│   │           ├── ReaderToolbarDesktop.vue
│   │           ├── ReaderToolbarMobile.vue
│   │           ├── ReaderBottomNav.vue
│   │           └── ReaderSettingsDrawer.vue
│   └── management/
│       ├── ComicListPage.vue
│       ├── ComicEditPage.vue
│       ├── BatchEditDialog.vue
│       ├── ImportPage.vue
│       ├── TaskPage.vue
│       ├── MetadataPage.vue
│       ├── SettingsPage.vue
│       ├── InterceptPage.vue
│       └── storage/
│           ├── StoragePage.vue
│           ├── StorageSummary.vue
│           ├── StorageTable.vue
│           ├── StorageChapterDrawer.vue
│           ├── StorageToolbar.vue
│           ├── StorageBatchBar.vue
│           └── StorageStatusTag.vue
├── components/
│   ├── reading/
│   │   ├── HeroBanner.vue
│   │   ├── comic/
│   │   │   ├── CatalogTree.vue
│   │   │   ├── CatalogTreeNode.vue
│   │   │   ├── ChapterRow.vue
│   │   │   ├── ComicCard.vue
│   │   │   └── ComicPoster.vue
│   │   └── home/
│   │       ├── HomeHero.vue
│   │       ├── HomeRow.vue
│   │       └── HomeActionGrid.vue
│   ├── management/
│   │   └── task/
│   │       └── TaskCard.vue
│   └── layout/
│       └── TopNav.vue
├── composables/
│   ├── useBreakpoint.ts
│   ├── useMediaQuery.ts
│   └── storage/
│       ├── useStorageFilter.ts
│       ├── useStorageSelection.ts
│       └── useStoragePolling.ts
├── stores/
│   ├── comic-store.ts
│   ├── reader-store.ts
│   ├── reader-settings-store.ts
│   ├── history-store.ts
│   ├── tag-store.ts
│   ├── app-store.ts
│   └── management/
│       ├── comic.ts
│       ├── import.ts
│       ├── category.ts
│       └── storage.ts
├── services/
│   ├── api.ts
│   ├── management.ts
│   ├── reading.ts
│   ├── storage.ts
│   └── media-url.ts
├── router/
│   └── index.ts
├── types/
│   └── index.ts
├── App.vue
└── main.ts
```

---

## Layout

### ReadingLayout.vue

- 顶部导航：ComicAtlas / 首页 / 历史 / 管理
- 内容区：白色/暗色背景
- 底部：可选的轻量 footer
- 所有阅读页面共享此 Layout

### ManagementLayout.vue

- 顶部导航：ComicAtlas / 返回阅读
- 左侧边栏：漫画 / 导入 / 存储 / 元数据 / 设置
- 内容区：管理表单/表格
- 所有管理页面共享此 Layout

---

## Router 结构

```ts
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: ReadingLayout,
      children: [
        { path: '', name: 'home', component: HomePage },
        { path: 'library', name: 'library', component: LibraryPage },
        { path: 'history', name: 'history', component: HistoryPage },
        { path: 'comic/:id', name: 'comic-detail', component: DetailPage },
      ],
    },
    {
      path: '/reader/:chapterId',
      component: ReaderLayout,
      children: [
        { path: '', name: 'reader', component: ReaderPage },
      ],
    },
    {
      path: '/manage',
      component: ManagementLayout,
      children: [
        { path: '', redirect: '/manage/comics' },
        { path: 'comics', name: 'manage-comics', component: ComicListPage },
        { path: 'comics/:id/edit', name: 'manage-comic-edit', component: ComicEditPage },
        { path: 'import', name: 'manage-import', component: ImportPage },
        { path: 'import/tasks', name: 'manage-import-tasks', component: TaskPage },
        { path: 'storage', name: 'manage-storage', component: StoragePage },
        { path: 'metadata', name: 'manage-metadata', component: MetadataPage },
        { path: 'settings', name: 'manage-settings', component: SettingsPage },
      ],
    },
  ],
})
```

---

## 组件边界

- `reading/` 组件不依赖 `management/` 组件。
- `management/` 组件可以复用 `common/` 组件。
- `common/` 组件保持通用，不携带业务状态。

---

## Store 组织

- `reading.ts`：阅读首页、漫画库、详情、阅读器、历史的状态。
- `management/*.ts`：按管理模块拆分。
- `common/app.ts`：主题、全局导航状态等。

---

## 与旧结构的对应

| 旧路径 | 新路径 |
|--------|--------|
| `pages/HomePage.vue` | `views/reading/HomePage.vue` |
| `pages/ComicListPage.vue` | `views/reading/LibraryPage.vue` |
| `pages/ComicDetailPage.vue` | `views/reading/DetailPage.vue` |
| `pages/ReaderPage.vue` | `views/reading/ReaderPage.vue` |
| `pages/HistoryPage.vue` | `views/reading/HistoryPage.vue` |
| `pages/ComicEditPage.vue` | `views/management/ComicEditPage.vue` |
| `pages/ImportPage.vue` | `views/management/ImportPage.vue` |
| `pages/TaskCenterPage.vue` | `views/management/TaskPage.vue` |
| `pages/DashboardPage.vue` | 删除 |
| `pages/OperationLogPage.vue` | 删除 |
