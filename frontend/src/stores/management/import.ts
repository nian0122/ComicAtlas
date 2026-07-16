import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { importApi } from '@/services/api'
import type { ImportTaskVO, ImportStatusVO } from '@/types'

/**
 * 导入工作流统一 Store
 *
 * 状态机：
 *   PENDING / PARSING / IMPORTING / DOWNLOADING / EXTRACTING → 进行中（轮询）
 *   SUCCESS / FAILED / CANCELLED                              → 终态（停止轮询该任务）
 *
 * 轮询节奏：
 *   PENDING        → 2s
 *   IMPORTING 等   → 1s
 *   终态           → 停止
 *
 * 单例 timer：全应用共享一个 setInterval，由 TopNav 在应用启动时 startPolling，
 * 任何页面切换都不会中断；activeCount 自动驱动 TopNav 红点徽章。
 */

const TERMINAL_STATUSES = new Set(['SUCCESS', 'FAILED', 'CANCELLED'])

function isTerminal(status: string): boolean {
  return TERMINAL_STATUSES.has(status)
}

function pickInterval(tasks: ImportTaskVO[]): number {
  // 取最活跃任务的最短需求间隔：有 IMPORTING/PARSING 等就 1s，否则 PENDING 2s
  for (const t of tasks) {
    if (isTerminal(t.status)) continue
    if (t.status === 'PENDING') continue
    return 1000 // IMPORTING / PARSING / DOWNLOADING / EXTRACTING
  }
  return 2000
}

export const useImportStore = defineStore('import', () => {
  const tasks = ref<ImportTaskVO[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const polling = ref(false)
  const lastUpdated = ref<number | null>(null)

  let pollTimer: ReturnType<typeof setInterval> | null = null

  // —— Getters ——

  /** 进行中任务数（驱动 TopNav 红点徽章） */
  const activeCount = computed(
    () => tasks.value.filter(t => !isTerminal(t.status)).length
  )

  /** 是否有进行中任务 */
  const hasActive = computed(() => activeCount.value > 0)

  /** 失败任务列表 */
  const failedTasks = computed(() => tasks.value.filter(t => t.status === 'FAILED'))

  /** 成功任务列表 */
  const completedTasks = computed(() => tasks.value.filter(t => t.status === 'SUCCESS'))

  /** 进行中任务列表 */
  const activeTasks = computed(() => tasks.value.filter(t => !isTerminal(t.status)))

  // —— Actions ——

  async function fetchList() {
    error.value = null
    try {
      const res: any = await importApi.list({ page: 1, size: 50 })
      tasks.value = (res.data?.records || []) as ImportTaskVO[]
      lastUpdated.value = Date.now()
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      error.value = msg || '加载任务列表失败'
    }
  }

  async function create(sourceType: string, sourcePath: string): Promise<ImportTaskVO> {
    loading.value = true
    try {
      const res: any = await importApi.create(sourceType, sourcePath)
      await fetchList()
      // 创建后立刻启动轮询（如果还没启动）
      if (!polling.value) startPolling()
      return res.data as ImportTaskVO
    } finally {
      loading.value = false
    }
  }

  async function pollStatus(taskId: number): Promise<ImportStatusVO> {
    const res: any = await importApi.status(taskId)
    return res.data as ImportStatusVO
  }

  async function cancel(taskId: number) {
    await importApi.cancel(taskId)
    await fetchList()
  }

  async function retry(taskId: number) {
    await importApi.retry(taskId)
    await fetchList()
    if (!polling.value) startPolling()
  }

  // —— Polling lifecycle ——

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

  /** 自适应间隔：根据当前最活跃任务状态决定下一次拉取间隔 */
  function scheduleNext() {
    if (!polling.value) return
    const interval = pickInterval(tasks.value)
    pollTimer = setTimeout(async () => {
      if (!polling.value) return
      await fetchList()
      // 没有进行中任务了 → 自动停止轮询（节能）
      if (!hasActive.value) {
        stopPolling()
        return
      }
      scheduleNext()
    }, interval)
  }

  /** 应用启动时调用：先拉一次再决定是否轮询 */
  async function bootstrap() {
    await fetchList()
    if (hasActive.value) startPolling()
  }

  return {
    // state
    tasks,
    loading,
    error,
    polling,
    lastUpdated,
    // getters
    activeCount,
    hasActive,
    activeTasks,
    failedTasks,
    completedTasks,
    // actions
    fetchList,
    create,
    pollStatus,
    cancel,
    retry,
    // polling
    startPolling,
    stopPolling,
    bootstrap,
  }
})
