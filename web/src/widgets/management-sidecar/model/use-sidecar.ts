import { ref, computed, onBeforeUnmount } from 'vue'
import { comicApi, type ComicDetailVO } from '@/entities/comic'
import { sidecarApi, type SidecarStatus, type SidecarResult } from '@/entities/sidecar'
import { taskApi, type Task } from '@/entities/task'
import { sidecarBinding, sidecarCommands } from '@/features/sync-sidecar'
import { getApiErrorMessage } from '@/shared/api/http'
export function useSidecar(
  props: { comicId: string; taskId: string; writable: boolean },
  accepted: (task: Task) => void,
) {
  const comicId = ref(props.comicId),
    comic = ref<ComicDetailVO | null>(null),
    status = ref<SidecarStatus | null>(null)
  const task = ref<Task | null>(null),
    result = ref<SidecarResult | null>(null),
    selectedTaskId = ref(props.taskId)
  const busy = ref(false),
    loading = ref(true),
    fresh = ref(false),
    acknowledged = ref(false)
  const statusError = ref(''),
    taskError = ref(''),
    comicError = ref(''),
    error = ref(''),
    notice = ref('')
  const receipt = ref<{ comic: string; key: string } | null>(null)
  let disposed = false,
    refreshing = false,
    timer: ReturnType<typeof setTimeout> | undefined
  const running = computed(() => !!task.value && ['queued', 'running', 'cancelling'].includes(task.value.status))
  const canSubmit = computed(
    () =>
      !busy.value &&
      !loading.value &&
      props.writable &&
      (receipt.value !== null ||
        (fresh.value && acknowledged.value && !!status.value && !['blocked', 'syncing'].includes(status.value.state))),
  )
  const historical = computed(() => !!task.value && !!status.value && status.value.taskId !== task.value.id)
  async function readStatus() {
    fresh.value = false
    statusError.value = ''
    try {
      const value = await sidecarApi.status(comicId.value)
      if (disposed) return
      if (!status.value || sidecarBinding(status.value) !== sidecarBinding(value)) acknowledged.value = false
      status.value = value
      fresh.value = true
    } catch (e) {
      if (!disposed) statusError.value = getApiErrorMessage(e, '读取侧车状态失败')
    }
  }
  async function readComic() {
    comicError.value = ''
    try {
      const value = await comicApi.detail(comicId.value)
      if (!disposed) comic.value = value
    } catch (e) {
      if (!disposed) comicError.value = getApiErrorMessage(e, '当前漫画资料不可读取，仍可核对历史任务')
    }
  }
  async function readTask(id: string) {
    taskError.value = ''
    try {
      const value = await taskApi.detail(id)
      if (value.type !== 'metadata_sync' || !value.comicId || (comicId.value && value.comicId !== comicId.value))
        throw new Error('此任务不是当前漫画的侧车同步任务')
      if (disposed) return
      if (task.value?.id !== value.id || task.value?.attempt !== value.attempt) result.value = null
      comicId.value = value.comicId
      task.value = value
      const observed = await sidecarApi.result(id, value.comicId)
      if (!disposed) result.value = observed
    } catch (e) {
      if (!disposed) taskError.value = getApiErrorMessage(e, '读取侧车任务结果失败')
    }
  }
  function schedule() {
    if (disposed) return
    const value = status.value
    if (running.value || (value && (['pending', 'syncing'].includes(value.state) || !!value.retryAt)))
      timer = setTimeout(() => {
        if (document.hidden) schedule()
        else void refresh()
      }, 3000)
  }
  async function refresh() {
    if (refreshing || busy.value || disposed) return
    refreshing = true
    loading.value = true
    clearTimeout(timer)
    try {
      let initialTaskRead = false
      if (!comicId.value && selectedTaskId.value) {
        await readTask(selectedTaskId.value)
        initialTaskRead = true
      }
      if (!comicId.value || disposed) return
      await Promise.all([readStatus(), readComic()])
      if (disposed) return
      const id = selectedTaskId.value || status.value?.taskId
      if (id && !initialTaskRead) await readTask(id)
    } finally {
      refreshing = false
      if (!disposed) {
        loading.value = false
        schedule()
      }
    }
  }
  async function submit() {
    if (!canSubmit.value) return
    busy.value = true
    error.value = ''
    notice.value = ''
    clearTimeout(timer)
    try {
      if (!receipt.value) {
        const before = sidecarBinding(status.value!)
        await readStatus()
        if (disposed) return
        if (!fresh.value || !status.value) throw new Error('当前同步状态未核实，请刷新后再确认')
        if (before !== sidecarBinding(status.value)) {
          notice.value = '同步状态已变化，请重新核对并确认。'
          return
        }
        if (['blocked', 'syncing'].includes(status.value.state)) throw new Error('当前暂不可受理新的侧车同步')
        receipt.value = { comic: comicId.value, key: crypto.randomUUID() }
      }
      const value = await sidecarCommands.sync(receipt.value.comic, receipt.value.key)
      if (disposed) return
      task.value = value
      selectedTaskId.value = value.id
      result.value = null
      receipt.value = null
      acknowledged.value = false
      notice.value = '同步任务已受理；实际发布修订和结果请在下方核对。'
      accepted(value)
    } catch (e) {
      if (!disposed) error.value = getApiErrorMessage(e, '同步受理失败')
    } finally {
      busy.value = false
      if (!disposed && !error.value) void refresh()
      else schedule()
    }
  }
  async function retry() {
    if (busy.value || loading.value || !props.writable || !task.value?.canRetry) return
    busy.value = true
    error.value = ''
    notice.value = ''
    clearTimeout(timer)
    try {
      const value = await sidecarCommands.retry(task.value.id)
      if (disposed) return
      task.value = value
      selectedTaskId.value = value.id
      result.value = null
      accepted(value)
      notice.value = '已受理原任务重试，将按后端当前资料执行；不强制覆盖冲突。'
    } catch (e) {
      if (!disposed) error.value = getApiErrorMessage(e, '重试受理失败')
    } finally {
      busy.value = false
      if (!disposed && !error.value) void refresh()
      else schedule()
    }
  }
  function resetReceipt() {
    if (busy.value) return
    receipt.value = null
    acknowledged.value = false
    error.value = ''
    notice.value = '已放弃原受理参数。重新核对后可明确开始另一轮同步。'
    void refresh()
  }
  onBeforeUnmount(() => {
    disposed = true
    clearTimeout(timer)
  })
  void refresh()
  return {
    comicId,
    comic,
    status,
    task,
    result,
    busy,
    loading,
    fresh,
    acknowledged,
    statusError,
    taskError,
    comicError,
    error,
    notice,
    receipt,
    canSubmit,
    historical,
    refresh,
    submit,
    retry,
    resetReceipt,
  }
}
