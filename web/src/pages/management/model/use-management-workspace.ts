import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { comicApi, comicCoverUrl, type ComicDetailVO, type Term, type NodePreview } from '@/entities/comic'
import { readerApi, type ChapterResponse } from '@/entities/chapter'
import { importScanApi } from '@/entities/import-scan'
import { storageApi, type Statistics, type StorageObject, type StorageQuery } from '@/entities/storage'
import {
  taskApi,
  type Task,
  type TaskEvent,
  type TaskItem,
  type TaskSummary,
  type TaskStatus,
  type TaskArtifacts,
  type TaskType,
} from '@/entities/task'
import { trashApi, type TrashEntry } from '@/entities/trash'
import { analysisCommands } from '@/features/analyze-comic'
import {
  managementCommands as commands,
  requiresBatchConfirmation,
  dislikedTrashFilter,
  batchPreviewExpired,
  confirmBatchPreview,
  createBatchPreview,
  readBatchPreviewPage,
  type BatchPreview,
  type BatchItem,
  type BatchRequest,
  type TrashOperation,
} from '@/features/manage-items'
import { useSelection } from '@/shared/lib/selection'
import type { FileRow } from '@/widgets/management-files'
import type { RecycleRequest } from '@/widgets/management-reactions'
import { ApiError, getApiErrorMessage } from '@/shared/api/http'

const NODE_PAGE_SIZE = 50
const resultTypes = new Set([
  'metadata_sync',
  'metadata_batch',
  'command_batch',
  'image_lq',
  'metadata_refresh',
  'media_upload',
  'video_transcode',
  'hq_release',
  'lifecycle_audit',
  'lifecycle',
])

