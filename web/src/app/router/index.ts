import { createRouter, createWebHistory } from 'vue-router'
import { installReadingNavigation } from '@/features/reading-navigation'
const positions = new Map<string, number>()
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/manage/timers',
      name: 'schedules',
      component: () => import('@/pages/schedules').then((m) => m.SchedulesPage),
    },
    {
      path: '/manage/:section(tasks|trash|storage|reactions|system)?',
      name: 'management',
      component: () => import('@/pages/management').then((m) => m.ManagementPage),
    },
    {
      path: '/',
      component: () => import('@/widgets/reading-shell').then((m) => m.ReadingShell),
      children: [
        { path: '', redirect: { name: 'library' } },
        {
          path: 'library',
          name: 'library',
          component: () => import('@/pages/reading/library').then((m) => m.LibraryPage),
        },
        {
          path: 'history',
          name: 'history',
          component: () => import('@/pages/reading/history').then((m) => m.HistoryPage),
        },
        {
          path: 'favorites',
          name: 'favorites',
          component: () => import('@/pages/reading/favorites').then((m) => m.FavoritesPage),
        },
        {
          path: 'comic/:id',
          name: 'comic-detail',
          component: () => import('@/pages/reading/detail').then((m) => m.DetailPage),
        },
      ],
    },
    { path: '/reader/:chapterId', name: 'reader', component: () => import('@/pages/reader').then((m) => m.ReaderPage) },
    {
      path: '/reader/:chapterId/video',
      name: 'reader-video',
      component: () => import('@/pages/reader-video').then((m) => m.VideoReaderPage),
    },
    { path: '/:pathMatch(.*)*', redirect: '/library' },
  ],
  scrollBehavior(to, _from, saved) {
    if (to.name === 'reader' || to.name === 'reader-video') return { top: 0 }
    return saved || { top: positions.get(to.fullPath) ?? 0 }
  },
})
router.beforeEach((_to, from) => {
  positions.set(from.fullPath, window.scrollY)
})
installReadingNavigation(router)
export default router
