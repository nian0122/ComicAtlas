import { defineStore } from 'pinia'
import { reactive, computed, toRefs } from 'vue'
import { readerApi, historyApi } from '@/services/api'
import { useHistoryStore } from '@/stores/history-store'
import type { PageInfo, ReaderDTO } from '@/types'

export interface ReaderState {
  chapterId: number
  chapterTitle: string
  pages: PageInfo[]
  currentPage: number
  prevChapterId: number | null
  nextChapterId: number | null
  comicId: number
  loading: boolean
  error: string | null
}

export const useReaderStore = defineStore('reader', () => {
  const state = reactive<ReaderState>({
    chapterId: 0,
    chapterTitle: '',
    pages: [],
    currentPage: 1,
    prevChapterId: null,
    nextChapterId: null,
    comicId: 0,
    loading: false,
    error: null,
  })

  const totalPages = computed(() => state.pages.length)
  const hasPrevPage = computed(() => state.currentPage > 1)
  const hasNextPage = computed(() => state.currentPage < state.pages.length)
  const progress = computed(() =>
    state.pages.length > 0 ? Math.round((state.currentPage / state.pages.length) * 100) : 0
  )

  function reset() {
    state.chapterId = 0
    state.chapterTitle = ''
    state.pages = []
    state.currentPage = 1
    state.prevChapterId = null
    state.nextChapterId = null
    state.loading = false
    state.error = null
  }

  async function loadChapter(chId: number) {
    state.loading = true
    state.error = null
    state.chapterId = chId
    state.currentPage = 1

    try {
      const res = await readerApi.chapter(chId)
      const data = res.data as ReaderDTO
      state.chapterTitle = data.chapterTitle
      state.pages = data.pages
      state.prevChapterId = data.prevChapterId
      state.nextChapterId = data.nextChapterId
    } catch (err: any) {
      state.error = err?.response?.data?.message || '加载章节失败'
      state.pages = []
    } finally {
      state.loading = false
    }
  }

  async function restoreProgress() {
    if (!state.comicId || state.pages.length === 0) return
    try {
      const res = await historyApi.get(state.comicId)
      const pageNumber = res.data?.pageNumber
      if (pageNumber && pageNumber >= 1 && pageNumber <= state.pages.length) {
        state.currentPage = pageNumber
      }
    } catch {
      // silent: start from page 1
    }
  }

  async function saveProgress() {
    if (!state.comicId || !state.chapterId) return
    try {
      await historyApi.update(state.comicId, {
        chapterId: state.chapterId,
        pageNumber: state.currentPage,
      })
      useHistoryStore().fetchList().catch(() => {})
    } catch {
      // silent fail on progress sync
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
    saveProgress,
    nextPage,
    prevPage,
    goToPage,
  }
})
