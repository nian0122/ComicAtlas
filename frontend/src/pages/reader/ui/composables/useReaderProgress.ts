import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useReaderStore } from '@/features/reader-navigation'

/** 管理阅读进度的防抖保存、章节切换保存与页面离开兜底。 */
export function useReaderProgress() {
  const store = useReaderStore()
  const lastSyncedPage = ref(1)
  const progressDirty = ref(false)
  const chapterLoading = ref(false)
  const saveDebounceTimer = ref<number | null>(null)

  function clearSaveDebounce(): void {
    if (saveDebounceTimer.value) {
      clearTimeout(saveDebounceTimer.value)
      saveDebounceTimer.value = null
    }
  }

  async function prepareProgressForReload(): Promise<boolean> {
    clearSaveDebounce()

    const canRestoreProgress = !progressDirty.value
    if (!progressDirty.value || store.comicId <= 0 || store.chapterId <= 0) {
      return canRestoreProgress
    }

    const chapterId = store.chapterId
    const pageNumber = store.currentPage
    const saved = await store.saveProgress()
    if (!saved || store.chapterId !== chapterId || store.currentPage !== pageNumber) {
      return false
    }

    lastSyncedPage.value = pageNumber
    progressDirty.value = false
    return true
  }

  function retryProgressSave(): Promise<boolean> {
    const chapterId = store.chapterId
    const pageNumber = store.currentPage
    progressDirty.value = true
    return store.saveProgress().then((saved) => {
      if (saved && store.chapterId === chapterId && store.currentPage === pageNumber) {
        lastSyncedPage.value = pageNumber
        progressDirty.value = false
        return true
      }
      return false
    })
  }

  function saveVideoProgress(pageNumber: number): void {
    progressDirty.value = true
    void store.saveProgress().then((saved) => {
      if (saved && store.currentPage === pageNumber) {
        lastSyncedPage.value = pageNumber
        progressDirty.value = false
      }
    })
  }

  function prepareProgressForChapterChange(): void {
    clearSaveDebounce()
    if (store.comicId <= 0 || store.currentPage === lastSyncedPage.value) return

    const chapterId = store.chapterId
    const pageNumber = store.currentPage
    void store.saveProgress().then((saved) => {
      if (saved && store.chapterId === chapterId && store.currentPage === pageNumber) {
        lastSyncedPage.value = pageNumber
        progressDirty.value = false
      }
    })
  }

  function flushProgressOnPageHide(): void {
    clearSaveDebounce()
    if (store.comicId <= 0 || !progressDirty.value) return

    lastSyncedPage.value = store.currentPage
    progressDirty.value = false
    store.saveProgressKeepalive()
  }

  function onVisibilityChange(): void {
    if (document.visibilityState === 'hidden') {
      flushProgressOnPageHide()
    }
  }

  watch(
    () => store.currentPage,
    (pageNumber) => {
      if (
        chapterLoading.value ||
        store.comicId <= 0 ||
        store.chapterId <= 0 ||
        store.pages.length === 0 ||
        pageNumber === lastSyncedPage.value
      ) {
        return
      }

      progressDirty.value = true
      clearSaveDebounce()
      saveDebounceTimer.value = window.setTimeout(() => {
        void store.saveProgress().then((saved) => {
          if (saved && store.currentPage === pageNumber) {
            lastSyncedPage.value = pageNumber
            progressDirty.value = false
          }
        })
      }, 300)
    },
  )

  onMounted(() => {
    window.addEventListener('pagehide', flushProgressOnPageHide)
    document.addEventListener('visibilitychange', onVisibilityChange)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('pagehide', flushProgressOnPageHide)
    document.removeEventListener('visibilitychange', onVisibilityChange)
    clearSaveDebounce()

    if (store.comicId <= 0 || store.currentPage === lastSyncedPage.value) return

    const chapterId = store.chapterId
    const pageNumber = store.currentPage
    void store.saveProgress().then((saved) => {
      if (saved && store.chapterId === chapterId && store.currentPage === pageNumber) {
        lastSyncedPage.value = pageNumber
        progressDirty.value = false
      }
    })
  })

  return {
    lastSyncedPage,
    chapterLoading,
    prepareProgressForReload,
    retryProgressSave,
    saveVideoProgress,
    prepareProgressForChapterChange,
  }
}
