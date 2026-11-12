import { ref, computed, onBeforeUnmount } from 'vue'
import {
  directoryMoveApi,
  type DirectoryTarget,
  type DirectoryPreview,
  type DirectoryResults,
} from '@/entities/directory-move'
import {
  directoryMoveCommands,
  directoryIds,
  confirmable,
  loadDirectorySession,
  saveDirectorySession,
  lastDirectorySession,
  type DirectorySession,
} from '@/features/move-directory'
import { taskApi, type Task } from '@/entities/task'
import { ApiError, getApiErrorMessage } from '@/shared/api/http'
export function useDirectoryMove(
  props: { targets: DirectoryTarget[]; previewId: string },
  accepted: (task: Task) => void,
) {
  const targets = ref<DirectoryTarget[]>(props.targets.map((x) => ({ ...x }))),
    preview = ref<DirectoryPreview | null>(null),
    receipt = ref<DirectorySession | null>(null)
  const preflight = ref<Task | null>(null),
    move = ref<Task | null>(null),
    results = ref<DirectoryResults | null>(null),
    page = ref(1)
  const busy = ref(false),
    loading = ref(false),
    fresh = ref(false),
    acknowledged = ref(false),
    error = ref(''),
    notice = ref(''),
    storageError = ref(''),
    now = ref(Date.now())
  let generation = 0,
    disposed = false
  const active = (task: Task | null) => !!task && ['queued', 'running', 'cancelling'].includes(task.status)
  const expired = computed(() => !!preview.value?.expiresAt && Date.parse(preview.value.expiresAt) <= now.value)
  const unresolved = computed(() => !!receipt.value?.attempted && !receipt.value.rejected && !move.value)
  const ready = computed(
    () =>
      fresh.value &&
      !receipt.value?.rejected &&
      targets.value.length === preview.value?.bookCount &&
      confirmable(preview.value, receipt.value?.token ?? '', now.value),
  )
  const canConfirm = computed(
    () => !busy.value && !loading.value && !move.value && (unresolved.value || (ready.value && acknowledged.value)),
  )
  const canCreate = computed(
    () =>
      targets.value.length > 0 &&
      !busy.value &&
      !loading.value &&
      !unresolved.value &&
      !move.value &&
      !active(preflight.value),
  )
  function store() {
    if (!receipt.value) return
    try {
      saveDirectorySession(receipt.value)
      storageError.value = ''
    } catch {
      storageError.value =
        '当前标签页无法保存确认信息。请保持此窗口；若刷新后凭据丢失，只能查看状态，不能重新取得凭据。'
    }
  }
  function stamp(value: DirectoryPreview | null) {
    return value
      ? JSON.stringify([
          value.id,
          value.taskId,
          value.bookCount,
          value.fileCount,
          value.bytes,
          value.expiresAt,
          value.ready,
          value.moveTaskId,
        ])
      : ''
  }
  async function refresh() {
    if (!preview.value || loading.value) return
    const id = preview.value.id,
      request = ++generation
    loading.value = true
    fresh.value = false
    error.value = ''
    try {
      const view = await directoryMoveApi.preview(id)
      if (view.id !== id || (targets.value.length && view.bookCount !== targets.value.length))
        throw new Error('预检响应与当前作品范围不一致')
      const [task, moved] = await Promise.all([
        taskApi.detail(view.taskId),
        view.moveTaskId
          ? taskApi.detail(view.moveTaskId)
          : move.value
            ? taskApi.detail(move.value.id)
            : Promise.resolve(null),
      ])
      const nextPage = moved?.id !== move.value?.id ? 1 : page.value
      let files = moved ? await directoryMoveApi.results(moved.id, nextPage) : null
      let actualPage = nextPage
      if (files && nextPage > Math.max(1, Math.ceil(files.total / 20))) {
        actualPage = Math.max(1, Math.ceil(files.total / 20))
        files = await directoryMoveApi.results(moved!.id, actualPage)
      }
      if (disposed || request !== generation) return
      if (stamp(view) !== stamp(preview.value)) acknowledged.value = false
      preview.value = view
      preflight.value = task
      move.value = moved
      results.value = files
      page.value = actualPage
      fresh.value = true
    } catch (e) {
      if (!disposed && request === generation) error.value = getApiErrorMessage(e, '无法刷新移出状态，已停止新的确认')
    } finally {
      if (!disposed && request === generation) loading.value = false
    }
  }
  async function create() {
    if (!canCreate.value) return
    busy.value = true
    error.value = ''
    notice.value = ''
    acknowledged.value = false
    fresh.value = false
    try {
      const ids = directoryIds(targets.value)
      const view = await directoryMoveCommands.create(targets.value)
      if (disposed) return
      preview.value = view
      move.value = null
      preflight.value = null
      results.value = null
      page.value = 1
      fresh.value = false
      if (view.bookCount !== ids.length || !view.id || !view.taskId)
        throw new Error('受理响应与所选范围不一致，请到任务中心核对')
      if (!view.confirmationToken) throw new Error('创建响应未提供确认凭据。只能查看或取消预检，不能从状态接口补取凭据')
      receipt.value = {
        id: view.id,
        token: view.confirmationToken,
        targets: targets.value.map((x) => ({ ...x })),
        key: crypto.randomUUID(),
        attempted: false,
        rejected: false,
      }
      store()
      notice.value = '只读预检已受理，完成后请核对数量和移出影响。未确认前不移动原件。'
      await refresh()
    } catch (e) {
      if (!disposed)
        error.value =
          getApiErrorMessage(e, '创建结果未确认，请到任务中心检查，避免重复占用预检名额') +
          '；创建接口不支持幂等重放，未确认结果时请先查看任务中心。'
    } finally {
      if (!disposed) busy.value = false
    }
  }
  async function confirm() {
    if (!canConfirm.value || !receipt.value || !preview.value) return
    busy.value = true
    error.value = ''
    notice.value = ''
    try {
      if (!unresolved.value) {
        const before = stamp(preview.value)
        await refresh()
        if (
          !fresh.value ||
          !confirmable(preview.value, receipt.value.token, Date.now()) ||
          before !== stamp(preview.value)
        ) {
          acknowledged.value = false
          throw new Error('预检或有效期已变化，本次未提交，请重新核对')
        }
      }
      receipt.value.attempted = true
      store()
      const task = await directoryMoveCommands.confirm(receipt.value.id, receipt.value.token, receipt.value.key)
      if (
        task.type !== 'directory_move' ||
        task.targetType !== 'directory_preview' ||
        task.targetId !== receipt.value.id
      )
        throw new Error('确认响应与预检不一致，请到任务中心核对实际结果')
      if (disposed) return
      move.value = task
      acknowledged.value = false
      notice.value = '移出任务已受理且不可取消。原件可能开始搬移，尚未表示整批发布或脱管成功。'
      accepted(task)
      await refresh()
    } catch (e) {
      if (disposed) return
      if (e instanceof ApiError && e.status >= 400 && e.status < 500 && receipt.value) {
        receipt.value.rejected = true
        receipt.value.attempted = false
        acknowledged.value = false
        store()
      }
      error.value = getApiErrorMessage(e, '确认结果未获响应，可用原凭据和同一幂等键重试')
    } finally {
      if (!disposed) busy.value = false
    }
  }
  async function action(kind: 'cancel' | 'retry') {
    const task = move.value ?? preflight.value
    if (!task || busy.value || loading.value || (kind === 'cancel' ? !!move.value || !task.canCancel : !task.canRetry))
      return
    busy.value = true
    error.value = ''
    try {
      const updated = await directoryMoveCommands.task(task.id, kind)
      if (updated.id !== task.id) throw new Error('任务响应身份变化，请刷新后核对')
      if (move.value) move.value = updated
      else preflight.value = updated
      notice.value =
        kind === 'cancel'
          ? '取消请求已受理，请等待实际终态。'
          : '原任务重新受理，后台仍会完整核验现场，不表示处理成功。'
      accepted(updated)
      await refresh()
    } catch (e) {
      error.value = getApiErrorMessage(e, '任务操作未获确认，请刷新查看')
    } finally {
      busy.value = false
    }
  }
  async function paginate(next: number) {
    if (loading.value || busy.value || next < 1) return
    page.value = next
    await refresh()
  }
  async function boot() {
    try {
      let saved = props.previewId ? loadDirectorySession(props.previewId) : lastDirectorySession()
      if (
        !props.previewId &&
        saved &&
        JSON.stringify(saved.targets.map((x) => x.id).sort()) !== JSON.stringify(targets.value.map((x) => x.id).sort())
      )
        saved = null
      if (saved) {
        receipt.value = saved
        targets.value = saved.targets.map((x) => ({ ...x }))
      }
      const id = props.previewId || saved?.id
      if (id) {
        preview.value = { id, taskId: '', status: 'queued', ready: false, bookCount: targets.value.length }
        await refresh()
      }
    } catch (e) {
      error.value = getApiErrorMessage(e, '无法读取当前标签页预检，请到任务中心核对')
    }
  }
  void boot()
  const timer = window.setInterval(() => {
    now.value = Date.now()
    if (
      !disposed &&
      !document.hidden &&
      !busy.value &&
      !loading.value &&
      (active(preflight.value) || active(move.value))
    )
      void refresh()
  }, 2000)
  onBeforeUnmount(() => {
    disposed = true
    ++generation
    window.clearInterval(timer)
  })
  return {
    targets,
    preview,
    receipt,
    preflight,
    move,
    results,
    page,
    busy,
    loading,
    fresh,
    acknowledged,
    error,
    notice,
    storageError,
    expired,
    unresolved,
    ready,
    canConfirm,
    canCreate,
    refresh,
    create,
    confirm,
    action,
    paginate,
  }
}
