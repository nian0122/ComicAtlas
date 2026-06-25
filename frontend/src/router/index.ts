import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/comics' },
    {
      path: '/comics',
      name: 'comic-list',
      component: () => import('@/pages/ComicListPage.vue'),
    },
    {
      path: '/comics/:id',
      name: 'comic-detail',
      component: () => import('@/pages/ComicDetailPage.vue'),
      props: true,
    },
    {
      path: '/comics/:id/read',
      name: 'reader',
      component: () => import('@/pages/ReaderPage.vue'),
      props: true,
    },
    {
      path: '/import',
      name: 'import',
      component: () => import('@/pages/ImportPage.vue'),
    },
    {
      path: '/history',
      name: 'history',
      component: () => import('@/pages/HistoryPage.vue'),
    },
    {
      path: '/dashboard',
      name: 'dashboard',
      component: () => import('@/pages/DashboardPage.vue'),
    },
    {
      path: '/operations',
      name: 'operations',
      component: () => import('@/pages/OperationLogPage.vue'),
    },
  ],
})

export default router
