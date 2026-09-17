import type { ComicListQuery, ComicListVO } from '@/entities/comic'
import { usePaginatedListState } from '@/shared/lib/composables/usePaginatedListState'

interface ComicListOptions {
  fetchPage: Parameters<typeof usePaginatedListState<ComicListVO, ComicListQuery>>[0]['fetchPage']
  syncCurrentPage?: boolean
}

function createDefaultQuery(): ComicListQuery {
  return { page: 1, size: 24, sort: 'createdAt' }
}

/** 共享列表行为；接口与阅读端页码回填策略由各 Store 显式指定。 */
export function useComicListState(options: ComicListOptions) {
  return usePaginatedListState<ComicListVO, ComicListQuery>({
    defaultQuery: createDefaultQuery(),
    ...options,
  })
}

export type ComicListState = ReturnType<typeof useComicListState>
