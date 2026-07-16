import { createRouter, createWebHistory } from 'vue-router'
import ReadingLayout from '@/layouts/ReadingLayout.vue'
import ReaderLayout from '@/layouts/ReaderLayout.vue'
import ManagementLayout from '@/layouts/ManagementLayout.vue'

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
      path: '/manage',
      component: ManagementLayout,
      children: [
        { path: '', redirect: '/manage/comics' },
        {
          path: 'comics',
          name: 'manage-comics',
          component: () => import('@/views/management/ComicListPage.vue'),
        },
        {
          path: 'comics/:id/edit',
          name: 'manage-comic-edit',
          component: () => import('@/views/management/ComicEditPage.vue'),
          props: true,
        },
        {
          path: 'import',
          name: 'manage-import',
          component: () => import('@/views/management/ImportPage.vue'),
        },
        {
          path: 'import/tasks',
          name: 'manage-import-tasks',
          component: () => import('@/views/management/TaskPage.vue'),
        },
        {
          path: 'storage',
          name: 'manage-storage',
          component: () => import('@/views/management/StoragePage.vue'),
        },
        {
          path: 'metadata',
          name: 'manage-metadata',
          component: () => import('@/views/management/MetadataPage.vue'),
        },
        {
          path: 'settings',
          name: 'manage-settings',
          component: () => import('@/views/management/SettingsPage.vue'),
        },
      ],
    },
    // 旧路由兼容重定向，Phase 4 清理
    { path: '/home', redirect: '/' },
    { path: '/comics', redirect: '/library' },
    { path: '/comics/:id', redirect: (to) => `/comic/${to.params.id}` },
    { path: '/comics/:id/edit', redirect: (to) => `/manage/comics/${to.params.id}/edit` },
    { path: '/tasks', redirect: '/manage/import/tasks' },
    { path: '/import', redirect: '/manage/import' },
    { path: '/dashboard', redirect: '/manage' },
    { path: '/operations', redirect: '/manage' },
  ],
})

export default router
