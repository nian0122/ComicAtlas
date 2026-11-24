import { computed, onBeforeUnmount, ref } from 'vue'
import { directoryMoveApi, type DirectoryTarget, type DirectoryResults } from '@/entities/directory-move'
import { directoryMoveCommands } from '@/features/move-directory'
import { taskApi, type Task } from '@/entities/task'
import { getApiErrorMessage } from '@/shared/api/http'

export function useDirectoryMove(
  props: { targets: DirectoryTarget[]; taskId: string },
  accepted: (task: Task) => void,
) {
  const targets = ref(props.targets.map((x) => ({ ...x })))
  const task = ref<Task | null>(null)
  const results = ref<DirectoryResults | null>(null)
  const page = ref(1)
  const busy = ref(false)
  const loading = ref(false)
  const acknowledged = ref(false)
  const error = ref('')
  const notice = ref('')
  const key = ref('')
  let generation = 0
  let disposed = false
  const active = (value: Task | null) => !!value && ['queued', 'running', 'cancelling'].includes(value.status)
  const canCreate = computed(() => targets.value.length > 0 && !busy.value && !loading.value && !task.value)
  const canSubmit = computed(() => canCreate.value && acknowledged.value)
  const canRetry = computed(() => !busy.value && !loading.value && !!task.value?.canRetry)
  const succeeded = computed(() => task.value?.status === 'succeeded')
  const progress = computed(() =>
    task.value?.totalCount ? `${task.value.completedCount} / ${task.value.totalCount}` : '',
  )
  async function refresh() {
    const current = task.value
    if (!current || loading.value) return
    const request = ++generation
    loading.value = true
    error.value = ''
    try {
      const [view, files] = await Promise.all([
        taskApi.detail(current.id),
        directoryMoveApi.results(current.id, page.value).catch(() => null),
      ])
      if (disposed || request !== generation) return
      task.value = view
      if (files) {
        const lastPage = Math.max(1, Math.ceil(files.total / 20))
        if (page.value > lastPage) {
          page.value = lastPage
          results.value = await directoryMoveApi.results(view.id, lastPage)
        } else {
          results.value = files
        }
      }
      if (view.status === 'succeeded') notice.value = '移出已完成，输出目录已发布。'
    } catch (e) {
      if (!disposed && request === generation) error.value = getApiErrorMessage(e, '无法刷新移出状态')
    } finally {
      if (!disposed && request === generation) loading.value = false
    }
  }
  async function create() {
    if (!canSubmit.value) return
    busy.value = true
    error.value = ''
    notice.value = ''
    try {
      if (!key.value) key.value = crypto.randomUUID()
      const created = await directoryMoveCommands.create(targets.value, key.value)
      if (created.type !== 'directory_move') throw new Error('受理响应不是移出任务')
      task.value = created
      page.value = 1
      notice.value = '移出任务已受理；整批发布和脱管成功后才会从漫画库移除。'
      accepted(created)
      await refresh()
    } catch (e) {
      error.value = getApiErrorMessage(e, '移出受理结果未确认，请到任务中心核对，不要重复创建')
    } finally {
      busy.value = false
    }
  }
  async function retry() {
    if (!canRetry.value || !task.value) return
    busy.value = true
    error.value = ''
    notice.value = ''
    try {
      const updated = await directoryMoveCommands.retry(task.value.id)
      if (updated.id !== task.value.id) throw new Error('任务响应身份变化，请刷新后核对')
      task.value = updated
      notice.value = '原任务已重新受理，后台会核对已发布的现场。'
      accepted(updated)
      await refresh()
    } catch (e) {
      error.value = getApiErrorMessage(e, '任务重试未获确认')
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
    if (!props.taskId) return
    loading.value = true
    try {
      const view = await taskApi.detail(props.taskId)
      if (view.type !== 'directory_move') throw new Error('任务不是文件夹移出')
      task.value = view
    } catch (e) {
      error.value = getApiErrorMessage(e, '无法读取移出任务，请到任务中心核对')
    } finally {
      loading.value = false
    }
    if (task.value) await refresh()
  }
  void boot()
  const timer = window.setInterval(() => {
    if (!disposed && !document.hidden && !busy.value && !loading.value && active(task.value)) void refresh()
  }, 2000)
  onBeforeUnmount(() => {
    disposed = true
    ++generation
    window.clearInterval(timer)
  })
  return {
    targets,
    task,
    results,
    page,
    busy,
    loading,
    acknowledged,
    error,
    notice,
    canSubmit,
    canRetry,
    succeeded,
    progress,
    create,
    retry,
    refresh,
    paginate,
  }
}
