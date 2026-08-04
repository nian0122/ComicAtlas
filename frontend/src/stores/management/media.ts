import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { mediaApi } from '@/services/management/media'
import { uploadApi } from '@/services/management/upload'
import { request, toErrorMessage } from '@/services/management/http'
import {
  chapterOptionLabel,
  parseChapterOptions,
  sanitizeErrorMessage,
} from '@/types/management/media'
import type {
  ManagementMediaItem,
  MediaChapterOption,
  MediaChapterPayload,
} from '@/types/management/media'

/**
 * 媒体管理 Store（Task 19）：章节列表 / 媒体列表 / 批量选择 / 键盘重排 / 回收恢复。
 * 重排走 T17 uploadApi.reorderMedia；回收走 mediaApi.trashMedia；恢复走 mediaApi.restoreMedia。
 */
export const useMediaStore = defineStore('media', () => {
  const comicId = ref(0)
  const comicTitle = ref('')
  const chapters = ref<readonly MediaChapterOption[]>([])
  const currentChapterId = ref<number | null>(null)
  const currentChapterTitle = ref('')
  const items = ref<readonly ManagementMediaItem[]>([])
  const total = ref(0)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const selectedIds = ref<readonly number[]>([])
  const orderDirty = ref(false)
  const savingOrder = ref(false)
  const savedOrder = ref(false)

  const hasItems = computed(() => items.value.length > 0)
  const selectedCount = computed(() => selectedIds.value.length)
  const allSelected = computed(
    () => items.value.length > 0 && selectedIds.value.length === items.value.length,
  )

  // ======================== 加载 ========================

  async function loadComic(id: number): Promise<void> {
    comicId.value = id
    loading.value = true
    error.value = null
    try {
      const raw = await request<unknown>({ method: 'GET', url: `/comics/${id}` })
      const detail = (raw as Record<string, unknown>) ?? {}
      comicTitle.value =
        typeof detail.title === 'string' ? detail.title : ''
      chapters.value = parseChapterOptions(raw)
      if (chapters.value.length > 0) {
        await selectChapter(chapters.value[0].chapterId)
      } else {
        items.value = []
        total.value = 0
        currentChapterId.value = null
        currentChapterTitle.value = ''
      }
    } catch (err: unknown) {
      error.value = toErrorMessage(err, '加载漫画失败')
      throw err
    } finally {
      loading.value = false
    }
  }

  async function selectChapter(chapterId: number): Promise<void> {
    currentChapterId.value = chapterId
    const option = chapters.value.find((c) => c.chapterId === chapterId)
    currentChapterTitle.value = option ? chapterOptionLabel(option) : ''
    await loadChapter(chapterId)
  }

  async function loadChapter(chapterId: number): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const payload: MediaChapterPayload = await mediaApi.chapterMedia(chapterId)
      items.value = payload.pages
      total.value = payload.total
      selectedIds.value = []
      orderDirty.value = false
      savedOrder.value = false
    } catch (err: unknown) {
      error.value = sanitizeErrorMessage(toErrorMessage(err, '加载媒体列表失败'))
      items.value = []
      total.value = 0
    } finally {
      loading.value = false
    }
  }

  // ======================== 批量选择 ========================

  function toggleSelect(id: number): void {
    const next = selectedIds.value.includes(id)
      ? selectedIds.value.filter((v) => v !== id)
      : [...selectedIds.value, id]
    selectedIds.value = next
  }

  function selectAll(): void {
    selectedIds.value =
      selectedIds.value.length === items.value.length
        ? []
        : items.value.map((m) => m.id)
  }

  // ======================== 键盘重排 ========================

  function moveItem(id: number, direction: -1 | 1): void {
    const current = items.value
    const index = current.findIndex((m) => m.id === id)
    const target = index + direction
    if (index < 0 || target < 0 || target >= current.length) return
    const reordered = current.slice()
    const [moved] = reordered.splice(index, 1)
    reordered.splice(target, 0, moved)
    items.value = reordered.map((m, i) => ({ ...m, pageNumber: i + 1 }))
    orderDirty.value = true
    savedOrder.value = false
  }

  async function saveOrder(): Promise<void> {
    const chapterId = currentChapterId.value
    if (chapterId === null || savingOrder.value) return
    savingOrder.value = true
    try {
      await uploadApi.reorderMedia(chapterId, {
        mediaIds: items.value.map((m) => m.id),
      })
      orderDirty.value = false
      savedOrder.value = true
      window.setTimeout(() => {
        savedOrder.value = false
      }, 3000)
    } catch (err: unknown) {
      error.value = sanitizeErrorMessage(toErrorMessage(err, '保存排序失败'))
      throw err
    } finally {
      savingOrder.value = false
    }
  }

  // ======================== 回收 / 恢复 ========================

  function updateItem(id: number, patch: Partial<ManagementMediaItem>): void {
    items.value = items.value.map((m) => (m.id === id ? { ...m, ...patch } : m))
  }

  async function trashItem(id: number): Promise<void> {
    try {
      await mediaApi.trashMedia(id)
      updateItem(id, { lifecycle: 'TRASHED', hqStatus: 'DELETED' })
    } catch (err: unknown) {
      error.value = sanitizeErrorMessage(toErrorMessage(err, '回收媒体失败'))
      throw err
    }
  }

  async function restoreItem(id: number): Promise<void> {
    try {
      await mediaApi.restoreMedia(id)
      updateItem(id, { lifecycle: 'READY', hqStatus: 'READY' })
    } catch (err: unknown) {
      error.value = sanitizeErrorMessage(toErrorMessage(err, '恢复媒体失败'))
      throw err
    }
  }

  function isSelected(id: number): boolean {
    return selectedIds.value.includes(id)
  }

  return {
    comicId,
    comicTitle,
    chapters,
    currentChapterId,
    currentChapterTitle,
    items,
    total,
    loading,
    error,
    selectedIds,
    orderDirty,
    savingOrder,
    savedOrder,
    hasItems,
    selectedCount,
    allSelected,
    loadComic,
    selectChapter,
    loadChapter,
    toggleSelect,
    selectAll,
    isSelected,
    moveItem,
    saveOrder,
    trashItem,
    restoreItem,
  }
})