export function useManagementWorkspace() {
  const route = useRoute(),
    router = useRouter()
  const section = computed(() =>
    typeof route.params.section === 'string' && route.params.section ? route.params.section : 'library',
  )
  const comicId = computed(() => (typeof route.query.comic === 'string' ? route.query.comic : ''))
  const catalogId = computed(() => (typeof route.query.catalog === 'string' ? route.query.catalog : ''))
  const chapterId = computed(() => (typeof route.query.chapter === 'string' ? route.query.chapter : ''))
  const loading = ref(false),
    loadingMore = ref(false),
    loadMoreError = ref(''),
    scopeFailed = ref(false),
    busy = ref(false),
    error = ref(''),
    notice = ref(''),
    ready = ref(false)
  const reactionRefresh = ref(0)
  const records = ref<StorageObject[]>([]),
    statistics = ref<Statistics | null>(null)
  const detail = ref<ComicDetailVO | null>(null),
    chapter = ref<ChapterResponse | null>(null)
  const resourceStorage = ref<StorageObject | null>(null)
  const pendingResource = ref<{
    kind: 'comics' | 'chapters' | 'media'
    id: string
    title: string
    operation: 'refresh' | 'transcode'
    images: number | null
    videos: number | null
  } | null>(null)
  const submittedBatch = ref<BatchPreview | null>(null)
  const batchReceipt = ref<{
    preview: BatchPreview
    items: BatchItem[]
    total: number
    page: number
    key: string
  } | null>(null)
  const transcodeAcknowledged = ref(false)
  const exportForm = ref({ id: '', title: '', format: 'cbz' as 'cbz' | 'zip', multipart: false, partMiB: 64 })
  const focused = ref(''),
    mode = ref<'list' | 'grid'>('grid'),
    inspector = ref(localStorage.getItem('comicatlas.management.inspector') === 'open')
  const nodePage = ref(1),
    nodeLoadedPage = ref(1),
    nodePreviews = ref<Map<string, NodePreview>>(new Map())
  const selectionMode = ref(false),
    tileSize = ref(138),
    previewId = ref('')
  const orderDraft = ref<FileRow[] | null>(null),
    orderVersion = ref(0)
  const orderMode = computed(() => orderDraft.value !== null)
  watch(inspector, (value) => localStorage.setItem('comicatlas.management.inspector', value ? 'open' : 'closed'))
  const page = ref(1),
    libraryBasePage = ref(1),
    total = ref<number | null>(null),
    keyword = ref(''),
    category = ref(''),
    uncategorized = ref(false),
    tagIds = ref<string[]>([]),
    tagMode = ref<'any' | 'all' | 'not'>('any'),
    untagged = ref(false),
    hq = ref<'' | 'has_hq' | 'no_hq'>(''),
    lq = ref<'' | 'needs_lq' | 'ready'>(''),
    attention = ref<NonNullable<StorageQuery['attention']> | ''>(''),
    sort = ref<'title' | 'createdAt' | 'pages' | 'totalBytes' | 'hqBytes' | 'lqBytes'>('title'),
    sortOrder = ref<'asc' | 'desc'>('asc')
  const categories = ref<Term[]>([]),
    tags = ref<Term[]>([])
  const tasks = ref<Task[]>([]),
    summary = ref<TaskSummary | null>(null),
    globalSummary = ref<TaskSummary | null>(null),
    taskTotal = ref(0),
    taskListLoaded = ref(false),
    taskListFailed = ref(false),
    taskStatus = ref(''),
    taskType = ref<TaskType | ''>(''),
    taskKeyword = ref(''),
    taskOldest = ref(false)
  const taskStates: TaskStatus[] = ['running', 'queued', 'cancelling', 'failed', 'succeeded', 'cancelled']
  const taskGroupPages = ref<Record<string, number>>({}),
    taskGroupBasePages = ref<Record<string, number>>({}),
    taskGroupTotals = ref<Record<string, number>>({}),
    taskGroupLoadingMore = ref<Record<string, boolean>>({}),
    taskGroupAppendErrors = ref<Record<string, string>>({})
  const taskGroups = computed(() =>
    taskStates
      .filter((status) => !taskStatus.value || status === taskStatus.value)
      .map((status) => ({
        status,
        tasks: tasks.value.filter((task) => task.status === status),
        basePage: taskGroupBasePages.value[status] ?? 1,
        total: taskGroupTotals.value[status] ?? 0,
        loadingMore: taskGroupLoadingMore.value[status] ?? false,
        appendError: taskGroupAppendErrors.value[status] ?? '',
      })),
  )
  const nextEvent = ref<number | null>(null),
    itemPage = ref(1)
  let loadingEvents = false,
    loadingItems = false
  const submittedTask = ref<Task | null>(null)
  const submittedActive = computed(
    () => submittedTask.value !== null && ['queued', 'running', 'cancelling'].includes(submittedTask.value.status),
  )
  const selectedTask = ref<Task | null>(null),
    events = ref<TaskEvent[]>([]),
    items = ref<TaskItem[]>([]),
    itemTotal = ref(0),
    taskError = ref('')
  const taskArtifacts = ref<TaskArtifacts | null>(null),
    artifactsLoading = ref(false)
  function taskSnapshot() {
    return {
      task: selectedTask.value!,
      events: [...events.value],
      next: nextEvent.value,
      items: [...items.value],
      total: itemTotal.value,
      page: itemPage.value,
      artifacts: taskArtifacts.value,
      scrollX: window.scrollX,
      scrollY: window.scrollY,
      detailScroll: document.querySelector('.mw-task-detail')?.scrollTop ?? 0,
    }
  }
  const taskTrail = ref<{ state: ReturnType<typeof taskSnapshot>; trigger: string }[]>([])
  const importResultPages = ref<Record<string, number>>({})
  let pendingTaskRestore: { state: ReturnType<typeof taskSnapshot>; trigger: string } | null = null
  function restoreTask(state: ReturnType<typeof taskSnapshot>) {
    ++taskGeneration
    selectedTask.value = state.task
    events.value = state.events
    nextEvent.value = state.next
    items.value = state.items
    itemTotal.value = state.total
    itemPage.value = state.page
    taskArtifacts.value = state.artifacts
    artifactsLoading.value = false
  }
  async function focusTaskResult(trigger: string, state: ReturnType<typeof taskSnapshot>) {
    await nextTick()
    if (selectedTask.value?.id !== state.task.id) return
    const target = document.querySelector<HTMLButtonElement>(`[data-task-relation="${CSS.escape(trigger)}"]`)
    if (!target) {
      pendingTaskRestore = { trigger, state }
      return
    }
    pendingTaskRestore = null
    const pane = document.querySelector('.mw-task-detail')
    if (pane) pane.scrollTop = state.detailScroll
    target.focus({ preventScroll: true })
    window.scrollTo({ left: state.scrollX, top: state.scrollY, behavior: 'instant' })
  }
  function taskResultsReady() {
    if (pendingTaskRestore) void focusTaskResult(pendingTaskRestore.trigger, pendingTaskRestore.state)
  }
  function setImportPage(page: number) {
    if (selectedTask.value?.type === 'import_batch' && Number.isSafeInteger(page) && page > 0)
      importResultPages.value[selectedTask.value.id] = page
  }
  function relatedTask(id: string, position: number, relation: 'child' | 'covered') {
    if (selectedTask.value?.type !== 'command_batch') return
    const item = items.value.find((value) => value.position === position)
    if (!item || item[relation === 'child' ? 'childTaskId' : 'coveredTaskId'] !== id) return
    return enterRelatedTask(id, `${position}:${relation}`)
  }
  function relatedImportTask(id: string, reference: string) {
    const parent = selectedTask.value
    if (parent?.type !== 'import_batch' || !parent.targetId) return
    return enterRelatedTask(id, `import:${reference}`, async () => {
      const batch = await importScanApi.batch(parent.targetId!)
      if (
        batch.id !== parent.targetId ||
        batch.taskId !== parent.id ||
        !batch.items.some((item) => item.referenceId === reference && item.taskId === id)
      )
        throw new Error('导入子任务不属于当前批次，请刷新逐本结果后核对')
    })
  }
  async function enterRelatedTask(id: string, trigger: string, verify?: () => Promise<void>) {
    if (busy.value || !selectedTask.value || id === selectedTask.value.id) return
    const state = taskSnapshot(),
      generation = taskGeneration
    let focusOnError = false
    busy.value = true
    try {
      await verify?.()
      if (disposed || generation !== taskGeneration) return
      const task = await taskApi.detail(id)
      if (disposed || generation !== taskGeneration) return
      if (task.id !== id) throw new Error('任务响应与所选关联任务不一致')
      const loaded = await inspectTask(task)
      if (disposed || taskGeneration !== generation + 1) return
      if (loaded) {
        taskTrail.value.push({ state, trigger })
        await nextTick()
        const heading = document.querySelector<HTMLElement>('.mw-task-detail h2')
        heading?.focus({ preventScroll: true })
        const pane = document.querySelector('.mw-task-detail')
        if (pane) pane.scrollTop = 0
        heading?.scrollIntoView({ block: 'nearest', behavior: 'instant' })
      } else {
        restoreTask(state)
        focusOnError = true
      }
    } catch (e) {
      if (!disposed && generation === taskGeneration) {
        taskError.value = message(e)
        focusOnError = true
      }
    } finally {
      busy.value = false
      if (focusOnError && !disposed && selectedTask.value?.id === state.task.id) await focusTaskResult(trigger, state)
    }
  }
  async function backTask() {
    if (busy.value) return
    const previous = taskTrail.value.pop()
    if (!previous) return
    restoreTask(previous.state)
    taskError.value = ''
    await focusTaskResult(previous.trigger, previous.state)
  }
  function closeTask() {
    ++taskGeneration
    taskTrail.value = []
    pendingTaskRestore = null
    selectedTask.value = null
  }
  async function selectTask(task: Task) {
    taskTrail.value = []
    pendingTaskRestore = null
    if (await inspectTask(task)) {
      await nextTick()
      const pane = document.querySelector('.mw-task-detail')
      if (pane) pane.scrollTop = 0
      const heading = pane?.querySelector<HTMLElement>('h2')
      heading?.focus({ preventScroll: true })
      heading?.scrollIntoView({ block: 'nearest', behavior: 'instant' })
    }
  }
  async function openImportedTask(id: string) {
    if (busy.value || !selectedTask.value || selectedTask.value.id !== id) return
    const generation = taskGeneration
    busy.value = true
    taskError.value = ''
    try {
      const task = await taskApi.detail(id)
      if (disposed || generation !== taskGeneration) return
      if (
        task.id !== id ||
        !['directory_import', 'archive_import'].includes(task.type) ||
        task.status !== 'succeeded' ||
        !task.comicId
      )
        throw new Error('任务尚未提供已完成的导入作品，请刷新任务后核对')
      const comic = await comicApi.detail(task.comicId)
      if (disposed || generation !== taskGeneration) return
      if (comic.id !== task.comicId) throw new Error('作品响应与导入任务的标识不一致')
      await router.push({ path: '/manage', query: { comic: comic.id } })
    } catch (e) {
      if (!disposed && generation === taskGeneration)
        taskError.value =
          e instanceof ApiError && e.status === 404
            ? '导入作品当前无法访问，可能已被回收或移出。原任务结果保留。'
            : message(e)
    } finally {
      busy.value = false
    }
  }
  const trash = ref<TrashEntry[]>([]),
    trashPage = ref(1),
    trashBasePage = ref(1),
    trashTotal = ref(0),
    trashLoadingMore = ref(false),
    trashLoadMoreError = ref(''),
    trashKeyword = ref('')
  type TrashPending = { trashing: number; restoring: number; purging: number }
  const trashPending = ref<TrashPending | null>(null)
  const trashPendingTotal = computed(() =>
    trashPending.value ? trashPending.value.trashing + trashPending.value.restoring + trashPending.value.purging : 0,
  )
  let trashPendingGeneration = 0
  const dialog = ref(''),
    dialogTitle = ref(''),
    dialogError = ref(''),
    key = ref('')
  const importKind = ref<'directories' | 'archives'>('directories'),
    sourcePath = ref(''),
    importTitle = ref('')
  const edit = ref({
    title: '',
    originalTitle: '',
    author: '',
    description: '',
    categoryId: '',
    tagIds: [] as string[],
    version: 0,
  })
  const removingCatalog = ref<{ id: string; title: string; version: number } | null>(null),
    reparentTo = ref('')
  function catalogDescendants(ids: string[]) {
    const blocked = new Set(ids)
    let changed = true
    while (changed) {
      changed = false
      for (const entry of detail.value?.catalogs ?? []) {
        if (entry.parentId && blocked.has(entry.parentId) && !blocked.has(entry.id)) {
          blocked.add(entry.id)
          changed = true
        }
      }
    }
    return blocked
  }
  const catalogDeleteTargets = computed(() => {
    const blocked = catalogDescendants(removingCatalog.value ? [removingCatalog.value.id] : [])
    return (detail.value?.catalogs ?? []).filter((entry) => !blocked.has(entry.id))
  })
  function catalogPath(id: string) {
    const parts: string[] = [],
      seen = new Set<string>()
    while (id && !seen.has(id)) {
      seen.add(id)
      const entry = detail.value?.catalogs.find((value) => value.id === id)
      if (!entry) break
      parts.unshift(entry.title)
      id = entry.parentId ?? ''
    }
    return parts.join(' / ')
  }
  function showDeleteCatalog(row: FileRow) {
    if (!writable.value || !detail.value || row.kind !== 'catalog') return
    const entry = detail.value.catalogs.find((c) => c.id === row.id)
    if (!entry) return
    removingCatalog.value = { id: row.id, title: row.title, version: detail.value.structureVersion }
    reparentTo.value = entry.parentId ?? ''
    showDialog('deleteCatalog', '删除目录并保留内容')
  }
  async function refreshTerms() {
    await run(async () => {
      const [c, t] = await Promise.all([comicApi.categories(), comicApi.tags()])
      categories.value = c.items
      tags.value = t.items
      await loadScope()
    })
  }
  const node = ref({ id: '', kind: 'catalogs' as 'catalogs' | 'chapters', title: '', parentId: '', version: 0 })
  const nodeParents = computed(() => {
    const blocked = catalogDescendants(node.value.kind === 'catalogs' && node.value.id ? [node.value.id] : [])
    return (detail.value?.catalogs ?? []).filter((entry) => !blocked.has(entry.id))
  })
  const moveTarget = ref(''),
    moveVersion = ref(0),
    moveRows = ref<FileRow[]>([])
  const moveTargets = computed(() => {
    const blocked = catalogDescendants(moveRows.value.filter((row) => row.kind === 'catalog').map((row) => row.id))
    return (detail.value?.catalogs ?? []).filter(
      (catalog) => !blocked.has(catalog.id) && catalog.id !== catalogId.value,
    )
  })
  const preview = ref<BatchPreview | null>(null),
    previewItems = ref<BatchItem[]>([])
  const purgeAcknowledged = ref(false),
    previewTotal = ref(0),
    previewPage = ref(1)
  let scopeGeneration = 0,
    focusGeneration = 0,
    taskGeneration = 0,
    disposed = false,
    polling = false
  const writable = computed(() => ready.value && !busy.value && !loading.value && !error.value)
  const activeTasks = computed(() =>
    globalSummary.value ? globalSummary.value.running + globalSummary.value.queued + globalSummary.value.cancelling : 0,
  )
  const root = computed(() => !comicId.value)
  const heading = computed(() =>
    section.value === 'reactions'
      ? '媒体标记'
      : section.value === 'tasks'
        ? '任务中心'
        : section.value === 'trash'
          ? '回收站'
          : section.value === 'system'
            ? '设置'
            : section.value === 'storage'
              ? '存储统计'
              : chapter.value?.chapter.title ||
                detail.value?.catalogs.find((c) => c.id === catalogId.value)?.title ||
                (comicId.value ? detail.value?.title : '漫画管理'),
  )
  const sourceRows = computed<FileRow[]>(() => {
    if (root.value)
      return records.value.map((c) => ({
        id: c.id,
        title: c.title,
        kind: 'comic',
        subtitle: `${c.inventory.chapters} 个章节 · ${c.inventory.images + c.inventory.videos} 个媒体`,
        cover: comicCoverUrl(c.id),
        previewImages: [],
        bytes: c.totalBytes,
        count: c.inventory.images + c.inventory.videos,
        hq: c.hqStatus,
        lq: c.lqStatus,
      }))
    if (chapterId.value)
      return (chapter.value?.items ?? []).map((m) => ({
        id: m.id,
        title: m.fileName,
        kind: m.mediaType,
        subtitle: `第 ${m.pageNumber} 项`,
        cover: m.previewUrl,
        previewImages: [],
        bytes: m.size,
        count: null,
        hq: m.hqState,
        lq: m.lq?.state ?? 'none',
      }))
    if (!detail.value) return []
    const parent = catalogId.value || null
    return [
      ...detail.value.catalogs
        .filter((c) => c.parentId === parent)
        .map((c) => ({
          id: c.id,
          title: c.title,
          kind: 'catalog' as const,
          subtitle: `${detail.value!.catalogs.filter((n) => n.parentId === c.id).length + detail.value!.chapters.filter((n) => n.catalogId === c.id).length} 个子项`,
          cover: null,
          previewImages: nodePreviews.value.get('catalog:' + c.id)?.images.map((image) => image.previewUrl) ?? [],
          bytes: null,
          count:
            detail.value!.catalogs.filter((n) => n.parentId === c.id).length +
            detail.value!.chapters.filter((n) => n.catalogId === c.id).length,
          hq: null,
          lq: null,
          order: c.sortOrder,
        })),
      ...detail.value.chapters
        .filter((c) => c.catalogId === parent)
        .map((c) => ({
          id: c.id,
          title: c.title,
          kind: 'chapter' as const,
          subtitle: nodePreviews.value.has('chapter:' + c.id)
            ? `${nodePreviews.value.get('chapter:' + c.id)!.mediaCount} 个媒体`
            : '章节',
          cover: null,
          previewImages: nodePreviews.value.get('chapter:' + c.id)?.images.map((image) => image.previewUrl) ?? [],
          bytes: null,
          count: nodePreviews.value.get('chapter:' + c.id)?.mediaCount ?? null,
          hq: null,
          lq: null,
          order: c.sortOrder,
        })),
    ].sort((a, b) => a.order - b.order)
  })
  const rows = computed(
    () =>
      orderDraft.value ??
      (root.value
        ? sourceRows.value
        : sourceRows.value.slice((nodePage.value - 1) * NODE_PAGE_SIZE, nodeLoadedPage.value * NODE_PAGE_SIZE)),
  )
  const selection = useSelection(rows)
  const selected = selection.selected
  const scopeTotal = computed(() => sourceRows.value.length)
  const nodePageCount = computed(() => Math.max(1, Math.ceil(scopeTotal.value / NODE_PAGE_SIZE)))
  const canOrder = computed(() => !root.value && scopeTotal.value > 1)
  const canBatchTrash = computed(
    () =>
      !root.value &&
      selected.value.length > 0 &&
      sourceRows.value.filter((row) => selected.value.includes(row.id)).length === selected.value.length &&
      sourceRows.value
        .filter((row) => selected.value.includes(row.id))
        .every((row) => ['chapter', 'image', 'video'].includes(row.kind)),
  )
  const focusedRow = computed(() => rows.value.find((r) => r.id === focused.value) ?? null)
  const selectedMedia = computed(() => chapter.value?.items.find((m) => m.id === focused.value) ?? null)
  const breadcrumbs = computed(() => {
    const result: { id: string; title: string }[] = []
    let id =
      catalogId.value ||
      (chapterId.value ? detail.value?.chapters.find((c) => c.id === chapterId.value)?.catalogId : null)
    const seen = new Set<string>()
    while (id && !seen.has(id)) {
      seen.add(id)
      const entry = detail.value?.catalogs.find((c) => c.id === id)
      if (!entry) break
      result.unshift({ id: entry.id, title: entry.title })
      id = entry.parentId
    }
    return result
  })
  const tree = computed(() => {
    const d = detail.value
    if (!d) return []
    const result: { id: string; title: string; kind: 'catalog' | 'chapter'; depth: number }[] = [],
      seen = new Set<string>()
    function walk(parent: string | null, depth: number) {
      if (depth > 100) return
      const children = [
        ...d!.catalogs.filter((c) => c.parentId === parent).map((c) => ({ ...c, kind: 'catalog' as const })),
        ...d!.chapters.filter((c) => c.catalogId === parent).map((c) => ({ ...c, kind: 'chapter' as const })),
      ].sort((a, b) => a.sortOrder - b.sortOrder)
      for (const child of children) {
        if (seen.has(child.id)) continue
        seen.add(child.id)
        result.push({ id: child.id, title: child.title, kind: child.kind, depth })
        if (child.kind === 'catalog') walk(child.id, depth + 1)
      }
    }
    walk(null, 0)
    return result
  })
  function message(e: unknown) {
    return e instanceof ApiError && e.status === 409
      ? '资料已被其他操作修改，请刷新后重新操作。'
      : getApiErrorMessage(e, '操作失败，请重试')
  }
  function navigate(value: string) {
    if (busy.value || orderMode.value) {
      if (orderMode.value) notice.value = '请先保存或取消当前顺序调整'
      return
    }
    dialog.value = ''
    error.value = ''
    void router.push(value === 'library' ? '/manage' : `/manage/${value}`)
  }
  function scope(comic = '', catalog = '', chapter = '') {
    if (busy.value || orderMode.value) {
      if (orderMode.value) notice.value = '请先保存或取消当前顺序调整'
      return
    }
    void router.push({
      path: '/manage',
      query: { ...(comic ? { comic } : {}), ...(catalog ? { catalog } : {}), ...(chapter ? { chapter } : {}) },
    })
  }
  async function focus(row: FileRow) {
    focused.value = row.id
    const generation = ++focusGeneration
    resourceStorage.value = null
    if (row.kind === 'comic') {
      detail.value = null
      try {
        const [d, usage] = await Promise.all([comicApi.detail(row.id), storageApi.comic(row.id)])
        if (!disposed && generation === focusGeneration && root.value && focused.value === row.id) {
          detail.value = d
          resourceStorage.value = usage
        }
      } catch (e) {
        if (generation === focusGeneration) error.value = message(e)
      }
    } else if (row.kind === 'chapter') {
      try {
        const usage = await storageApi.chapter(row.id)
        if (!disposed && generation === focusGeneration && focused.value === row.id) resourceStorage.value = usage
      } catch (e) {
        if (generation === focusGeneration) error.value = message(e)
      }
    }
  }
  let detailsTrigger = ''
  function showDetails(row: FileRow) {
    if (busy.value) return
    detailsTrigger = row.id
    inspector.value = true
    void focus(row)
  }
  async function closeInspector() {
    inspector.value = false
    await nextTick()
    if (detailsTrigger)
      document
        .querySelector<HTMLButtonElement>(`[data-file-id="${CSS.escape(detailsTrigger)}"] .mw-file-details`)
        ?.focus({ preventScroll: true })
  }
  function open(row: FileRow) {
    if (busy.value) return
    if (row.kind === 'comic') scope(row.id)
    else if (row.kind === 'catalog') scope(comicId.value, row.id)
    else if (row.kind === 'chapter') scope(comicId.value, catalogId.value, row.id)
    else {
      void focus(row)
      previewId.value = row.id
    }
  }
  function select(id: string, range = false) {
    selection.select(id, range)
    const row = rows.value.find((row) => row.id === id)
    if (row) void focus(row)
  }
  function selectAll() {
    selection.selectAll()
  }
  async function refreshStatistics() {
    statistics.value = await storageApi.statistics()
  }
  async function loadTrashPending() {
    const generation = ++trashPendingGeneration
    try {
      const [trashing, restoring, purging] = await Promise.all([
        trashApi.list({ page: 1, pageSize: 1, state: 'trashing' }),
        trashApi.list({ page: 1, pageSize: 1, state: 'restoring' }),
        trashApi.list({ page: 1, pageSize: 1, state: 'purging' }),
      ])
      if (disposed || generation !== trashPendingGeneration) return
      trashPending.value = { trashing: trashing.total, restoring: restoring.total, purging: purging.total }
    } catch {
      if (!disposed && generation === trashPendingGeneration) trashPending.value = null
    }
  }
  async function loadScope(preserveContext = false) {
    if (orderMode.value) {
      notice.value = '请先保存或取消当前顺序调整'
      return
    }
    const keepLibraryContext = preserveContext && section.value === 'library'
    const previousFocus = focused.value
    previewId.value = ''
    nodePreviews.value = new Map()
    const generation = ++scopeGeneration
    ++focusGeneration
    if (section.value !== 'trash') {
      trashPending.value = null
      ++trashPendingGeneration
    }
    loading.value = true
    scopeFailed.value = false
    error.value = ''
    if (!keepLibraryContext) {
      selection.clear()
      focused.value = ''
    }
    chapter.value = null
    detail.value = null
    resourceStorage.value = null
    try {
      if (section.value === 'library' && root.value) {
        const requestedPage = page.value
        const result = await storageApi.comics(libraryQuery(requestedPage))
        if (disposed || generation !== scopeGeneration) return
        records.value = result.items
        total.value = result.total
        libraryBasePage.value = requestedPage
        if (keepLibraryContext) {
          selection.retain(rows.value.map((row) => row.id))
        }
        const target = (keepLibraryContext && rows.value.find((row) => row.id === previousFocus)) || rows.value[0]
        if (target) {
          if (keepLibraryContext) await focus(target)
          else void focus(target)
        } else focused.value = ''
      } else if (section.value === 'library') {
        const [d, c] = await Promise.all([
          comicApi.detail(comicId.value),
          chapterId.value ? readerApi.chapter(chapterId.value) : Promise.resolve(null),
        ])
        if (disposed || generation !== scopeGeneration) return
        if (c && c.comicId !== d.id) throw new Error('章节不属于当前漫画')
        if (catalogId.value && !d.catalogs.some((n) => n.id === catalogId.value))
          throw new Error('目录不存在，请返回漫画根目录')
        detail.value = d
        chapter.value = c
        await loadNodePreviews(generation)
        if (disposed || generation !== scopeGeneration) return
        if (keepLibraryContext) {
          selection.retain(rows.value.map((row) => row.id))
        }
        const target = (keepLibraryContext && rows.value.find((row) => row.id === previousFocus)) || rows.value[0]
        if (target) {
          if (keepLibraryContext) await focus(target)
          else focused.value = target.id
        } else focused.value = ''
      } else if (section.value === 'reactions') reactionRefresh.value++
      else if (section.value === 'tasks') {
        await loadTasks()
        try {
          await refreshSelectedTask()
        } catch (e) {
          if (!disposed && generation === scopeGeneration) taskError.value = message(e)
        }
      } else if (section.value === 'system') return
      else if (section.value === 'trash') {
        const requestedPage = trashPage.value
        const r = await trashApi.list({ page: requestedPage, pageSize: 20, keyword: trashKeyword.value })
        if (generation === scopeGeneration && !disposed) {
          trash.value = r.items
          trashTotal.value = r.total
          trashBasePage.value = requestedPage
          void loadTrashPending()
        }
      } else await refreshStatistics()
    } catch (e) {
      if (generation === scopeGeneration && !disposed) {
        scopeFailed.value = true
        error.value = message(e)
      }
    } finally {
      if (generation === scopeGeneration && !disposed) loading.value = false
    }
  }
  function libraryQuery(target: number) {
    return {
      page: target,
      pageSize: 20,
      keyword: keyword.value,
      categoryId: category.value,
      uncategorized: uncategorized.value || undefined,
      tagId: untagged.value ? [] : tagIds.value,
      tagMode: untagged.value ? ('none' as const) : tagIds.value.length ? tagMode.value : undefined,
      hqStatus: hq.value,
      lqStatus: lq.value,
      attention: attention.value || undefined,
      sort: sort.value,
      order: sortOrder.value,
    }
  }
  async function loadMoreLibrary() {
    if (section.value !== 'library' || !root.value || loading.value || loadingMore.value || orderMode.value) return
    if (total.value === null || records.value.length >= total.value || page.value * 20 >= total.value) return
    const generation = scopeGeneration,
      next = page.value + 1
    loadingMore.value = true
    loadMoreError.value = ''
    try {
      const result = await storageApi.comics(libraryQuery(next))
      if (disposed || generation !== scopeGeneration) return
      records.value = [
        ...records.value,
        ...result.items.filter((item) => !records.value.some((row) => row.id === item.id)),
      ]
      total.value = result.total
      page.value = next
    } catch (e) {
      if (!disposed && generation === scopeGeneration) loadMoreError.value = message(e)
    } finally {
      if (!disposed && generation === scopeGeneration) loadingMore.value = false
    }
  }
  async function loadMoreTrash() {
    if (section.value !== 'trash' || loading.value || trashLoadingMore.value) return
    if (!trash.value.length || trashTotal.value === 0 || trash.value.length >= trashTotal.value) return
    if (trashPage.value * 20 >= trashTotal.value) return
    const generation = scopeGeneration,
      next = trashPage.value + 1
    trashLoadingMore.value = true
    trashLoadMoreError.value = ''
    try {
      const result = await trashApi.list({ page: next, pageSize: 20, keyword: trashKeyword.value })
      if (disposed || generation !== scopeGeneration) return
      trash.value = [...trash.value, ...result.items.filter((item) => !trash.value.some((row) => row.id === item.id))]
      trashTotal.value = result.total
      trashPage.value = next
    } catch (e) {
      if (!disposed && generation === scopeGeneration) trashLoadMoreError.value = message(e)
    } finally {
      if (!disposed && generation === scopeGeneration) trashLoadingMore.value = false
    }
  }
  function goTrashPage(target: number) {
    if (loading.value || trashBasePage.value === target) return
    const pages = Math.max(1, Math.ceil(trashTotal.value / 20))
    if (target < 1 || target > pages) return
    trashPage.value = target
    void loadScope()
  }
  function refreshContextKey() {
    return JSON.stringify([
      section.value,
      comicId.value,
      chapterId.value,
      catalogId.value,
      page.value,
      nodePage.value,
      keyword.value,
      category.value,
      uncategorized.value,
      tagIds.value,
      tagMode.value,
      untagged.value,
      hq.value,
      lq.value,
      attention.value,
      sort.value,
      sortOrder.value,
      taskType.value,
      taskStatus.value,
      taskKeyword.value,
      taskOldest.value,
      taskGroupPages.value,
      selectedTask.value?.id,
      trashPage.value,
      trashKeyword.value,
    ])
  }
  async function refreshCurrentScope() {
    const context = refreshContextKey()
    const expectedGeneration = scopeGeneration + 1
    const position = { left: window.scrollX, top: window.scrollY }
    await loadScope(true)
    await nextTick()
    if (disposed || scopeGeneration !== expectedGeneration || context !== refreshContextKey()) return false
    window.scrollTo(position)
    return !scopeFailed.value && !error.value
  }
  async function loadNodePreviews(generation: number) {
    if (root.value || chapterId.value || !comicId.value) return
    const nodes = rows.value
      .filter((row) => row.kind === 'catalog' || row.kind === 'chapter')
      .map((row) => ({ type: row.kind as 'catalog' | 'chapter', id: row.id }))
    if (!nodes.length) return
    const result = await comicApi.previews(comicId.value, nodes)
    if (!disposed && generation === scopeGeneration)
      nodePreviews.value = new Map(result.items.map((item) => [item.type + ':' + item.id, item]))
  }
  async function changeNodePage(next: number) {
    if (orderMode.value || busy.value) return
    nodePage.value = next
    nodeLoadedPage.value = next
    selection.clear()
    focused.value = ''
    nodePreviews.value = new Map()
    const generation = ++scopeGeneration
    try {
      await loadNodePreviews(generation)
    } catch (e) {
      if (generation === scopeGeneration) error.value = message(e)
    }
  }
  async function loadMoreNode() {
    if (root.value || orderMode.value || busy.value || nodeLoadedPage.value >= nodePageCount.value) return
    nodeLoadedPage.value += 1
    const generation = scopeGeneration
    try {
      await loadNodePreviews(generation)
    } catch (e) {
      if (generation === scopeGeneration) error.value = message(e)
    }
  }
  let keywordTimer: ReturnType<typeof setTimeout> | undefined
  watch(keyword, (_value, _previous, onCleanup) => {
    clearTimeout(keywordTimer)
    if (!ready.value || section.value !== 'library' || !root.value) return
    keywordTimer = setTimeout(() => {
      if (section.value === 'library' && root.value && !selected.value.length && !orderMode.value) filters()
    }, 300)
    onCleanup(() => clearTimeout(keywordTimer))
  })
  function filters() {
    clearTimeout(keywordTimer)
    page.value = 1
    void loadScope()
  }
  let tasksGeneration = 0
  async function loadTasks() {
    const generation = ++tasksGeneration
    taskListFailed.value = false
    try {
      const filters = { type: taskType.value, keyword: taskKeyword.value }
      const [allCounts, filteredCounts] = await Promise.all([
        taskApi.summary(),
        section.value === 'tasks' && (filters.type || filters.keyword)
          ? taskApi.summary(filters)
          : Promise.resolve(null),
      ])
      if (disposed || generation !== tasksGeneration) return
      globalSummary.value = allCounts
      summary.value = filteredCounts ?? allCounts
      if (section.value !== 'tasks') return
      const states = taskStatus.value ? taskStates.filter((status) => status === taskStatus.value) : taskStates
      const pages = await Promise.all(
        states.map(async (status) => {
          const requested = taskGroupPages.value[status] ?? 1
          const query = {
            ...filters,
            oldest: taskOldest.value,
            status,
            pageSize: 20,
          }
          let result = await taskApi.list({ ...query, page: requested })
          const page = Math.min(requested, Math.max(1, Math.ceil(result.total / 20)))
          if (page !== requested && result.total > 0) result = await taskApi.list({ ...query, page })
          return { result, page }
        }),
      )
      if (disposed || generation !== tasksGeneration) return
      tasks.value = pages.flatMap(({ result }) => result.items)
      states.forEach((status, index) => {
        taskGroupTotals.value[status] = pages[index]!.result.total
        taskGroupPages.value[status] = pages[index]!.page
        taskGroupBasePages.value[status] = pages[index]!.page
        taskGroupLoadingMore.value[status] = false
        taskGroupAppendErrors.value[status] = ''
      })
      taskTotal.value = pages.reduce((sum, { result }) => sum + result.total, 0)
      taskListLoaded.value = true
    } catch (e) {
      if (!disposed && generation === tasksGeneration && section.value === 'tasks') taskListFailed.value = true
      throw e
    }
  }
  async function taskGroupPage(status: string, page: number) {
    const previous = taskGroupPages.value[status] ?? 1
    taskGroupPages.value[status] = page
    const expectedGeneration = scopeGeneration + 1
    await loadScope()
    if (scopeGeneration === expectedGeneration && taskListFailed.value) taskGroupPages.value[status] = previous
  }
  async function loadMoreTaskGroup(status: string) {
    if (section.value !== 'tasks' || loading.value || taskGroupLoadingMore.value[status]) return
    if (taskListFailed.value) return
    const current = taskGroupPages.value[status] ?? 1,
      total = taskGroupTotals.value[status] ?? 0
    if (!total || current * 20 >= total) return
    const generation = tasksGeneration,
      next = current + 1
    taskGroupLoadingMore.value[status] = true
    taskGroupAppendErrors.value[status] = ''
    try {
      const result = await taskApi.list({
        type: taskType.value,
        keyword: taskKeyword.value,
        oldest: taskOldest.value,
        status,
        page: next,
        pageSize: 20,
      })
      if (disposed || generation !== tasksGeneration || section.value !== 'tasks') return
      tasks.value = [...tasks.value, ...result.items.filter((task) => !tasks.value.some((row) => row.id === task.id))]
      taskGroupTotals.value[status] = result.total
      taskGroupPages.value[status] = next
    } catch (e) {
      if (!disposed && generation === tasksGeneration) {
        taskGroupAppendErrors.value[status] = message(e)
      }
    } finally {
      if (generation === tasksGeneration) taskGroupLoadingMore.value[status] = false
    }
  }
  function retryTaskGroup(status: string) {
    taskGroupAppendErrors.value[status] = ''
    void loadMoreTaskGroup(status)
  }
  function filterTasks(filters: { type: TaskType | ''; keyword: string; oldest: boolean; status: string }) {
    if (new TextEncoder().encode(filters.keyword).byteLength > 500) {
      taskError.value = '搜索内容最多 500 字节，请缩短后再搜索'
      return
    }
    taskType.value = filters.type
    taskKeyword.value = filters.keyword
    taskOldest.value = filters.oldest
    taskStatus.value = filters.status
    taskGroupPages.value = {}
    taskGroupTotals.value = {}
    tasks.value = []
    taskTotal.value = 0
    taskListLoaded.value = false
    taskListFailed.value = false
    selectedTask.value = null
    taskTrail.value = []
    ++taskGeneration
    taskError.value = ''
    void loadScope()
  }
  async function inspectTask(task: Task) {
    const generation = ++taskGeneration
    selectedTask.value = task
    events.value = []
    nextEvent.value = null
    itemPage.value = 1
    items.value = []
    itemTotal.value = 0
    taskError.value = ''
    taskArtifacts.value = null
    artifactsLoading.value = task.type === 'archive_export'
    try {
      const [fresh, timeline, results, artifacts] = await Promise.all([
        taskApi.detail(task.id),
        taskApi.events(task.id),
        resultTypes.has(task.type) ? taskApi.items(task.id) : Promise.resolve({ items: [], total: 0 }),
        task.type === 'archive_export' ? taskApi.artifacts(task.id) : Promise.resolve(null),
      ])
      if (disposed || generation !== taskGeneration) return
      selectedTask.value = fresh
      events.value = timeline.items
      nextEvent.value = timeline.nextAfter
      items.value = results.items
      itemTotal.value = results.total
      taskArtifacts.value = artifacts
      return true
    } catch (e) {
      if (generation === taskGeneration) taskError.value = message(e)
    } finally {
      if (generation === taskGeneration) artifactsLoading.value = false
    }
    return false
  }
  async function moreTaskEvents() {
    const task = selectedTask.value,
      cursor = nextEvent.value,
      generation = taskGeneration
    if (!task || cursor === null || loadingEvents) return
    loadingEvents = true
    try {
      const result = await taskApi.events(task.id, cursor)
      if (!disposed && generation === taskGeneration) {
        events.value.push(...result.items)
        nextEvent.value = result.nextAfter
      }
    } catch (e) {
      if (generation === taskGeneration) taskError.value = message(e)
    } finally {
      loadingEvents = false
    }
  }
  async function moreTaskItems() {
    const task = selectedTask.value,
      generation = taskGeneration
    if (!task || items.value.length >= itemTotal.value || loadingItems) return
    loadingItems = true
    try {
      const result = await taskApi.items(task.id, itemPage.value + 1)
      if (!disposed && generation === taskGeneration) {
        items.value.push(...result.items)
        itemPage.value++
        itemTotal.value = result.total
      }
    } catch (e) {
      if (generation === taskGeneration) taskError.value = message(e)
    } finally {
      loadingItems = false
    }
  }

  async function refreshSelectedTask() {
    const task = selectedTask.value,
      generation = taskGeneration
    if (!task) return
    const fresh = await taskApi.detail(task.id)
    if (disposed || generation !== taskGeneration || task.id !== selectedTask.value?.id) return
    if (fresh.id !== task.id) throw new Error('任务响应与当前核对对象不一致')
    selectedTask.value = fresh
    if (task.type === 'archive_export') {
      const view = await taskApi.artifacts(task.id)
      if (!disposed && generation === taskGeneration && selectedTask.value?.id === task.id) taskArtifacts.value = view
    }
    if (resultTypes.has(task.type) && !loadingItems) {
      loadingItems = true
      try {
        const results = await Promise.all(
          Array.from({ length: itemPage.value }, (_, index) => taskApi.items(task.id, index + 1)),
        )
        if (!disposed && generation === taskGeneration && task.id === selectedTask.value?.id) {
          items.value = results.flatMap((result) => result.items)
          itemTotal.value = results[0]!.total
        }
      } finally {
        loadingItems = false
      }
    }
    if (nextEvent.value === null && !loadingEvents) {
      loadingEvents = true
      try {
        const result = await taskApi.events(task.id, events.value.at(-1)?.id ?? 0)
        if (!disposed && generation === taskGeneration && task.id === selectedTask.value?.id) {
          events.value.push(...result.items)
          nextEvent.value = result.nextAfter
        }
      } finally {
        loadingEvents = false
      }
    }
    if (!disposed && generation === taskGeneration) taskError.value = ''
  }

  async function run(action: () => Promise<void>, inDialog = false) {
    if (busy.value) return
    busy.value = true
    if (inDialog) dialogError.value = ''
    else error.value = ''
    try {
      await action()
    } catch (e) {
      if (inDialog) dialogError.value = message(e)
      else error.value = message(e)
    } finally {
      busy.value = false
    }
  }
  function showDialog(kind: string, title: string) {
    dialog.value = kind
    dialogTitle.value = title
    dialogError.value = ''
    purgeAcknowledged.value = false
    transcodeAcknowledged.value = false
    key.value = crypto.randomUUID()
  }
  function showImport() {
    sourcePath.value = ''
    importTitle.value = ''
    showDialog('import', '导入漫画资料')
  }
  function showRefresh() {
    const row = focusedRow.value,
      usage = resourceStorage.value
    if (!row || !usage || row.kind !== 'comic' || !usage.operations.refreshMetadata.canSubmit) return
    pendingResource.value = {
      kind: 'comics',
      id: row.id,
      title: row.title,
      operation: 'refresh',
      images: usage.inventory.images,
      videos: usage.inventory.videos,
    }
    showDialog('resource', '核对媒体')
  }
  function showTranscode() {
    const row = focusedRow.value,
      usage = resourceStorage.value
    if (!row || !writable.value || !['comic', 'chapter', 'video'].includes(row.kind)) return
    if (row.kind !== 'video' && (!usage?.operations.transcode.canSubmit || !usage.inventory.videos)) return
    pendingResource.value = {
      kind: row.kind === 'comic' ? 'comics' : row.kind === 'chapter' ? 'chapters' : 'media',
      id: row.id,
      title: row.title,
      operation: 'transcode',
      images: row.kind === 'video' ? null : (usage?.inventory.images ?? null),
      videos: row.kind === 'video' ? 1 : (usage?.inventory.videos ?? null),
    }
    showDialog('resource', '视频转码')
  }
  function showExport() {
    if (!detail.value || !writable.value) return
    exportForm.value = { id: detail.value.id, title: detail.value.title, format: 'cbz', multipart: false, partMiB: 64 }
    showDialog('export', '导出整部漫画')
  }
  watch(
    () => [exportForm.value.format, exportForm.value.multipart, exportForm.value.partMiB],
    () => {
      if (dialog.value === 'export' && !busy.value) key.value = crypto.randomUUID()
    },
  )
  function showMove() {
    if (!detail.value || root.value || chapterId.value || orderMode.value) return
    moveRows.value = sourceRows.value.filter((row) => selected.value.includes(row.id))
    if (!moveRows.value.length) return
    moveTarget.value = ''
    moveVersion.value = detail.value.structureVersion
    showDialog('move', '移动到其他目录')
  }
  function showEdit() {
    const d = detail.value
    if (!d) return
    edit.value = {
      title: d.title,
      originalTitle: d.originalTitle ?? '',
      author: d.author,
      description: d.description,
      categoryId: d.category?.id ?? '',
      tagIds: d.tags.map((t) => t.id),
      version: d.version,
    }
    showDialog('edit', '编辑漫画资料')
  }
  function showNode(kind: 'catalogs' | 'chapters', row?: FileRow) {
    if (!detail.value) return
    const current = row
      ? kind === 'catalogs'
        ? detail.value.catalogs.find((c) => c.id === row.id)
        : detail.value.chapters.find((c) => c.id === row.id)
      : null
    const parent = current ? ('parentId' in current ? current.parentId : current.catalogId) : catalogId.value
    node.value = {
      id: row?.id ?? '',
      kind,
      title: row?.title ?? '',
      parentId: parent ?? '',
      version: detail.value.structureVersion,
    }
    showDialog('node', row ? '重命名或移动' : kind === 'catalogs' ? '新建目录' : '新建章节')
  }
  async function previewOperation(request: BatchRequest, title: string) {
    if (batchReceipt.value) {
      resumeBatch()
      return false
    }
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    let success = false
    await run(async () => {
      const content = await createBatchPreview(request, {
        preview: (value) => {
          if (
            value.operation !== request.operation ||
            (requiresBatchConfirmation(request.operation) ? !value.confirmationToken : !!value.confirmationToken)
          )
            throw new Error('操作预览契约不完整，请重新核对')
          if (request.operation === 'trash_purge' && !value.purge) throw new Error('永久清理预览缺少范围统计')
        },
        items: (value) => {
          if (
            request.operation === 'video_transcode' &&
            value.some((item) => !Number.isSafeInteger(item.videoCount) || Number(item.videoCount) < 0)
          )
            throw new Error('视频预览未返回有效的登记视频数，请重新核对')
        },
      })
      preview.value = content.preview
      previewItems.value = content.items
      previewTotal.value = content.total
      previewPage.value = 1
      success = true
    }, dialog.value === 'batch')
    if (success) {
      await nextTick()
      if (trigger?.isConnected && !trigger.matches(':disabled')) trigger.focus({ preventScroll: true })
      showDialog('batch', title)
    }
    return success
  }
  async function batch(operation: 'metadata_refresh' | 'video_transcode' | 'comic_trash') {
    if (batchReceipt.value) {
      resumeBatch()
      return
    }
    if (!selected.value.length) return
    await previewOperation(
      { operation, selection: { mode: 'ids', ids: [...selected.value] } },
      operation === 'comic_trash' ? '确认移入回收站' : operation === 'video_transcode' ? '批量视频转码' : '核对媒体',
    )
  }
  async function batchTrash(operation: TrashOperation, ids: string[]) {
    if (!ids.length) return
    await previewOperation(
      { operation, trashSelection: { mode: 'ids', ids: [...ids] } },
      operation === 'trash_restore' ? '确认恢复所选内容' : '确认永久清理',
    )
  }
  async function previewTargetTrash(operation: 'chapter_trash' | 'media_trash', ids: string[]) {
    if (!ids.length) return
    await previewOperation({ operation, targetSelection: { mode: 'ids', ids: [...ids] } }, '确认移入回收站')
  }
  async function batchSelectedTrash() {
    if (!canBatchTrash.value) return
    await previewTargetTrash(chapterId.value ? 'media_trash' : 'chapter_trash', selected.value)
  }
  const recyclePreviews = ref<Record<string, string | null>>({})
  const recycledReactions = ref(0)
  async function recycleDislikedMedia(payload: RecycleRequest) {
    if (!writable.value) return
    if (payload.scope === 'ids' && !payload.ids.length) return
    if (payload.scope === 'filter' && payload.targetType === 'comic') return
    recyclePreviews.value = Object.fromEntries(payload.previews.map((item) => [item.id, item.url]))
    const request: BatchRequest =
      payload.scope === 'filter'
        ? payload.targetType === 'chapter'
          ? { operation: 'chapter_trash', targetSelection: { mode: 'filter', filter: dislikedTrashFilter('chapter') } }
          : {
              operation: 'media_trash',
              targetSelection: { mode: 'filter', filter: dislikedTrashFilter('media', payload.mediaType) },
            }
        : payload.targetType === 'comic'
          ? { operation: 'comic_trash', selection: { mode: 'ids', ids: [...payload.ids] } }
          : {
              operation: payload.targetType === 'chapter' ? 'chapter_trash' : 'media_trash',
              targetSelection: { mode: 'ids', ids: [...payload.ids] },
            }
    await previewOperation(
      request,
      payload.targetType === 'comic'
        ? '确认回收不喜欢的漫画'
        : payload.targetType === 'chapter'
          ? '确认回收不喜欢的章节'
          : '确认回收不喜欢的媒体',
    )
  }
  async function morePreviewItems() {
    if (!preview.value || previewItems.value.length >= previewTotal.value) return
    await run(async () => {
      const result = await readBatchPreviewPage(preview.value!, previewPage.value + 1, {
        items: (value) => {
          if (
            preview.value?.operation === 'video_transcode' &&
            value.some((item) => !Number.isSafeInteger(item.videoCount) || Number(item.videoCount) < 0)
          )
            throw new Error('视频预览未返回有效的登记视频数，保留已读取的范围')
        },
      })
      previewItems.value.push(...result.items)
      previewTotal.value = result.total
      previewPage.value++
    }, true)
  }
  async function showTrash(row: FileRow) {
    if (!writable.value || row.kind === 'catalog') return
    if (row.kind === 'comic') {
      await previewOperation({ operation: 'comic_trash', selection: { mode: 'ids', ids: [row.id] } }, '确认移入回收站')
      return
    }
    await previewTargetTrash(row.kind === 'chapter' ? 'chapter_trash' : 'media_trash', [row.id])
  }
  async function accepted(task: Task, batch: BatchPreview | null = null) {
    submittedTask.value = task
    submittedBatch.value = batch
    if (batch?.operation === 'media_trash') recycledReactions.value++
    notice.value = `已提交任务：${task.title || task.type}。执行结果请在任务中心查看。`
    dialog.value = ''
    await loadTasks()
    await refreshCurrentScope()
  }
  function resumeBatch() {
    const receipt = batchReceipt.value
    if (!receipt || busy.value) return
    preview.value = receipt.preview
    previewItems.value = receipt.items
    previewTotal.value = receipt.total
    previewPage.value = receipt.page
    showDialog('batch', '核对原批次受理结果')
    key.value = receipt.key
    transcodeAcknowledged.value = receipt.preview.operation === 'video_transcode'
    purgeAcknowledged.value = receipt.preview.operation === 'trash_purge'
    dialogError.value = '尚未取得原批次的受理结果。继续确认将核对同一批次，不重新选择对象。'
  }
  async function submit() {
    await run(async () => {
      if (dialog.value === 'import')
        await accepted(await commands.import(importKind.value, sourcePath.value, importTitle.value.trim(), key.value))
      else if (dialog.value === 'edit' && detail.value) {
        await commands.edit(detail.value.id, {
          ...edit.value,
          originalTitle: edit.value.originalTitle || null,
          categoryId: edit.value.categoryId || null,
        })
        dialog.value = ''
        notice.value = '漫画资料已保存'
        await loadScope()
      } else if (dialog.value === 'deleteCatalog' && detail.value && removingCatalog.value) {
        if (reparentTo.value && !catalogDeleteTargets.value.some((c) => c.id === reparentTo.value))
          throw new Error('请选择有效的接收位置')
        await commands.deleteCatalog(
          detail.value.id,
          removingCatalog.value.id,
          removingCatalog.value.version,
          reparentTo.value || null,
        )
        dialog.value = ''
        focused.value = ''
        notice.value = '目录已删除，子内容已移动到接收位置'
        await loadScope()
      } else if (dialog.value === 'node' && detail.value) {
        await commands.node(detail.value.id, node.value.kind, node.value.id || null, {
          version: node.value.version,
          title: node.value.title.trim(),
          parentId: node.value.parentId || null,
        })
        dialog.value = ''
        notice.value = '目录结构已保存'
        await loadScope()
      } else if (dialog.value === 'move' && detail.value) {
        if (moveTarget.value === catalogId.value) throw new Error('请选择其他目录')
        await commands.move(
          detail.value.id,
          moveVersion.value,
          moveTarget.value || null,
          moveRows.value.map((row) => ({ type: row.kind === 'catalog' ? 'catalog' : 'chapter', id: row.id })),
        )
        dialog.value = ''
        notice.value = '所选内容已移动，原有相对顺序已保留'
        await loadScope()
      } else if (dialog.value === 'batch' && preview.value) {
        if (preview.value.operation === 'trash_purge' && !purgeAcknowledged.value)
          throw new Error('请先核对永久清理范围并确认无法恢复')
        if (preview.value.operation === 'video_transcode' && !transcodeAcknowledged.value)
          throw new Error('请先确认批量视频转码影响')
        const firstConfirmation = !batchReceipt.value
        if (!batchReceipt.value) {
          if (batchPreviewExpired(preview.value)) throw new Error('操作预览已过期，请关闭后重新核对范围')
          batchReceipt.value = {
            preview: preview.value,
            items: [...previewItems.value],
            total: previewTotal.value,
            page: previewPage.value,
            key: key.value,
          }
        }
        const receipt = batchReceipt.value
        let task: Task
        try {
          task = await confirmBatchPreview(receipt.preview, receipt.key)
        } catch (e) {
          if (firstConfirmation && e instanceof ApiError && [400, 403, 404, 409].includes(e.status))
            batchReceipt.value = null
          throw e
        }
        batchReceipt.value = null
        await accepted(task, receipt.preview)
      } else if (dialog.value === 'resource' && pendingResource.value) {
        const pending = pendingResource.value
        if (pending.operation === 'transcode') {
          if (!transcodeAcknowledged.value) throw new Error('请先确认视频转码影响')
          await accepted(await commands.transcode(pending.kind, pending.id, key.value))
        } else {
          await accepted(await commands.refreshComic(pending.id, key.value))
        }
      } else if (dialog.value === 'export') {
        const form = exportForm.value
        const partBytes = form.multipart ? Math.round(Number(form.partMiB) * 1024 * 1024) : 0
        if (form.multipart && (!Number.isFinite(partBytes) || partBytes < 65536 || partBytes > 4294967295))
          throw new Error('分卷大小必须在 64 KiB 至 4 GiB 以下')
        await accepted(await commands.exportComic(form.id, { format: form.format, partBytes }, key.value))
      }
    }, true)
  }
  function beginOrder() {
    if (!writable.value || !canOrder.value || !detail.value) return
    const node = detail.value.chapters.find((c) => c.id === chapterId.value)
    if (chapterId.value && !node) {
      error.value = '章节排序版本缺失，请重新加载'
      return
    }
    orderVersion.value = chapterId.value ? node!.mediaVersion : detail.value.structureVersion
    orderDraft.value = [...sourceRows.value]
  }
  function cancelOrder() {
    orderDraft.value = null
  }
  function reorder(from: string, to: string, after = false) {
    if (!orderDraft.value || busy.value || from === to) return
    const ids = selected.value.includes(from) ? selected.value : [from]
    if (ids.includes(to)) return
    const group = orderDraft.value.filter((row) => ids.includes(row.id))
    const remaining = orderDraft.value.filter((row) => !ids.includes(row.id))
    const index = remaining.findIndex((row) => row.id === to)
    if (index < 0 || !group.length) return
    remaining.splice(index + (after ? 1 : 0), 0, ...group)
    orderDraft.value = remaining
  }
  async function saveOrder() {
    if (!writable.value || !orderDraft.value || !detail.value) return
    const draft = [...orderDraft.value]
    await run(async () => {
      if (chapterId.value)
        await commands.mediaOrder(
          chapterId.value,
          orderVersion.value,
          draft.map((row) => row.id),
        )
      else
        await commands.structureOrder(
          detail.value!.id,
          orderVersion.value,
          catalogId.value || null,
          draft.map((row) => ({ type: row.kind === 'catalog' ? 'catalog' : 'chapter', id: row.id })),
        )
      orderDraft.value = null
      await loadScope()
      notice.value = '顺序已保存'
    })
  }
  function move(direction: -1 | 1) {
    if (!orderDraft.value) return
    const group = selected.value.length ? selected.value : [focused.value]
    const indices = rows.value.flatMap((row, index) => (group.includes(row.id) ? [index] : []))
    if (!indices.length) return
    const target = rows.value[direction === -1 ? Math.min(...indices) - 1 : Math.max(...indices) + 1]
    if (target) reorder(group[0]!, target.id, direction === 1)
  }
  function taskAction(id: string, action: 'cancel' | 'retry') {
    void run(async () => {
      const current = await taskApi.detail(id)
      const t =
        current.type === 'ai_analysis' ? await analysisCommands.action(id, action) : await commands.task(id, action)
      await loadTasks()
      await inspectTask(t)
    })
  }
  function refreshStorage() {
    void run(async () => {
      await accepted(await commands.refreshStorage(crypto.randomUUID()))
    })
  }
  function showStorageObservation() {
    notice.value = '这里展示最近一次成功的媒体卷扫描，请核对观测所属任务；历史扫描任务不提供独立占用快照。'
    return router.push('/manage/storage')
  }
  onBeforeRouteUpdate(() => {
    if (orderMode.value) {
      notice.value = '请先保存或取消当前顺序调整'
      return false
    }
  })
  watch(
    () => route.fullPath,
    () => {
      page.value = 1
      nodePage.value = 1
      nodeLoadedPage.value = 1
      void loadScope()
    },
  )
  async function boot() {
    loading.value = true
    try {
      ready.value = true
      const [c, t] = await Promise.all([comicApi.categories(), comicApi.tags(), loadTasks()])
      categories.value = c.items
      tags.value = t.items
      await loadScope()
    } catch (e) {
      error.value =
        e instanceof ApiError && e.status === 403
          ? '此地址仅开放阅读接口，无法管理资料。请使用本机管理入口。'
          : message(e)
      loading.value = false
    }
  }
  void boot()
  const timer = window.setInterval(async () => {
    if (
      disposed ||
      document.hidden ||
      !ready.value ||
      polling ||
      busy.value ||
      (!activeTasks.value &&
        !submittedActive.value &&
        !['queued', 'running', 'cancelling'].includes(selectedTask.value?.status ?? ''))
    )
      return
    polling = true
    try {
      const before = activeTasks.value
      let submittedFinished = false
      if (submittedActive.value && submittedTask.value) {
        submittedTask.value = await taskApi.detail(submittedTask.value.id)
        submittedFinished = !submittedActive.value
      }
      await loadTasks()
      await refreshSelectedTask()
      if ((submittedFinished || (before && !activeTasks.value)) && !orderMode.value) {
        if (submittedFinished && submittedBatch.value) {
          submittedBatch.value = await commands.previewDetail(submittedBatch.value.id)
          const result = submittedBatch.value
          notice.value = `批量结果：成功 ${result.succeededCount} 项，失败 ${result.failedCount} 项，阻止 ${result.blockedCount} 项，取消 ${result.cancelledCount} 项。逐项原因请查看任务。`
        } else if (submittedFinished && submittedTask.value) {
          notice.value =
            submittedTask.value.status === 'succeeded'
              ? '任务已完成，列表与占用已刷新。'
              : `任务${submittedTask.value.status === 'failed' ? '失败' : '已取消'}，请查看执行结果。`
        }
        await refreshStatistics()
        if (section.value !== 'tasks') await loadScope()
      }
    } catch (e) {
      taskError.value = message(e)
    } finally {
      polling = false
    }
  }, 4000)
  onBeforeUnmount(() => {
    clearTimeout(keywordTimer)
    disposed = true
    ++scopeGeneration
    ++focusGeneration
    ++taskGeneration
    window.clearInterval(timer)
  })
  async function openTask(id: string) {
    await run(async () => {
      taskTrail.value = []
      taskStatus.value = ''
      taskType.value = ''
      taskKeyword.value = ''
      taskOldest.value = false
      taskGroupPages.value = {}
      await router.push('/manage/tasks')
      await inspectTask(await taskApi.detail(id))
    })
  }
  return {
    importResultPages,
    setImportPage,
    relatedImportTask,
    taskResultsReady,
    showStorageObservation,
    batchReceipt,
    resumeBatch,
    openImportedTask,
    taskTrail,
    relatedTask,
    backTask,
    closeTask,
    selectTask,
    openRecovered: (id: string) => router.push({ path: '/manage', query: { comic: id } }),
    reactionRefresh,
    acceptExternalTask: (task: Task, batch: BatchPreview | null = null) => run(() => accepted(task, batch)),
    openTask,
    refreshTerms,
    removingCatalog,
    reparentTo,
    catalogDeleteTargets,
    showDeleteCatalog,
    showTranscode,
    transcodeAcknowledged,
    showExport,
    exportForm,
    taskArtifacts,
    artifactsLoading,
    canBatchTrash,
    batchSelectedTrash,
    recycleDislikedMedia,
    recyclePreviews,
    recycledReactions,
    resourceStorage,
    pendingResource,
    showRefresh,
    showDetails,
    closeInspector,
    showMove,
    moveTarget,
    moveRows,
    moveTargets,
    section,
    submittedTask,
    viewSubmittedTask: async () => {
      if (!submittedTask.value) return
      await router.push('/manage/tasks')
      await inspectTask(submittedTask.value)
    },
    comicId,
    catalogId,
    chapterId,
    root,
    loading,
    loadingMore,
    loadMoreError,
    scopeFailed,
    busy,
    writable,
    error,
    notice,
    ready,
    records,
    statistics,
    detail,
    chapter,
    focused,
    focusedRow,
    selectedMedia,
    selected,
    mode,
    inspector,
    selectionMode,
    tileSize,
    previewId,
    orderMode,
    canOrder,
    scopeTotal,
    nodePage,
    nodePageCount,
    changeNodePage,
    loadMoreNode,
    beginOrder,
    cancelOrder,
    saveOrder,
    page,
    libraryBasePage,
    total,
    keyword,
    category,
    uncategorized,
    tagIds,
    tagMode,
    untagged,
    hq,
    lq,
    attention,
    sort,
    sortOrder,
    categories,
    tags,
    tasks,
    summary,
    activeTasks,
    taskGroups,
    taskGroupPage,
    loadMoreTaskGroup,
    retryTaskGroup,
    nextEvent,
    moreTaskEvents,
    moreTaskItems,
    taskTotal,
    taskListLoaded,
    taskListFailed,
    taskStatus,
    taskType,
    taskKeyword,
    taskOldest,
    filterTasks,
    selectedTask,
    events,
    items,
    itemTotal,
    taskError,
    trash,
    trashPage,
    trashBasePage,
    trashTotal,
    trashLoadingMore,
    trashLoadMoreError,
    loadMoreTrash,
    goTrashPage,
    trashKeyword,
    trashPending,
    trashPendingTotal,
    batchTrash,
    purgeAcknowledged,
    previewTotal,
    morePreviewItems,
    dialog,
    dialogTitle,
    dialogError,
    importKind,
    sourcePath,
    importTitle,
    edit,
    node,
    nodeParents,
    catalogPath,
    preview,
    previewItems,
    heading,
    rows,
    breadcrumbs,
    tree,
    navigate,
    scope,
    focus,
    open,
    select,
    selectAll,
    loadScope: () => (ready.value ? loadScope() : boot()),
    loadMoreLibrary,
    refreshCurrentScope,
    filters,
    loadTasks,
    inspectTask,
    showImport,
    showEdit,
    showNode,
    batch,
    showTrash,
    submit,
    reorder,
    move,
    taskAction,
    refreshStorage,
  }
}
