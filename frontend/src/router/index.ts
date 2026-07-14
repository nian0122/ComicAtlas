import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '@/components/layout/AppLayout.vue'
import ReaderLayout from '@/components/layout/ReaderLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/poster-test',
      name: 'poster-test',
      component: () => import('@/pages/PosterTestPage.vue'),
    },
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '/', redirect: '/home' },
        {
          path: '/home',
          name: 'home',
          component: () => import('@/pages/HomePage.vue'),
        },
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
          path: '/tasks',
          name: 'tasks',
          component: () => import('@/pages/TaskCenterPage.vue'),
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
    },
    {
      path: '/comics/:id/read',
      name: 'reader',
      component: ReaderLayout,
      children: [
        {
          path: '',
          component: () => import('@/pages/ReaderPage.vue'),
          props: true,
        },
      ],
    },
  ],
})

export default router
