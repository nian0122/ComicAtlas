import { createPinia, setActivePinia } from 'pinia'
import { createApp } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import { useReaderStore } from '@/features/reader-navigation'
import { useReaderNavigation } from './useReaderNavigation'

function createNavigationScenario() {
  const emptyPage = { render: () => null }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: emptyPage },
      { path: '/library', name: 'library', component: emptyPage },
      { path: '/history', name: 'history', component: emptyPage },
      { path: '/comic/:id', name: 'comic-detail', component: emptyPage },
      { path: '/reader/:chapterId', name: 'reader', component: emptyPage },
    ],
  })
  const pinia = createPinia()
  setActivePinia(pinia)
  const application = createApp(emptyPage)
  application.use(pinia)
  application.use(router)
  const readerStore = useReaderStore()
  const navigation = application.runWithContext(() => useReaderNavigation())
  return { router, readerStore, navigation }
}

describe('阅读器返回路径', () => {
  it('漫画库进入详情并切章后，依次返回详情和漫画库', async () => {
    const { router, readerStore, navigation } = createNavigationScenario()
    await router.push('/library')
    await router.push('/comic/296')
    await router.push('/reader/311?page=1')
    readerStore.comicId = 296
    readerStore.nextChapterId = 312

    navigation.goNextChapter()
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/reader/312?page=1'))
    // 内存路由不填充 back；模拟浏览器历史中保留的详情页入口。
    router.options.history.state.back = '/comic/296'
    navigation.goToCatalog()
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/comic/296'))
    router.back()
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/library'))
  })

  it('从历史页直达阅读器时，返回详情后再回到历史页', async () => {
    const { router, readerStore, navigation } = createNavigationScenario()
    await router.push('/history')
    await router.push('/reader/311?page=1')
    readerStore.comicId = 296
    router.options.history.state.back = '/history'

    navigation.goBack()
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/comic/296'))
    router.back()
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/history'))
  })

  it('搜索结果阅读时切章保留搜索关键词', async () => {
    const { router, readerStore, navigation } = createNavigationScenario()
    await router.push('/reader/311?page=1&search=番外')
    readerStore.comicId = 296
    readerStore.nextChapterId = 312

    navigation.goNextChapter()
    await vi.waitFor(() =>
      expect(router.currentRoute.value.fullPath).toBe('/reader/312?page=1&search=%E7%95%AA%E5%A4%96'),
    )
  })
})
