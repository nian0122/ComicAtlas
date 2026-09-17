import { computed, reactive, toRefs } from 'vue'
import type { ComicListQuery, ComicListVO } from '@/entities/comic/model/types'
import type { PageResult } from '@/shared/api/types'
import { getApiErrorMessage } from '@/shared/api/http'

export interface ComicListState {
  list: ComicListVO[]
  total: number
  loading: boolean
  error: string | null
  query: ComicListQuery
}

interface ComicListOptions {
  fetchPage: (query: ComicListQuery) => Promise<PageResult<ComicListVO>>
  syncCurrentPage?: boolean
}

function createDefaultQuery(): ComicListQuery {
  return { page: 1, size: 24, sort: 'createdAt' }
}

/** 共享列表行为；接口与阅读端页码回填策略由各 Store 显式指定。 */
export function useComicListState(options: ComicListOptions) {
  const state = reactive<ComicListState>({
    list: [],
    total: 0,
    loading: false,
    error: null,
    query: createDefaultQuery(),
  })
  let requestSequence = 0
  const hasMore = computed(() => state.list.length < state.total)

  function updateQuery(patch: Partial<ComicListQuery>) {
    Object.assign(state.query, patch)
  }

  function resetQuery() {
    state.query = createDefaultQuery()
  }

  async function fetchList() {
    const requestId = ++requestSequence
    state.loading = true
    state.error = null
    // 固定本次请求的标签，避免用户继续筛选时修改在途请求。
    const query = { ...state.query, tags: state.query.tags ? [...state.query.tags] : undefined }
    try {
      const page = await options.fetchPage(query)
      if (requestId !== requestSequence) return
      state.list = page.records || []
      state.total = page.total || 0
      if (options.syncCurrentPage) {
        state.query.page = Math.max(1, page.current || state.query.page || 1)
      }
    } catch (error: unknown) {
      if (requestId !== requestSequence) return
      state.error = getApiErrorMessage(error, '加载漫画列表失败')
      state.list = []
      state.total = 0
    } finally {
      // 旧请求结束时不能提前隐藏新请求的加载状态。
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

  return { ...toRefs(state), hasMore, updateQuery, resetQuery, fetchList, search, nextPage }
}
