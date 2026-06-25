import { defineStore } from 'pinia'
import { ref } from 'vue'
import { comicApi } from '@/services/api'
import type { ComicListVO, ComicListQuery } from '@/types'

export const useComicStore = defineStore('comic', () => {
  const list = ref<ComicListVO[]>([])
  const total = ref(0)
  const loading = ref(false)
  const query = ref<ComicListQuery>({ page: 1, size: 20, sort: 'createdAt' })

  async function fetchList() {
    loading.value = true
    const res: any = await comicApi.list(query.value)
    list.value = res.data.records
    total.value = res.data.total
    loading.value = false
  }
  return { list, total, loading, query, fetchList }
})
