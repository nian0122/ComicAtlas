import { defineStore } from 'pinia'
import { managementComicApi } from '@/entities/comic'
import type { ComicListQuery, ComicListVO } from '@/entities/comic'
import { usePaginatedListState } from '@/shared/lib/composables/usePaginatedListState'

export type ManagementComicState = ReturnType<typeof usePaginatedListState<ComicListVO, ComicListQuery>>

export const useManagementComicStore = defineStore('management-comic', () =>
  usePaginatedListState<ComicListVO, ComicListQuery>({
    defaultQuery: { page: 1, size: 24, sort: 'createdAt' },
    fetchPage: async (query) => (await managementComicApi.list(query)).data,
  }),
)
