import { defineStore } from 'pinia'
import { comicApi } from '@/entities/comic'
import type { ComicListQuery, ComicListVO } from '@/entities/comic'
import { usePaginatedListState } from '@/shared/lib/composables/usePaginatedListState'

export const useHomeComicStore = defineStore('home-comic', () =>
  usePaginatedListState<ComicListVO, ComicListQuery>({
    defaultQuery: { page: 1, size: 24, sort: 'createdAt' },
    fetchPage: async (query) => (await comicApi.list({ ...query, status: 'READY' })).data,
    syncCurrentPage: true,
  }),
)
