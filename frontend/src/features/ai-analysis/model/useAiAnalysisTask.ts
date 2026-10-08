import { computed, onScopeDispose, ref } from 'vue'
import { aiAnalysisApi, type AiAnalysisTask, type AiTaskStatus } from '../api'
import { useAsyncPolling } from '@/shared/lib/composables/useAsyncPolling'

export interface AnalysisResult {
  titleCandidate?: string | null
  authorCandidate?: string | null
  categoryCandidate?: string | null
  tags?: string[]
  description?: string
  warnings?: string[]
}

const STATUS_LABELS: Record<AiTaskStatus, string> = {
  QUEUED: '排队中',
  RUNNING: '分析中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCEL_REQUESTED: '取消中',
  CANCELLED: '已取消',
}
const ACTIVE_STATUSES: readonly AiTaskStatus[] = ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED']

function isAnalysisResult(value: unknown): value is AnalysisResult {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) return false
  const fields = value as Record<string, unknown>
  return (
    ['titleCandidate', 'authorCandidate', 'categoryCandidate'].every(
      (name) => fields[name] == null || typeof fields[name] === 'string',
    ) &&
    (fields.description === undefined || typeof fields.description === 'string') &&
    ['tags', 'warnings'].every(
      (name) =>
        fields[name] === undefined ||
        (Array.isArray(fields[name]) && fields[name].every((item: unknown) => typeof item === 'string')),
    )
  )
}

/** 两个分析入口共用任务状态与轮询生命周期，页面只负责提交对象和结果展示。 */
export function useAiAnalysisTask() {
  const task = ref<AiAnalysisTask | null>(null)
  const result = ref<AnalysisResult | null>(null)
  const resultError = ref('')
  const submitting = ref(false)
  const cancelling = ref(false)
  const pendingTaskId = ref<number | null>(null)
  let requestVersion = 0
  let disposed = false
  const isActive = computed(
    () => pendingTaskId.value != null || (task.value != null && ACTIVE_STATUSES.includes(task.value.status)),
  )
  const canCancel = computed(() => task.value != null && ['QUEUED', 'RUNNING'].includes(task.value.status))
  const statusLabel = computed(() => STATUS_LABELS[task.value?.status ?? 'QUEUED'])
  const progressStatus = computed(() =>
    task.value?.status === 'FAILED' ? 'exception' : task.value?.status === 'SUCCEEDED' ? 'success' : undefined,
  )

  function acceptTask(updatedTask: AiAnalysisTask): void {
    task.value = updatedTask
    pendingTaskId.value = null
    if (updatedTask.status !== 'SUCCEEDED') return
    try {
      const parsed: unknown = updatedTask.resultJson ? JSON.parse(updatedTask.resultJson) : null
      if (parsed != null && !isAnalysisResult(parsed)) throw new Error('分析结果格式无效')
      result.value = parsed == null ? null : parsed
      resultError.value = ''
    } catch {
      result.value = null
      resultError.value = '分析已完成，但结果格式无效，请重新分析'
    }
  }

  async function refreshTask(): Promise<boolean> {
    const taskId = pendingTaskId.value ?? task.value?.id
    const version = requestVersion
    if (disposed || taskId == null) return false
    try {
      const updatedTask = await aiAnalysisApi.get(taskId)
      if (disposed || version !== requestVersion) return false
      acceptTask(updatedTask)
      return isActive.value
    } catch {
      return !disposed && version === requestVersion
    }
  }
  const polling = useAsyncPolling(refreshTask, 1500)

  async function startTask(comicId: number): Promise<number | null> {
    if (disposed || submitting.value || isActive.value) return null
    const version = ++requestVersion
    polling.stop()
    submitting.value = true
    result.value = null
    resultError.value = ''
    try {
      const created = await aiAnalysisApi.create(comicId)
      if (disposed || version !== requestVersion) return null
      // 创建成功后立即保留 ID，首次状态请求失败也继续追踪同一任务，避免重复提交。
      task.value = null
      pendingTaskId.value = created.taskId
      if (await refreshTask()) polling.start()
      return created.taskId
    } finally {
      if (version === requestVersion) submitting.value = false
    }
  }

  async function cancelTask(): Promise<void> {
    if (!task.value || cancelling.value || !canCancel.value) return
    const version = requestVersion
    cancelling.value = true
    try {
      await aiAnalysisApi.cancel(task.value.id)
      if (disposed || version !== requestVersion) return
      if (await refreshTask()) polling.start()
      else polling.stop()
    } finally {
      cancelling.value = false
    }
  }

  onScopeDispose(() => {
    disposed = true
    requestVersion += 1
  })
  return {
    task,
    result,
    resultError,
    submitting,
    cancelling,
    isActive,
    canCancel,
    statusLabel,
    progressStatus,
    startTask,
    cancelTask,
  }
}
