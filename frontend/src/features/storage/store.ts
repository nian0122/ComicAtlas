import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getApiErrorMessage } from '@/shared/api/http'
import { storageService } from '@/features/storage/service'
import type {
  ComicStorageItem,
  ChapterStorageItem,
  StorageStats,
  StorageOperation,
  ComicStorageQuery,
} from '@/entities/storage'

export const useStorageStore = defineStore('storage', () => {
  const comicList = ref<ComicStorageItem[]>([])
  const chapters = ref<Record<number, readonly ChapterStorageItem[]>>({})
  const summary = ref<StorageStats | null>(null)
  const summaryLoading = ref(false)
  const summaryError = ref('')
  let summaryRequestVersion = 0
  const busyState = ref<Record<number, boolean>>({})
  const loading = ref(false)
  const serverTotal = ref(0)
  let comicsRequestVersion = 0

  async function loadComics(params?: ComicStorageQuery): Promise<void> {
    const requestVersion = ++comicsRequestVersion
    loading.value = true
    try {
      const data = await storageService.fetchComics(params ?? {})
      if (requestVersion !== comicsRequestVersion) return
      comicList.value = Array.isArray(data?.records) ? data.records : []
      serverTotal.value = data?.total ?? 0
    } catch {
      if (requestVersion !== comicsRequestVersion) return
      comicList.value = []
      serverTotal.value = 0
    } finally {
      if (requestVersion === comicsRequestVersion) loading.value = false
    }
  }

  async function loadSummary() {
    const requestVersion = ++summaryRequestVersion
    summaryLoading.value = true
    summaryError.value = ''
    try {
      const statistics = await storageService.fetchSummary()
      if (requestVersion === summaryRequestVersion) summary.value = statistics
    } catch (error) {
      if (requestVersion === summaryRequestVersion) summaryError.value = getApiErrorMessage(error, '统计读取失败')
    } finally {
      if (requestVersion === summaryRequestVersion) summaryLoading.value = false
    }
  }

  async function refreshStatistics() {
    summaryError.value = ''
    try {
      await storageService.refreshStatistics()
      await loadSummary()
    } catch (error) {
      summaryError.value = getApiErrorMessage(error, '统计刷新提交失败')
    }
  }

  async function loadChapters(comicId: number) {
    if (chapters.value[comicId]) return
    try {
      chapters.value[comicId] = await storageService.fetchChapters(comicId)
    } catch {
      chapters.value[comicId] = []
    }
  }

  async function executeOperation(op: StorageOperation): Promise<void> {
    await storageService.executeOperation(op)
  }

  function replaceRow(item: ComicStorageItem) {
    const idx = comicList.value.findIndex((c) => c.comicId === item.comicId)
    if (idx !== -1) {
      comicList.value[idx] = item
    }
  }

  async function refreshRow(comicId: number) {
    try {
      replaceRow(await storageService.fetchComic(comicId))
    } catch {
      // row refresh failure is non-critical
    }
  }

  function setBusy(comicId: number, busy: boolean) {
    busyState.value = { ...busyState.value, [comicId]: busy }
  }

  function invalidateChapters(comicId: number) {
    delete chapters.value[comicId]
  }

  return {
    comicList,
    chapters,
    summary,
    summaryLoading,
    summaryError,
    busyState,
    loading,
    serverTotal,
    loadComics,
    loadSummary,
    refreshStatistics,
    loadChapters,
    executeOperation,
    replaceRow,
    refreshRow,
    setBusy,
    invalidateChapters,
  }
})
