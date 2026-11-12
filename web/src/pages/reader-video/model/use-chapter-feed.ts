import { computed, ref, shallowRef } from 'vue'
import { readerApi, type ChapterResponse } from '@/entities/chapter'
import type { MediaItemInfo } from '@/entities/media'
import { getApiErrorMessage } from '@/shared/api/http'
import { indexOfMedia, playableItems } from './feed'

const MAX_CACHED_CHAPTERS = 6
const MAX_EMPTY_STEPS = 8

interface FeedChapter {
  chapter: ChapterResponse
  items: MediaItemInfo[]
}

/** 只负责章节与播放位置；播放器、手势和路由由页面组合。 */
export function useChapterFeed() {
  const chapter = shallowRef<ChapterResponse>()
  const items = shallowRef<MediaItemInfo[]>([])
  const index = ref(0)
  const loading = ref(true)
  const error = ref('')
  const nextChapter = shallowRef<FeedChapter>()
  const previousChapter = shallowRef<FeedChapter>()
  const cache = new Map<string, Promise<ChapterResponse>>()
  let generation = 0
  let switching = false

  const current = computed(() => items.value[index.value])
  const title = computed(() => chapter.value?.chapter.title ?? '')
  const hasNext = computed(() => index.value < items.value.length - 1 || chapter.value?.nextChapterId != null)
  const hasPrevious = computed(() => index.value > 0 || chapter.value?.previousChapterId != null)
  const nextItem = computed(() => items.value[index.value + 1] ?? nextChapter.value?.items[0])
  const previousItem = computed(() => items.value[index.value - 1] ?? previousChapter.value?.items.at(-1))

  function request(chapterId: string): Promise<ChapterResponse> {
    const cached = cache.get(chapterId)
    if (cached) {
      cache.delete(chapterId)
      cache.set(chapterId, cached)
      return cached
    }
    const pending = readerApi.chapter(chapterId)
    cache.set(chapterId, pending)
    void pending.catch(() => {
      if (cache.get(chapterId) === pending) cache.delete(chapterId)
    })
    while (cache.size > MAX_CACHED_CHAPTERS) {
      const oldest = cache.keys().next().value
      if (!oldest) break
      cache.delete(oldest)
    }
    return pending
  }

  /** 空章节沿阅读顺序跳过，短片模式不会停在黑屏上。 */
  async function resolveAdjacent(startId: string | null, direction: 1 | -1): Promise<FeedChapter | undefined> {
    const visited = new Set<string>()
    let chapterId: string | null = startId
    while (chapterId && !visited.has(chapterId) && visited.size < MAX_EMPTY_STEPS) {
      visited.add(chapterId)
      const data = await request(chapterId)
      const playable = playableItems(data.items)
      if (playable.length) return { chapter: data, items: playable }
      chapterId = direction > 0 ? data.nextChapterId : data.previousChapterId
    }
    return undefined
  }

  function prefetch(): void {
    nextChapter.value = undefined
    previousChapter.value = undefined
    const active = chapter.value
    if (!active) return
    const activeId = active.chapter.id
    if (active.nextChapterId)
      void resolveAdjacent(active.nextChapterId, 1)
        .then((value) => {
          if (chapter.value?.chapter.id === activeId) nextChapter.value = value
        })
        .catch(() => {
          // 真正翻到边界时会重试并给出提示。
        })
    if (active.previousChapterId)
      void resolveAdjacent(active.previousChapterId, -1)
        .then((value) => {
          if (chapter.value?.chapter.id === activeId) previousChapter.value = value
        })
        .catch(() => {
          // 上一章预取失败不影响当前播放。
        })
  }

  async function load(chapterId: string, mediaId?: string | null): Promise<boolean> {
    if (!chapterId || chapterId.length > 128) {
      loading.value = false
      error.value = '章节地址无效'
      return false
    }
    const sequence = ++generation
    loading.value = true
    error.value = ''
    try {
      const data = await request(chapterId)
      if (sequence !== generation) return false
      const playable = playableItems(data.items)
      chapter.value = data
      items.value = playable
      index.value = Math.max(0, indexOfMedia(playable, mediaId))
      if (playable.length) prefetch()
      loading.value = false
      return playable.length > 0
    } catch (reason) {
      if (sequence === generation) {
        error.value = getApiErrorMessage(reason, '章节加载失败')
        loading.value = false
      }
      return false
    }
  }

  function goToMedia(mediaId: string): boolean {
    const position = indexOfMedia(items.value, mediaId)
    if (position < 0) return false
    index.value = position
    return true
  }

  /** 返回 item 表示同章前进，chapter 表示跨章，null 表示已到边界。 */
  async function move(direction: 1 | -1): Promise<'item' | 'chapter' | null> {
    if (switching) return null
    const target = index.value + direction
    if (target >= 0 && target < items.value.length) {
      index.value = target
      return 'item'
    }
    const active = chapter.value
    if (!active) return null
    const adjacentId = direction > 0 ? active.nextChapterId : active.previousChapterId
    if (!adjacentId) return null
    const activeId = active.chapter.id
    switching = true
    try {
      const prefetched = direction > 0 ? nextChapter.value : previousChapter.value
      const resolved = prefetched ?? (await resolveAdjacent(adjacentId, direction))
      if (!resolved || chapter.value?.chapter.id !== activeId) return null
      chapter.value = resolved.chapter
      items.value = resolved.items
      index.value = direction > 0 ? 0 : resolved.items.length - 1
      prefetch()
      return 'chapter'
    } finally {
      switching = false
    }
  }

  return {
    chapter,
    items,
    index,
    loading,
    error,
    current,
    title,
    hasNext,
    hasPrevious,
    nextItem,
    previousItem,
    load,
    move,
    goToMedia,
  }
}
