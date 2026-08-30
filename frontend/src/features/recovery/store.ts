import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { recoveryApi } from '@/features/recovery/api'
import type { RecoveryTaskVO } from '@/features/recovery/types'
import { getApiErrorMessage } from '@/services/http'

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED'])

function isTerminal(status: string): boolean {
  return TERMINAL_STATUSES.has(status)
}

export const useRecoveryStore = defineStore('recovery', () => {
  const tasks = ref<RecoveryTaskVO[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const polling = ref(false)
  const lastUpdated = ref<number | null>(null)

  let pollTimer: ReturnType<typeof setInterval> | null = null

  const hasActive = computed(
    () => tasks.value.some(t => !isTerminal(t.status))
  )

  const activeTasks = computed(
    () => tasks.value.filter(t => !isTerminal(t.status))
  )

  const failedTasks = computed(
    () => tasks.value.filter(t => t.status === 'FAILED')
  )

  const completedTasks = computed(
    () => tasks.value.filter(t => isTerminal(t.status))
  )

  async function fetchTasks(params?: { page?: number; size?: number }) {
    error.value = null
    try {
      const res = await recoveryApi.list({ page: 1, size: 50, ...params })
      tasks.value = res.data.records
      lastUpdated.value = Date.now()
    } catch (err: unknown) {
      error.value = getApiErrorMessage(err, '加载恢复任务失败')
    }
  }

  async function createTask(): Promise<RecoveryTaskVO> {
    loading.value = true
    try {
      const res = await recoveryApi.create()
      const task = res.data
      tasks.value.unshift(task)
      lastUpdated.value = Date.now()
      if (!polling.value) startPolling()
      return task
    } catch (err: unknown) {
      if (err && typeof err === 'object' && 'response' in err &&
        (err.response as { status?: number } | undefined)?.status === 409) {
        throw new Error('已有恢复任务正在执行')
      }
      throw new Error(getApiErrorMessage(err, '创建恢复任务失败'))
    } finally {
      loading.value = false
    }
  }

  async function retryTask(id: number): Promise<RecoveryTaskVO> {
    try {
      const res = await recoveryApi.retry(id)
      const task = res.data
      const idx = tasks.value.findIndex(t => t.id === id)
      if (idx >= 0) tasks.value[idx] = task
      if (!polling.value) startPolling()
      return task
    } catch (err: unknown) {
      throw new Error(getApiErrorMessage(err, '重试恢复任务失败'))
    }
  }

  function startPolling() {
    if (polling.value) return
    polling.value = true
    scheduleNext()
  }

  function stopPolling() {
    polling.value = false
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function scheduleNext() {
    if (!polling.value) return
    pollTimer = setTimeout(async () => {
      if (!polling.value) return
      await fetchTasks()
      if (!hasActive.value) {
        stopPolling()
        return
      }
      scheduleNext()
    }, 3000)
  }

  function reset() {
    stopPolling()
    tasks.value = []
    error.value = null
    lastUpdated.value = null
  }

  return {
    tasks,
    loading,
    error,
    polling,
    lastUpdated,
    hasActive,
    activeTasks,
    failedTasks,
    completedTasks,
    fetchTasks,
    createTask,
    retryTask,
    startPolling,
    stopPolling,
    reset,
  }
})
