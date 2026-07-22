import { defineStore } from 'pinia'
import { ref } from 'vue'
import { storageService } from '@/services/storage'
import type { ComicStorageItem, ChapterStorageItem, StorageStats, StorageOperation, ComicStorageQuery } from '@/types'

export const useStorageStore = defineStore('storage', () => {
  const comicList = ref<ComicStorageItem[]>([])
  const chapters = ref<Record<number, ChapterStorageItem[]>>({})
  const summary = ref<StorageStats | null>(null)
  const busyState = ref<Record<number, boolean>>({})
  const loading = ref(false)

  async function loadComics(params?: ComicStorageQuery) {
    loading.value = true
    try {
      const data = await storageService.fetchComics(params ?? {})
      comicList.value = Array.isArray(data?.records) ? data.records : []
    } catch {
      comicList.value = []
    } finally {
      loading.value = false
    }
  }

  async function loadSummary() {
    try {
      summary.value = await storageService.fetchSummary()
    } catch {
      // keep existing summary
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
      const data = await storageService.fetchComics({ keyword: String(comicId), size: 1 })
      const item = data.records?.find((c) => c.comicId === comicId)
      if (item) {
        replaceRow(item)
      }
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
    busyState,
    loading,
    loadComics,
    loadSummary,
    loadChapters,
    executeOperation,
    replaceRow,
    refreshRow,
    setBusy,
    invalidateChapters,
  }
})
