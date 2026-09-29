import { onBeforeUnmount, onMounted } from 'vue'
import { useHistoryStore } from '@/entities/history'

export interface ReadingProgressPayload {
  comicId: number
  chapterId: number
  pageNumber: number
  totalPages: number
}

interface ReadingProgressPersistenceOptions {
  onSaved?: (progress: ReadingProgressPayload) => void
  onError?: (progress: ReadingProgressPayload, error: unknown) => void
}

/** 统一普通阅读与沉浸式阅读的进度排队、防抖、串行写入和离页兜底。 */
export function useReadingProgressPersistence(
  readProgress: () => ReadingProgressPayload | null,
  debounceMs: number,
  options: ReadingProgressPersistenceOptions = {},
) {
  const historyStore = useHistoryStore()
  let pendingProgress: ReadingProgressPayload | null = null
  let saveTimer: number | null = null
  let savePromise: Promise<boolean> | null = null
  let isDirty = false

  function clearSaveTimer(): void {
    if (saveTimer === null) return
    window.clearTimeout(saveTimer)
    saveTimer = null
  }

  function flushPendingProgress(): Promise<boolean> {
    if (savePromise) return savePromise

    const flushPromise = (async () => {
      let isSaved = true
      while (pendingProgress) {
        const progress = pendingProgress
        pendingProgress = null
        try {
          await historyStore.recordProgress(
            progress.comicId,
            progress.chapterId,
            progress.pageNumber,
            progress.totalPages,
          )
          options.onSaved?.(progress)
          if (!pendingProgress) isDirty = false
        } catch (error: unknown) {
          isSaved = false
          isDirty = true
          options.onError?.(progress, error)
          break
        }
      }
      return isSaved
    })()

    savePromise = flushPromise.finally(() => {
      savePromise = null
      if (pendingProgress) void flushPendingProgress()
    })
    return savePromise
  }

  function scheduleSave(): void {
    const progress = readProgress()
    if (!progress) return
    pendingProgress = progress
    isDirty = true
    clearSaveTimer()
    saveTimer = window.setTimeout(() => {
      saveTimer = null
      void flushPendingProgress()
    }, debounceMs)
  }

  function saveNow(): Promise<boolean> {
    clearSaveTimer()
    const progress = readProgress()
    if (progress) {
      pendingProgress = progress
      isDirty = true
    }
    if (!pendingProgress && !savePromise) return Promise.resolve(false)
    return flushPendingProgress()
  }

  function saveOnPageHide(): void {
    clearSaveTimer()
    if (!isDirty) return
    const progress = readProgress() ?? pendingProgress
    if (!progress) return
    pendingProgress = null
    isDirty = false
    historyStore.recordProgressKeepalive(progress)
  }

  function onVisibilityChange(): void {
    if (document.visibilityState === 'hidden') saveOnPageHide()
  }

  onMounted(() => {
    window.addEventListener('pagehide', saveOnPageHide)
    document.addEventListener('visibilitychange', onVisibilityChange)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('pagehide', saveOnPageHide)
    document.removeEventListener('visibilitychange', onVisibilityChange)
    clearSaveTimer()
    if (isDirty) void saveNow()
  })

  return { scheduleSave, saveNow }
}
