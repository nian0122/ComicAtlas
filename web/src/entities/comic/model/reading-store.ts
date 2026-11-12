import { defineStore } from 'pinia'
import { ref } from 'vue'
import { comicApi } from '../api'
import type { ComicListVO, ComicListQuery } from './types'
import { getApiErrorMessage } from '@/shared/api/http'
export const useReadingCatalog = defineStore('reading-catalog', () => {
  const list = ref<ComicListVO[]>([]),
    total = ref<number | null>(null),
    loading = ref(false),
    error = ref('')
  let generation = 0
  async function fetchPage(query: ComicListQuery, append = false) {
    const current = ++generation
    loading.value = true
    error.value = ''
    try {
      const data = await comicApi.list(query)
      if (current !== generation) return
      list.value = append
        ? [...list.value, ...data.items.filter((item) => !list.value.some((old) => old.id === item.id))]
        : data.items
      total.value = data.total
    } catch (reason) {
      if (current === generation) error.value = getApiErrorMessage(reason, '漫画库加载失败')
    } finally {
      if (current === generation) loading.value = false
    }
  }
  return { list, total, loading, error, fetchPage }
})
