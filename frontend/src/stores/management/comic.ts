import { defineStore } from 'pinia'
import { reactive, computed, toRefs } from 'vue'
import { managementComicApi } from '@/services/management'
import type { ComicListVO, ComicListQuery } from '@/types'

export interface ManagementComicState {
  list: ComicListVO[]
  total: number
  loading: boolean
  error: string | null
  query: ComicListQuery
}

export const useManagementComicStore = defineStore('management-comic', () => {
  const state = reactive<ManagementComicState>({
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
      const res = await managementComicApi.list({
        ...state.query,
        tags: state.query.tags ? [...state.query.tags] : undefined,
      })
      if (requestId !== requestSequence) return
      state.list = res.data.records || []
      state.total = res.data.total || 0
    } catch (err: unknown) {
      if (requestId !== requestSequence) return
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      state.error = msg || '加载漫画列表失败'
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
