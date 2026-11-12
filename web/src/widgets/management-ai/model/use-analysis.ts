import { computed, ref, onBeforeUnmount } from 'vue'
import { comicApi, type ComicDetailVO, type Term } from '@/entities/comic'
import { taskApi, type Task } from '@/entities/task'
import { analysisApi, type AnalysisResult } from '@/entities/ai-analysis'
import { analysisCommands, reviewBlocker, reviewBody, type ReviewField } from '@/features/analyze-comic'
import { getApiErrorMessage } from '@/shared/api/http'
export function useAnalysis(
  props: { comicId: string; taskId: string; writable: boolean },
  accepted: (task: Task) => void,
) {
  const comic = ref<ComicDetailVO | null>(null),
    categories = ref<Term[]>([]),
    tags = ref<Term[]>([])
  const task = ref<Task | null>(null),
    result = ref<AnalysisResult | null>(null)
  const busy = ref(false),
    loading = ref(true),
    error = ref(''),
    notice = ref(''),
    saved = ref(false),
    writeAttempted = ref(false)
  const fields = ref<ReviewField[]>([]),
    tagIds = ref<string[]>([]),
    sampleCount = ref(10)
  const receipt = ref<{
    id: string
    body: { version: number; structureVersion: number; sampleCount: number }
    key: string
  } | null>(null)
  let disposed = false,
    timer: ReturnType<typeof setTimeout> | undefined
  const running = computed(() => !!task.value && ['queued', 'running', 'cancelling'].includes(task.value.status))
  const blocker = computed(() =>
    !comic.value || !result.value ? '' : reviewBlocker(result.value, comic.value, categories.value, tags.value),
  )
  const canApply = computed(
    () =>
      !busy.value &&
      !loading.value &&
      !saved.value &&
      !!comic.value &&
      !!result.value &&
      !blocker.value &&
      (fields.value.length > 0 || tagIds.value.length > 0),
  )
  async function source(id: string) {
    const [detail, categoryPage, tagPage] = await Promise.all([
      comicApi.detail(id),
      comicApi.categories(),
      comicApi.tags(),
    ])
    if (disposed) return
    comic.value = detail
    categories.value = categoryPage.items
    tags.value = tagPage.items
  }
  async function refresh() {
    if (busy.value) return
    busy.value = true
    error.value = ''
    if (timer) clearTimeout(timer)
    try {
      const id = task.value?.id || props.taskId
      if (id) {
        const value = await taskApi.detail(id)
        if (value.type !== 'ai_analysis' || !value.comicId) throw new Error('此任务不是绑定漫画的 AI 分析')
        if (disposed) return
        task.value = value
        if (value.status !== 'succeeded') {
          result.value = null
          fields.value = []
          tagIds.value = []
        }
        if (value.status === 'succeeded') {
          const candidate = await analysisApi.result(id)
          if (candidate.taskId !== id || candidate.request.comicId !== value.comicId)
            throw new Error('候选来源与任务不一致')
          if (disposed) return
          result.value = candidate
        }
        await source(value.comicId)
      } else await source(props.comicId)
    } catch (e) {
      if (!disposed) error.value = getApiErrorMessage(e, '读取分析失败')
    } finally {
      if (!disposed) {
        busy.value = false
        loading.value = false
        if (running.value) timer = setTimeout(() => void refresh(), 3000)
      }
    }
  }
  async function submit() {
    if (busy.value || loading.value || !comic.value || !props.writable || running.value) return
    if (!Number.isInteger(sampleCount.value) || sampleCount.value < 1 || sampleCount.value > 24) {
      error.value = '抽样张数需为 1—24 的整数'
      return
    }
    busy.value = true
    error.value = ''
    notice.value = ''
    try {
      if (!receipt.value) {
        await source(comic.value.id)
        if (disposed) return
        const c = comic.value
        receipt.value = {
          id: c.id,
          body: { version: c.version, structureVersion: c.structureVersion, sampleCount: sampleCount.value },
          key: crypto.randomUUID(),
        }
      }
      const value = await analysisCommands.create(receipt.value.id, receipt.value.body, receipt.value.key)
      if (disposed) return
      task.value = value
      result.value = null
      fields.value = []
      tagIds.value = []
      saved.value = false
      receipt.value = null
      accepted(value)
      notice.value = '分析已受理，完成后仍需人工选择并保存资料。'
    } catch (e) {
      if (!disposed) error.value = getApiErrorMessage(e, '分析受理失败')
    } finally {
      busy.value = false
      if (!disposed && task.value && !error.value) void refresh()
    }
  }
  async function action(kind: 'retry' | 'cancel') {
    if (!task.value || busy.value || !props.writable) return
    busy.value = true
    error.value = ''
    try {
      const value = await analysisCommands.action(task.value.id, kind)
      if (disposed) return
      task.value = value
      result.value = null
      fields.value = []
      tagIds.value = []
      accepted(value)
    } catch (e) {
      if (!disposed) error.value = getApiErrorMessage(e, '任务操作失败')
    } finally {
      busy.value = false
      if (!disposed && !error.value) void refresh()
    }
  }
  async function apply() {
    if (!canApply.value || !props.writable || !comic.value || !result.value) return
    busy.value = true
    error.value = ''
    notice.value = ''
    try {
      await source(comic.value.id)
      if (disposed) return
      if (blocker.value) throw new Error(blocker.value)
      writeAttempted.value = true
      await analysisCommands.apply(
        comic.value.id,
        reviewBody(result.value, comic.value, { fields: fields.value, tagIds: tagIds.value }),
      )
      if (disposed) return
      saved.value = true
      fields.value = []
      tagIds.value = []
      notice.value = '已保存所选资料。历史候选保持原样，未选字段和已有标签保留。'
      await source(comic.value.id)
    } catch (e) {
      if (!disposed)
        error.value = saved.value
          ? '所选资料已保存，但刷新当前资料失败，请刷新分析状态核对。'
          : getApiErrorMessage(e, '资料保存失败；请刷新核对当前版本')
    } finally {
      if (!disposed) busy.value = false
    }
  }
  function resetReceipt() {
    if (busy.value) return
    receipt.value = null
    notice.value = '下次受理使用当前资料与新的幂等键。'
    void refresh()
  }
  onBeforeUnmount(() => {
    disposed = true
    if (timer) clearTimeout(timer)
  })
  void refresh()
  return {
    comic,
    task,
    result,
    loading,
    busy,
    error,
    notice,
    fields,
    tagIds,
    sampleCount,
    receipt,
    running,
    blocker,
    saved,
    writeAttempted,
    canApply,
    refresh,
    submit,
    action,
    apply,
    resetReceipt,
  }
}
