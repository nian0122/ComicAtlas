import { defineStore } from 'pinia'
import { managementComicApi } from '@/features/comic/management-api'
import { useComicListState } from '@/features/comic/composables/useComicListState'

export type { ComicListState as ManagementComicState } from '@/features/comic/composables/useComicListState'

export const useManagementComicStore = defineStore('management-comic', () => useComicListState({
  fetchPage: async (query) => (await managementComicApi.list(query)).data,
}))
