import { useRouter, type LocationQueryRaw, type Router } from 'vue-router'

const HISTORY_KEY = 'comicAtlasReadingNavigation'
const SOURCE_NAMES = new Set(['home', 'library', 'history', 'favorites'])
const SESSION_NAMES = new Set(['comic-detail', 'reader', 'chapter-videos'])

interface ReadingNavigationContext {
  sourcePath: string
  sourcePosition: number | null
  detailPath: string | null
  detailPosition: number | null
}

function positionOf(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null
}

/** 历史状态也可能来自旧版本或外部入口，只接受站内阅读来源。 */
function readContext(router: Router): ReadingNavigationContext | null {
  const storedContext = router.options.history.state[HISTORY_KEY]
  if (!storedContext || typeof storedContext !== 'object' || Array.isArray(storedContext)) return null
  const sourcePath = storedContext.sourcePath
  if (typeof sourcePath !== 'string' || !sourcePath.startsWith('/') || sourcePath.startsWith('//')) return null
  if (!SOURCE_NAMES.has(String(router.resolve(sourcePath).name))) return null
  const detailPath = storedContext.detailPath
  return {
    sourcePath,
    sourcePosition: positionOf(storedContext.sourcePosition),
    detailPath:
      typeof detailPath === 'string' &&
      detailPath.startsWith('/') &&
      !detailPath.startsWith('//') &&
      router.resolve(detailPath).name === 'comic-detail'
        ? detailPath
        : null,
    detailPosition: positionOf(storedContext.detailPosition),
  }
}

function fallbackContext(): ReadingNavigationContext {
  return { sourcePath: '/library', sourcePosition: null, detailPath: null, detailPosition: null }
}

/** 每条阅读历史携带来源和详情位置，刷新、前进和后退时恢复各自的会话。 */
export function installReadingNavigation(router: Router): () => void {
  let previousContext = readContext(router)
  let previousPosition = positionOf(router.options.history.state.position)

  return router.afterEach((to, from, failure) => {
    if (failure) return
    const history = router.options.history
    const currentPosition = positionOf(history.state.position)
    let context: ReadingNavigationContext | null = null
    if (SESSION_NAMES.has(String(to.name))) {
      context = readContext(router)
      if (!context && SOURCE_NAMES.has(String(from.name))) {
        context = {
          sourcePath: from.fullPath,
          sourcePosition: previousPosition,
          detailPath: null,
          detailPosition: null,
        }
      }
      context ??= SESSION_NAMES.has(String(from.name)) ? previousContext : null
      context = { ...(context ?? fallbackContext()) }
      if (to.name === 'comic-detail') {
        context.detailPath = to.fullPath
        context.detailPosition = currentPosition
      }
    }
    history.replace(history.location, { ...history.state, [HISTORY_KEY]: context ? { ...context } : null })
    previousContext = context
    previousPosition = currentPosition
  })
}

function returnTo(router: Router, path: string, targetPosition: number | null): void {
  const currentPosition = positionOf(router.options.history.state.position)
  if (targetPosition !== null && currentPosition !== null && targetPosition < currentPosition) {
    router.go(targetPosition - currentPosition)
  } else {
    void router.replace(path)
  }
}

export function createReadingNavigation(router: Router) {
  function goToSource(): void {
    const context = readContext(router) ?? fallbackContext()
    returnTo(router, context.sourcePath, context.sourcePosition)
  }

  function goToDetail(comicId: number): void {
    if (!Number.isSafeInteger(comicId) || comicId <= 0) {
      goToSource()
      return
    }
    const context = readContext(router)
    const savedDetail = context?.detailPath ? router.resolve(context.detailPath) : null
    const hasMatchingDetail = savedDetail?.params.id === String(comicId)
    const detailPath = hasMatchingDetail
      ? savedDetail.fullPath
      : router.resolve({ name: 'comic-detail', params: { id: comicId } }).fullPath
    const detailPosition = hasMatchingDetail ? (context?.detailPosition ?? null) : null
    returnTo(router, detailPath, detailPosition)
  }

  /** 切章与切换模式复用当前阅读项；从详情或来源页面进入才创建阅读项。 */
  function goToReadingMode(name: 'reader' | 'chapter-videos', chapterId: number, query: LocationQueryRaw): void {
    if (!Number.isSafeInteger(chapterId) || chapterId <= 0) return
    const target = { name, params: { chapterId }, query }
    if (router.currentRoute.value.name === 'reader' || router.currentRoute.value.name === 'chapter-videos') {
      void router.replace(target)
    } else {
      void router.push(target)
    }
  }

  return {
    goToSource,
    goToDetail,
    goToReader: (chapterId: number, query: LocationQueryRaw = {}) => goToReadingMode('reader', chapterId, query),
    goToImmersive: (chapterId: number, query: LocationQueryRaw = {}) =>
      goToReadingMode('chapter-videos', chapterId, query),
  }
}

export function useReadingNavigation() {
  return createReadingNavigation(useRouter())
}
