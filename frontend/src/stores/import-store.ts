import { defineStore } from 'pinia'
import { ref } from 'vue'
import { importApi } from '@/services/api'
import type { ImportTaskVO, ImportStatusVO } from '@/types'

export const useImportStore = defineStore('import', () => {
  const tasks = ref<ImportTaskVO[]>([])
  const loading = ref(false)

  async function create(sourceType: string, sourcePath: string): Promise<ImportTaskVO> {
    const res: any = await importApi.create(sourceType, sourcePath)
    await fetchList()
    return res.data
  }

  async function fetchList() {
    loading.value = true
    const res: any = await importApi.list({ page: 1, size: 50 })
    tasks.value = res.data.records
    loading.value = false
  }

  async function pollStatus(taskId: number): Promise<ImportStatusVO> {
    const res: any = await importApi.status(taskId)
    return res.data
  }

  async function cancel(taskId: number) { await importApi.cancel(taskId); await fetchList() }
  async function retry(taskId: number) { await importApi.retry(taskId); await fetchList() }

  return { tasks, loading, create, fetchList, pollStatus, cancel, retry }
})
