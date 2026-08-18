import { createRouter, createWebHistory } from 'vue-router'
import ReadingLayout from '@/layouts/ReadingLayout.vue'
import ReaderLayout from '@/layouts/ReaderLayout.vue'
import ManagementLayout from '@/layouts/ManagementLayout.vue'
import { isMobileReadingDevice } from '@/utils/device'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/poster-test',
      name: 'poster-test',
      component: () => import('@/views/reading/PosterTestPage.vue'),
    },
    {
      path: '/',
      component: ReadingLayout,
      children: [
        {
          path: '',
          name: 'home',
          component: () => import('@/views/reading/HomePage.vue'),
        },
        {
          path: 'library',
          name: 'library',
          component: () => import('@/views/reading/LibraryPage.vue'),
        },
        {
          path: 'history',
          name: 'history',
          component: () => import('@/views/reading/HistoryPage.vue'),
        },
        {
          path: 'comic/:id',
          name: 'comic-detail',
          component: () => import('@/views/reading/DetailPage.vue'),
          props: true,
        },
      ],
    },
    {
      path: '/reader/:chapterId',
      component: ReaderLayout,
      children: [
        {
          path: '',
          name: 'reader',
          component: () => import('@/views/reading/ReaderPage.vue'),
          props: true,
        },
      ],
    },
    {
      path: '/manage/intercept',
      name: 'manage-intercept',
      component: () => import('@/views/management/InterceptPage.vue'),
    },
    {
      path: '/manage',
      component: ManagementLayout,
      children: [
        {
          path: '',
          name: 'manage-home',
          component: () => import('@/views/management/ManagementHomePage.vue'),
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
          component: () => import('@/views/management/TrashPage.vue'),
        },
        {
          path: 'tasks',
          name: 'manage-tasks',
          component: () => import('@/views/management/ManagementTasksPage.vue'),
        },
        {
          path: 'upload',
          name: 'manage-upload',
          redirect: { name: 'manage-comics' },
        },
        {
          path: 'comics',
          name: 'manage-comics',
          component: () => import('@/views/management/ComicListPage.vue'),
        },
        {
          path: 'comics/:id',
          name: 'manage-comic-workspace',
          component: () => import('@/views/management/ComicWorkspacePage.vue'),
          props: true,
        },
        {
          path: 'comics/:id/edit',
          name: 'manage-comic-edit',
          redirect: (to) => ({ name: 'manage-comic-workspace', params: { id: to.params.id }, query: { ...to.query, tab: 'edit' } }),
        },
        {
          path: 'import',
          name: 'manage-import',
          component: () => import('@/views/management/ImportPage.vue'),
        },
        {
          // 兼容旧书签：旧任务页已删除，只跳转到统一任务中心。
          path: 'import/tasks',
          redirect: (to) => ({ name: 'manage-tasks', query: to.query }),
        },
        {
          path: 'storage',
          name: 'manage-storage',
          component: () => import('@/views/management/storage/StoragePage.vue'),
        },
        {
          path: 'storage/:id',
          name: 'manage-storage-detail',
          redirect: (to) => ({ name: 'manage-comic-workspace', params: { id: to.params.id }, query: { ...to.query, tab: 'storage' } }),
        },
        {
          path: 'metadata',
          name: 'manage-metadata',
          component: () => import('@/views/management/MetadataPage.vue'),
        },
        {
          path: 'dlq',
          name: 'manage-dlq',
          component: () => import('@/views/management/DeadLetterPage.vue'),
        },
        {
          path: 'settings',
          name: 'manage-settings',
          component: () => import('@/views/management/SettingsPage.vue'),
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
