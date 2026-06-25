import { defineStore } from 'pinia'
import { ref } from 'vue'
import { dashboardApi } from '@/services/api'
import type { StatisticsVO } from '@/types'

export const useDashboardStore = defineStore('dashboard', () => {
  const stats = ref<StatisticsVO | null>(null)
  async function fetch() { const res: any = await dashboardApi.statistics(); stats.value = res.data }
  return { stats, fetch }
})
