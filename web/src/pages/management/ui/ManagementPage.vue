<script setup lang="ts">
import { AppSearch } from '@/shared/ui/search'
import { ManagementPagedFeed } from '@/shared/ui/pagination'
import { reactive, ref, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import { ManagementMetadata, type MetadataTarget } from '@/widgets/management-metadata'
import { ManagementSidecar } from '@/widgets/management-sidecar'
import { ManagementSystem } from '@/widgets/management-system'
import { ManagementAi } from '@/widgets/management-ai'
import { ManagementRecovery } from '@/widgets/management-recovery'
import { ManagementDirectoryMove } from '@/widgets/management-directory-move'
import type { DirectoryTarget } from '@/entities/directory-move'
import { ManagementLifecycle } from '@/widgets/management-lifecycle'
import { ManagementReactions, ManagementReactionConfirm } from '@/widgets/management-reactions'
import type { ReactionTarget, SavedReaction } from '@/entities/managed-reaction'
import { RouterLink } from 'vue-router'
import { comicCoverUrl } from '@/entities/comic'
import { operationBlockers, type StorageObject } from '@/entities/storage'
import type { BatchItem } from '@/features/manage-items'
import type { TrashEntry } from '@/entities/trash'
import { ManagementHqRelease, type HqTarget } from '@/widgets/management-hq-release'
import { ManagementLqDialog, type LqTarget } from '@/widgets/management-lq'
import { ManagementUpload, type UploadTarget } from '@/widgets/management-upload'
import { ManagementCover } from '@/widgets/management-cover'
import { ManagementImportScan } from '@/widgets/management-imports'
import { ManagementTerms } from '@/widgets/management-terms'
import { ManagementShell } from '@/widgets/management-shell'
import { ManagementFiles } from '@/widgets/management-files'
import { ManagementOrder } from '@/widgets/management-order'
import { ManagementTrash } from '@/widgets/management-trash'
import { ManagementStorage } from '@/widgets/management-storage'
import { ManagementPreview } from '@/widgets/management-preview'
import { ManagementTasks } from '@/widgets/management-tasks'
import {
  ManagementIcon,
  ManagementDialog,
  ManagementImage,
  ManagementActionMenu,
  BatchPreviewSummary,
  BatchPreviewList,
  registrationLabel,
  formatBytes,
  formatDate,
} from '@/shared/ui/management'
import { useManagementWorkspace } from '../model/use-management-workspace'
type LibraryFilterKey = 'category' | 'tag' | 'hq' | 'lq' | 'attention'
const systemPanel = ref<InstanceType<typeof ManagementSystem>>()
const metadataOpen = ref(false),
  metadataTargets = ref<MetadataTarget[]>([]),
  metadataPreview = ref('')
let metadataTrigger: HTMLElement | null = null
let disposed = false
onBeforeUnmount(() => {
  disposed = true
})
function showMetadata(selected = false, previewId = '') {
  metadataTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  metadataTargets.value = selected
    ? w.rows
        .filter((row) => w.selected.includes(row.id) && row.kind === 'comic')
        .map((row) => ({ id: row.id, title: row.title, cover: row.cover }))
    : []
  metadataPreview.value = previewId
  metadataOpen.value = true
}
async function closeMetadata() {
  metadataOpen.value = false
  await nextTick()
  if (disposed) return
  if (
    metadataTrigger?.isConnected &&
    metadataTrigger.getClientRects().length &&
    !metadataTrigger.closest('[inert]') &&
    !metadataTrigger.matches(':disabled')
  ) {
    metadataTrigger.focus({ preventScroll: true })
  } else if (choosing.value) {
    libraryControls.value
      ?.querySelector<HTMLInputElement>('.mw-toolbar-selection input[type="checkbox"]')
      ?.focus({ preventScroll: true })
  } else selectionTrigger.value?.focus({ preventScroll: true })
}
function inspectMetadataTask(id: string) {
  metadataOpen.value = false
  void w.openTask(id)
}
async function metadataSaved() {
  w.notice = '整批资料已保存，正在重新读取漫画库。'
  if (await w.refreshCurrentScope()) w.notice = '整批资料已保存，漫画库已更新；保留当前筛选和仍在本页的选集。'
}
const sidecarOpen = ref(false),
  sidecarComic = ref(''),
  sidecarTask = ref('')
function showSidecar(comic = '', task = '') {
  sidecarComic.value = comic
  sidecarTask.value = task
  sidecarOpen.value = true
}
function inspectSidecarTask(id: string) {
  sidecarOpen.value = false
  void w.openTask(id)
}
const recoveryOpen = ref(false),
  recoveryMode = ref<'scan' | 'repair'>('scan'),
  recoveryComic = ref(''),
  recoveryTask = ref('')
const analysisOpen = ref(false),
  analysisComic = ref(''),
  analysisTask = ref('')
function showAnalysis(comic = '', task = '') {
  analysisComic.value = comic
  analysisTask.value = task
  analysisOpen.value = true
}
function closeAnalysis(changed: boolean) {
  analysisOpen.value = false
  if (changed) void w.refreshCurrentScope()
}
function inspectAnalysisTask(id: string) {
  analysisOpen.value = false
  void w.openTask(id)
}
function showRecovery(mode: 'scan' | 'repair', comic = '', task = '') {
  recoveryMode.value = mode
  recoveryComic.value = comic
  recoveryTask.value = task
  recoveryOpen.value = true
}
function inspectRecoveryTask(id: string) {
  recoveryOpen.value = false
  void w.openTask(id)
}
function closeRecovery(changed: boolean) {
  recoveryOpen.value = false
  if (changed) void w.refreshCurrentScope()
}
function openRecoveredComic(id: string) {
  recoveryOpen.value = false
  void w.openRecovered(id)
}
const directoryOpen = ref(false),
  directoryPreview = ref(''),
  directoryTargets = ref<DirectoryTarget[]>([])
const canMoveDirectory = computed(
  () =>
    w.root &&
    w.selected.length > 0 &&
    w.selected.length <= 10000 &&
    selectedRatingRows.value.length === w.selected.length &&
    selectedRatingRows.value.every((row) => row.kind === 'comic'),
)
function showDirectoryMove() {
  if (!canMoveDirectory.value) return
  directoryTargets.value = selectedRatingRows.value.map((row) => ({ id: row.id, title: row.title, cover: row.cover }))
  directoryPreview.value = ''
  directoryOpen.value = true
}
function showDirectorySingle() {
  if (!w.detail) return
  directoryTargets.value = [{ id: w.detail.id, title: w.detail.title, cover: w.detail.coverUrl }]
  directoryPreview.value = ''
  directoryOpen.value = true
}
function resumeDirectory(id: string) {
  directoryTargets.value = []
  directoryPreview.value = id
  directoryOpen.value = true
}
function closeDirectory(changed: boolean) {
  directoryOpen.value = false
  if (changed) void w.refreshCurrentScope()
}
function inspectDirectoryTask(id: string) {
  directoryOpen.value = false
  void w.openTask(id)
}
const lifecycleOpen = ref(false),
  lifecycleEntry = ref(''),
  lifecycleState = ref<TrashEntry['state']>('trashing')
function showLifecycle(id = '', state: TrashEntry['state'] = 'trashing') {
  lifecycleEntry.value = id
  lifecycleState.value = state
  lifecycleOpen.value = true
}
function inspectLifecycleTask(id: string) {
  lifecycleOpen.value = false
  void w.openTask(id)
}
const ratingOpen = ref(false),
  ratingTargets = ref<ReactionTarget[]>([])
const selectedRatingRows = computed(() => w.rows.filter((row) => w.selected.includes(row.id)))
const canRateSelected = computed(
  () =>
    w.selected.length > 0 &&
    w.selected.length <= 100 &&
    selectedRatingRows.value.length === w.selected.length &&
    selectedRatingRows.value.every((row) => row.kind !== 'catalog'),
)
function showRating() {
  if (!canRateSelected.value) return
  ratingTargets.value = selectedRatingRows.value.map((row) => ({
    targetType: row.kind === 'comic' ? 'comic' : row.kind === 'chapter' ? 'chapter' : 'media',
    targetId: row.id,
    title: row.title,
    comicTitle: w.root ? row.title : w.detail?.title,
    previewUrl: row.previewImages[0] || row.cover || undefined,
    hidden: false,
    canChange: true,
  }))
  ratingOpen.value = true
}
async function ratingSaved(result: SavedReaction[]) {
  ratingOpen.value = false
  w.submittedTask = null
  w.notice = `已保存 ${result.length} 项标记；仅修改所选对象。`
  await w.refreshCurrentScope()
}
const hqOpen = ref(false),
  hqTarget = ref<HqTarget | null>(null),
  hqIds = ref<string[]>([])
function showHqResource() {
  const row = w.focusedRow
  if (!row || !['comic', 'chapter'].includes(row.kind)) return
  hqIds.value = []
  hqTarget.value = {
    kind: row.kind === 'comic' ? 'comics' : 'chapters',
    id: row.id,
    title: row.title,
    cover: row.previewImages[0] || row.cover,
  }
  hqOpen.value = true
}
function showHqStorage(kind: 'comics' | 'chapters', id: string, title: string) {
  hqIds.value = []
  hqTarget.value = {
    kind,
    id,
    title,
    cover: kind === 'comics' ? comicCoverUrl(id) : null,
  }
  hqOpen.value = true
}
function showHqBatch(ids: string[]) {
  hqTarget.value = null
  hqIds.value = [...ids]
  hqOpen.value = true
}
function acceptedHq(
  task: Parameters<typeof w.acceptExternalTask>[0],
  batch: Parameters<typeof w.acceptExternalTask>[1],
) {
  hqOpen.value = false
  void w.acceptExternalTask(task, batch)
}
const lqOpen = ref(false),
  lqTarget = ref<LqTarget | null>(null),
  lqObjects = ref<StorageObject[]>([])
function showLqSingle() {
  const row = w.focusedRow
  if (!row || !['comic', 'chapter'].includes(row.kind)) return
  lqTarget.value = {
    kind: row.kind === 'comic' ? 'comics' : 'chapters',
    id: row.id,
    title: row.title,
    cover: row.kind === 'comic' ? comicCoverUrl(row.id) : null,
  }
  lqObjects.value = []
  lqOpen.value = true
}
function showLqBatch(objects: StorageObject[]) {
  if (!objects.length) return
  lqTarget.value = null
  lqObjects.value = objects
  lqOpen.value = true
}
function showLibraryLq() {
  showLqBatch(w.records.filter((item) => w.selected.includes(item.id)))
}
function showLqSingleFromStorage(item: StorageObject) {
  lqTarget.value = {
    kind: 'comics',
    id: item.id,
    title: item.title,
    cover: comicCoverUrl(item.id),
  }
  lqObjects.value = []
  lqOpen.value = true
}
function acceptedLq(
  task: Parameters<typeof w.acceptExternalTask>[0],
  batch: Parameters<typeof w.acceptExternalTask>[1],
) {
  lqOpen.value = false
  void w.acceptExternalTask(task, batch)
}
const uploadOpen = ref(false),
  uploadTarget = ref<UploadTarget | null>(null),
  uploadSessionId = ref('')
function showUpload(replace = false) {
  if (!w.chapter || !w.detail) return
  uploadTarget.value = {
    chapterId: w.chapter.chapter.id,
    comicId: w.detail.id,
    title: w.chapter.chapter.title,
    replaceMediaId: replace ? (w.selectedMedia?.id ?? null) : null,
    replaceTitle: replace ? (w.selectedMedia?.fileName ?? '') : '',
  }
  uploadSessionId.value = ''
  uploadOpen.value = true
}
function restoreUpload(id: string) {
  uploadTarget.value = null
  uploadSessionId.value = id
  uploadOpen.value = true
}
function inspectUploadTask(id: string) {
  uploadOpen.value = false
  void w.openTask(id)
}
const coverOpen = ref(false)
function coverChanged() {
  coverOpen.value = false
  w.notice = '漫画封面选择已保存，重排不会改变封面选择'
}
const termsOpen = ref(false),
  scanOpen = ref(false),
  scanTaskId = ref('')
function showScan(id = '') {
  scanTaskId.value = id
  w.dialog = ''
  scanOpen.value = true
}
function inspectImportTask(id: string) {
  scanOpen.value = false
  void w.openTask(id)
}
const w = reactive(useManagementWorkspace())
const trashPendingState = computed<TrashEntry['state']>(() => {
  const pending = w.trashPending
  if (pending?.trashing) return 'trashing'
  if (pending?.restoring) return 'restoring'
  if (pending?.purging) return 'purging'
  return 'trashing'
})
const inspectingComic = computed(() => w.focusedRow?.kind === 'comic')
const inspectedKindLabel = computed(() =>
  w.focusedRow
    ? { comic: '漫画', catalog: '目录', chapter: '章节', image: '图片', video: '视频' }[w.focusedRow.kind]
    : '',
)
const hasInspectionMaintenance = computed(
  () => !!w.focusedRow && ['comic', 'chapter', 'catalog', 'video'].includes(w.focusedRow.kind),
)
watch(
  () => [w.comicId, w.chapterId, w.selectedMedia?.id],
  () => {
    coverOpen.value = false
  },
)
function searchTrash() {
  w.trashPage = 1
  void w.loadScope()
}
const activeLibraryFilters = computed(
  () =>
    Number(!!w.category || w.uncategorized) +
    Number(!!w.tagIds.length || w.untagged) +
    Number(!!w.hq) +
    Number(!!w.lq) +
    Number(!!w.attention),
)
const UNCATEGORIZED = '__uncategorized__'
const categorySelection = computed({
  get: () => (w.uncategorized ? UNCATEGORIZED : w.category),
  set: (value: string) => {
    w.uncategorized = value === UNCATEGORIZED
    w.category = w.uncategorized ? '' : value
    void w.filters()
  },
})
const tagModeLabels = { any: '任一', all: '全部', not: '排除' } as const
const tagQuery = ref('')
const visibleTags = computed(() => {
  const query = tagQuery.value.trim().toLocaleLowerCase()
  return query ? w.tags.filter((term) => term.name.toLocaleLowerCase().includes(query)) : w.tags
})
const tagSummary = computed(() => {
  if (w.untagged) return '无标签'
  if (!w.tagIds.length) return '全部标签'
  return `${w.tagIds.length} 个标签 · ${tagModeLabels[w.tagMode]}`
})
function toggleTag(id: string, checked: boolean) {
  w.untagged = false
  w.tagIds = checked ? [...new Set([...w.tagIds, id])] : w.tagIds.filter((value) => value !== id)
  if (!w.tagIds.length) w.tagMode = 'any'
  void w.filters()
}
function toggleUntagged(checked: boolean) {
  w.untagged = checked
  if (checked) {
    w.tagIds = []
    w.tagMode = 'any'
  }
  void w.filters()
}
function changeSort() {
  w.sortOrder = w.sort === 'title' ? 'asc' : 'desc'
  void w.filters()
}
function toggleSortOrder() {
  w.sortOrder = w.sortOrder === 'asc' ? 'desc' : 'asc'
  void w.filters()
}
const submitLabel = computed(() => {
  if (w.dialog === 'node') return w.node.id ? '保存名称与位置' : w.node.kind === 'catalogs' ? '创建目录' : '创建章节'
  if (w.dialog === 'edit') return '保存资料'
  if (w.dialog === 'resource')
    return { transcode: '开始视频转码', refresh: '开始核对媒体' }[w.pendingResource?.operation ?? 'refresh']
  if (w.dialog === 'batch')
    return (
      (
        {
          comic_trash: '确认回收',
          chapter_trash: '确认回收',
          media_trash: '确认回收',
          trash_restore: '确认恢复',
          trash_purge: '永久清理',
          video_transcode: '开始视频转码',
          metadata_refresh: '开始核对媒体',
        } as Record<string, string>
      )[w.preview?.operation ?? ''] ?? '提交任务'
    )
  return (
    (
      {
        import: '开始导入',
        export: '生成归档',
        move: '移动所选内容',
        deleteCatalog: '删除目录并保留内容',
      } as Record<string, string>
    )[w.dialog] ?? '提交任务'
  )
})
function previewItemLabel(item: BatchItem) {
  if (item.coveredBy) return `由第 ${item.coveredBy} 项一并处理`
  if (item.errorCode) {
    const names: Record<string, string> = {
      NO_VIDEOS: '没有登记视频',
      TARGET_NOT_FOUND: '对象已不存在或不可用',
      TARGET_BUSY: '对象正在处理中',
      BATCH_SCOPE_CHANGED: '操作范围已变化，请重新预览',
      BATCH_DEPENDENCY_FAILED: '前置项目未完成',
    }
    const translated = names[item.errorCode] ?? operationBlockers([item.errorCode])
    return translated === item.errorCode ? '暂时无法处理，请查看诊断详情' : translated
  }
  return (
    (
      {
        pending: '待处理',
        eligible: '可执行',
        blocked: '暂时无法执行',
        covered: '与其他项目一并处理',
        succeeded: '已完成',
        failed: '处理失败',
        cancelled: '已取消',
      } as Record<string, string>
    )[item.status] ?? '状态待核对'
  )
}
function restorePlacement(item: BatchItem) {
  if (item.targetType === 'comic') return '恢复到漫画库'
  if (item.targetType === 'chapter') return '追加到原目录末尾'
  return '追加到原章节末尾'
}
function previewCover(item: BatchItem) {
  if (item.entryId) return w.trash.find((entry) => entry.id === item.entryId)?.coverUrl ?? null
  const row = w.rows.find((entry) => entry.id === item.targetId)
  return w.recyclePreviews[item.targetId] ?? row?.previewImages[0] ?? row?.cover ?? null
}
const batchPreviewUnit = computed(() =>
  ['chapter_trash', 'media_trash', 'trash_restore', 'trash_purge'].includes(w.preview?.operation ?? '') ? '项' : '部',
)
const batchPreviewRows = computed(() =>
  w.previewItems.map((item) => {
    const meta: string[] = []
    if (w.preview?.operation === 'trash_restore' && !item.coveredBy) meta.push(restorePlacement(item))
    if (w.preview?.operation === 'video_transcode') meta.push(`登记视频 ${item.videoCount ?? 0} 个`)
    if (w.preview?.operation === 'metadata_refresh')
      meta.push(`登记图片 ${item.imageCount ?? 0} 张 · 视频 ${item.videoCount ?? 0} 个`)
    return {
      key: item.position,
      title: item.title,
      cover: previewCover(item),
      meta: meta.join(' · ') || undefined,
      status: previewItemLabel(item),
      errorCode: item.errorCode ?? undefined,
    }
  }),
)
const libraryControls = ref<HTMLElement>(),
  selectionTrigger = ref<HTMLButtonElement>()
const choosing = computed(() => w.selectionMode || w.selected.length > 0)
async function enterSelection() {
  w.selectionMode = true
  await nextTick()
  libraryControls.value
    ?.querySelector<HTMLInputElement>('.mw-selection-controls input[type="checkbox"]')
    ?.focus({ preventScroll: true })
}
async function exitSelection() {
  w.selected = []
  w.selectionMode = false
  await nextTick()
  selectionTrigger.value?.focus({ preventScroll: true })
}
async function selectPage() {
  w.selectAll()
  await nextTick()
  const checkbox = libraryControls.value?.querySelector<HTMLInputElement>(
    '.mw-toolbar-panel:not(.mw-toolbar-inactive) input[type="checkbox"]',
  )
  if (checkbox) checkbox.focus({ preventScroll: true })
  else selectionTrigger.value?.focus({ preventScroll: true })
}
const libraryPageCount = computed(() => (w.total === null ? 0 : Math.max(1, Math.ceil(w.total / 20))))
function goLibraryPage(target: number) {
  if (!w.root || w.loading || w.busy || w.total === null) return
  if (target < 1 || target > libraryPageCount.value || target === w.libraryBasePage) return
  w.page = target
  void w.loadScope()
}
function changeLibraryPage(target: number) {
  if (w.root) goLibraryPage(target)
  else goNodePage(target)
}
function loadMoreLibraryPage() {
  if (w.root) void w.loadMoreLibrary()
  else void w.loadMoreNode()
}
function retryLibraryPage() {
  if (w.root) void w.loadMoreLibrary()
  else void w.loadMoreNode()
}
function goNodePage(target: number) {
  if (w.root || w.loading || w.busy || w.orderMode || target === w.nodePage) return
  if (target < 1 || target > w.nodePageCount) return
  void w.changeNodePage(target)
}
const orderSelection = computed(() => (w.selected.length ? w.selected : w.focused ? [w.focused] : []))
const orderBounds = computed(() => {
  const indices = w.rows.flatMap((row, index) => (orderSelection.value.includes(row.id) ? [index] : []))
  return indices.length ? { first: Math.min(...indices), last: Math.max(...indices) } : null
})
const librarySearchRegion = ref<HTMLElement>(),
  appliedFilterBar = ref<HTMLElement>()
const appliedLibraryFilters = computed(() => {
  const filters: {
    key: LibraryFilterKey
    label: string
  }[] = []
  if (w.uncategorized) filters.push({ key: 'category', label: '分类：未分类' })
  else if (w.category)
    filters.push({
      key: 'category',
      label: `分类：${w.categories.find((term) => term.id === w.category)?.name ?? '所选分类'}`,
    })
  if (w.untagged) filters.push({ key: 'tag', label: '无标签' })
  else if (w.tagIds.length) {
    const names = w.tagIds.map((id) => w.tags.find((term) => term.id === id)?.name ?? '所选标签')
    const prefix = w.tagIds.length > 1 ? `标签（${tagModeLabels[w.tagMode]}）` : '标签'
    filters.push({ key: 'tag', label: `${prefix}：${names.join('、')}` })
  }
  if (w.hq) filters.push({ key: 'hq', label: w.hq === 'has_hq' ? '有原件' : '无原件' })
  if (w.lq) filters.push({ key: 'lq', label: w.lq === 'ready' ? '轻量副本已就绪' : '轻量副本未就绪' })
  if (w.attention)
    filters.push({
      key: 'attention',
      label: { missing_lq: '缺少副本', can_release: '可释放原件', missing_source_sha256: '缺少原件 SHA-256' }[
        w.attention
      ],
    })
  return filters
})
const scopeCountLabel = computed(() => {
  if (w.loading) return '读取中…'
  if (w.scopeFailed) return w.root && w.total !== null ? `${w.total} 部作品（上次结果）` : '数量暂不可用'
  return w.root ? (w.total === null ? '数量暂不可用' : `${w.total} 部作品`) : `${w.scopeTotal} 项内容`
})
const emptyState = computed(() => {
  if (w.scopeFailed) return { title: '暂时无法读取内容', message: '请使用上方“刷新”重新读取。' }
  if (w.root)
    return w.keyword || activeLibraryFilters.value
      ? { title: '没有匹配的漫画', message: '调整搜索条件，或清除当前筛选。' }
      : { title: '漫画库还没有作品', message: '使用右上角“导入漫画”添加作品。' }
  return w.chapterId
    ? { title: '这个章节还没有媒体', message: '使用右上角“上传媒体”添加图片或视频。' }
    : { title: '这里还没有内容', message: '使用右上角“新建章节”或“新建目录”添加内容。' }
})
function focusFilterTrigger() {
  librarySearchRegion.value
    ?.querySelector<HTMLElement>('.mw-library-filter-menu > summary')
    ?.focus({ preventScroll: true })
}
async function clearLibraryFilters() {
  w.category = ''
  w.uncategorized = false
  w.tagIds = []
  w.tagMode = 'any'
  w.untagged = false
  w.hq = ''
  w.lq = ''
  w.attention = ''
  tagQuery.value = ''
  void w.filters()
  await nextTick()
  focusFilterTrigger()
}
async function removeLibraryFilter(key: LibraryFilterKey) {
  const position = appliedLibraryFilters.value.findIndex((filter) => filter.key === key)
  if (key === 'category') {
    w.category = ''
    w.uncategorized = false
  } else if (key === 'tag') {
    w.tagIds = []
    w.tagMode = 'any'
    w.untagged = false
    tagQuery.value = ''
  } else if (key === 'hq') w.hq = ''
  else if (key === 'lq') w.lq = ''
  else w.attention = ''
  void w.filters()
  await nextTick()
  const chips = appliedFilterBar.value?.querySelectorAll<HTMLButtonElement>('.mw-filter-chip') ?? []
  const next = chips[Math.min(position, chips.length - 1)]
  if (next) next.focus({ preventScroll: true })
  else focusFilterTrigger()
}
</script>

<template>
  <ManagementShell
    :section="w.section"
    :active-tasks="w.activeTasks"
    :busy="w.busy || w.orderMode"
    :inspector="w.inspector && w.section === 'library' && w.ready"
    :outline="!!w.detail && !!w.comicId && w.section === 'library'"
    @section="w.navigate"
  >
    <template #tree>
      <template v-if="w.detail && w.comicId && w.section === 'library'">
        <button
          class="mw-tree-item"
          :class="{ active: w.comicId && !w.catalogId && !w.chapterId }"
          @click="w.scope(w.detail.id)"
        >
          <ManagementIcon name="book" :size="15" /><span class="mw-tree-name">{{ w.detail.title }}</span>
        </button>
        <button
          v-for="entry in w.tree"
          :key="entry.id"
          class="mw-tree-item"
          :class="{ active: entry.id === w.catalogId || entry.id === w.chapterId }"
          :style="{ paddingLeft: `${23 + Math.min(entry.depth, 6) * 13}px` }"
          @click="entry.kind === 'catalog' ? w.scope(w.detail.id, entry.id) : w.scope(w.detail.id, '', entry.id)"
        >
          <ManagementIcon :name="entry.kind === 'catalog' ? 'folder' : 'image'" :size="14" /><span
            class="mw-tree-name"
            >{{ entry.title }}</span
          >
        </button>
      </template>
    </template>

    <div class="mw-content">
      <div class="mw-page-heading" :class="{ 'mw-library-heading': w.section === 'library' }">
        <div>
          <div class="mw-title-line">
            <h1 :title="w.heading">
              {{ w.section === 'library' && !w.root && !w.catalogId && !w.chapterId ? '作品内容' : w.heading }}
            </h1>
            <span v-if="w.section === 'library'" class="mw-muted" aria-live="polite">{{ scopeCountLabel }}</span>
          </div>
          <p v-if="w.section !== 'library'">
            {{
              w.section === 'tasks'
                ? '跟进执行过程，查看每一项处理结果。'
                : w.section === 'trash'
                  ? '选择内容恢复或清理；操作会先核对，结果在任务中心查看。'
                  : w.section === 'reactions'
                    ? '整理各层级的喜欢与不喜欢，按需包含回收内容。'
                    : w.section === 'system'
                      ? '核对当前服务身份与媒体工具的运行能力。'
                      : '查看资料登记与资料盘的实际占用。'
            }}
          </p>
        </div>
        <div class="mw-inline-actions">
          <span v-if="w.section !== 'library'" class="mw-connection"
            ><ManagementIcon :name="w.ready ? 'check' : 'loading'" :size="14" />{{
              w.ready ? '本机工作空间' : '正在连接'
            }}</span
          >
          <button
            v-if="w.section === 'library' && w.root"
            class="mw-button"
            :disabled="!w.writable"
            @click="termsOpen = true"
          >
            分类与标签
          </button>
          <button
            v-if="w.section === 'library' && w.root && !w.selected.length"
            class="mw-button"
            :disabled="!w.writable"
            @click="showMetadata()"
          >
            批量编辑资料
          </button>
          <button
            v-if="w.section === 'library' && w.root"
            class="mw-button mw-button-primary"
            :disabled="!w.writable"
            @click="w.showImport"
          >
            <ManagementIcon name="plus" />导入漫画
          </button>
          <button
            v-if="w.section === 'library' && !w.root && !w.chapterId"
            class="mw-button"
            :disabled="!w.writable || w.orderMode"
            @click="w.showNode('catalogs')"
          >
            <ManagementIcon name="folder" :size="14" />新建目录
          </button>
          <button
            v-if="w.section === 'library' && !w.root"
            class="mw-button mw-button-primary"
            :disabled="!w.writable || w.orderMode"
            @click="w.chapterId ? showUpload() : w.showNode('chapters')"
          >
            <ManagementIcon name="plus" />{{ w.chapterId ? '上传媒体' : '新建章节' }}
          </button>
          <button
            class="mw-icon-button"
            aria-label="刷新当前页面"
            :disabled="w.loading || w.busy"
            @click="w.section === 'system' ? systemPanel?.refresh() : w.loadScope()"
          >
            <ManagementIcon name="refresh" :class="{ 'mw-spin': w.loading }" /></button
          ><button
            v-if="w.section === 'library'"
            class="mw-icon-button"
            aria-label="切换详情面板"
            :aria-pressed="w.inspector"
            @click="w.inspector = !w.inspector"
          >
            <ManagementIcon :name="w.inspector ? 'hidePanel' : 'showPanel'" />
          </button>
        </div>
      </div>
      <p v-if="w.notice" class="mw-notice" role="status">
        {{ w.notice }}<button v-if="w.submittedTask" class="mw-button" @click="w.viewSubmittedTask">查看任务</button
        ><button class="mw-icon-button" aria-label="关闭通知" @click="w.notice = ''">
          <ManagementIcon name="close" :size="14" />
        </button>
      </p>
      <div v-if="w.error" class="mw-error" role="alert">
        {{ w.error }}<button class="mw-button" :disabled="w.loading" @click="w.loadScope">刷新</button>
      </div>
      <p v-if="w.batchReceipt" class="mw-notice" role="status">
        有一批操作尚未取得受理结果。<button class="mw-button" :disabled="w.busy" @click="w.resumeBatch">
          继续核对原批次
        </button>
      </p>

      <template v-if="w.section === 'library' && w.ready">
        <nav v-if="!w.root" class="mw-breadcrumb" aria-label="资料位置">
          <button @click="w.scope()"><ManagementIcon name="library" :size="13" />全部漫画</button>
          <template v-if="w.detail"
            ><ManagementIcon name="right" :size="12" /><button @click="w.scope(w.comicId)">{{ w.detail.title }}</button>
            <template v-for="entry in w.breadcrumbs" :key="entry.id"
              ><ManagementIcon name="right" :size="12" /><button @click="w.scope(w.comicId, entry.id)">
                {{ entry.title }}
              </button></template
            >
            <template v-if="w.chapter"
              ><ManagementIcon name="right" :size="12" /><span :title="w.chapter.chapter.title">{{
                w.chapter.chapter.title
              }}</span></template
            >
          </template>
        </nav>
        <section
          v-if="!w.root && w.detail"
          class="mw-comic-context"
          :class="{ 'mw-comic-context-compact': !!w.chapterId || !!w.catalogId }"
          aria-label="当前漫画"
        >
          <ManagementImage
            v-if="w.detail.coverUrl && !w.chapterId && !w.catalogId"
            class="mw-context-backdrop"
            :src="w.detail.coverUrl"
            alt=""
            aria-hidden="true"
          />
          <ManagementImage class="mw-context-poster" :src="w.detail.coverUrl" :alt="w.detail.title" />
          <div class="mw-context-copy">
            <h2 :title="w.detail.title">{{ w.detail.title }}</h2>
            <p>
              <span v-if="w.detail.author">{{ w.detail.author }} · </span>{{ w.detail.chapters.length }} 个章节<span
                v-if="w.detail.category"
              >
                · {{ w.detail.category.name }}</span
              >
            </p>
            <p v-if="w.detail.description && !w.chapterId && !w.catalogId" class="mw-context-description">
              {{ w.detail.description }}
            </p>
          </div>
          <RouterLink
            :to="`/comic/${w.detail.id}`"
            class="app-button app-button--secondary mw-context-reading"
            :class="{ 'is-small': !!w.chapterId || !!w.catalogId }"
            ><ManagementIcon name="book" :size="15" />阅读详情</RouterLink
          >
        </section>
        <div class="mw-library-workbench">
          <div v-if="w.root" ref="librarySearchRegion" class="mw-library-search" role="search" aria-label="搜索漫画库">
            <label class="mw-workbench-field mw-workbench-query">
              <span>查找漫画</span>
              <AppSearch
                v-model="w.keyword"
                label="搜索漫画名称"
                placeholder="搜索名称、原名或作者"
                clear-label="清空漫画搜索"
                @submit="w.filters()"
              />
            </label>
            <label class="mw-workbench-field mw-workbench-sort">
              <span>排列方式</span>
              <div class="mw-sort-controls">
                <select v-model="w.sort" aria-label="漫画排序" @change="changeSort">
                  <option value="title">名称</option>
                  <option value="createdAt">创建时间</option>
                  <option value="pages">页数</option>
                  <option value="totalBytes">总体积</option>
                  <option value="hqBytes">原件体积</option>
                  <option value="lqBytes">轻量副本体积</option>
                </select>
                <button
                  type="button"
                  class="mw-icon-button"
                  :aria-label="w.sortOrder === 'asc' ? '切换为降序排列' : '切换为升序排列'"
                  :title="w.sortOrder === 'asc' ? '当前升序，切换为降序' : '当前降序，切换为升序'"
                  @click="toggleSortOrder"
                >
                  <ManagementIcon :name="w.sortOrder === 'asc' ? 'up' : 'moveDown'" :size="15" />
                </button>
              </div>
            </label>
            <ManagementActionMenu
              class="mw-library-filter-menu"
              :label="activeLibraryFilters ? `筛选 · ${activeLibraryFilters}` : '筛选'"
              :aria-label="activeLibraryFilters ? `筛选，已应用 ${activeLibraryFilters} 项条件` : undefined"
            >
              <div class="mw-filter-panel" role="group" aria-label="漫画筛选条件">
                <p class="mw-filter-heading">资料</p>
                <label class="mw-workbench-field" :class="{ 'is-filtered': w.category || w.uncategorized }">
                  <span>分类</span>
                  <select v-model="categorySelection" aria-label="分类筛选">
                    <option value="">全部分类</option>
                    <option :value="UNCATEGORIZED">未分类</option>
                    <option v-for="term in w.categories" :key="term.id" :value="term.id">{{ term.name }}</option>
                  </select>
                </label>
                <div
                  class="mw-workbench-field mw-filter-tags"
                  :class="{ 'is-filtered': w.tagIds.length || w.untagged }"
                >
                  <span>标签</span>
                  <details class="mw-tag-picker">
                    <summary class="mw-tag-picker-trigger">
                      <span :title="tagSummary">{{ tagSummary }}</span>
                      <ManagementIcon name="down" :size="14" />
                    </summary>
                    <div class="mw-tag-picker-body" role="group" aria-label="标签筛选" data-menu-keep-open>
                      <AppSearch
                        v-if="w.tags.length > 8"
                        v-model="tagQuery"
                        label="搜索标签"
                        placeholder="搜索标签名称"
                        clear-label="清空标签搜索"
                      />
                      <label class="mw-filter-option mw-tag-none">
                        <input
                          type="checkbox"
                          :checked="w.untagged"
                          @change="toggleUntagged(($event.target as HTMLInputElement).checked)"
                        />无标签
                      </label>
                      <div class="mw-tag-options">
                        <label v-for="term in visibleTags" :key="term.id" class="mw-tag-option" :title="term.name">
                          <input
                            type="checkbox"
                            :checked="w.tagIds.includes(term.id)"
                            @change="toggleTag(term.id, ($event.target as HTMLInputElement).checked)"
                          /><span>{{ term.name }}</span>
                        </label>
                      </div>
                      <p v-if="!visibleTags.length" class="mw-muted">没有匹配的标签。</p>
                      <label class="mw-tag-mode">
                        <span>匹配方式</span>
                        <select
                          v-model="w.tagMode"
                          :disabled="w.untagged || !w.tagIds.length"
                          aria-label="标签匹配方式"
                          @change="w.filters"
                        >
                          <option value="any">任一</option>
                          <option value="all">全部</option>
                          <option value="not">排除</option>
                        </select>
                      </label>
                    </div>
                  </details>
                </div>
                <p class="mw-filter-heading">存储与维护</p>
                <fieldset class="mw-filter-group">
                  <legend>原件</legend>
                  <div class="mw-filter-options">
                    <label class="mw-filter-option"
                      ><input v-model="w.hq" type="radio" name="library-hq" value="" @change="w.filters" />不限</label
                    >
                    <label class="mw-filter-option"
                      ><input
                        v-model="w.hq"
                        type="radio"
                        name="library-hq"
                        value="has_hq"
                        @change="w.filters"
                      />有原件</label
                    >
                    <label class="mw-filter-option"
                      ><input
                        v-model="w.hq"
                        type="radio"
                        name="library-hq"
                        value="no_hq"
                        @change="w.filters"
                      />无原件</label
                    >
                  </div>
                </fieldset>
                <fieldset class="mw-filter-group">
                  <legend>轻量副本</legend>
                  <div class="mw-filter-options">
                    <label class="mw-filter-option"
                      ><input v-model="w.lq" type="radio" name="library-lq" value="" @change="w.filters" />不限</label
                    >
                    <label class="mw-filter-option"
                      ><input
                        v-model="w.lq"
                        type="radio"
                        name="library-lq"
                        value="ready"
                        @change="w.filters"
                      />已就绪</label
                    >
                    <label class="mw-filter-option"
                      ><input
                        v-model="w.lq"
                        type="radio"
                        name="library-lq"
                        value="needs_lq"
                        @change="w.filters"
                      />未就绪</label
                    >
                  </div>
                </fieldset>
                <fieldset class="mw-filter-group">
                  <legend>维护关注</legend>
                  <div class="mw-filter-options">
                    <label class="mw-filter-option"
                      ><input
                        v-model="w.attention"
                        type="radio"
                        name="library-attention"
                        value=""
                        @change="w.filters"
                      />不限</label
                    >
                    <label class="mw-filter-option"
                      ><input
                        v-model="w.attention"
                        type="radio"
                        name="library-attention"
                        value="missing_lq"
                        @change="w.filters"
                      />缺少副本</label
                    >
                    <label class="mw-filter-option"
                      ><input
                        v-model="w.attention"
                        type="radio"
                        name="library-attention"
                        value="can_release"
                        @change="w.filters"
                      />可释放原件</label
                    >
                    <label class="mw-filter-option"
                      ><input
                        v-model="w.attention"
                        type="radio"
                        name="library-attention"
                        value="missing_source_sha256"
                        @change="w.filters"
                      />缺少原件 SHA-256</label
                    >
                  </div>
                </fieldset>
                <button v-if="activeLibraryFilters" type="button" class="mw-filter-reset" @click="clearLibraryFilters">
                  清除全部筛选
                </button>
              </div>
            </ManagementActionMenu>
          </div>
          <div
            v-if="w.root && activeLibraryFilters"
            ref="appliedFilterBar"
            class="mw-applied-filters"
            role="group"
            aria-label="已应用筛选"
          >
            <button
              v-for="filter in appliedLibraryFilters"
              :key="filter.key"
              class="mw-button mw-filter-chip"
              :title="filter.label"
              :aria-label="`移除筛选：${filter.label}`"
              @click="removeLibraryFilter(filter.key)"
            >
              <span>{{ filter.label }}</span
              ><ManagementIcon name="close" :size="13" />
            </button>
            <button class="mw-button mw-filter-clear" @click="clearLibraryFilters">清除筛选</button>
          </div>
          <div v-if="w.rows.length || w.orderMode" ref="libraryControls" class="mw-library-controls">
            <div
              class="mw-toolbar-panel mw-toolbar-normal"
              :class="{ 'mw-toolbar-inactive': choosing || w.orderMode }"
              :inert="choosing || w.orderMode"
              :aria-hidden="choosing || w.orderMode"
              role="toolbar"
              aria-label="漫画整理工具"
            >
              <button ref="selectionTrigger" class="mw-button" @click="enterSelection">
                <ManagementIcon name="check" />{{ w.root ? '选择漫画' : '选择内容' }}
              </button>
              <div class="mw-workbench-display" role="group" aria-label="内容显示">
                <label class="mw-density"
                  >{{ w.chapterId ? '缩略图' : '封面'
                  }}<input
                    v-model.number="w.tileSize"
                    type="range"
                    min="112"
                    max="220"
                    step="2"
                    aria-label="缩略图大小"
                /></label>
                <div class="mw-view-switch">
                  <button
                    type="button"
                    class="mw-icon-button"
                    aria-label="网格视图"
                    :aria-pressed="w.mode === 'grid'"
                    :class="{ active: w.mode === 'grid' }"
                    @click="w.mode = 'grid'"
                  >
                    <ManagementIcon name="grid" /></button
                  ><button
                    type="button"
                    class="mw-icon-button"
                    aria-label="列表视图"
                    :aria-pressed="w.mode === 'list'"
                    :class="{ active: w.mode === 'list' }"
                    @click="w.mode = 'list'"
                  >
                    <ManagementIcon name="list" />
                  </button>
                </div>
              </div>
              <button v-if="!w.root" class="mw-button" :disabled="!w.writable || !w.canOrder" @click="w.beginOrder">
                调整顺序
              </button>
            </div>
            <div
              class="mw-toolbar-panel mw-toolbar-selection"
              :class="{ 'mw-toolbar-inactive': !choosing || w.orderMode }"
              :inert="!choosing || w.orderMode"
              :aria-hidden="!choosing || w.orderMode"
              role="toolbar"
              aria-label="所选内容操作"
            >
              <div class="mw-selection-controls" role="group" aria-label="选择范围">
                <label class="mw-select-page"
                  ><input
                    type="checkbox"
                    :aria-label="w.root ? '选择已加载全部' : '选择本页全部'"
                    :checked="w.rows.length > 0 && w.rows.every((row) => w.selected.includes(row.id))"
                    @change="selectPage"
                  />{{ w.root ? '已加载全选' : '本页全选' }}</label
                >
                <strong class="mw-selection" aria-live="polite"
                  >已选 {{ w.selected.length }} {{ w.root ? '部漫画' : '项' }}</strong
                >
                <button class="mw-button" @click="exitSelection">退出选择</button>
              </div>
              <div
                class="mw-selection-actions"
                role="group"
                aria-label="批量操作"
                :class="{ 'mw-toolbar-inactive': !w.selected.length }"
                :inert="!w.selected.length"
                :aria-hidden="!w.selected.length"
              >
                <button
                  v-if="w.root"
                  class="mw-button"
                  aria-label="编辑所选资料"
                  :disabled="!w.writable"
                  @click="showMetadata(true)"
                >
                  <ManagementIcon name="edit" :size="14" />编辑资料
                </button>
                <button
                  class="mw-button"
                  aria-label="标记所选内容"
                  :disabled="!w.writable || !canRateSelected"
                  title="只标记漫画、章节和媒体；目录不支持标记"
                  @click="showRating"
                >
                  <ManagementIcon name="heart" :size="14" />标记
                </button>
                <template v-if="w.root"
                  ><ManagementActionMenu label="更多处理">
                    <button class="mw-button" :disabled="!w.writable" @click="w.batch('metadata_refresh')">
                      核对媒体
                    </button>
                    <button class="mw-button" :disabled="!w.writable" @click="w.batch('video_transcode')">
                      批量视频转码
                    </button>
                    <button class="mw-button" :disabled="!w.writable" @click="showLibraryLq">生成轻量副本</button>
                    <hr />
                    <button class="mw-button" :disabled="!w.writable || !canMoveDirectory" @click="showDirectoryMove">
                      移出到文件夹
                    </button>
                    <button class="mw-button mw-button-danger" :disabled="!w.writable" @click="showHqBatch(w.selected)">
                      释放图片原件
                    </button>
                  </ManagementActionMenu>
                  <button class="mw-button mw-workbench-trash" :disabled="!w.writable" @click="w.batch('comic_trash')">
                    <ManagementIcon name="trash" :size="14" />回收
                  </button></template
                >
                <template v-else
                  ><button v-if="!w.chapterId" class="mw-button" :disabled="!w.writable" @click="w.showMove">
                    移动到</button
                  ><button class="mw-button" :disabled="!w.writable || !w.canOrder" @click="w.beginOrder">
                    调整顺序
                  </button></template
                >
                <button
                  v-if="w.canBatchTrash"
                  class="mw-button mw-workbench-trash"
                  :disabled="!w.writable"
                  @click="w.batchSelectedTrash"
                >
                  <ManagementIcon name="trash" :size="14" />回收所选
                </button>
              </div>
            </div>
            <div v-if="w.orderMode" class="mw-toolbar-panel mw-toolbar-order" role="toolbar" aria-label="调整顺序">
              <span class="mw-selection">整组排序</span>
              <span v-if="w.selected.length" class="mw-muted">已选 {{ w.selected.length }} 项，按整组移动</span>
              <button v-if="w.selected.length" class="mw-button" @click="w.selected = []">清除选择</button>
              <span class="mw-actionbar-spacer" />
              <button
                class="mw-icon-button"
                aria-label="将当前项上移"
                :disabled="!w.writable || !orderBounds || orderBounds.first === 0"
                @click="w.move(-1)"
              >
                <ManagementIcon name="up" :size="15" />
              </button>
              <button
                class="mw-icon-button"
                aria-label="将当前项下移"
                :disabled="!w.writable || !orderBounds || orderBounds.last === w.rows.length - 1"
                @click="w.move(1)"
              >
                <ManagementIcon name="moveDown" :size="15" />
              </button>
              <button class="mw-button" :disabled="w.busy" @click="w.cancelOrder">取消调整</button
              ><button class="mw-button mw-button-primary" :disabled="!w.writable" @click="w.saveOrder">
                保存顺序
              </button>
            </div>
          </div>
        </div>
        <ManagementOrder
          v-if="w.orderMode"
          :rows="w.rows"
          :selected="w.selected"
          :active="w.focused"
          :busy="w.busy"
          :unit="w.chapterId ? '项媒体' : '项内容'"
          @reorder="w.reorder"
          @move="w.move"
          @focus="w.focused = $event"
        />
        <template v-else>
          <div v-if="w.loading" class="mw-loading" role="status">
            <ManagementIcon name="loading" class="mw-spin" />正在读取资料…
          </div>
          <ManagementPagedFeed
            v-else
            :items="w.rows"
            :page="w.root ? w.libraryBasePage : w.nodePage"
            :total="w.root ? (w.total ?? 0) : w.scopeTotal"
            :page-size="w.root ? 20 : 50"
            :infinite="true"
            :loading="w.loading"
            :loading-more="w.root ? w.loadingMore : false"
            :append-error="w.root ? w.loadMoreError : ''"
            :disabled="w.loading || w.busy"
            :label="w.root ? '漫画库分页' : '内容分页'"
            @page-change="changeLibraryPage"
            @load-more="loadMoreLibraryPage"
            @retry="retryLibraryPage"
          >
            <template #default="{ items }">
              <ManagementFiles
                :rows="items"
                :selected="w.selected"
                :active="w.focused"
                :mode="w.mode"
                :busy="w.busy"
                :loading="false"
                :selection-mode="w.selectionMode"
                :tile-size="w.tileSize"
                :empty-title="emptyState.title"
                :empty-message="emptyState.message"
                @open="w.open"
                @focus="w.focus"
                @details="w.showDetails"
                @select="w.select"
                @select-all="w.selectAll"
              />
            </template>
          </ManagementPagedFeed>
        </template>
        <footer
          v-if="!w.orderMode && !w.loading && !w.scopeFailed && (!w.root || w.total !== null)"
          class="mw-pagination"
        >
          <span>{{
            w.root
              ? `当前 ${w.records.length} 部 · 共 ${w.total} 部漫画`
              : `共 ${w.scopeTotal} 项 · 单击打开，勾选后整理`
          }}</span>
        </footer>
        <p class="mw-footnote">
          <ManagementIcon name="info" :size="13" />体积与原件状态来自资料登记；实体文件核对结果请在任务中心查看。
        </p>
      </template>

      <ManagementReactions
        v-else-if="w.section === 'reactions' && w.ready"
        :revision="w.reactionRefresh"
        :recycled="w.recycledReactions"
        :writable="w.writable"
        @recycle="w.recycleDislikedMedia"
      />
      <ManagementSystem v-else-if="w.section === 'system' && w.ready" ref="systemPanel" />
      <ManagementTasks
        v-else-if="w.section === 'tasks' && w.ready"
        :tasks="w.tasks"
        :groups="w.taskGroups"
        :next-event="w.nextEvent"
        :artifacts="w.taskArtifacts"
        :artifacts-loading="w.artifactsLoading"
        :parent-task="w.taskTrail.at(-1)?.state.task.title || (w.taskTrail.length ? '上一级批次' : '')"
        :return-task-id="w.taskTrail[0]?.state.task.id || ''"
        :import-page="w.selectedTask ? (w.importResultPages[w.selectedTask.id] ?? 1) : 1"
        :summary="w.summary"
        :selected="w.selectedTask"
        :events="w.events"
        :items="w.items"
        :item-total="w.itemTotal"
        :loading="w.loading"
        :busy="w.busy"
        :total="w.taskTotal"
        :list-loaded="w.taskListLoaded"
        :list-failed="w.taskListFailed"
        :error="w.taskError"
        :status="w.taskStatus"
        :type="w.taskType"
        :keyword="w.taskKeyword"
        :oldest="w.taskOldest"
        @import-page="w.setImportPage"
        @results-ready="w.taskResultsReady"
        @storage="w.showStorageObservation"
        @related="w.relatedTask"
        @back="w.backTask"
        @imported="w.openImportedTask"
        @filters="w.filterTasks"
        @group-page="w.taskGroupPage"
        @group-load-more="w.loadMoreTaskGroup"
        @group-retry="w.retryTaskGroup"
        @more-events="w.moreTaskEvents"
        @more-items="w.moreTaskItems"
        @close="w.closeTask"
        @inspect="w.selectTask"
        @scan="showScan"
        @upload="restoreUpload"
        @inspect-import="w.relatedImportTask"
        @audit="showLifecycle"
        @directory="resumeDirectory"
        @recovery="(id) => showRecovery('scan', '', id)"
        @analysis="(id) => showAnalysis('', id)"
        @sidecar="(id) => showSidecar('', id)"
        @metadata="(id) => showMetadata(false, id)"
        @repair="(comic, id) => showRecovery('repair', comic, id)"
        @action="w.taskAction"
        @refresh="w.loadScope"
      />

      <template v-else-if="w.section === 'trash' && w.ready"
        ><div class="mw-toolbar" role="search" aria-label="搜索回收站">
          <AppSearch
            v-model="w.trashKeyword"
            label="搜索回收内容"
            placeholder="搜索回收内容"
            :disabled="w.busy"
            @search="searchTrash"
          />
        </div>
        <p v-if="w.trashPendingTotal" class="mw-notice" role="status">
          <ManagementIcon name="warning" :size="14" />
          有 {{ w.trashPendingTotal }} 条回收、恢复或清理尚未收尾，回收站只列出已回收内容。
          <button class="mw-button" @click="showLifecycle('', trashPendingState)">查看未收尾记录</button>
        </p>
        <ManagementTrash
          :entries="w.trash"
          :writable="w.writable"
          :loading="w.loading"
          :loading-more="w.trashLoadingMore"
          :total="w.trashTotal"
          :page="w.trashBasePage"
          :keyword="w.trashKeyword"
          :append-error="w.trashLoadMoreError"
          @restore="(ids) => w.batchTrash('trash_restore', ids)"
          @purge="(ids) => w.batchTrash('trash_purge', ids)"
          @audit="showLifecycle"
          @page-change="w.goTrashPage"
          @load-more="w.loadMoreTrash"
          @retry="w.loadMoreTrash"
      /></template>

      <ManagementStorage
        v-else-if="w.section === 'storage' && w.statistics"
        :statistics="w.statistics"
        :writable="w.writable"
        :busy="w.busy"
        @audit="showLifecycle()"
        @recover="showRecovery('scan')"
        @release="showHqStorage"
        @release-batch="showHqBatch"
        @refresh="w.refreshStorage"
        @generate="showLqBatch"
        @generate-single="showLqSingleFromStorage"
      />
    </div>

    <template #inspector
      ><div class="mw-inspector-head">
        <span>详细信息</span
        ><button class="mw-icon-button" aria-label="关闭详情面板" @click="w.closeInspector">
          <ManagementIcon name="close" :size="16" />
        </button>
      </div>
      <div class="mw-inspector-body">
        <template v-if="w.detail && w.focusedRow"
          ><div class="mw-preview">
            <ManagementImage
              :src="
                w.selectedMedia
                  ? w.selectedMedia.previewUrl
                  : (w.focusedRow.previewImages[0] ?? (inspectingComic ? w.detail.coverUrl : null))
              "
              :alt="w.focusedRow?.title || w.detail.title"
            /><span>{{ inspectedKindLabel }}</span>
          </div>
          <h2>{{ w.focusedRow.title }}</h2>
          <p class="mw-muted">
            当前查看：{{ w.focusedRow.title
            }}<template v-if="w.selected.length > 1"> · 已选 {{ w.selected.length }} 项，此处为单项详情</template>
          </p>
          <div class="mw-inspector-primary">
            <button
              v-if="w.selectedMedia?.mediaType === 'image'"
              class="mw-button"
              :disabled="!w.writable"
              @click="coverOpen = true"
            >
              <ManagementIcon name="image" :size="14" />设为漫画封面
            </button>
            <button v-if="w.selectedMedia" class="mw-button" :disabled="!w.writable" @click="showUpload(true)">
              替换此媒体文件</button
            ><button
              v-if="w.focusedRow && ['catalog', 'chapter'].includes(w.focusedRow.kind)"
              class="mw-button"
              :disabled="!w.writable"
              @click="w.showNode(w.focusedRow.kind === 'catalog' ? 'catalogs' : 'chapters', w.focusedRow)"
            >
              重命名 / 移动</button
            ><button
              v-if="w.focusedRow && ['comic', 'chapter', 'image', 'video'].includes(w.focusedRow.kind)"
              class="mw-button mw-button-danger"
              :disabled="!w.writable"
              @click="w.showTrash(w.focusedRow)"
            >
              <ManagementIcon name="trash" :size="14" />移入回收站
            </button>
            <button v-if="inspectingComic" class="mw-button" :disabled="!w.writable" @click="w.showEdit">
              <ManagementIcon name="edit" :size="14" />编辑漫画资料</button
            ><RouterLink
              v-if="w.focusedRow.kind !== 'catalog'"
              class="mw-button mw-button-secondary"
              :to="
                w.focusedRow.kind === 'chapter' || w.chapterId
                  ? {
                      path: `/reader/${w.focusedRow.kind === 'chapter' ? w.focusedRow.id : w.chapterId}`,
                      query: w.selectedMedia ? { media: w.selectedMedia.id } : {},
                    }
                  : `/comic/${w.detail.id}`
              "
              ><ManagementIcon name="book" :size="14" />{{
                inspectingComic ? '打开阅读详情' : '从这里阅读'
              }}</RouterLink
            >
          </div>
          <details v-if="hasInspectionMaintenance" class="mw-inspector-maintenance">
            <summary>维护与文件操作</summary>
            <p class="mw-muted">仅处理当前{{ inspectedKindLabel }}：{{ w.focusedRow.title }}。</p>
            <div class="mw-inspector-actions">
              <template v-if="w.resourceStorage">
                <button
                  v-if="w.focusedRow && ['comic', 'chapter'].includes(w.focusedRow.kind)"
                  class="mw-button mw-button-danger"
                  :disabled="!w.writable"
                  @click="showHqResource"
                >
                  释放图片原件
                </button>
                <p v-if="!w.resourceStorage.operations.releaseHq.canSubmit" class="mw-muted">
                  {{ operationBlockers(w.resourceStorage.operations.releaseHq.blockers) }}
                </p>
                <template v-if="w.resourceStorage.inventory.videos > 0"
                  ><button
                    class="mw-button"
                    :disabled="!w.writable || !w.resourceStorage.operations.transcode.canSubmit"
                    @click="w.showTranscode"
                  >
                    <ManagementIcon name="video" :size="14" />视频转码
                  </button>
                  <p v-if="!w.resourceStorage.operations.transcode.canSubmit" class="mw-muted">
                    {{ operationBlockers(w.resourceStorage.operations.transcode.blockers) }}
                  </p></template
                >
                <button
                  class="mw-button"
                  :disabled="!w.writable || !w.resourceStorage.operations.generateLq.canSubmit"
                  @click="showLqSingle"
                >
                  <ManagementIcon name="image" :size="14" />生成轻量副本
                </button>
                <p v-if="!w.resourceStorage.operations.generateLq.canSubmit" class="mw-muted">
                  {{ operationBlockers(w.resourceStorage.operations.generateLq.blockers) }}
                </p>
                <template v-if="w.focusedRow?.kind === 'comic'"
                  ><button
                    class="mw-button"
                    :disabled="!w.writable || !w.resourceStorage.operations.refreshMetadata.canSubmit"
                    @click="w.showRefresh"
                  >
                    <ManagementIcon name="scan" :size="14" />核对媒体
                  </button>
                  <p v-if="!w.resourceStorage.operations.refreshMetadata.canSubmit" class="mw-muted">
                    {{ operationBlockers(w.resourceStorage.operations.refreshMetadata.blockers) }}
                  </p></template
                >
              </template>
              <button
                v-if="w.selectedMedia?.mediaType === 'video'"
                class="mw-button"
                :disabled="!w.writable"
                @click="w.showTranscode"
              >
                <ManagementIcon name="video" :size="14" />视频转码
              </button>

              <template v-if="inspectingComic">
                <button class="mw-button" :disabled="!w.writable" @click="showAnalysis(w.detail.id)">AI 候选分析</button
                ><button class="mw-button" @click="showSidecar(w.detail.id)">侧车同步状态</button
                ><button class="mw-button" :disabled="!w.writable" @click="w.showExport">导出整部漫画</button
                ><button class="mw-button" :disabled="!w.writable" @click="showDirectorySingle">
                  移出整部漫画到文件夹
                </button>
              </template>
              <button
                v-if="w.focusedRow?.kind === 'catalog'"
                class="mw-button mw-button-danger"
                :disabled="!w.writable"
                @click="w.showDeleteCatalog(w.focusedRow)"
              >
                删除目录
              </button>
            </div>
          </details>
          <details class="mw-info-section">
            <summary>对象标识</summary>
            <p class="mw-id">{{ w.focusedRow.id }}</p>
          </details>
          <section class="mw-info-section">
            <h3>基本信息</h3>
            <dl class="mw-properties">
              <dt>所属作品</dt>
              <dd>{{ w.detail.title }}</dd>
              <template v-if="w.focusedRow?.kind === 'chapter' && w.focusedRow.count != null"
                ><dt>媒体数量</dt>
                <dd>{{ w.focusedRow.count }}</dd></template
              >
              <template v-if="w.focusedRow.kind === 'catalog'"
                ><dt>直接子项</dt>
                <dd>{{ w.focusedRow.count }}</dd></template
              >
              <template v-if="inspectingComic">
                <dt>作者</dt>
                <dd>{{ w.detail.author || '未填写' }}</dd>
                <dt>分类</dt>
                <dd>{{ w.detail.category?.name || '未分类' }}</dd>
                <dt>章节数量</dt>
                <dd>{{ w.detail.chapters.length }}</dd>
                <dt>添加时间</dt>
                <dd>{{ formatDate(w.detail.createdAt) }}</dd>
              </template>
              <template v-if="w.selectedMedia"
                ><dt>文件大小</dt>
                <dd>{{ formatBytes(w.selectedMedia.size) }}</dd>
                <dt>原件登记</dt>
                <dd>{{ registrationLabel(w.selectedMedia.hqState) }}</dd>
                <dt>媒体顺序</dt>
                <dd>{{ w.selectedMedia.pageNumber }}</dd></template
              >
              <template v-if="!w.selectedMedia && w.focusedRow?.bytes != null"
                ><dt>登记大小</dt>
                <dd>{{ formatBytes(w.focusedRow.bytes) }}</dd></template
              >
              <template v-if="!w.selectedMedia && w.focusedRow?.hq"
                ><dt>原件登记</dt>
                <dd>{{ registrationLabel(w.focusedRow.hq) }}</dd></template
              >
              <template v-if="w.focusedRow?.lq"
                ><dt>轻量副本登记</dt>
                <dd>{{ registrationLabel(w.focusedRow.lq) }}</dd></template
              >
              <template v-if="w.selectedMedia?.width && w.selectedMedia?.height"
                ><dt>画面尺寸</dt>
                <dd>{{ w.selectedMedia.width }} × {{ w.selectedMedia.height }}</dd></template
              >
            </dl>
          </section>
          <section v-if="inspectingComic" class="mw-info-section">
            <h3>作品标签 <ManagementIcon name="tag" :size="13" /></h3>
            <div class="mw-tags">
              <span v-for="tag in w.detail.tags" :key="tag.id">{{ tag.name }}</span
              ><small v-if="!w.detail.tags.length" class="mw-muted">尚未添加标签</small>
            </div>
          </section>
          <section v-if="inspectingComic" class="mw-info-section">
            <h3>作品简介</h3>
            <p class="mw-inspector-description">{{ w.detail.description || '这部作品还没有简介。' }}</p>
          </section>
        </template>
        <div v-else class="mw-empty">
          <ManagementIcon name="info" :size="36" />
          <p>打开卡片详情，查看当前对象的信息</p>
        </div>
      </div></template
    >

    <template #overlay
      ><ManagementPreview
        :items="w.chapter?.items ?? []"
        :active="w.previewId"
        :title="w.heading || ''"
        @close="w.previewId = ''"
        @change="w.previewId = $event"
      />
      <ManagementRecovery
        v-if="recoveryOpen"
        :key="recoveryMode + ':' + recoveryComic + ':' + recoveryTask"
        :mode="recoveryMode"
        :comic-id="recoveryComic"
        :task-id="recoveryTask"
        :writable="w.writable"
        @close="closeRecovery"
        @accepted="w.acceptExternalTask"
        @task="inspectRecoveryTask"
        @repair="(id) => showRecovery('repair', id)"
        @scan="(id) => showRecovery('scan', '', id)"
        @comic="openRecoveredComic"
      />
      <ManagementMetadata
        v-if="metadataOpen"
        :key="metadataPreview"
        :targets="metadataTargets"
        :preview-id="metadataPreview"
        :writable="w.writable"
        @close="closeMetadata"
        @saved="metadataSaved"
        @accepted="w.acceptExternalTask"
        @task="inspectMetadataTask"
      />
      <ManagementSidecar
        v-if="sidecarOpen"
        :key="sidecarComic + ':' + sidecarTask"
        :comic-id="sidecarComic"
        :task-id="sidecarTask"
        :writable="w.writable"
        @close="sidecarOpen = false"
        @accepted="w.acceptExternalTask"
        @task="inspectSidecarTask"
      />
      <ManagementAi
        v-if="analysisOpen"
        :key="analysisComic + ':' + analysisTask"
        :comic-id="analysisComic"
        :task-id="analysisTask"
        :writable="w.writable"
        @close="closeAnalysis"
        @accepted="w.acceptExternalTask"
        @task="inspectAnalysisTask"
      />
      <ManagementDirectoryMove
        v-if="directoryOpen"
        :targets="directoryTargets"
        :preview-id="directoryPreview"
        :writable="w.writable"
        @close="closeDirectory"
        @accepted="w.acceptExternalTask"
        @task="inspectDirectoryTask"
      />
      <ManagementLifecycle
        v-if="lifecycleOpen"
        :open="lifecycleOpen"
        :entry-id="lifecycleEntry"
        :initial-state="lifecycleState"
        :writable="w.writable"
        @close="lifecycleOpen = false"
        @accepted="w.acceptExternalTask"
        @task="inspectLifecycleTask"
      />
      <ManagementReactionConfirm
        :open="ratingOpen"
        :items="ratingTargets"
        scope="active"
        @close="ratingOpen = false"
        @saved="ratingSaved"
      />
      <ManagementHqRelease
        :open="hqOpen"
        :target="hqTarget"
        :ids="hqIds"
        :workspace-busy="w.busy"
        @close="hqOpen = false"
        @accepted="acceptedHq"
      />
      <ManagementLqDialog
        :open="lqOpen"
        :target="lqTarget"
        :objects="lqObjects"
        :workspace-busy="w.busy"
        @close="lqOpen = false"
        @accepted="acceptedLq"
      />
      <ManagementUpload
        :open="uploadOpen"
        :target="uploadTarget"
        :session-id="uploadSessionId"
        :workspace-busy="w.busy"
        @close="uploadOpen = false"
        @accepted="w.acceptExternalTask"
        @inspect="inspectUploadTask"
      />
      <ManagementCover
        v-if="w.detail && w.selectedMedia"
        :open="coverOpen"
        :comic-id="w.detail.id"
        :media-id="w.selectedMedia.id"
        :title="w.selectedMedia.fileName"
        :preview-url="w.selectedMedia.previewUrl"
        :writable="w.writable"
        @close="coverOpen = false"
        @changed="coverChanged"
      />
      <ManagementImportScan
        :open="scanOpen"
        :task-id="scanTaskId"
        :workspace-busy="w.busy"
        @close="scanOpen = false"
        @accepted="w.acceptExternalTask"
        @inspect="inspectImportTask"
      />
      <ManagementTerms :open="termsOpen" @close="termsOpen = false" @changed="w.refreshTerms" />
      <ManagementDialog
        :placement="w.dialog === 'import' ? 'right' : 'center'"
        :open="!!w.dialog"
        :title="w.dialogTitle"
        :busy="w.busy"
        @close="w.dialog = ''"
        ><form @submit.prevent="w.submit">
          <p v-if="w.dialogError" class="mw-error" role="alert">{{ w.dialogError }}</p>
          <template v-if="w.dialog === 'import'"
            ><p class="mw-muted">单本来源支持目录或 ZIP / CBZ；多本导入使用批量入口。</p>
            <button type="button" class="mw-button" :disabled="w.busy" @click="showScan()">
              <ManagementIcon name="folder" :size="14" />批量导入目录或压缩包
            </button>
            <label class="mw-field"
              >导入方式<select v-model="w.importKind">
                <option value="directories">本地目录</option>
                <option value="archives">ZIP / CBZ 归档</option>
              </select></label
            ><label class="mw-field"
              >来源绝对路径<input
                v-model="w.sourcePath"
                required
                placeholder="例如 D:\ComicAtlas-import\一本漫画 或 D:\ComicAtlas-import\book.cbz"
            /></label>
            <p class="mw-muted">填写已配置导入目录内的完整路径。目录导入会移动来源媒体；压缩包导入保留原包。</p>
            <label class="mw-field">作品名称（可选）<input v-model="w.importTitle" /></label
          ></template>
          <template v-else-if="w.dialog === 'resource' && w.pendingResource">
            <p>
              处理对象：{{ w.pendingResource.title }}
              <span class="mw-muted"
                >·
                {{
                  w.pendingResource.kind === 'media'
                    ? '单个视频'
                    : w.pendingResource.kind === 'chapters'
                      ? '指定章节'
                      : '整部漫画'
                }}</span
              >
            </p>
            <template v-if="w.pendingResource.operation === 'transcode'"
              ><p class="mw-muted">
                转码为浏览器可播放的
                H.264/AAC；已兼容的视频会跳过。成功后会替换旧视频文件，媒体标识和阅读位置保持不变。该任务受理后不能手动取消。
              </p>
              <p v-if="w.pendingResource.videos !== null" class="mw-muted">
                登记视频 {{ w.pendingResource.videos }} 个<template v-if="w.pendingResource.images">
                  · 登记图片 {{ w.pendingResource.images }} 张</template
                >。
              </p>
              <label class="mw-select-page"
                ><input
                  v-model="w.transcodeAcknowledged"
                  type="checkbox"
                  :disabled="w.busy"
                />我理解视频转码会更新旧文件，已受理的任务不可取消</label
              ></template
            >
            <template v-else>
              <p class="mw-muted">
                核对该漫画的已登记媒体，记录缺失或变更，并处理可归属的新媒体。此操作不是磁盘占用扫描，具体结果在任务中心查看。
              </p>
              <p v-if="w.pendingResource.images !== null" class="mw-muted">
                登记图片 {{ w.pendingResource.images }} 张 · 视频 {{ w.pendingResource.videos ?? 0 }} 个。
              </p>
            </template>
          </template>
          <template v-else-if="w.dialog === 'export'">
            <p>导出范围：{{ w.exportForm.title }} 的全部活动目录、章节与媒体。</p>
            <label class="mw-field"
              >归档格式<select v-model="w.exportForm.format" :disabled="w.busy">
                <option value="cbz">CBZ 漫画归档</option>
                <option value="zip">ZIP 归档</option>
              </select></label
            >
            <label class="mw-select-page"
              ><input v-model="w.exportForm.multipart" type="checkbox" :disabled="w.busy" />按大小分卷</label
            >
            <label v-if="w.exportForm.multipart" class="mw-field"
              >分卷大小（MiB）<input
                v-model.number="w.exportForm.partMiB"
                type="number"
                min="0.0625"
                max="4095.999999"
                step="any"
                required
                :disabled="w.busy"
            /></label>
            <p class="mw-muted">
              生成内容与资料的归档快照，源文件保留。完成后在任务详情查看服务端文件位置；分卷需取得全部卷。支持 64 KiB 至
              4 GiB 以下的卷大小。
            </p>
          </template>
          <template v-else-if="w.dialog === 'edit'"
            ><label class="mw-field">作品名称<input v-model="w.edit.title" required /></label>
            <div class="mw-field-row">
              <label class="mw-field">原名<input v-model="w.edit.originalTitle" /></label
              ><label class="mw-field">作者<input v-model="w.edit.author" /></label>
            </div>
            <label class="mw-field"
              >分类<select v-model="w.edit.categoryId">
                <option value="">未分类</option>
                <option v-for="term in w.categories" :key="term.id" :value="term.id">{{ term.name }}</option>
              </select></label
            ><label class="mw-field">简介<textarea v-model="w.edit.description" rows="4" /></label>
            <fieldset class="mw-checkbox-list">
              <legend>标签</legend>
              <label v-for="term in w.tags" :key="term.id"
                ><input v-model="w.edit.tagIds" type="checkbox" :value="term.id" />{{ term.name }}</label
              >
              <p v-if="!w.tags.length" class="mw-muted">暂无可选标签</p>
            </fieldset></template
          >
          <template v-else-if="w.dialog === 'node'"
            ><label class="mw-field">名称<input v-model="w.node.title" required /></label
            ><label class="mw-field"
              >所在目录<select v-model="w.node.parentId">
                <option value="">漫画根目录</option>
                <option v-for="entry in w.nodeParents" :key="entry.id" :value="entry.id">
                  {{ w.catalogPath(entry.id) }}
                </option>
              </select></label
            >
            <p class="mw-muted">仅调整当前漫画内的名称和位置，不移动磁盘文件；目录不能放入自身或其子目录。</p></template
          >
          <template v-else-if="w.dialog === 'deleteCatalog' && w.removingCatalog">
            <p>删除目录「{{ w.removingCatalog.title }}」</p>
            <p class="mw-muted">
              仅移除目录层级；其中的直接子目录和章节会追加到接收位置，内部层级、媒体文件与阅读记录保留。此操作不进入回收站。
            </p>
            <label class="mw-field"
              >子内容接收位置<select v-model="w.reparentTo" :disabled="w.busy">
                <option value="">漫画根目录</option>
                <option v-for="entry in w.catalogDeleteTargets" :key="entry.id" :value="entry.id">
                  {{ w.catalogPath(entry.id) }}
                </option>
              </select></label
            >
          </template>
          <template v-else-if="w.dialog === 'move'">
            <p>
              当前位置：{{ w.detail?.title }} /
              {{ w.detail?.catalogs.find((c) => c.id === w.catalogId)?.title || '漫画根目录' }}
            </p>
            <div class="mw-preview-thumbs">
              <ManagementImage
                v-for="row in w.moveRows.slice(0, 8)"
                :key="row.id"
                :src="row.previewImages[0] || row.cover"
                :alt="row.title"
              />
            </div>
            <p class="mw-muted">
              已选 {{ w.moveRows.length }} 项，按当前顺序追加到目标目录末尾。目录内部内容随目录一起移动。
            </p>
            <label class="mw-field"
              >目标目录<select v-model="w.moveTarget">
                <option v-if="w.catalogId" value="">漫画根目录</option>
                <option v-else value="" disabled>请选择目录</option>
                <option v-for="entry in w.moveTargets" :key="entry.id" :value="entry.id">
                  {{ w.catalogPath(entry.id) }}
                </option>
              </select></label
            >
            <p class="mw-muted">仅移动本漫画内部结构，不移动或删除磁盘文件。</p>
          </template>
          <template v-else-if="w.dialog === 'batch' && w.preview">
            <BatchPreviewSummary
              :selected="w.preview.selectedCount"
              :eligible="w.preview.eligibleCount"
              :blocked="w.preview.blockedCount"
              :unit="batchPreviewUnit"
            />
            <p class="mw-muted">以下是本次操作的预览。确认后提交后台任务，请在任务中心检查逐项结果。</p>
            <p v-if="w.preview.operation === 'metadata_refresh'" class="mw-muted">
              按作品核对已登记媒体，记录缺失或变更；没有可核对媒体的作品会跳过。此操作不是磁盘占用扫描，逐项结果在任务中心查看。
            </p>
            <p v-if="w.preview.operation === 'trash_restore'" class="mw-muted">
              恢复后追加到原位置末尾，不保证回收前的序号：章节回到原目录末尾，媒体回到原章节末尾；阅读进度跟随媒体身份。祖先仍被回收时，需要先恢复祖先。
            </p>
            <p v-if="w.preview.operation === 'media_trash'" class="mw-muted">
              媒体回收只处理活动内容：已回收或所属章节、作品已回收的媒体不在范围内。回收会移入回收站并可在回收站恢复，喜欢／不喜欢标记保留。
            </p>
            <p v-if="w.preview.operation === 'chapter_trash'" class="mw-muted">
              章节回收会连同其下媒体一起移入回收站；恢复时按原顺序追加到原位置末尾，喜欢／不喜欢标记保留。
            </p>
            <p v-if="w.preview.operation === 'comic_trash'" class="mw-muted">
              漫画回收会连同目录、章节和媒体一起移入回收站；可在回收站恢复，喜欢／不喜欢标记保留。
            </p>
            <template v-if="w.preview.operation === 'video_transcode'">
              <p class="mw-muted">
                按作品冻结登记视频范围；没有视频的作品不会执行。转码为
                H.264/AAC，可能替换旧视频文件；已经兼容的视频由后端核对后跳过。已受理子任务不可取消，取消批次只停止未分派项并等待其余任务结束。
              </p>
              <label class="mw-select-page"
                ><input
                  v-model="w.transcodeAcknowledged"
                  type="checkbox"
                  :disabled="w.busy || !!w.batchReceipt"
                />我理解批量视频转码会更新旧文件，已受理的视频任务不可取消。</label
              >
            </template>
            <div v-if="w.preview.operation === 'trash_purge' && w.preview.purge" class="mw-error">
              <strong>永久清理后无法恢复</strong>
              <p>
                实际范围 {{ w.preview.purge.entryCount }} 个回收条目 · {{ w.preview.purge.fileCount }} 个登记文件 ·
                预计登记量 {{ formatBytes(w.preview.purge.registeredBytes) }}
              </p>
              <p>
                其中
                {{ w.preview.purge.unmeasuredFiles }}
                个文件尚未计量。父条目清理会包含其独立回收的子条目，覆盖范围不重复计算。
              </p>
              <p>
                漫画和章节按托管目录整体删除，目录内未登记文件也会一并删除；媒体只删除清单文件，不删除章节目录中的其他内容。
              </p>
            </div>
            <BatchPreviewList
              :rows="batchPreviewRows"
              :total="w.previewTotal"
              :loading-more="w.busy"
              @more="w.morePreviewItems"
            />
            <p class="mw-muted">预览有效至 {{ formatDate(w.preview.expiresAt) }}</p>
            <label v-if="w.preview.operation === 'trash_purge'" class="mw-select-page"
              ><input
                v-model="w.purgeAcknowledged"
                type="checkbox"
              />我已核对所选及覆盖内容，理解永久清理后无法恢复。</label
            ></template
          >
          <footer class="mw-dialog-footer">
            <button type="button" class="mw-button" :disabled="w.busy" @click="w.dialog = ''">取消</button
            ><button
              type="submit"
              class="mw-button mw-button-primary"
              :disabled="
                w.busy ||
                (w.dialog === 'resource' && w.pendingResource?.operation === 'transcode' && !w.transcodeAcknowledged) ||
                (w.dialog === 'batch' &&
                  (!w.preview?.eligibleCount || (w.preview.operation === 'trash_purge' && !w.purgeAcknowledged))) ||
                (w.dialog === 'batch' && w.preview?.operation === 'video_transcode' && !w.transcodeAcknowledged)
              "
            >
              <ManagementIcon v-if="w.busy" name="loading" class="mw-spin" :size="14" />{{
                w.busy ? '正在提交…' : submitLabel
              }}
            </button>
          </footer>
        </form></ManagementDialog
      ></template
    >
  </ManagementShell>
</template>
