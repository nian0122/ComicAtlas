import { computed, reactive, toRefs } from 'vue'
import type { PageResult } from '@/shared/api/types'
import { getApiErrorMessage } from '@/shared/api/http'

export interface PaginatedListState<TItem, TQuery extends object> {
  list: TItem[]
  total: number
  loading: boolean
  error: string | null
  query: TQuery
}

interface PaginatedListOptions<TItem, TQuery extends object> {
  defaultQuery: TQuery
  fetchPage: (query: TQuery) => Promise<PageResult<TItem>>
  syncCurrentPage?: boolean
}

/** 提供无业务含义的分页、请求竞态和错误状态管理。 */
export function usePaginatedListState<TItem, TQuery extends object>(options: PaginatedListOptions<TItem, TQuery>) {
  const state = reactive({
    list: [],
    total: 0,
    loading: false,
    error: null,
    query: { ...options.defaultQuery },
  }) as unknown as PaginatedListState<TItem, TQuery>
  let requestSequence = 0
  const hasMore = computed(() => state.list.length < state.total)

  function updateQuery(patch: Partial<TQuery>) {
    Object.assign(state.query, patch)
  }

  function resetQuery() {
    state.query = { ...options.defaultQuery } as TQuery
  }

  async function fetchList() {
    const requestId = ++requestSequence
    state.loading = true
    state.error = null
    const query = { ...state.query } as TQuery
    const queryWithTags = query as TQuery & { tags?: unknown }
    if (Array.isArray(queryWithTags.tags)) queryWithTags.tags = [...queryWithTags.tags]
    try {
      const page = await options.fetchPage(query)
      if (requestId !== requestSequence) return
      state.list = (page.records || []) as TItem[]
      state.total = page.total || 0
      if (options.syncCurrentPage && 'page' in state.query && 'current' in page) {
        ;(state.query as TQuery & { page?: number }).page = Math.max(
          1,
          Number(page.current) || Number((state.query as TQuery & { page?: number }).page) || 1,
        )
      }
    } catch (error: unknown) {
      if (requestId !== requestSequence) return
      state.error = getApiErrorMessage(error, '加载列表失败')
      state.list = []
      state.total = 0
    } finally {
      if (requestId === requestSequence) state.loading = false
    }
  }

  async function search(patch: Partial<TQuery>) {
    updateQuery({ ...patch, ...('page' in state.query ? { page: 1 } : {}) } as Partial<TQuery>)
    await fetchList()
  }

  async function nextPage() {
    if (state.list.length >= state.total || !('page' in state.query)) return
    const queryWithPage = state.query as TQuery & { page?: number }
    queryWithPage.page = (queryWithPage.page || 1) + 1
    await fetchList()
  }

  return { ...toRefs(state), hasMore, updateQuery, resetQuery, fetchList, search, nextPage }
}
