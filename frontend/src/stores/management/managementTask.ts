import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { taskApi } from '@/services/management/task'
import { isAbortError, toErrorMessage } from '@/services/management/http'
import { parseTaskItemList, parseTaskListPage } from '@/services/management/parse'
import { ManagementTaskStatus, assertNever } from '@/types/management/enums'
import type {
  ManagementTaskEntry,
  ManagementTaskItemEntry,
  ManagementTaskListQuery,
} from '@/types/management/task'
import type { ManagementTaskStatus as StatusValue } from '@/types/management/enums'
import type { TaskType as TaskTypeValue } from '@/types/management/enums'

/**
 * 统一管理任务 Store（T17）
 *
 * 轮询契约：
 *  - 可见页 2s、后台（document.hidden）10s；可见性变化即时重排期
 *  - 全部任务进入终态（CANCELLED/SUCCEEDED/PARTIALLY_SUCCEEDED/FAILED）→ 停止轮询
 *  - 有界：MAX_POLLS 硬上限 + MAX_CONSECUTIVE_ERRORS 连续错误上限
 *  - AbortController：每次拉取持有信号，stopPolling/卸载时取消在途请求（net::ERR_ABORTED 静默处理）
 *  - unknown 枚举状态视为非终态（继续轮询）、渲染"未知状态"回退标签，绝不崩溃
 */

const VISIBLE_INTERVAL = 2_000
const HIDDEN_INTERVAL = 10_000
const MAX_POLLS = 300
const MAX_CONSECUTIVE_ERRORS = 5

function isTerminalStatus(status: ManagementTaskStatus): boolean {
  switch (status) {
    case ManagementTaskStatus.QUEUED:
    case ManagementTaskStatus.RUNNING:
    case ManagementTaskStatus.CANCELLING:
      return false
    case ManagementTaskStatus.CANCELLED:
    case ManagementTaskStatus.SUCCEEDED:
    case ManagementTaskStatus.PARTIALLY_SUCCEEDED:
    case ManagementTaskStatus.FAILED:
      return true
    default:
      return assertNever(status)
  }
}

function isTerminalEntry(task: ManagementTaskEntry): boolean {
  if (task.status.kind === 'unknown') return false
  return isTerminalStatus(task.status.value)
}

export const useManagementTaskStore = defineStore('management-task', () => {
  const tasks = ref<readonly ManagementTaskEntry[]>([])
  const itemsByTask = ref<Readonly<Record<number, readonly ManagementTaskItemEntry[]>>>({})
  const loading = ref(false)
  const error = ref<string | null>(null)
  const polling = ref(false)
  const lastUpdated = ref<number | null>(null)

  // 分页 / 过滤（TasksTab 使用；轮询复用当前过滤条件）
  const page = ref(1)
  const size = ref(20)
  const total = ref(0)
  const typeFilter = ref<TaskTypeValue | ''>('')
  const statusFilter = ref<StatusValue | ''>('')

  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let abortController: AbortController | null = null
  let pollCount = 0
  let consecutiveErrors = 0
  let visibilityListener: (() => void) | null = null

  const hasActive = computed(() => tasks.value.some((t) => !isTerminalEntry(t)))
  const activeTasks = computed(() => tasks.value.filter((t) => !isTerminalEntry(t)))
  const terminalTasks = computed(() => tasks.value.filter((t) => isTerminalEntry(t)))
  const failedTasks = computed(
    () =>
      tasks.value.filter(
        (t) => t.status.kind === 'known' && t.status.value === ManagementTaskStatus.FAILED,
      ),
  )

  async function fetchList(params?: ManagementTaskListQuery): Promise<void> {
    if (params?.page !== undefined) page.value = params.page
    if (params?.size !== undefined) size.value = params.size
    if (params?.type !== undefined) typeFilter.value = params.type
    if (params?.status !== undefined) statusFilter.value = params.status
    const controller = new AbortController()
    abortController?.abort()
    abortController = controller
    loading.value = true
    try {
      const raw = await taskApi.list(
        {
          page: page.value,
          size: size.value,
          batchId: params?.batchId,
          type: typeFilter.value || undefined,
          status: statusFilter.value || undefined,
        },
        controller.signal,
      )
      const parsed = parseTaskListPage(raw)
      tasks.value = parsed.records
      total.value = parsed.total
      lastUpdated.value = Date.now()
      consecutiveErrors = 0
    } catch (err: unknown) {
      if (isAbortError(err)) return
      consecutiveErrors += 1
      error.value = toErrorMessage(err, '加载任务列表失败')
    } finally {
      loading.value = false
      if (abortController === controller) abortController = null
    }
  }

  async function cancelTask(id: number): Promise<void> {
    await taskApi.cancel(id)
    await fetchList()
  }

  async function retryTask(id: number): Promise<void> {
    await taskApi.retry(id)
    await fetchList()
  }

  async function fetchItems(taskId: number, signal?: AbortSignal): Promise<void> {
    const raw = await taskApi.items(taskId, signal)
    itemsByTask.value = { ...itemsByTask.value, [taskId]: parseTaskItemList(raw) }
  }

  function clearItems(taskId: number): void {
    const next = { ...itemsByTask.value }
    delete next[taskId]
    itemsByTask.value = next
  }

  function currentInterval(): number {
    return document.visibilityState === 'hidden' ? HIDDEN_INTERVAL : VISIBLE_INTERVAL
  }

  function clearTimer(): void {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function scheduleNext(): void {
    if (!polling.value) return
    if (pollCount >= MAX_POLLS || consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
      stopPolling()
      return
    }
    pollTimer = setTimeout(() => {
      if (!polling.value) return
      void runPollCycle()
    }, currentInterval())
  }

  async function runPollCycle(): Promise<void> {
    pollCount += 1
    await fetchList()
    if (!polling.value) return
    if (!hasActive.value || pollCount >= MAX_POLLS || consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
      stopPolling()
      return
    }
    scheduleNext()
  }

  function onVisibilityChange(): void {
    if (!polling.value) return
    clearTimer()
    scheduleNext()
  }

  function startPolling(): void {
    if (polling.value) return
    polling.value = true
    pollCount = 0
    consecutiveErrors = 0
    if (!visibilityListener) {
      visibilityListener = onVisibilityChange
      document.addEventListener('visibilitychange', visibilityListener)
    }
    scheduleNext()
  }

  function stopPolling(): void {
    polling.value = false
    clearTimer()
    abortController?.abort()
    abortController = null
    if (visibilityListener) {
      document.removeEventListener('visibilitychange', visibilityListener)
      visibilityListener = null
    }
  }

  async function bootstrap(): Promise<void> {
    await fetchList()
    if (hasActive.value) startPolling()
  }

  function reset(): void {
    stopPolling()
    tasks.value = []
    itemsByTask.value = {}
    error.value = null
    lastUpdated.value = null
  }

  return {
    tasks,
    itemsByTask,
    loading,
    error,
    polling,
    lastUpdated,
    page,
    size,
    total,
    typeFilter,
    statusFilter,
    hasActive,
    activeTasks,
    terminalTasks,
    failedTasks,
    fetchList,
    fetchItems,
    clearItems,
    cancelTask,
    retryTask,
    startPolling,
    stopPolling,
    bootstrap,
    reset,
  }
})
