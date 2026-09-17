import { defineStore } from 'pinia'
import { managementComicApi } from '@/entities/comic/api/management-api'
import { useComicListState } from '@/pages/reading/library/model/useComicListState'

export type { ComicListState as ManagementComicState } from '@/pages/reading/library/model/useComicListState'

export const useManagementComicStore = defineStore('management-comic', () =>
  useComicListState({
    fetchPage: async (query) => (await managementComicApi.list(query)).data,
  }),
)
