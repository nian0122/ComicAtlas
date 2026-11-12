import { ref, computed, onBeforeUnmount } from 'vue'
import {
  recoveryApi,
  type RecoveryResults,
  type RecoveryItem,
  type RecoveryPlaceholder,
  type RecoveryRepairResult,
  type RepairVersions,
} from '@/entities/recovery'
import { comicApi, type ComicDetailVO } from '@/entities/comic'
import { taskApi, type Task } from '@/entities/task'
import { recoveryCommands, repairBody, repairBinding } from '@/features/recover-library'
import { ApiError, getApiErrorMessage } from '@/shared/api/http'
export function useRecovery(
  props: { mode: 'scan' | 'repair'; comicId: string; taskId: string },
  accepted: (task: Task) => void,
) {
  const task = ref<Task | null>(null),
    results = ref<RecoveryResults | null>(null),
    rows = ref<RecoveryItem[]>([]),
    repairResult = ref<RecoveryRepairResult | null>(null)
  const comic = ref<ComicDetailVO | null>(null),
    marker = ref<RecoveryPlaceholder | null>(null),
    fresh = ref(false)
  const initializing = ref(true)
  const busy = ref(false),
    loading = ref(false),
    moreLoading = ref(false),
    sourceLoading = ref(false),
    error = ref(''),
    notice = ref(''),
    acknowledged = ref(false),
    attempted = ref(false)
  const receipt = ref<{ comic: string; body: RepairVersions | null; key: string } | null>(null)
  let generation = 0,
    sourceGeneration = 0,
    disposed = false
  const running = computed(() => !!task.value && ['queued', 'running', 'cancelling'].includes(task.value.status))
  const uncertain = computed(() => attempted.value && !task.value)
  const canRepair = computed(
    () =>
      fresh.value && !!comic.value && !!marker.value && !comic.value.chapters.length && !comic.value.catalogs.length,
  )
  const canSubmit = computed(
    () =>
      !initializing.value &&
      !task.value &&
      !busy.value &&
      !loading.value &&
      !sourceLoading.value &&
      (uncertain.value || (acknowledged.value && (props.mode === 'scan' || canRepair.value))),
  )
  function binding() {
    return comic.value && marker.value ? repairBinding(comic.value, marker.value) : ''
  }
  async function source() {
    if (props.mode !== 'repair' || !props.comicId || sourceLoading.value) return
    const request = ++sourceGeneration
    sourceLoading.value = true
    fresh.value = false
    try {
      const [detail, placeholder] = await Promise.all([
        comicApi.detail(props.comicId),
        recoveryApi.placeholder(props.comicId).catch((e) => {
          if (e instanceof ApiError && e.status === 404) return null
          throw e
        }),
      ])
      if (disposed || request !== sourceGeneration) return
      const before = binding()
      comic.value = detail
      marker.value = placeholder
      fresh.value = true
      if (before !== binding()) acknowledged.value = false
    } catch (e) {
      if (!disposed && request === sourceGeneration)
        error.value = getApiErrorMessage(e, '无法核对占位与版本，已禁止补全')
    } finally {
      if (!disposed && request === sourceGeneration) sourceLoading.value = false
    }
  }
  async function refresh() {
    if (!task.value || loading.value || moreLoading.value) return
    const id = task.value.id,
      request = ++generation
    loading.value = true
    error.value = ''
    try {
      const current = await taskApi.detail(id)
      if (current.type !== (props.mode === 'scan' ? 'disk_recovery' : 'recovery_repair'))
        throw new Error('任务类型与当前恢复入口不一致')
      if (props.mode === 'repair' && current.targetId !== props.comicId) throw new Error('补全任务不属于当前漫画')
      if (props.mode === 'scan') {
        const page = await recoveryApi.results(id)
        if (disposed || request !== generation) return
        task.value = current
        results.value = page
        rows.value = page.items
      } else {
        const result = await recoveryApi.repairResult(id)
        if (result.comicId !== props.comicId || result.taskId !== id) throw new Error('补全结果与当前漫画不一致')
        if (disposed || request !== generation) return
        task.value = current
        repairResult.value = result
        await source()
      }
    } catch (e) {
      if (!disposed && request === generation) error.value = getApiErrorMessage(e, '无法读取恢复结果')
    } finally {
      if (!disposed && request === generation) loading.value = false
    }
  }
  async function more() {
    const cursor = results.value?.nextAfter,
      id = task.value?.id
    if (!cursor || !id || loading.value || moreLoading.value || busy.value) return
    const request = ++generation
    moreLoading.value = true
    error.value = ''
    try {
      const page = await recoveryApi.results(id, cursor)
      if (disposed || request !== generation || task.value?.id !== id) return
      if (page.nextAfter === cursor) throw new Error('结果游标未前进，请刷新重新读取')
      const merged = new Map(rows.value.map((x) => [x.comicId, x]))
      page.items.forEach((x) => merged.set(x.comicId, x))
      rows.value = [...merged.values()]
      results.value = page
    } catch (e) {
      if (!disposed && request === generation) error.value = getApiErrorMessage(e, '读取后续恢复结果失败')
    } finally {
      if (!disposed && request === generation) moreLoading.value = false
    }
  }
  async function submit() {
    if (!canSubmit.value) return
    busy.value = true
    error.value = ''
    notice.value = ''
    try {
      if (!uncertain.value) {
        if (props.mode === 'repair') {
          const before = binding()
          await source()
          if (!canRepair.value || before !== binding()) {
            acknowledged.value = false
            throw new Error('占位或资料版本已变化，本次未提交，请重新核对')
          }
        }
        receipt.value = {
          comic: props.comicId,
          body: props.mode === 'repair' ? repairBody(comic.value!) : null,
          key: crypto.randomUUID(),
        }
      }
      if (!receipt.value) throw new Error('缺少本次明确受理范围')
      attempted.value = true
      const created =
        props.mode === 'scan'
          ? await recoveryCommands.create(receipt.value.key)
          : await recoveryCommands.repair(receipt.value.comic, receipt.value.body!, receipt.value.key)
      if (
        created.type !== (props.mode === 'scan' ? 'disk_recovery' : 'recovery_repair') ||
        (props.mode === 'repair' && created.targetId !== props.comicId)
      )
        throw new Error('任务受理响应与当前恢复范围不一致，请到任务中心核对')
      if (disposed) return
      task.value = created
      acknowledged.value = false
      notice.value = '任务已受理，已写入的数据会保留。任务结束后仍需核对实际分类或补全结果。'
      accepted(created)
      await refresh()
    } catch (e) {
      if (!disposed) {
        if (e instanceof ApiError && e.status >= 400 && e.status < 500) {
          attempted.value = false
          receipt.value = null
          acknowledged.value = false
        }
        error.value = getApiErrorMessage(e, '受理结果不确定，请用原请求重试或到任务中心查看')
      }
    } finally {
      if (!disposed) busy.value = false
    }
  }
  async function action(kind: 'cancel' | 'retry') {
    if (
      !task.value ||
      busy.value ||
      loading.value ||
      moreLoading.value ||
      (kind === 'cancel' ? !task.value.canCancel : !task.value.canRetry)
    )
      return
    busy.value = true
    error.value = ''
    try {
      const updated = await recoveryCommands.task(task.value.id, kind)
      if (updated.id !== task.value.id) throw new Error('任务身份变化，请刷新核对')
      task.value = updated
      rows.value = []
      results.value = null
      repairResult.value = null
      notice.value =
        kind === 'cancel'
          ? '取消请求已受理，已提交的数据不会回滚。'
          : '原任务重新受理。恢复成功项不重复执行，仍需查看实际结果。'
      accepted(updated)
      await refresh()
    } catch (e) {
      error.value = getApiErrorMessage(e, '操作未获确认，请刷新查看')
    } finally {
      busy.value = false
    }
  }
  async function startNew() {
    if (busy.value || loading.value || running.value || sourceLoading.value || moreLoading.value) return
    task.value = null
    results.value = null
    rows.value = []
    repairResult.value = null
    receipt.value = null
    attempted.value = false
    acknowledged.value = false
    notice.value = ''
    error.value = ''
    if (props.mode === 'repair') await source()
  }
  async function boot() {
    if (props.mode === 'repair') await source()
    if (props.taskId) {
      try {
        const loaded = await taskApi.detail(props.taskId)
        if (disposed) return
        if (loaded.type !== (props.mode === 'scan' ? 'disk_recovery' : 'recovery_repair'))
          throw new Error('任务类型与当前恢复入口不一致')
        task.value = loaded
        await refresh()
      } catch (e) {
        if (!disposed) error.value = getApiErrorMessage(e, '无法读取指定恢复任务')
      }
    }
  }
  void boot().finally(() => {
    initializing.value = false
  })
  const timer = window.setInterval(() => {
    if (
      !document.hidden &&
      running.value &&
      !busy.value &&
      !loading.value &&
      !moreLoading.value &&
      !sourceLoading.value
    )
      void refresh()
  }, 2000)
  onBeforeUnmount(() => {
    disposed = true
    ++generation
    ++sourceGeneration
    window.clearInterval(timer)
  })
  return {
    task,
    results,
    rows,
    repairResult,
    comic,
    marker,
    fresh,
    busy,
    loading,
    moreLoading,
    sourceLoading,
    error,
    notice,
    acknowledged,
    uncertain,
    running,
    canRepair,
    canSubmit,
    source,
    refresh,
    more,
    submit,
    action,
    startNew,
  }
}
