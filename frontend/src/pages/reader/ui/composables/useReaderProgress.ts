import { ref, watch } from 'vue'
import { useReaderStore } from '@/features/reader-navigation'
import { getApiErrorMessage } from '@/shared/api/http'
import { clientLogger } from '@/shared/lib/logger'
import { useReadingProgressPersistence } from './useReadingProgressPersistence'

/** 普通阅读器的进度触发条件；持久化队列和离页处理由共享 composable 负责。 */
export function useReaderProgress() {
  const store = useReaderStore()
  const lastSyncedPage = ref(1)
  const progressDirty = ref(false)
  const chapterLoading = ref(false)
  const persistence = useReadingProgressPersistence(
    () => {
      if (store.comicId <= 0 || store.chapterId <= 0 || store.pages.length === 0) return null
      return {
        comicId: store.comicId,
        chapterId: store.chapterId,
        pageNumber: store.currentPage,
        totalPages: store.pages.length,
      }
    },
    300,
    {
      onSaved: (progress) => {
        store.progressSaveError = null
        if (store.chapterId !== progress.chapterId || store.currentPage !== progress.pageNumber) return
        lastSyncedPage.value = progress.pageNumber
        progressDirty.value = false
      },
      onError: (progress, error) => {
        progressDirty.value = true
        store.progressSaveError = getApiErrorMessage(error, '阅读进度保存失败')
        clientLogger.error('阅读进度保存失败', {
          operation: 'history.update',
          comicId: progress.comicId,
          chapterId: progress.chapterId,
        })
      },
    },
  )

  async function prepareProgressForReload(): Promise<boolean> {
    const canRestoreProgress = !progressDirty.value
    if (!progressDirty.value || store.comicId <= 0 || store.chapterId <= 0) return canRestoreProgress

    const chapterId = store.chapterId
    const pageNumber = store.currentPage
    const isSaved = await persistence.saveNow()
    return isSaved && store.chapterId === chapterId && store.currentPage === pageNumber
  }

  function retryProgressSave(): Promise<boolean> {
    progressDirty.value = true
    return persistence.saveNow()
  }

  function saveVideoProgress(pageNumber: number): void {
    progressDirty.value = true
    if (store.currentPage === pageNumber) void persistence.saveNow()
  }

  function prepareProgressForChapterChange(): void {
    if (store.comicId > 0 && store.currentPage !== lastSyncedPage.value) {
      progressDirty.value = true
      void persistence.saveNow()
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
      persistence.scheduleSave()
    },
  )

  return {
    lastSyncedPage,
    chapterLoading,
    prepareProgressForReload,
    retryProgressSave,
    saveVideoProgress,
    prepareProgressForChapterChange,
  }
}
