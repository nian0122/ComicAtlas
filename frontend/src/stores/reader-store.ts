import { defineStore } from 'pinia'
import { ref } from 'vue'
import { readerApi, historyApi } from '@/services/api'
import type { PageInfo } from '@/types'

export const useReaderStore = defineStore('reader', () => {
  const pages = ref<PageInfo[]>([])
  const currentPage = ref(1)
  const comicId = ref<number>(0)
  const chapterId = ref<number>(0)
  const prevChapterId = ref<number | null>(null)
  const nextChapterId = ref<number | null>(null)
  const hqMode = ref(false)

  async function loadChapter(chId: number) {
    chapterId.value = chId
    const res: any = await readerApi.chapter(chId)
    pages.value = res.data.pages
    prevChapterId.value = res.data.prevChapterId
    nextChapterId.value = res.data.nextChapterId
    // comicId from first load can be set by caller
  }

  async function restoreProgress() {
    try {
      const res: any = await historyApi.get(comicId.value)
      if (res?.data) currentPage.value = res.data.pageNumber
    } catch {}
  }

  function nextPage() { if (currentPage.value < pages.value.length) currentPage.value++ }
  function prevPage() { if (currentPage.value > 1) currentPage.value-- }

  return { pages, currentPage, comicId, chapterId, prevChapterId, nextChapterId, hqMode, loadChapter, restoreProgress, nextPage, prevPage }
})
