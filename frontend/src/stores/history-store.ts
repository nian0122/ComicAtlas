import { defineStore } from 'pinia'
import { ref } from 'vue'
import { historyApi } from '@/services/api'
import type { HistoryVO } from '@/types'

export const useHistoryStore = defineStore('history', () => {
  const list = ref<HistoryVO[]>([])
  async function fetchList() { const res: any = await historyApi.list(); list.value = res.data }
  return { list, fetchList }
})
