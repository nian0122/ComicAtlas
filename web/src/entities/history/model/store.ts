import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { historyApi } from '../api'
import type { HistoryVO } from './types'
import { getApiErrorMessage } from '@/shared/api/http'
export const useHistoryStore = defineStore('reading-history', () => {
  const list = ref<HistoryVO[]>([]),
    total = ref(0),
    page = ref(0),
    loading = ref(false),
    loadingMore = ref(false),
    error = ref(''),
    loadMoreError = ref('')
  const hasMore = computed(() => list.value.length < total.value)
  let generation = 0
  async function fetchPage(target: number, append = false) {
    if (append && (loadingMore.value || loading.value || !hasMore.value)) return
    const current = ++generation
    if (append) loadingMore.value = true
    else loading.value = true
    error.value = ''
    loadMoreError.value = ''
    try {
      const data = await historyApi.page(target, 48)
      if (current !== generation) return
      list.value = append
        ? [...list.value, ...data.items.filter((item) => !list.value.some((old) => old.comicId === item.comicId))]
        : data.items
      total.value = data.total
      page.value = target
    } catch (reason) {
      if (current === generation) {
        if (append) loadMoreError.value = getApiErrorMessage(reason, '加载更多失败')
        else error.value = getApiErrorMessage(reason, '阅读历史加载失败')
      }
    } finally {
      if (current === generation) {
        loading.value = false
        loadingMore.value = false
      }
    }
  }
  async function recordProgress(comicId: string, chapterId: string, mediaId: string) {
    const saved = await historyApi.update(comicId, { chapterId, mediaId })
    const item = list.value.find((row) => row.comicId === comicId)
    if (item) {
      Object.assign(item, {
        chapterId: saved.chapterId,
        pageNumber: saved.pageNumber,
        mediaId: saved.mediaId,
        updatedAt: saved.updatedAt,
      })
      list.value = [item, ...list.value.filter((row) => row !== item)]
    }
  }
  function recordProgressKeepalive(value: { comicId: string; chapterId: string; mediaId: string }) {
    void historyApi.update(value.comicId, { chapterId: value.chapterId, mediaId: value.mediaId }, true).catch(() => {})
  }
  return {
    list,
    total,
    page,
    loading,
    loadingMore,
    error,
    loadMoreError,
    hasMore,
    fetchPage,
    recordProgress,
    recordProgressKeepalive,
  }
})
