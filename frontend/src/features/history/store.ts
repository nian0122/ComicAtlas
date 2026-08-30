import { defineStore } from 'pinia'
import { reactive, toRefs } from 'vue'
import { getApiErrorMessage } from '@/services/http'
import { historyApi } from '@/features/history/api'
import type { HistoryVO } from '@/features/history/types'

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
      state.list = res.data
      state.total = state.list.length
      state.page = 1
      state.hasMore = false
    } catch (err: unknown) {
      state.error = getApiErrorMessage(err, '加载阅读历史失败')
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
      const data = res.data
      state.list = data.records
      state.total = data.total || 0
      state.page = data.current || 1
      state.hasMore = state.list.length < state.total
    } catch (err: unknown) {
      state.error = getApiErrorMessage(err, '加载阅读历史失败')
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
      const data = res.data
      const existingIds = new Set(state.list.map((item) => item.comicId))
      state.list.push(...(data.records || []).filter((item) => !existingIds.has(item.comicId)))
      state.total = data.total || state.total
      state.page = data.current || nextPage
      state.hasMore = state.list.length < state.total
    } catch (err: unknown) {
      state.loadMoreError = getApiErrorMessage(err, '加载更多阅读历史失败')
    } finally {
      state.loadingMore = false
    }
  }

  /** 只更新当前已加载的历史项，避免每次翻页都重新请求整页数据。 */
  function updateEntry(comicId: number, chapterId: number, pageNumber: number): void {
    const index = state.list.findIndex((item) => item.comicId === comicId)
    if (index < 0) return
    const item = state.list[index]
    const next = {
      ...item,
      chapterId,
      pageNumber,
      progressPercent: item.totalPages > 0
        ? Math.round((pageNumber / item.totalPages) * 100)
        : item.progressPercent,
      updatedAt: new Date().toISOString(),
    }
    state.list.splice(index, 1)
    state.list.unshift(next)
  }

  /** 阅读器保存成功后同步已加载的本地项，不触发全量刷新。 */
  async function recordProgress(
    comicId: number,
    chapterId: number,
    pageNumber: number
  ): Promise<void> {
    await historyApi.update(comicId, { chapterId, pageNumber })
    updateEntry(comicId, chapterId, pageNumber)
  }

  /** 刷新历史页首屏，避免刷新时再次拉取全部记录。 */
  const refresh = fetchFirstPage

  return {
    ...toRefs(state),
    fetchList,
    fetchFirstPage,
    fetchNextPage,
    updateEntry,
    refresh,
    recordProgress,
  }
})
