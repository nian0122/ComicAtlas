import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import { createReadingNavigation, installReadingNavigation } from './reading-navigation'

function createScenario() {
  const emptyPage = { render: () => null }
  const history = createMemoryHistory()
  const push = history.push.bind(history)
  const replace = history.replace.bind(history)
  // 内存历史不生成浏览器的 position，补齐历史适配器的标准位置语义。
  history.push = (path, data) => push(path, { ...data, position: Number(history.state.position ?? 0) + 1 })
  history.replace = (path, data) =>
    replace(path, { ...history.state, ...data, position: Number(history.state.position ?? 0) })
  const router = createRouter({
    history,
    routes: [
      { path: '/', name: 'home', component: emptyPage },
      { path: '/library', name: 'library', component: emptyPage },
      { path: '/history', name: 'history', component: emptyPage },
      { path: '/favorites', name: 'favorites', component: emptyPage },
      { path: '/comic/:id', name: 'comic-detail', component: emptyPage },
      { path: '/reader/:chapterId', name: 'reader', component: emptyPage },
      { path: '/videos/:chapterId', name: 'chapter-videos', component: emptyPage },
    ],
  })
  const uninstall = installReadingNavigation(router)
  return { router, navigation: createReadingNavigation(router), uninstall }
}

describe('阅读会话导航', () => {
  it.each(['/', '/library?keyword=测试&page=3', '/history', '/favorites?tab=MEDIA&order=oldest'])(
    '详情返回完整来源 %s',
    async (sourcePath) => {
      const { router, navigation } = createScenario()
      await router.push(sourcePath)
      await router.push('/comic/7')
      navigation.goToSource()
      await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(router.resolve(sourcePath).path))
      expect(router.currentRoute.value.query).toEqual(router.resolve(sourcePath).query)
    },
  )

  it.each(['/comic/7', '/reader/1', '/videos/1'])('直达 %s 时缺少来源，安全回到漫画库', async (entryPath) => {
    const { router, navigation } = createScenario()
    await router.push(entryPath)
    navigation.goToSource()
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/library'))
  })

  it('反复切换模式并跨章后返回详情与来源，保留搜索与页码', async () => {
    const { router, navigation } = createScenario()
    await router.push('/history')
    await router.push('/comic/7')
    navigation.goToReader(1, { page: '2', search: '番外' })
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('reader'))
    for (const chapterId of [1, 2, 3]) {
      navigation.goToImmersive(chapterId, { page: '4', search: '番外' })
      await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('chapter-videos'))
      navigation.goToReader(chapterId, { page: '4', search: '番外' })
      await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('reader'))
      expect(router.currentRoute.value.query).toEqual({ page: '4', search: '番外' })
    }
    navigation.goToDetail(7)
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/comic/7'))
    navigation.goToSource()
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/history'))
  })

  it('重新装配导航后仍能读取历史状态中的来源', async () => {
    const { router, navigation, uninstall } = createScenario()
    await router.push('/library?categoryId=2')
    await router.push('/comic/7')
    await router.push('/reader/1')
    uninstall()
    installReadingNavigation(router)
    await router.replace('/videos/1')
    navigation.goToSource()
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/library?categoryId=2'))
  })

  it('返回详情时保留入口查询参数和目录锚点', async () => {
    const { router, navigation } = createScenario()
    await router.push('/library')
    await router.push('/comic/7?source=link#catalog')
    navigation.goToReader(1)
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('reader'))
    navigation.goToDetail(7)
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/comic/7?source=link#catalog'))
  })

  it('取消的导航不覆盖原来的会话来源', async () => {
    const { router, navigation } = createScenario()
    await router.push('/history')
    await router.push('/comic/7')
    router.beforeEach((to) => to.name !== 'library')
    await router.push('/library')
    expect(router.currentRoute.value.fullPath).toBe('/comic/7')
    navigation.goToSource()
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/history'))
  })

  it('已有详情与来源位置时跨过中间阅读记录回退', async () => {
    const { router, navigation } = createScenario()
    await router.push('/library')
    await router.replace('/library?keyword=test')
    await router.push('/comic/7')
    await router.push('/reader/1')
    await router.push('/videos/1')
    await router.push('/reader/2')
    const go = vi.spyOn(router, 'go').mockImplementation(() => {})
    navigation.goToDetail(7)
    expect(go).toHaveBeenLastCalledWith(-3)
    navigation.goToSource()
    expect(go).toHaveBeenLastCalledWith(-4)
  })

  it('不采用损坏或站外历史来源', async () => {
    const { router, navigation } = createScenario()
    await router.push('/comic/7')
    router.options.history.state.comicAtlasReadingNavigation = { sourcePath: '//example.com', sourcePosition: 0 }
    navigation.goToSource()
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/library'))
  })

  it('无效漫画和章节编号不会进入错误阅读页', async () => {
    const { router, navigation } = createScenario()
    await router.push('/comic/7')
    navigation.goToReader(0)
    navigation.goToImmersive(Number.NaN)
    expect(router.currentRoute.value.fullPath).toBe('/comic/7')
    navigation.goToDetail(0)
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/library'))
  })
})
