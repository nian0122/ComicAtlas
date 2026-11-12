import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { comicApi, type Term } from '@/entities/comic'
import {
  metadataApi,
  type MetadataPreview,
  type MetadataItem,
  type MetadataRequest,
  type MetadataFilter,
  type MetadataPatch,
} from '@/entities/metadata-edit'
import { taskApi, type Task } from '@/entities/task'
import { metadataBinding, metadataCommands, validateMetadataRequest } from '@/features/edit-metadata'
import { getApiErrorMessage } from '@/shared/api/http'
import type { MetadataTarget } from './types'
export function useMetadataBatch(
  props: { targets: MetadataTarget[]; previewId: string; writable: boolean },
  accepted: (task: Task) => void,
) {
  const selectionMode = ref<'ids' | 'filter'>(props.targets.length ? 'ids' : 'filter'),
    chosen = ref(props.targets.map((target) => target.id)),
    filter = ref<Required<MetadataFilter>>({ keyword: '', categoryId: '', tagId: '', readStatus: '', reaction: '' }),
    excluded = ref<string[]>([])
  const field = ref({
    titleOn: false,
    title: '',
    authorOn: false,
    author: '',
    descriptionOn: false,
    description: '',
    categoryMode: 'keep',
    category: '',
    addTags: [] as string[],
    removeTags: [] as string[],
  })
  const categories = ref<Term[]>([]),
    tags = ref<Term[]>([]),
    termsError = ref('')
  const preview = ref<MetadataPreview | null>(null),
    rows = ref<MetadataItem[]>([]),
    total = ref(0),
    page = ref(1),
    task = ref<Task | null>(null)
  const history = ref(!!props.previewId),
    restoreId = ref(props.previewId),
    acknowledged = ref(false),
    bound = ref(''),
    fresh = ref(false),
    loading = ref(false),
    busy = ref(false),
    error = ref(''),
    notice = ref('')
  const receipt = ref<{ id: string; key: string } | null>(null),
    now = ref(Date.now())
  let disposed = false,
    generation = 0,
    timer: ReturnType<typeof setTimeout> | undefined
  let localValidationError = false
  function clearError() {
    error.value = ''
    localValidationError = false
  }
  const clock = setInterval(() => {
    now.value = Date.now()
  }, 1000)
  const request = computed<MetadataRequest>(() => {
    const f = field.value,
      patch: MetadataPatch = {}
    if (f.titleOn) patch.title = f.title
    if (f.authorOn) patch.author = f.author
    if (f.descriptionOn) patch.description = f.description
    if (f.categoryMode === 'set') patch.categoryId = f.category
    if (f.categoryMode === 'clear') patch.clearCategory = true
    if (f.addTags.length) patch.addTagIds = [...f.addTags]
    if (f.removeTags.length) patch.removeTagIds = [...f.removeTags]
    return {
      operation: 'metadata_update',
      selection:
        selectionMode.value === 'ids'
          ? { mode: 'ids', ids: [...chosen.value].sort() }
          : {
              mode: 'filter',
              filter: Object.fromEntries(
                Object.entries(filter.value).filter(([, value]) => value !== ''),
              ) as MetadataFilter,
              excludedIds: [...excluded.value].sort(),
            },
      patch,
    }
  })
  const signature = computed(() => JSON.stringify(request.value))
  watch(signature, () => {
    acknowledged.value = false
    if (localValidationError) clearError()
  })
  const stale = computed(() => !!preview.value && !history.value && bound.value !== signature.value)
  const locked = computed(() => history.value || !!preview.value?.taskId || !!receipt.value)
  const dirty = computed(
    () =>
      !preview.value?.taskId &&
      !history.value &&
      (Object.keys(request.value.patch).length > 0 ||
        Object.values(filter.value).some(Boolean) ||
        excluded.value.length > 0 ||
        selectionMode.value !== (props.targets.length ? 'ids' : 'filter') ||
        JSON.stringify([...chosen.value].sort()) !== JSON.stringify(props.targets.map((target) => target.id).sort()) ||
        !!preview.value ||
        !!receipt.value),
  )
  const expired = computed(
    () =>
      !!preview.value &&
      (!Number.isFinite(Date.parse(preview.value.expiresAt)) || Date.parse(preview.value.expiresAt) <= now.value),
  )
  const canConfirm = computed(
    () =>
      !busy.value &&
      !loading.value &&
      props.writable &&
      !!preview.value &&
      (!!receipt.value ||
        (fresh.value &&
          !stale.value &&
          !expired.value &&
          !preview.value.taskId &&
          preview.value.eligibleCount > 0 &&
          acknowledged.value)),
  )
  function schedule() {
    clearTimeout(timer)
    if (!disposed && task.value && ['queued', 'running', 'cancelling'].includes(task.value.status))
      timer = setTimeout(() => {
        if (document.hidden) schedule()
        else void refresh()
      }, 3000)
  }
  async function read(id: string, requestedPage: number, token: number) {
    const [value, records] = await Promise.all([metadataApi.preview(id), metadataApi.items(id, requestedPage)])
    let active: Task | null = null
    if (value.taskId) {
      active = await taskApi.detail(value.taskId)
      if (active.type !== 'metadata_batch' || active.targetId !== value.id) throw new Error('资料批次关联任务不一致')
    }
    if (disposed || token !== generation) return
    preview.value = value
    rows.value = records.items
    total.value = records.total
    task.value = active
    page.value = requestedPage
    fresh.value = true
  }
  async function refresh(requestedPage = page.value) {
    if (!preview.value || busy.value || loading.value) return
    loading.value = true
    fresh.value = false
    clearError()
    const token = ++generation
    try {
      await read(preview.value.id, requestedPage, token)
    } catch (e) {
      if (!disposed && token === generation) error.value = getApiErrorMessage(e, '读取批次失败；旧结果仅供核对')
    } finally {
      if (!disposed && token === generation) {
        loading.value = false
        schedule()
      }
    }
  }
  async function create() {
    if (locked.value || busy.value || loading.value || !props.writable) return
    clearError()
    notice.value = ''
    let body: MetadataRequest
    try {
      body = validateMetadataRequest(JSON.parse(signature.value) as MetadataRequest)
    } catch (e) {
      error.value = getApiErrorMessage(e, '请核对修改与范围')
      localValidationError = true
      return
    }
    busy.value = true
    fresh.value = false
    acknowledged.value = false
    const stamp = JSON.stringify(body),
      token = ++generation
    try {
      const value = await metadataCommands.preview(body)
      if (disposed) return
      preview.value = value
      bound.value = stamp
      acknowledged.value = false
      fresh.value = false
      await read(value.id, 1, token)
      notice.value = '预览已冻结目标与版本；尚未修改漫画资料。'
    } catch (e) {
      if (!disposed) error.value = getApiErrorMessage(e, '创建预览失败；输入已保留')
    } finally {
      if (!disposed) busy.value = false
    }
  }
  async function restore(force = false) {
    if (busy.value || loading.value || receipt.value) return
    if (dirty.value && !force) {
      notice.value = '读取其他预览将放弃当前编辑；请明确确认后继续。'
      return
    }
    const id = restoreId.value.trim()
    if (!id || id.length > 128) {
      error.value = '请输入有效预览编号'
      return
    }
    busy.value = true
    fresh.value = false
    acknowledged.value = false
    clearError()
    notice.value = ''
    const token = ++generation
    try {
      await read(id, 1, token)
      if (!disposed) {
        history.value = true
        acknowledged.value = false
      }
    } catch (e) {
      if (!disposed) error.value = getApiErrorMessage(e, '读取已有预览失败')
    } finally {
      if (!disposed) {
        busy.value = false
        schedule()
      }
    }
  }
  async function confirm() {
    if (!canConfirm.value || !preview.value) return
    busy.value = true
    clearError()
    notice.value = ''
    clearTimeout(timer)
    try {
      if (!receipt.value) {
        const before = metadataBinding(preview.value),
          value = await metadataApi.preview(preview.value.id)
        if (disposed) return
        preview.value = value
        if (before !== metadataBinding(value) || value.taskId || Date.parse(value.expiresAt) <= Date.now()) {
          acknowledged.value = false
          throw new Error('预览范围、状态或有效期已变化，请刷新并重新核对')
        }
        receipt.value = { id: value.id, key: crypto.randomUUID() }
      }
      const value = await metadataCommands.confirm(receipt.value.id, receipt.value.key)
      if (disposed) return
      task.value = value
      preview.value.taskId = value.id
      receipt.value = null
      acknowledged.value = false
      accepted(value)
      notice.value = '批量任务已受理；任务完成不代表每部作品都修改成功，请核对逐项结果。'
    } catch (e) {
      if (!disposed) error.value = getApiErrorMessage(e, '确认结果未获响应，重试保留同一预览与幂等键')
    } finally {
      busy.value = false
      if (!disposed && !error.value) void refresh(1)
      else schedule()
    }
  }
  async function action(kind: 'cancel' | 'retry') {
    if (!task.value || busy.value || loading.value || !props.writable) return
    if (kind === 'cancel' ? !task.value.canCancel : !task.value.canRetry) return
    busy.value = true
    clearError()
    clearTimeout(timer)
    try {
      const value = await metadataCommands.action(task.value.id, kind)
      if (!disposed) {
        task.value = value
        accepted(value)
      }
    } catch (e) {
      if (!disposed) error.value = getApiErrorMessage(e, '任务操作失败，保留已提交结果')
    } finally {
      busy.value = false
      if (!disposed && !error.value) void refresh()
      else schedule()
    }
  }
  function exclude(id: string) {
    if (locked.value || busy.value) return
    if (selectionMode.value === 'ids')
      chosen.value = chosen.value.includes(id) ? chosen.value.filter((value) => value !== id) : [...chosen.value, id]
    else
      excluded.value = excluded.value.includes(id)
        ? excluded.value.filter((value) => value !== id)
        : [...excluded.value, id]
  }
  function newDraft() {
    if (busy.value || loading.value) return
    ++generation
    clearTimeout(timer)
    preview.value = null
    rows.value = []
    task.value = null
    receipt.value = null
    history.value = false
    bound.value = ''
    fresh.value = false
    acknowledged.value = false
    clearError()
    notice.value = ''
    field.value = {
      titleOn: false,
      title: '',
      authorOn: false,
      author: '',
      descriptionOn: false,
      description: '',
      categoryMode: 'keep',
      category: '',
      addTags: [],
      removeTags: [],
    }
  }
  async function terms() {
    try {
      const [c, t] = await Promise.all([comicApi.categories(), comicApi.tags()])
      if (!disposed) {
        categories.value = c.items
        tags.value = t.items
      }
    } catch (e) {
      if (!disposed) termsError.value = getApiErrorMessage(e, '词表读取失败；相关条件需核实后使用')
    }
  }
  void terms()
  if (props.previewId) void restore()
  onBeforeUnmount(() => {
    disposed = true
    ++generation
    clearTimeout(timer)
    clearInterval(clock)
  })
  return {
    selectionMode,
    chosen,
    filter,
    excluded,
    field,
    categories,
    tags,
    termsError,
    preview,
    rows,
    total,
    page,
    task,
    history,
    restoreId,
    acknowledged,
    fresh,
    loading,
    busy,
    error,
    notice,
    receipt,
    stale,
    locked,
    dirty,
    expired,
    canConfirm,
    request,
    create,
    refresh,
    restore,
    confirm,
    action,
    exclude,
    newDraft,
  }
}
