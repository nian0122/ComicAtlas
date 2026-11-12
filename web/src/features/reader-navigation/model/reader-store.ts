import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { readerApi } from '@/entities/chapter'
import { historyApi } from '@/entities/history'
import type { MediaItemInfo } from '@/entities/media'
import { getApiErrorMessage } from '@/shared/api/http'
export const useReaderStore = defineStore('cinema-reader', () => {
  const chapterId = ref(''),
    comicId = ref(''),
    chapterTitle = ref(''),
    pages = ref<MediaItemInfo[]>([]),
    currentPage = ref(1),
    prevChapterId = ref<string | null>(null),
    nextChapterId = ref<string | null>(null),
    loading = ref(false),
    error = ref<string | null>(null),
    progressSaveError = ref<string | null>(null),
    progressLoadError = ref<string | null>(null)
  const totalPages = computed(() => pages.value.length)
  let generation = 0
  let prepared:
    | {
        id: string
        expires: number
        timer: ReturnType<typeof setTimeout>
        controller: AbortController
        result: ReturnType<typeof readerApi.chapter>
      }
    | undefined
  function discardPreparedChapter() {
    clearTimeout(prepared?.timer)
    prepared?.controller.abort()
    prepared = undefined
  }
  function prepareChapter(id: string) {
    if (prepared?.id === id && prepared.expires > Date.now()) return prepared.result
    discardPreparedChapter()
    const controller = new AbortController()
    const result = readerApi.chapter(id, AbortSignal.any([controller.signal, AbortSignal.timeout(5000)]))
    const expires = Date.now() + 60_000
    const timer = setTimeout(discardPreparedChapter, 60_000)
    prepared = { id, expires, timer, controller, result }
    return result
  }
  async function loadChapter(id: string) {
    const current = ++generation
    chapterId.value = id
    loading.value = true
    error.value = null
    pages.value = []
    comicId.value = ''
    currentPage.value = 1
    progressSaveError.value = null
    progressLoadError.value = null
    prevChapterId.value = null
    nextChapterId.value = null
    const preparation = prepared?.id === id && prepared.expires > Date.now() ? prepared.result : undefined
    if (preparation) {
      clearTimeout(prepared?.timer)
      prepared = undefined
    } else discardPreparedChapter()
    try {
      let data = preparation ? await preparation.catch(() => undefined) : undefined
      if (current !== generation) return
      data ??= await readerApi.chapter(id)
      if (current !== generation) return
      comicId.value = data.comicId
      chapterTitle.value = data.chapter.title
      pages.value = data.items
      prevChapterId.value = data.previousChapterId
      nextChapterId.value = data.nextChapterId
    } catch (reason) {
      if (current === generation) error.value = getApiErrorMessage(reason, '章节加载失败')
    } finally {
      if (current === generation) loading.value = false
    }
  }
  async function restoreProgress() {
    const current = generation
    progressLoadError.value = null
    try {
      const data = await historyApi.get(comicId.value)
      if (current === generation && data?.chapterId === chapterId.value && !goToMedia(data.mediaId))
        progressLoadError.value = '记录中的媒体不在当前章节，未覆盖原阅读位置'
    } catch (reason) {
      if (current === generation) progressLoadError.value = getApiErrorMessage(reason, '阅读位置加载失败')
    }
  }
  function goToPage(page: number) {
    if (Number.isInteger(page) && page >= 1 && page <= pages.value.length) currentPage.value = page
  }
  function goToMedia(id: string) {
    const index = pages.value.findIndex((media) => media.id === id)
    if (index < 0) return false
    currentPage.value = index + 1
    return true
  }
  return {
    chapterId,
    comicId,
    chapterTitle,
    pages,
    currentPage,
    prevChapterId,
    nextChapterId,
    loading,
    error,
    progressSaveError,
    progressLoadError,
    totalPages,
    loadChapter,
    prepareChapter,
    discardPreparedChapter,
    restoreProgress,
    goToPage,
    goToMedia,
  }
})
