import { defineStore } from 'pinia'
import { reactive, toRefs } from 'vue'
import { historyApi } from '@/services/reading'
import type { HistoryPageVO, HistoryVO } from '@/types'

export interface HistoryState {
  list: HistoryVO[]
  loading: boolean
  error: string | null
  total: number
  page: number
  pageSize: number
  hasMore: boolean
  loadingMore: boolean
  loadMoreError: string | null
}

export const useHistoryStore = defineStore('history', () => {
  const state = reactive<HistoryState>({
    list: [],
    loading: false,
    error: null,
    total: 0,
    page: 1,
    pageSize: 20,
    hasMore: true,
    loadingMore: false,
    loadMoreError: null,
  })

  async function fetchList(): Promise<void> {
    state.loading = true
    state.error = null
    state.loadMoreError = null
    try {
      const res = await historyApi.list()
      state.list = (res.data || []) as HistoryVO[]
      state.total = state.list.length
      state.page = 1
      state.hasMore = false
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      state.error = msg || '加载阅读历史失败'
      state.list = []
      state.total = 0
      state.hasMore = false
    } finally {
      state.loading = false
    }
  }

  async function fetchFirstPage(): Promise<void> {
    if (state.loading || state.loadingMore) return
    state.loading = true
    state.error = null
    state.loadMoreError = null
    state.page = 1
    try {
      const res = await historyApi.page(1, state.pageSize)
      const data = res.data as HistoryPageVO
      state.list = data.records || []
      state.total = data.total || 0
      state.page = data.current || 1
      state.hasMore = state.list.length < state.total
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      state.error = msg || '加载阅读历史失败'
      state.list = []
      state.total = 0
      state.hasMore = false
    } finally {
      state.loading = false
    }
  }

  async function fetchNextPage(): Promise<void> {
    if (state.loading || state.loadingMore || !state.hasMore) return
    state.loadingMore = true
    state.loadMoreError = null
    try {
      const nextPage = state.page + 1
      const res = await historyApi.page(nextPage, state.pageSize)
      const data = res.data as HistoryPageVO
      const existingIds = new Set(state.list.map((item) => item.comicId))
      state.list.push(...(data.records || []).filter((item) => !existingIds.has(item.comicId)))
      state.total = data.total || state.total
      state.page = data.current || nextPage
      state.hasMore = state.list.length < state.total
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      state.loadMoreError = msg || '加载更多阅读历史失败'
    } finally {
      state.loadingMore = false
    }
  }

  /**
   * 阅读器滚动/翻页后调用：先更新服务端记录，再刷新本地列表。
   * 这样 Reading Center 永远是实时进度。
   */
  async function recordProgress(
    comicId: number,
    chapterId: number,
    pageNumber: number
  ): Promise<void> {
    await historyApi.update(comicId, { chapterId, pageNumber })
    await fetchFirstPage()
  }

  /** 刷新历史页首屏，避免刷新时再次拉取全部记录。 */
  const refresh = fetchFirstPage

  return {
    ...toRefs(state),
    fetchList,
    fetchFirstPage,
    fetchNextPage,
    refresh,
    recordProgress,
  }
})
