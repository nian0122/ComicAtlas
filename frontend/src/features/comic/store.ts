import { defineStore } from 'pinia'
import { reactive, computed, toRefs } from 'vue'
import { getApiErrorMessage } from '@/services/http'
import { comicApi } from '@/entities/comic/api'
import type { ComicListVO, ComicListQuery } from '@/entities/comic/types'

export interface ComicListState {
  list: ComicListVO[]
  total: number
  loading: boolean
  error: string | null
  query: ComicListQuery
}

export const useComicStore = defineStore('comic', () => {
  const state = reactive<ComicListState>({
    list: [],
    total: 0,
    loading: false,
    error: null,
    query: {
      page: 1,
      size: 24,
      sort: 'createdAt',
    },
  })
  let requestSequence = 0

  const hasMore = computed(() => state.list.length < state.total)

  function updateQuery(patch: Partial<ComicListQuery>) {
    Object.assign(state.query, patch)
  }

  function resetQuery() {
    state.query = { page: 1, size: 24, sort: 'createdAt' }
  }

  async function fetchList() {
    const requestId = ++requestSequence
    state.loading = true
    state.error = null

    try {
      const res = await comicApi.list({ ...state.query, status: 'READY' })
      if (requestId !== requestSequence) return
      state.list = res.data.records || []
      state.total = res.data.total || 0
      state.query.page = Math.max(1, res.data.current || state.query.page || 1)
    } catch (err: unknown) {
      if (requestId !== requestSequence) return
      state.error = getApiErrorMessage(err, '加载漫画列表失败')
      state.list = []
      state.total = 0
    } finally {
      if (requestId === requestSequence) state.loading = false
    }
  }

  async function search(patch: Partial<ComicListQuery>) {
    updateQuery({ ...patch, page: 1 })
    await fetchList()
  }

  async function nextPage() {
    if (state.list.length >= state.total) return
    state.query.page = (state.query.page || 1) + 1
    await fetchList()
  }

  return {
    ...toRefs(state),
    hasMore,
    updateQuery,
    resetQuery,
    fetchList,
    search,
    nextPage,
  }
})
