import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { trashApi, type TrashEntry } from '@/entities/trash'
import { lifecycleApi, type AuditSummary, type AuditPage, type FileResultPage } from '@/entities/lifecycle'
import { taskApi, type Task } from '@/entities/task'
import { lifecycleCommands, repairAllowed, reportStamp } from '@/features/reconcile-lifecycle'
import { ApiError, getApiErrorMessage } from '@/shared/api/http'
export function useLifecycle(
  props: { open: boolean; entryId: string; initialState?: TrashEntry['state'] },
  accepted: (task: Task) => void,
) {
  const entries = ref<TrashEntry[]>([]),
    total = ref(0),
    page = ref(1),
    state = ref<TrashEntry['state']>(props.initialState ?? 'trashing'),
    keyword = ref('')
  const entry = ref<TrashEntry | null>(null),
    report = ref<AuditSummary | null>(null),
    auditTask = ref<Task | null>(null),
    owner = ref<Task | null>(null)
  const observations = ref<AuditPage | null>(null),
    results = ref<FileResultPage | null>(null),
    observationPage = ref(1),
    resultPage = ref(1)
  const listLoading = ref(false),
    loading = ref(false),
    busy = ref(false),
    error = ref(''),
    listError = ref(''),
    notice = ref(''),
    fresh = ref(false),
    acknowledged = ref(false),
    pendingKey = ref(''),
    rejectedStamp = ref('')
  let listGeneration = 0,
    generation = 0,
    disposed = false
  const active = (task: Task | null) => !!task && ['queued', 'running', 'cancelling'].includes(task.status)
  const running = computed(() => active(auditTask.value) || active(owner.value))
  const canRepair = computed(
    () =>
      fresh.value &&
      !running.value &&
      repairAllowed(report.value, owner.value?.status) &&
      reportStamp(report.value!) !== rejectedStamp.value,
  )
  const currentStamp = computed(() => (report.value ? reportStamp(report.value) : ''))
  watch(currentStamp, () => {
    acknowledged.value = false
  })
  async function list() {
    const request = ++listGeneration
    listLoading.value = true
    listError.value = ''
    try {
      const data = await trashApi.list({ page: page.value, pageSize: 20, state: state.value, keyword: keyword.value })
      if (disposed || request !== listGeneration) return
      entries.value = data.items
      total.value = data.total
      if (total.value && page.value > Math.ceil(total.value / 20)) {
        page.value = Math.ceil(total.value / 20)
        await list()
      }
    } catch (e) {
      if (request === listGeneration && !disposed) listError.value = getApiErrorMessage(e, '无法读取回收历史')
    } finally {
      if (request === listGeneration && !disposed) listLoading.value = false
    }
  }
  function filters() {
    page.value = 1
    void list()
  }
  async function refresh() {
    if (!entry.value || loading.value) return
    const id = entry.value.id,
      request = ++generation
    loading.value = true
    fresh.value = false
    error.value = ''
    try {
      const item = await trashApi.detail(id)
      let summary: AuditSummary | null = null
      try {
        summary = await lifecycleApi.report(id)
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 404)) throw e
      }
      const [audit, task] = await Promise.all([
        summary ? taskApi.detail(summary.taskId) : Promise.resolve(null),
        summary?.ownerTaskId ? taskApi.detail(summary.ownerTaskId) : Promise.resolve(null),
      ])
      const newReport = summary?.taskId !== report.value?.taskId
      const op = newReport ? 1 : observationPage.value
      const rp = task?.attempt !== owner.value?.attempt || task?.id !== owner.value?.id ? 1 : resultPage.value
      const [files, execution] = await Promise.all([
        summary ? lifecycleApi.observations(summary.taskId, op) : Promise.resolve(null),
        task ? lifecycleApi.results(task.id, rp) : Promise.resolve(null),
      ])
      if (disposed || request !== generation || !props.open) return
      entry.value = item
      report.value = summary
      auditTask.value = audit
      owner.value = task
      observations.value = files
      results.value = execution
      observationPage.value = op
      resultPage.value = rp
      fresh.value = true
    } catch (e) {
      if (request === generation && !disposed) error.value = getApiErrorMessage(e, '无法读取核对报告，已禁止修复')
    } finally {
      if (request === generation && !disposed) loading.value = false
    }
  }
  async function select(item: TrashEntry) {
    if (busy.value) return
    ++generation
    loading.value = false
    entry.value = item
    report.value = null
    auditTask.value = null
    owner.value = null
    observations.value = null
    results.value = null
    observationPage.value = 1
    resultPage.value = 1
    acknowledged.value = false
    pendingKey.value = ''
    notice.value = ''
    error.value = ''
    rejectedStamp.value = ''
    await refresh()
  }
  async function openEntry(id: string) {
    const request = ++generation
    loading.value = true
    fresh.value = false
    error.value = ''
    try {
      const item = await trashApi.detail(id)
      if (!disposed && request === generation && props.open) await select(item)
    } catch (e) {
      if (request === generation && !disposed) error.value = getApiErrorMessage(e, '无法读取历史条目')
    } finally {
      if (request === generation && !disposed) loading.value = false
    }
  }
  async function reconcile() {
    if (!entry.value || busy.value || loading.value || running.value) return
    if (entry.value.state === 'purged') {
      error.value = '该对象已永久清理，文件清单已删除；不能再发起新的核对'
      return
    }
    busy.value = true
    error.value = ''
    notice.value = ''
    pendingKey.value ||= crypto.randomUUID()
    try {
      const task = await lifecycleCommands.reconcile(entry.value.id, pendingKey.value)
      pendingKey.value = ''
      auditTask.value = task
      acknowledged.value = false
      notice.value = '核对任务已受理。检查完整不代表文件一致，请查看实际报告。'
      accepted(task)
      await refresh()
    } catch (e) {
      error.value = getApiErrorMessage(e, '受理结果不确定，可用同一请求重试')
    } finally {
      busy.value = false
    }
  }
  async function repair() {
    if (!entry.value || !canRepair.value || !acknowledged.value || busy.value || loading.value) return
    const stamp = currentStamp.value,
      original = report.value!.ownerTaskId
    busy.value = true
    error.value = ''
    notice.value = ''
    try {
      await refresh()
      if (!canRepair.value || stamp !== currentStamp.value)
        throw new Error('报告或原任务已变化，请重新核对；本次未提交修复')
      const task = await lifecycleCommands.repair(entry.value.id)
      if (task.id !== original) throw new Error('修复响应不是原任务，请到任务中心核对实际结果')
      owner.value = task
      acknowledged.value = false
      rejectedStamp.value = stamp
      notice.value = '原失败任务已重新受理。文件仍会再次校验，尚未表示修复成功。'
      accepted(task)
      await refresh()
    } catch (e) {
      rejectedStamp.value = stamp
      acknowledged.value = false
      error.value = getApiErrorMessage(e, '修复未获确认，请刷新任务状态并重新核对')
    } finally {
      busy.value = false
    }
  }
  async function cancel() {
    if (!auditTask.value?.canCancel || busy.value) return
    busy.value = true
    error.value = ''
    try {
      const task = await lifecycleCommands.cancelAudit(auditTask.value.id)
      auditTask.value = task
      accepted(task)
      await refresh()
    } catch (e) {
      error.value = getApiErrorMessage(e, '取消未获确认，请刷新查看')
    } finally {
      busy.value = false
    }
  }
  async function paginate(kind: 'observations' | 'results', next: number) {
    if (loading.value || busy.value || next < 1) return
    if (kind === 'observations') observationPage.value = next
    else resultPage.value = next
    await refresh()
  }
  watch(
    () => [props.open, props.entryId] as const,
    async ([open, id]) => {
      ++generation
      loading.value = false
      error.value = ''
      notice.value = ''
      entry.value = null
      report.value = null
      auditTask.value = null
      owner.value = null
      fresh.value = false
      pendingKey.value = ''
      if (!open) return
      state.value = props.initialState ?? 'trashing'
      void list()
      if (id) await openEntry(id)
    },
    { immediate: true },
  )
  const timer = window.setInterval(() => {
    if (props.open && running.value && !busy.value && !loading.value && !document.hidden) void refresh()
  }, 2000)
  onBeforeUnmount(() => {
    disposed = true
    ++generation
    ++listGeneration
    window.clearInterval(timer)
  })
  return {
    entries,
    total,
    page,
    state,
    keyword,
    entry,
    report,
    auditTask,
    owner,
    observations,
    results,
    observationPage,
    resultPage,
    listLoading,
    loading,
    busy,
    error,
    listError,
    notice,
    fresh,
    acknowledged,
    pendingKey,
    running,
    canRepair,
    list,
    filters,
    refresh,
    select,
    reconcile,
    repair,
    cancel,
    paginate,
  }
}
