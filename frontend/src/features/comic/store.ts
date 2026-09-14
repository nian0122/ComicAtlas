import { defineStore } from 'pinia'
import { comicApi } from '@/entities/comic/api'
import { useComicListState } from '@/features/comic/composables/useComicListState'

export type { ComicListState } from '@/features/comic/composables/useComicListState'

export const useComicStore = defineStore('comic', () => useComicListState({
  fetchPage: async (query) => (await comicApi.list({ ...query, status: 'READY' })).data,
  syncCurrentPage: true,
}))
