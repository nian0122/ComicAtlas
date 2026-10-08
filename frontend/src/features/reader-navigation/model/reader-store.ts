import { defineStore } from 'pinia'
import { reactive, computed, toRefs } from 'vue'
import { getApiErrorMessage } from '@/shared/api/http'
import { readerApi } from '@/entities/chapter'
import { historyApi } from '@/entities/history'
import type { MediaItemInfo } from '@/entities/media'
import type { MediaReaction } from '@/entities/media'

export interface ReaderState {
  chapterId: number
  chapterTitle: string
  pages: MediaItemInfo[]
  currentPage: number
  prevChapterId: number | null
  nextChapterId: number | null
  reaction: MediaReaction
  comicId: number
  loading: boolean
  error: string | null
  progressSaveError: string | null
}

export const useReaderStore = defineStore('reader', () => {
  const state = reactive<ReaderState>({
    chapterId: 0,
    chapterTitle: '',
    pages: [],
    currentPage: 1,
    prevChapterId: null,
    nextChapterId: null,
    reaction: 'NONE',
    comicId: 0,
    loading: false,
    error: null,
    progressSaveError: null,
  })

  const totalPages = computed(() => state.pages.length)
  const hasPrevPage = computed(() => state.currentPage > 1)
  const hasNextPage = computed(() => state.currentPage < state.pages.length)
  const progress = computed(() =>
    state.pages.length > 0 ? Math.round((state.currentPage / state.pages.length) * 100) : 0,
  )

  function reset() {
    state.chapterId = 0
    state.chapterTitle = ''
    state.pages = []
    state.currentPage = 1
    state.prevChapterId = null
    state.nextChapterId = null
    state.reaction = 'NONE'
    state.loading = false
    state.error = null
    state.progressSaveError = null
  }

  let loadSeq = 0
  async function loadChapter(chId: number, preservePage = false) {
    // 请求序号闸:快速连续切章时 HTTP 响应可能乱序返回,
    // 只允许最新一次请求写入 state,过期响应直接丢弃
    const seq = ++loadSeq
    state.loading = true
    state.error = null
    state.chapterId = chId
    if (!preservePage) {
      state.currentPage = 1
    }

    try {
      const res = await readerApi.chapter(chId)
      if (seq !== loadSeq) return
      const data = res.data
      state.comicId = data.comicId
      state.chapterTitle = data.chapterTitle
      state.pages = data.pages
      state.prevChapterId = data.prevChapterId
      state.nextChapterId = data.nextChapterId
      state.reaction = data.reaction || 'NONE'
    } catch (err: unknown) {
      if (seq !== loadSeq) return
      state.error = getApiErrorMessage(err, '加载章节失败')
      state.pages = []
    } finally {
      if (seq === loadSeq) {
        state.loading = false
      }
    }
  }

  async function restoreProgress() {
    if (!state.comicId || state.pages.length === 0) return
    const seq = loadSeq
    const chapterId = state.chapterId
    try {
      const res = await historyApi.get(state.comicId)
      const historyChapterId = res.data?.chapterId
      const pageNumber = res.data?.pageNumber
      // 历史请求可能晚于切章返回，过期响应不得覆盖新章节的当前页。
      // reading_history 保存的是漫画最近一次阅读位置，只有历史章节与当前章节一致时才能恢复章节内页码。
      if (
        seq === loadSeq &&
        state.chapterId === chapterId &&
        historyChapterId === chapterId &&
        pageNumber &&
        pageNumber >= 1 &&
        pageNumber <= state.pages.length
      ) {
        state.currentPage = pageNumber
      }
    } catch {
      // silent: start from page 1
    }
  }

  function nextPage() {
    if (state.currentPage < state.pages.length) state.currentPage++
  }

  function prevPage() {
    if (state.currentPage > 1) state.currentPage--
  }

  function goToPage(page: number) {
    if (page >= 1 && page <= state.pages.length) state.currentPage = page
  }

  return {
    ...toRefs(state),
    totalPages,
    hasPrevPage,
    hasNextPage,
    progress,
    reset,
    loadChapter,
    restoreProgress,
    nextPage,
    prevPage,
    goToPage,
  }
})
