import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { taskApi, type Task } from '@/entities/task'
import { importScanApi, archiveImportPaths, type ImportCandidate, type ImportBatch } from '@/entities/import-scan'
import { importCommands } from '@/features/manage-imports'
import { managementCommands } from '@/features/manage-items'
import { getApiErrorMessage } from '@/shared/api/http'
export function useImportScan(props: { open: boolean; taskId: string }, accepted: (task: Task) => void) {
  const mode = ref<'directory' | 'archives'>('directory')
  const archives = ref('')
  const source = ref(''),
    scan = ref<Task | null>(null),
    candidates = ref<ImportCandidate[]>([]),
    selected = ref<ImportCandidate[]>([])
  const page = ref(1),
    total = ref(0),
    busy = ref(false),
    loading = ref(false),
    error = ref(''),
    confirm = ref(false),
    batch = ref<ImportBatch | null>(null)
  let generation = 0,
    disposed = false,
    polling = false
  const scanKey = ref(crypto.randomUUID()),
    importKey = ref(crypto.randomUUID())
  const active = computed(() => scan.value && ['queued', 'running', 'cancelling'].includes(scan.value.status))
  const archiveCount = computed(() => archives.value.split(/\r?\n/).filter((line) => line.trim()).length)
  const pageSelected = computed(
    () =>
      candidates.value.some((c) => c.selectable) &&
      candidates.value.filter((c) => c.selectable).every((c) => selected.value.some((s) => s.id === c.id)),
  )
  watch([archives, mode], () => {
    confirm.value = false
    importKey.value = crypto.randomUUID()
  })
  watch(source, () => {
    if (!busy.value) scanKey.value = crypto.randomUUID()
  })
  watch(
    () =>
      selected.value
        .map((x) => x.id)
        .sort()
        .join(','),
    () => {
      importKey.value = crypto.randomUUID()
      confirm.value = false
    },
  )
  async function results(targetPage = page.value) {
    if (!scan.value || scan.value.status !== 'succeeded') return
    const id = scan.value.id,
      current = generation
    loading.value = true
    try {
      const value = await importScanApi.candidates(id, targetPage)
      if (current === generation && !disposed) {
        candidates.value = value.items
        total.value = value.total
        page.value = value.page
      }
    } finally {
      if (current === generation && !disposed) loading.value = false
    }
  }
  async function refresh() {
    if ((!scan.value && !batch.value) || polling) return
    polling = true
    const current = generation
    try {
      if (scan.value) {
        const task = await taskApi.detail(scan.value.id)
        if (current !== generation || disposed) return
        const newlyReady = scan.value.status !== 'succeeded' && task.status === 'succeeded'
        scan.value = task
        if (!batch.value && (newlyReady || (task.status === 'succeeded' && !candidates.value.length))) await results()
      }
      if (batch.value) {
        const value = await importScanApi.batch(batch.value.id)
        if (current === generation && !disposed) batch.value = value
      }
      if (current === generation && !disposed) error.value = ''
    } catch (e) {
      if (current === generation && !disposed) error.value = getApiErrorMessage(e, '读取扫描失败')
    } finally {
      polling = false
    }
  }
  watch(
    () => [props.open, props.taskId],
    async () => {
      if (!props.open) return
      if (props.taskId && scan.value?.id !== props.taskId) {
        generation++
        selected.value = []
        candidates.value = []
        total.value = 0
        page.value = 1
        error.value = ''
        batch.value = null
        confirm.value = false
        scan.value = null
        loading.value = true
        const current = generation
        try {
          const value = await taskApi.detail(props.taskId)
          if (current !== generation || disposed) return
          scan.value = value
        } catch (e) {
          if (current === generation && !disposed) error.value = getApiErrorMessage(e, '恢复扫描失败')
        } finally {
          if (current === generation && !disposed) loading.value = false
        }
      }
      if (props.taskId && scan.value?.id === props.taskId && scan.value.status === 'succeeded') {
        try {
          if (!batch.value) await results(page.value)
        } catch (e) {
          error.value = getApiErrorMessage(e, '读取候选失败')
        }
      }
      await refresh()
    },
    { immediate: true },
  )
  async function start() {
    if (busy.value) return
    busy.value = true
    error.value = ''
    try {
      const task = await importCommands.scan(source.value, scanKey.value)
      generation++
      scan.value = task
      candidates.value = []
      selected.value = []
      page.value = 1
      total.value = 0
      batch.value = null
      confirm.value = false
      accepted(task)
      await refresh()
    } catch (e) {
      error.value = getApiErrorMessage(e, '扫描提交失败')
    } finally {
      busy.value = false
    }
  }
  function toggle(candidate: ImportCandidate) {
    if (busy.value || !candidate.selectable) return
    if (selected.value.some((c) => c.id === candidate.id)) {
      selected.value = selected.value.filter((c) => c.id !== candidate.id)
      return
    }
    error.value = ''
    selected.value = [...selected.value, candidate]
  }
  function togglePage() {
    if (busy.value || loading.value) return
    const available = candidates.value.filter((candidate) => candidate.selectable)
    if (pageSelected.value)
      selected.value = selected.value.filter((candidate) => !available.some((item) => item.id === candidate.id))
    else
      selected.value = [
        ...selected.value,
        ...available.filter((candidate) => !selected.value.some((item) => item.id === candidate.id)),
      ]
  }
  async function submitArchives() {
    if (busy.value || !confirm.value) return
    error.value = ''
    busy.value = true
    try {
      const paths = archiveImportPaths(archives.value)
      batch.value = await importCommands.archives(paths, importKey.value)
      try {
        accepted(await taskApi.detail(batch.value.taskId))
      } catch (e) {
        throw new Error(`批次已受理，读取父任务失败：${getApiErrorMessage(e, '请刷新结果')}`, { cause: e })
      }
    } catch (e) {
      error.value = getApiErrorMessage(e, '压缩包批量导入提交失败')
    } finally {
      busy.value = false
    }
  }
  async function changePage(target: number) {
    if (loading.value || busy.value) return
    error.value = ''
    try {
      await results(target)
    } catch (e) {
      error.value = getApiErrorMessage(e, '读取候选失败')
    }
  }
  async function submit() {
    if (busy.value || !scan.value || !selected.value.length || !confirm.value) return
    busy.value = true
    error.value = ''
    try {
      batch.value = await importCommands.import(
        scan.value.id,
        selected.value.map((c) => c.id),
        importKey.value,
      )
      accepted(await taskApi.detail(batch.value.taskId))
    } catch (e) {
      error.value = getApiErrorMessage(e, batch.value ? '批次已受理，读取父任务失败，请刷新结果' : '批量导入提交失败')
    } finally {
      busy.value = false
    }
  }
  async function cancel() {
    if (!scan.value || !scan.value.canCancel || busy.value) return
    busy.value = true
    error.value = ''
    try {
      scan.value = await requestAction(scan.value.id, 'cancel')
    } catch (e) {
      error.value = getApiErrorMessage(e, '取消失败')
    } finally {
      busy.value = false
    }
  }
  async function retry() {
    if (!scan.value || !scan.value.canRetry || busy.value) return
    busy.value = true
    error.value = ''
    try {
      scan.value = await requestAction(scan.value.id, 'retry')
      await refresh()
    } catch (e) {
      error.value = getApiErrorMessage(e, '重试失败')
    } finally {
      busy.value = false
    }
  }
  async function requestAction(id: string, action: 'cancel' | 'retry') {
    const task = await managementCommands.task(id, action)
    accepted(task)
    return task
  }
  const timer = window.setInterval(() => {
    if (
      props.open &&
      (active.value || (batch.value && ['queued', 'running', 'cancelling'].includes(batch.value.status)))
    )
      void refresh()
  }, 2000)
  onBeforeUnmount(() => {
    disposed = true
    generation++
    window.clearInterval(timer)
  })
  async function returnCandidates() {
    if (busy.value || !batch.value || ['queued', 'running', 'cancelling'].includes(batch.value.status)) return
    batch.value = null
    selected.value = []
    confirm.value = false
    importKey.value = crypto.randomUUID()
    if (scan.value) await changePage(1)
    else reset()
  }
  function reset() {
    generation++
    scan.value = null
    batch.value = null
    candidates.value = []
    selected.value = []
    total.value = 0
    error.value = ''
    confirm.value = false
    scanKey.value = crypto.randomUUID()
    importKey.value = crypto.randomUUID()
    archives.value = ''
  }
  return {
    returnCandidates,
    source,
    scan,
    candidates,
    selected,
    page,
    total,
    busy,
    loading,
    error,
    confirm,
    batch,
    active,
    mode,
    archives,
    archiveCount,
    pageSelected,
    togglePage,
    submitArchives,
    start,
    refresh,
    results: changePage,
    toggle,
    submit,
    cancel,
    retry,
    reset,
  }
}
