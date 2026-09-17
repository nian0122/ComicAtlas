import { defineStore } from 'pinia'
import { comicApi } from '@/entities/comic'
import { useComicListState } from '@/pages/reading/library/model/useComicListState'

export type { ComicListState } from '@/pages/reading/library/model/useComicListState'

export const useComicStore = defineStore('comic', () =>
  useComicListState({
    fetchPage: async (query) => (await comicApi.list({ ...query, status: 'READY' })).data,
    syncCurrentPage: true,
  }),
)
