import { useRouter, type Router, type LocationQueryRaw } from 'vue-router'
const sources = new Set(['/', '/library', '/history', '/favorites'])
const readingModes = new Set(['reader', 'reader-video'])
let source = '/library',
  detail = ''
export function installReadingNavigation(router: Router) {
  router.afterEach((to, from, failure) => {
    if (failure) return
    if (sources.has(from.path) && /^\/(comic|reader)\//.test(to.path)) {
      source = from.fullPath
      detail = ''
    }
    if (to.name === 'comic-detail') detail = to.fullPath
  })
}
export function useReadingNavigation() {
  const router = useRouter()
  /** 在阅读器与短视频之间切换复用同一条历史记录，不额外压栈。 */
  function goToReadingMode(name: 'reader' | 'reader-video', id: string, query: LocationQueryRaw) {
    const target = { name, params: { chapterId: id }, query }
    return readingModes.has(String(router.currentRoute.value.name)) ? router.replace(target) : router.push(target)
  }
  return {
    goToSource: () => router.push(source),
    goToDetail: (id: string) =>
      router.push(id !== '' ? (detail.startsWith(`/comic/${id}`) ? detail : `/comic/${id}`) : source),
    goToReader: (id: string, query: LocationQueryRaw = {}) => goToReadingMode('reader', id, query),
    goToVideo: (id: string, query: LocationQueryRaw = {}) => goToReadingMode('reader-video', id, query),
  }
}
