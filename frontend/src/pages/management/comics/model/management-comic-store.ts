import { defineStore } from 'pinia'
import { managementComicApi } from '@/entities/comic'
import type { ManagementComicListQuery, ComicListVO } from '@/entities/comic'
import { usePaginatedListState } from '@/shared/lib/composables/usePaginatedListState'

export type ManagementComicState = ReturnType<typeof usePaginatedListState<ComicListVO, ManagementComicListQuery>>

export const useManagementComicStore = defineStore('management-comic', () =>
  usePaginatedListState<ComicListVO, ManagementComicListQuery>({
    defaultQuery: { page: 1, size: 20, sort: 'createdAt' },
    fetchPage: async (query) => (await managementComicApi.list(query)).data,
  }),
)
