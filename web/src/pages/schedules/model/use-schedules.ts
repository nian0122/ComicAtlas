import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { scheduleApi, type Schedule, type ScheduleRun } from '@/entities/schedule'
import { taskApi } from '@/entities/task'
import { getApiErrorMessage } from '@/shared/api/http'

export type IntervalUnit = 'seconds' | 'minutes' | 'hours' | 'days'
interface IntervalDraft {
  amount: number
  unit: IntervalUnit
}
const unitSeconds: Record<IntervalUnit, number> = { seconds: 1, minutes: 60, hours: 3600, days: 86400 }
const activePollMs = 2000
const idlePollMs = 15000

export function useSchedules() {
  const schedules = ref<Schedule[]>([])
  const drafts = ref<Record<string, IntervalDraft>>({})
  const dirty = ref<Record<string, boolean>>({})
  const loading = ref(true)
  const error = ref('')
  const notice = ref('')
  const runningId = ref('')
  const savingId = ref('')
  const activeTasks = ref(0)
  const trackedRuns = ref<Record<string, ScheduleRun>>({})
  let timer: ReturnType<typeof setTimeout> | undefined
  let requestSequence = 0
  let disposed = false

  const summary = computed(() => {
    const running = schedules.value.filter(isRunning).length
    const paused = schedules.value.filter((item) => !item.enabled).length
    return `共 ${schedules.value.length} 个定时器 · 运行中 ${running} · 已暂停 ${paused}`
  })

  function resetDraft(item: Schedule) {
    let unit: IntervalUnit = 'hours'
    let amount = item.intervalSeconds / 3600
    if (item.intervalSeconds % 86400 === 0) {
      unit = 'days'
      amount = item.intervalSeconds / 86400
    } else if (item.intervalSeconds % 60 !== 0) {
      unit = 'seconds'
      amount = item.intervalSeconds
    } else if (item.intervalSeconds % 3600 !== 0) {
      unit = 'minutes'
      amount = item.intervalSeconds / 60
    }
    drafts.value[item.id] = { amount, unit }
    dirty.value[item.id] = false
  }
  function draft(item: Schedule): IntervalDraft {
    if (!drafts.value[item.id]) resetDraft(item)
    return drafts.value[item.id]!
  }
  function setDraft(id: string, amount: number, unit: IntervalUnit) {
    drafts.value[id] = { amount, unit }
    dirty.value[id] = true
  }
  function isDirty(id: string) {
    return !!dirty.value[id]
  }
  function triggerLabel(trigger: ScheduleRun['trigger']) {
    return (
      ({ startup: '启动', schedule: '定时', manual: '手动', auto: '自动补算' } as Record<string, string>)[trigger] ??
      trigger
    )
  }
  function runResult(run: ScheduleRun) {
    if (!run.finishedAt) return '进行中'
    if (run.error === 'interrupted') return '进程中断'
    if (run.error === 'cancelled') return '已取消'
    if (run.error) return '失败'
    return '完成'
  }
  function isRunning(item: Schedule) {
    return item.running || Boolean(trackedRuns.value[item.id])
  }
  function stateLabel(item: Schedule) {
    if (isRunning(item)) return '运行中'
    if (!item.enabled) return '已暂停'
    if (item.lastRun?.error) return '上次失败'
    return '按计划执行'
  }
  function stateClass(item: Schedule) {
    if (isRunning(item)) return 'is-running'
    if (!item.enabled) return 'is-paused'
    if (item.lastRun?.error) return 'is-error'
    return 'is-idle'
  }

  async function load(silent = false) {
    clearTimeout(timer)
    const sequence = ++requestSequence
    if (!silent) loading.value = true
    try {
      const [items, taskSummary] = await Promise.all([scheduleApi.list(20), taskApi.summary().catch(() => null)])
      if (disposed || sequence !== requestSequence) return
      schedules.value = items
      if (taskSummary) activeTasks.value = taskSummary.running + taskSummary.queued + taskSummary.cancelling
      for (const item of items) {
        if (!drafts.value[item.id] || !dirty.value[item.id]) resetDraft(item)
        const tracked = trackedRuns.value[item.id]
        if (
          tracked &&
          item.runs.some((entry) => (entry.id === tracked.id ? entry.finishedAt : entry.startedAt > tracked.startedAt))
        ) {
          delete trackedRuns.value[item.id]
        }
      }
      error.value = ''
    } catch (e) {
      if (!disposed && sequence === requestSequence) error.value = getApiErrorMessage(e, '无法读取定时器')
    } finally {
      if (!disposed && sequence === requestSequence) {
        loading.value = false
        poll()
      }
    }
  }
  function poll() {
    clearTimeout(timer)
    if (document.hidden) return
    const delay = schedules.value.some(isRunning) ? activePollMs : idlePollMs
    timer = setTimeout(() => void load(true), delay)
  }
  function onVisibilityChange() {
    clearTimeout(timer)
    if (!document.hidden) void load(true)
  }
  async function run(id: string) {
    if (runningId.value || savingId.value || trackedRuns.value[id]) return
    runningId.value = id
    notice.value = ''
    try {
      const execution = await scheduleApi.run(id)
      if (disposed) return
      trackedRuns.value[id] = execution
      notice.value = '已开始执行，完成后会自动更新状态与日志。'
      await load(true)
    } catch (e) {
      notice.value = getApiErrorMessage(e, '无法启动执行')
      if (!disposed) await load(true)
    } finally {
      runningId.value = ''
    }
  }
  async function setEnabled(item: Schedule, enabled: boolean) {
    if (runningId.value || savingId.value) return
    savingId.value = item.id
    notice.value = ''
    try {
      await scheduleApi.update(item.id, { enabled })
      if (disposed) return
      notice.value = enabled ? `已启用「${item.title}」` : `已暂停「${item.title}」`
      await load(true)
    } catch (e) {
      notice.value = getApiErrorMessage(e, '无法更新启用状态')
      if (!disposed) await load(true)
    } finally {
      savingId.value = ''
    }
  }
  async function saveInterval(item: Schedule) {
    if (runningId.value || savingId.value) return
    const submitted = draft(item)
    const seconds = submitted.amount * unitSeconds[submitted.unit]
    if (!Number.isInteger(seconds) || seconds < 60 || seconds > 604800) {
      notice.value = '执行间隔必须在 1 分钟到 7 天之间'
      return
    }
    savingId.value = item.id
    notice.value = ''
    try {
      const updated = await scheduleApi.update(item.id, { intervalSeconds: seconds })
      if (disposed) return
      if (drafts.value[item.id] === submitted) resetDraft(updated)
      notice.value = `「${item.title}」的执行间隔已更新`
      await load(true)
    } catch (e) {
      notice.value = getApiErrorMessage(e, '无法更新执行间隔')
    } finally {
      savingId.value = ''
    }
  }

  onMounted(() => {
    document.addEventListener('visibilitychange', onVisibilityChange)
    void load()
  })
  onBeforeUnmount(() => {
    disposed = true
    clearTimeout(timer)
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  return {
    schedules,
    loading,
    error,
    notice,
    runningId,
    savingId,
    activeTasks,
    summary,
    draft,
    setDraft,
    isDirty,
    isRunning,
    triggerLabel,
    runResult,
    stateLabel,
    stateClass,
    load,
    run,
    setEnabled,
    saveInterval,
  }
}
