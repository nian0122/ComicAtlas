import { defineStore } from 'pinia'
import { ref } from 'vue'
import { comicApi, historyApi } from '@/services/api'
import type { PageInfo } from '@/types'

export const useReaderStore = defineStore('reader', () => {
  const pages = ref<PageInfo[]>([])
  const currentPage = ref(1)
  const comicId = ref<number>(0)
  const chapterId = ref<number>(0)
  const hqMode = ref(false)
  const virtualScrollEnabled = ref(false)
  const visibleRange = ref({ start: 0, end: 200 })

  async function loadChapter(id: number, chId: number) {
    comicId.value = id; chapterId.value = chId
    const res: any = await comicApi.pages(id, chId)
    pages.value = res.data.pages
    visibleRange.value = { start: 0, end: res.data.pages.length }
  }

  async function restoreProgress() {
    try {
      const res: any = await historyApi.get(comicId.value)
      if (res?.data) currentPage.value = res.data.pageNumber
    } catch {}
  }

  function nextPage() { if (currentPage.value < pages.value.length) currentPage.value++ }
  function prevPage() { if (currentPage.value > 1) currentPage.value-- }

  return { pages, currentPage, comicId, chapterId, hqMode, visibleRange, virtualScrollEnabled, loadChapter, restoreProgress, nextPage, prevPage }
})
