import { defineStore } from 'pinia'
import { reactive, toRefs } from 'vue'
import { historyApi } from '@/services/api'
import type { HistoryVO } from '@/types'

export interface HistoryState {
  list: HistoryVO[]
  loading: boolean
  error: string | null
}

export const useHistoryStore = defineStore('history', () => {
  const state = reactive<HistoryState>({
    list: [],
    loading: false,
    error: null,
  })

  async function fetchList() {
    state.loading = true
    state.error = null
    try {
      const res: any = await historyApi.list()
      state.list = (res.data || []) as HistoryVO[]
    } catch (err: any) {
      state.error = err?.response?.data?.message || '加载阅读历史失败'
      state.list = []
    } finally {
      state.loading = false
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
  ) {
    await historyApi.update(comicId, { chapterId, pageNumber })
    await fetchList()
  }

  /** 快捷别名 */
  const refresh = fetchList

  return {
    ...toRefs(state),
    fetchList,
    refresh,
    recordProgress,
  }
})
