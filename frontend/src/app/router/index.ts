import { createRouter, createWebHistory } from 'vue-router'
import { isMobileReadingDevice } from '@/shared/lib/device/index'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: () => import('@/widgets/reading-layout').then(({ ReadingLayout }) => ReadingLayout),
      children: [
        {
          path: '',
          name: 'home',
          component: () => import('@/pages/reading/home/index').then(({ HomePage }) => HomePage),
        },
        {
          path: 'library',
          name: 'library',
          component: () => import('@/pages/reading/library/index').then(({ LibraryPage }) => LibraryPage),
        },
        {
          path: 'history',
          name: 'history',
          component: () => import('@/pages/reading/history/index').then(({ HistoryPage }) => HistoryPage),
        },
        {
          path: 'comic/:id',
          name: 'comic-detail',
          component: () => import('@/pages/reading/detail/index').then(({ DetailPage }) => DetailPage),
          props: true,
        },
      ],
    },
    {
      path: '/reader/:chapterId',
      component: () => import('@/widgets/reader').then(({ ReaderLayout }) => ReaderLayout),
      children: [
        {
          path: '',
          name: 'reader',
          component: () => import('@/pages/reader/index').then(({ ReaderPage }) => ReaderPage),
          props: true,
        },
      ],
    },
    {
      path: '/manage/intercept',
      name: 'manage-intercept',
    component: () => import('@/pages/management/intercept/index').then(({ InterceptPage }) => InterceptPage),
    },
    {
      path: '/manage',
      component: () => import('@/widgets/management-layout').then(({ ManagementLayout }) => ManagementLayout),
      children: [
        {
          path: '',
          name: 'manage-home',
          component: () => import('@/pages/management/home/index').then(({ ManagementHomePage }) => ManagementHomePage),
        },
        {
          path: 'workbench',
          name: 'manage-workbench',
          redirect: { name: 'manage-comics' },
        },
        {
          path: 'operations',
          name: 'manage-operations',
          redirect: (to) => ({ name: 'manage-comics', query: to.query }),
        },
        {
          path: 'status',
          name: 'manage-status',
          redirect: (to) => ({ name: 'manage-comics', query: to.query }),
        },
        {
          path: 'trash',
          name: 'manage-trash',
          component: () => import('@/pages/management/trash/index').then(({ TrashPage }) => TrashPage),
        },
        {
          path: 'tasks',
          name: 'manage-tasks',
          component: () => import('@/pages/management/tasks/index').then(({ ManagementTasksPage }) => ManagementTasksPage),
        },
        {
          path: 'ai-analysis',
          name: 'manage-ai-analysis',
          component: () => import('@/pages/management/ai-analysis/index').then(({ AiAnalysisPage }) => AiAnalysisPage),
        },
        {
          path: 'upload',
          name: 'manage-upload',
          component: () => import('@/pages/management/upload/index').then(({ MediaUploadPage }) => MediaUploadPage),
        },
        {
          path: 'comics',
          name: 'manage-comics',
          component: () => import('@/pages/management/comics/index').then(({ ComicListPage }) => ComicListPage),
        },
        {
          path: 'comics/:id',
          name: 'manage-comic-workspace',
          component: () => import('@/pages/management/comic-workspace/index').then(({ ComicWorkspacePage }) => ComicWorkspacePage),
          props: true,
        },
        {
          path: 'comics/:id/edit',
          name: 'manage-comic-edit',
          redirect: (to) => ({
            name: 'manage-comic-workspace',
            params: { id: to.params.id },
            query: { ...to.query, tab: 'edit' },
          }),
        },
        {
          path: 'import',
          name: 'manage-import',
          component: () => import('@/pages/management/import/index').then(({ ImportPage }) => ImportPage),
        },
        {
          // 兼容旧书签：旧任务页已删除，只跳转到统一任务中心。
          path: 'import/tasks',
          redirect: (to) => ({ name: 'manage-tasks', query: to.query }),
        },
        {
          path: 'storage',
          name: 'manage-storage',
          component: () => import('@/pages/management/storage/index').then(({ StoragePage }) => StoragePage),
        },
        {
          path: 'storage/:id',
          name: 'manage-storage-detail',
          redirect: (to) => ({
            name: 'manage-comic-workspace',
            params: { id: to.params.id },
            query: { ...to.query, tab: 'storage' },
          }),
        },
        {
          path: 'metadata',
          name: 'manage-metadata',
          component: () => import('@/pages/management/metadata/index').then(({ MetadataPage }) => MetadataPage),
        },
        {
          path: 'dlq',
          name: 'manage-dlq',
          component: () => import('@/pages/management/dlq/index').then(({ DeadLetterPage }) => DeadLetterPage),
        },
        {
          path: 'settings',
          name: 'manage-settings',
          component: () => import('@/pages/management/settings/index').then(({ SettingsPage }) => SettingsPage),
        },
      ],
    },
  ],
})

// 移动端管理后台拦截守卫：
// 移动阅读设备访问 /manage/* 时重定向到拦截提示页，其余路由零开销直接放行。
router.beforeEach((to) => {
  // 1. 非 /manage 路由直接放行（前缀检查放最前，保证阅读端路由零额外开销）
  if (!to.path.startsWith('/manage')) {
    return true
  }
  // 2. 目标已是拦截页本身（也在 /manage/ 下），放行以避免无限重定向循环
  if (to.name === 'manage-intercept') {
    return true
  }
  // 3. DEV 旁路：开发环境下带 ?force-desktop=1 可强制进入管理后台，方便调试
  if (import.meta.env.DEV && to.query['force-desktop'] === '1') {
    return true
  }
  // 4. 移动阅读设备 → 重定向到拦截页
  if (isMobileReadingDevice()) {
    return { name: 'manage-intercept' }
  }
  // 5. 桌面设备正常放行
  return true
})

export default router
