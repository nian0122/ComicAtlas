<template>
  <div class="structure-page">
    <header class="structure-header">
      <div class="page-heading"><span class="eyebrow">COMIC / STRUCTURE</span><h1>目录与媒体</h1><p>从目录树定位章节，再在右侧完成维护。</p></div>
      <div class="header-tools"><span class="comic-ref">漫画 #{{ comicId }}</span><el-button :loading="loading" @click="loadTree">刷新结构</el-button></div>
    </header>
    <section class="structure-summary" aria-label="结构概览">
      <div><span>目录</span><strong>{{ catalogCount }}</strong></div>
      <div><span>章节</span><strong>{{ chapterCount }}</strong></div>
      <div><span>媒体</span><strong>{{ mediaItems.length || '—' }}</strong></div>
      <div class="summary-hint"><span>同步状态</span><strong>{{ treeStateLabel }}</strong></div>
    </section>
    <el-alert v-if="error" :title="error" type="error" show-icon />

    <section class="structure-browser">
      <aside class="tree-panel">
        <div class="panel-topline"><div><span class="panel-kicker">NAVIGATOR</span><h2>目录树</h2></div><span class="node-count">{{ structureRows.length }} 个根节点</span></div>
        <el-input v-model="structureKeyword" clearable placeholder="搜索目录或章节" class="tree-search" />
        <el-table v-loading="loading" class="structure-table" :data="filteredStructureRows" row-key="key" :tree-props="{ children: 'children' }" :row-class-name="rowClassName" :empty-text="emptyStateText" highlight-current-row @row-click="selectStructureRow">
          <el-table-column prop="title" min-width="190"><template #default="{ row }"><div class="tree-title"><span class="tree-icon">{{ row.kind === 'CATALOG' ? '▰' : '▱' }}</span><span>{{ row.title }}</span></div></template></el-table-column>
          <el-table-column width="72"><template #default="{ row }"><span class="tree-kind">{{ row.kind === 'CATALOG' ? '目录' : '章节' }}</span></template></el-table-column>
        </el-table>
      </aside>

      <main class="detail-panel">
        <template v-if="selectedRow">
          <div class="selected-header"><div><span class="panel-kicker">SELECTED NODE</span><h2>{{ selectedRow.title }}</h2><p>{{ selectedRow.kind === 'CATALOG' ? '目录节点' : '章节节点' }} · ID {{ selectedRow.id }} · 顺序 {{ selectedRow.order ?? '—' }}</p></div><el-tag :type="selectedRow.kind === 'CATALOG' ? 'warning' : 'info'" effect="plain">{{ selectedRow.kind === 'CATALOG' ? '目录' : '章节' }}</el-tag></div>
          <template v-if="selectedRow.kind === 'CATALOG'">
            <div class="child-summary"><strong>{{ selectedRow.children?.length ?? 0 }}</strong><span>个下级节点</span></div>
            <div class="child-list"><button v-for="child in selectedRow.children" :key="child.key" type="button" @click="selectStructureRow(child)"><span>{{ child.kind === 'CATALOG' ? '▰' : '▱' }}</span>{{ child.title }}<small>{{ child.kind === 'CATALOG' ? '目录' : '章节' }}</small></button><div v-if="!selectedRow.children?.length" class="empty-copy">这个目录还没有下级节点。</div></div>
          </template>
          <template v-else>
            <div class="media-heading"><div><h3>章节媒体</h3><p>{{ mediaItems.length ? `共 ${mediaItems.length} 个媒体` : '正在等待媒体加载' }}</p></div><el-button text @click="loadMedia">刷新媒体</el-button></div>
            <div class="media-summary"><div><span>HQ</span><strong>{{ mediaHqReadyCount }} / {{ mediaItems.length }}</strong><small>可访问</small></div><div><span>LQ</span><strong>{{ mediaLqReadyCount }} / {{ mediaLqApplicableCount }}</strong><small>已生成</small></div><div><span>媒体类型</span><strong>{{ mediaVideoCount ? '视频' : '图片' }}</strong><small>{{ mediaVideoCount ? `${mediaVideoCount} 个视频` : '图片媒体' }}</small></div></div>
            <el-table class="media-table" :data="mediaItems" row-key="id" empty-text="该章节暂无媒体" highlight-current-row :row-class-name="mediaRowClassName" @row-click="selectMediaRow">
              <el-table-column prop="pageNumber" label="顺序" width="76" />
              <el-table-column prop="fileName" label="文件名" min-width="190" show-overflow-tooltip />
              <el-table-column label="类型" width="90"><template #default="{ row }">{{ row.mediaType === 'VIDEO' ? 'VIDEO' : 'IMAGE' }}</template></el-table-column>
              <el-table-column :label="mediaStorageColumnLabel" width="120"><template #default="{ row }"><span class="media-status" :class="mediaHqClass(row)">{{ mediaHqLabel(row.hqStatus, row.hqUrl, row.mediaType) }}</span></template></el-table-column>
              <el-table-column label="LQ 状态" width="110"><template #default="{ row }"><span v-if="row.mediaType === 'VIDEO'" class="media-status is-na">不适用</span><span v-else class="media-status" :class="row.lqStatus === 'READY' ? 'is-ready' : 'is-pending'">{{ mediaLqLabel(row.lqStatus) }}</span></template></el-table-column>
              <el-table-column label="处理建议" min-width="150"><template #default="{ row }"><span class="media-hint" :class="mediaHintClass(row)">{{ mediaActionHint(row) }}</span></template></el-table-column>
            </el-table>
          </template>
        </template>
        <div v-else class="selection-empty"><span class="empty-mark">✦</span><h2>选择一个目录或章节</h2><p>左侧目录树用于导航，选中节点后这里会显示详细内容。</p></div>
      </main>

      <aside class="action-panel">
        <template v-if="selectedRow?.kind === 'CATALOG'">
          <div class="panel-topline"><div><span class="panel-kicker">MAINTENANCE</span><h2>目录操作</h2></div></div>
          <el-form label-position="top" class="action-form">
            <el-form-item label="操作"><el-select v-model="catalogForm.action"><el-option v-for="item in CATALOG_ACTIONS" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
            <el-form-item v-if="['create', 'rename'].includes(catalogForm.action)" label="目录标题"><el-input v-model="catalogForm.title" placeholder="输入目录标题" /></el-form-item>
            <el-form-item v-if="['create', 'move'].includes(catalogForm.action)" label="父目录 ID"><el-input-number v-model="catalogForm.parentId" :min="1" :controls="false" placeholder="留空为根级" clearable /></el-form-item>
            <el-form-item v-if="catalogForm.action === 'reorder'" label="目标顺序"><el-input-number v-model="catalogForm.order" :min="1" :controls="false" /></el-form-item>
            <el-form-item v-if="catalogForm.action === 'delete'" label="重挂目标 ID"><el-input-number v-model="catalogForm.reparentTo" :min="1" :controls="false" clearable /></el-form-item>
            <el-button type="primary" block @click="submitCatalog">执行目录操作</el-button>
          </el-form>
        </template>
        <template v-else-if="selectedMedia">
          <div class="action-card action-card--media">
            <div class="action-card-head"><div><span class="panel-kicker">MEDIA MAINTENANCE</span><h2>媒体操作</h2><p>只修改当前选中的媒体，不影响同章节其他文件。</p></div><span class="action-id">MEDIA · {{ selectedMedia.id }}</span></div>
            <div class="action-context"><span class="context-mark">{{ selectedMedia.mediaType === 'VIDEO' ? '▶' : '▧' }}</span><div><strong>{{ selectedMedia.fileName || '未命名媒体' }}</strong><small>第 {{ selectedMedia.pageNumber }} 项 · {{ selectedMedia.mediaType === 'VIDEO' ? '视频' : '图片' }}</small></div></div>
            <div class="media-detail-grid"><div><small>{{ selectedMedia.mediaType === 'VIDEO' ? '源文件' : 'HQ' }}</small><strong>{{ mediaHqLabel(selectedMedia.hqStatus, selectedMedia.hqUrl, selectedMedia.mediaType) }}</strong></div><div><small>LQ</small><strong>{{ selectedMedia.mediaType === 'VIDEO' ? '不适用' : mediaLqLabel(selectedMedia.lqStatus) }}</strong></div><div><small>文件大小</small><strong>{{ selectedMedia.fileSize ? formatSize(selectedMedia.fileSize) : '未统计' }}</strong></div><div><small>分辨率</small><strong>{{ mediaResolution(selectedMedia) }}</strong></div><div><small>时长</small><strong>{{ selectedMedia.mediaType === 'VIDEO' ? formatDuration(selectedMedia.duration) : '不适用' }}</strong></div><div><small>容器 / 编码</small><strong>{{ mediaCodec(selectedMedia) }}</strong></div><div><small>转码</small><strong>{{ selectedMedia.mediaType === 'VIDEO' ? transcodeLabel(selectedMedia.transcodeStatus) : '不适用' }}</strong></div></div>
            <div class="media-operation-note">HQ 删除和 LQ 生成属于章节级操作。当前媒体面板只执行针对这一份文件的操作。</div>
            <div class="media-action-buttons"><el-button v-if="selectedMedia.mediaType === 'VIDEO'" type="warning" plain block @click="transcodeSelectedMedia">转码此视频</el-button><el-button type="danger" plain block @click="trashSelectedMedia">回收此媒体</el-button></div>
          </div>
        </template>
        <template v-else-if="selectedRow?.kind === 'CHAPTER'">
          <div class="action-card action-card--chapter">
            <div class="action-card-head"><div><span class="panel-kicker">CHAPTER MAINTENANCE</span><h2>章节操作</h2><p>只修改当前选中的章节，不影响其他章节。</p></div><span class="action-id">CH · {{ selectedRow.id }}</span></div>
            <div class="action-context"><span class="context-mark">▱</span><div><strong>{{ selectedRow.title }}</strong><small>全书顺序 {{ selectedRow.order ?? '—' }}</small></div></div>
            <el-form label-position="top" class="action-form">
              <el-form-item label="选择操作"><el-select v-model="chapterForm.action"><el-option v-for="item in CHAPTER_ACTIONS" :key="item.value" :label="item.label" :value="item.value" /></el-select><small class="field-help">{{ chapterActionDescription(chapterForm.action) }}</small></el-form-item>
              <div class="form-grid" v-if="['create', 'rename'].includes(chapterForm.action)"><el-form-item label="章节标题"><el-input v-model="chapterForm.title" placeholder="输入章节标题" /></el-form-item><el-form-item label="原始章节编号"><el-input v-model="chapterForm.chapterNo" placeholder="如 01、番外" /></el-form-item></div>
              <el-form-item v-if="['create', 'move'].includes(chapterForm.action)" label="目标目录 ID"><el-input-number v-model="chapterForm.catalogId" :min="1" :controls="false" clearable placeholder="留空为根目录" /></el-form-item>
              <el-form-item v-if="chapterForm.action === 'reorder'" label="全书目标顺序"><el-input-number v-model="chapterForm.order" :min="1" :controls="false" /></el-form-item>
              <el-button class="action-submit" :type="chapterForm.action === 'trash' ? 'danger' : 'primary'" block @click="submitChapter">{{ chapterForm.action === 'trash' ? '回收当前章节' : '执行章节操作' }}</el-button>
            </el-form>
          </div>
          <div class="action-card media-action"><div class="action-card-head action-card-head--compact"><div><span class="panel-kicker">MEDIA ORDER</span><h3>媒体顺序</h3></div><span class="media-count">{{ mediaItems.length }} 项</span></div><p>按媒体 ID 逗号分隔，提交后会覆盖当前章节的完整顺序。</p><el-input v-model="mediaOrder" type="textarea" :rows="3" placeholder="例如 128905,128906,128907" /><div class="media-action-footer"><small>当前列表已加载 {{ mediaItems.length }} 个媒体</small><el-button @click="reorderMedia">保存媒体顺序</el-button></div></div>
          <div class="action-card storage-action"><div class="action-card-head action-card-head--compact"><div><span class="panel-kicker">STORAGE</span><h3>章节存储</h3></div><StorageStatusTag v-if="selectedStorageChapter" :status="selectedStorageChapter.hqStatus" type="hq" /></div><div class="storage-mini-grid"><div><small>HQ 占用</small><strong>{{ formatSize(selectedStorageChapter?.hqSize ?? 0) }}</strong></div><div><small>LQ 占用</small><strong>{{ formatSize(selectedStorageChapter?.lqSize ?? 0) }}</strong></div></div><div class="storage-action-buttons"><el-button type="danger" plain @click="deleteChapterHq">删除本章 HQ</el-button><el-button v-if="mediaLqApplicableCount > 0" type="primary" plain @click="generateChapterLq">生成本章 LQ</el-button><el-button v-if="mediaVideoCount > 0" type="warning" plain @click="transcodeChapter">转码本章视频</el-button></div></div>
        </template>
        <div v-else class="action-empty"><span class="empty-mark">＋</span><p>选择节点后显示可用操作。</p></div>
      </aside>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi, catalogApi, catalogManagementApi, chapterManagementApi, hqApi, mediaManagementApi, readerApi } from '@/services/api'
import { storageService } from '@/services/storage'
import StorageStatusTag from './storage/StorageStatusTag.vue'
import type { CatalogNode, ChapterStorageItem, MediaItemInfo } from '@/types'
import { StorageOperationType } from '@/types'

type CatalogAction = 'create' | 'rename' | 'move' | 'reorder' | 'delete'
type ChapterAction = 'create' | 'rename' | 'move' | 'reorder' | 'trash'
interface StructureRow {
  readonly key: string
  readonly kind: 'CATALOG' | 'CHAPTER'
  readonly id: number
  readonly title: string
  readonly chapterNo?: string
  readonly order: number | null
  readonly status: string | null
  readonly children?: readonly StructureRow[]
}
const CATALOG_ACTIONS = [{ value: 'create', label: '新建目录' }, { value: 'rename', label: '重命名目录' }, { value: 'move', label: '移动目录' }, { value: 'reorder', label: '目录重排' }, { value: 'delete', label: '删除目录' }] as const
const CHAPTER_ACTIONS = [{ value: 'create', label: '新建章节' }, { value: 'rename', label: '重命名章节' }, { value: 'move', label: '移动章节' }, { value: 'reorder', label: '章节重排' }, { value: 'trash', label: '回收章节' }] as const
const route = useRoute()
const comicId = ref(Number(route.params.id) || 1)
const tree = ref<readonly CatalogNode[]>([])
const mediaItems = ref<readonly MediaItemInfo[]>([])
const selectedMedia = ref<MediaItemInfo | null>(null)
const storageChapters = ref<readonly ChapterStorageItem[]>([])
const selectedStorageChapter = computed(() => selectedRow.value?.kind === 'CHAPTER' ? storageChapters.value.find((chapter) => chapter.chapterId === selectedRow.value?.id) ?? null : null)
const mediaChapterId = ref(1)
const mediaOrder = ref('')
const structureKeyword = ref('')
const selectedRow = ref<StructureRow | null>(null)
const loading = ref(false)
const error = ref('')
const catalogForm = reactive<{ action: CatalogAction; id?: number; title: string; parentId?: number; order?: number; reparentTo?: number }>({ action: 'create', title: '' })
const chapterForm = reactive<{ action: ChapterAction; id?: number; title: string; chapterNo: string; catalogId?: number; order?: number }>({ action: 'create', title: '', chapterNo: '' })
const structureRows = computed<readonly StructureRow[]>(() => tree.value.flatMap(toStructureRows))
const filteredStructureRows = computed<readonly StructureRow[]>(() => filterStructureRows(structureRows.value, structureKeyword.value.trim().toLowerCase()))
const catalogCount = computed(() => countRows(structureRows.value, 'CATALOG'))
const chapterCount = computed(() => countRows(structureRows.value, 'CHAPTER'))
const mediaHqReadyCount = computed(() => mediaItems.value.filter((item) => normalizedHqStatus(item) === 'READY').length)
const mediaLqApplicableCount = computed(() => mediaItems.value.filter((item) => item.mediaType !== 'VIDEO').length)
const mediaLqReadyCount = computed(() => mediaItems.value.filter((item) => item.mediaType !== 'VIDEO' && item.lqStatus === 'READY').length)
const mediaVideoCount = computed(() => mediaItems.value.filter((item) => item.mediaType === 'VIDEO').length)
const treeState = ref<'idle' | 'loading' | 'loaded' | 'empty' | 'error'>('idle')
const treeStateLabel = computed(() => ({ idle: '等待加载', loading: '加载中', loaded: '已加载', empty: '已加载，暂无结构', error: '加载失败' })[treeState.value])
const emptyStateText = computed(() => ({ idle: '请输入漫画 ID 后加载目录', loading: '正在加载目录…', loaded: '目录为空', empty: '该漫画暂无目录或章节', error: '目录加载失败，请重试' })[treeState.value])

function countRows(rows: readonly StructureRow[], kind: StructureRow['kind']): number {
  return rows.reduce((count, row) => count + (row.kind === kind ? 1 : 0) + (row.children ? countRows(row.children, kind) : 0), 0)
}

function rowClassName({ row }: { row: StructureRow }): string {
  return row.kind === 'CATALOG' ? 'structure-row--catalog' : 'structure-row--chapter'
}

function toStructureRows(node: CatalogNode): readonly StructureRow[] {
  const children = [
    ...node.chapters.map((chapter) => ({
      key: `chapter-${chapter.id}`,
      kind: 'CHAPTER' as const,
      id: chapter.id,
      title: chapter.title,
      chapterNo: chapter.chapterNo,
      order: chapter.globalOrder,
      status: chapter.status ?? null,
    })),
    ...node.children.flatMap(toStructureRows),
  ].sort((left, right) => (left.order ?? Number.MAX_SAFE_INTEGER) - (right.order ?? Number.MAX_SAFE_INTEGER))

  if (node.id === null) return children
  return [{
    key: `catalog-${node.id}`,
    kind: 'CATALOG',
    id: node.id,
    title: node.title ?? '未命名目录',
    order: node.globalOrder ?? null,
    status: null,
    children,
  }]
}
function filterStructureRows(rows: readonly StructureRow[], keyword: string): readonly StructureRow[] {
  if (!keyword) return rows
  return rows.flatMap((row) => {
    const children = row.children ? filterStructureRows(row.children, keyword) : []
    return row.title.toLowerCase().includes(keyword) || children.length > 0 ? [{ ...row, children }] : []
  })
}
function selectStructureRow(row: StructureRow): void {
  selectedMedia.value = null
  selectedRow.value = row
  if (row.kind === 'CATALOG') {
    catalogForm.id = row.id
    catalogForm.parentId = row.id
    return
  }
  chapterForm.id = row.id
  chapterForm.action = 'rename'
  chapterForm.title = row.title
  chapterForm.chapterNo = row.chapterNo ?? ''
  mediaChapterId.value = row.id
  void loadMedia()
}
function selectMediaRow(row: MediaItemInfo): void { selectedMedia.value = row }
function mediaRowClassName({ row }: { row: MediaItemInfo }): string { return selectedMedia.value?.id === row.id ? 'media-row--selected' : '' }
function mediaLqLabel(status: string): string {
  const labels: Readonly<Record<string, string>> = { READY: '已生成', QUEUED: '排队中', GENERATING: '生成中', MISSING: '文件缺失', FAILED: '生成失败', NOT_GENERATED: '未生成' }
  return labels[status] ?? (status || '未生成')
}
const mediaStorageColumnLabel = computed(() => {
  if (mediaItems.value.length > 0 && mediaItems.value.every((item) => item.mediaType === 'VIDEO')) return '源文件状态'
  if (mediaItems.value.some((item) => item.mediaType === 'VIDEO')) return 'HQ / 源文件'
  return 'HQ 状态'
})
function transcodeLabel(status?: string): string {
  const labels: Readonly<Record<string, string>> = { NOT_NEEDED: '无需转码', PENDING: '待处理', QUEUED: '排队中', TRANSCODING: '转码中', PROCESSING: '转码中', READY: '已完成', DONE: '已完成', FAILED: '失败' }
  return labels[status ?? ''] ?? (status || '待检查')
}
function mediaResolution(item: MediaItemInfo): string { return item.width && item.height ? `${item.width} × ${item.height}` : '未统计' }
function formatDuration(seconds?: number): string {
  if (!seconds || seconds < 0) return '未统计'
  const total = Math.round(seconds)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}
function mediaCodec(item: MediaItemInfo): string {
  if (item.mediaType !== 'VIDEO') return item.container || '图片'
  return [item.container, item.videoCodec, item.audioCodec].filter(Boolean).join(' / ') || '未统计'
}
function chapterActionDescription(action: ChapterAction): string {
  const descriptions: Readonly<Record<ChapterAction, string>> = {
    create: '在当前漫画中新建一个章节',
    rename: '修改章节标题或原始编号',
    move: '将章节移动到其他目录',
    reorder: '调整章节在全书中的阅读顺序',
    trash: '将当前章节移入回收站',
  }
  return descriptions[action]
}
function formatSize(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / 1024 ** index).toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}
function normalizedHqStatus(item: MediaItemInfo): string {
  return item.hqStatus || (item.hqUrl ? 'READY' : 'UNKNOWN')
}
function mediaHqLabel(status: string | undefined, hqUrl: string | undefined, mediaType?: string): string {
  const normalized = status || (hqUrl ? 'READY' : 'UNKNOWN')
  const labels: Readonly<Record<string, string>> = { READY: '已就绪', DELETE_QUEUED: '删除排队中', DELETING: '删除中', DELETED: '已删除', MISSING: '文件缺失', FAILED: '处理失败', PENDING: '待处理', UNKNOWN: '状态未同步' }
  const label = labels[normalized] ?? normalized
  return mediaType === 'VIDEO' ? `源文件${label}` : label
}
function mediaHqClass(item: MediaItemInfo): string {
  const status = normalizedHqStatus(item)
  if (status === 'READY') return 'is-ready'
  if (status === 'DELETED') return 'is-deleted'
  if (status === 'DELETE_QUEUED' || status === 'DELETING' || status === 'PENDING') return 'is-pending'
  if (status === 'UNKNOWN') return 'is-na'
  return 'is-missing'
}
function mediaActionHint(item: MediaItemInfo): string {
  const hqStatus = normalizedHqStatus(item)
  if (hqStatus === 'DELETED') return item.mediaType !== 'VIDEO' && item.lqStatus === 'READY' ? 'HQ 已删除，LQ 可继续使用' : 'HQ 已删除'
  if (hqStatus === 'DELETE_QUEUED' || hqStatus === 'DELETING') return 'HQ 删除处理中'
  if (hqStatus === 'FAILED') return '查看 HQ 处理任务'
  if (hqStatus === 'UNKNOWN') return '刷新阅读服务后重试'
  if (hqStatus !== 'READY') return '先检查 HQ 文件'
  if (item.mediaType === 'VIDEO') return '可检查转码状态'
  if (item.lqStatus === 'READY') return '无需处理'
  if (item.lqStatus === 'FAILED') return '重新生成 LQ'
  return '可生成 LQ'
}
function mediaHintClass(item: MediaItemInfo): string {
  const hqStatus = normalizedHqStatus(item)
  if (hqStatus === 'DELETED') return 'is-info'
  if (hqStatus !== 'READY' || item.lqStatus === 'FAILED') return 'is-alert'
  if (item.mediaType === 'VIDEO') return 'is-info'
  return item.lqStatus === 'READY' ? 'is-ok' : 'is-actionable'
}
function errorMessage(reason: unknown): string { if (axios.isAxiosError<{ message?: string }>(reason)) return reason.response?.data?.message ?? reason.message; return reason instanceof Error ? reason.message : '未知错误' }
function assertNever(value: never): never { throw new TypeError(`未知操作: ${String(value)}`) }
async function loadTree(): Promise<void> { loading.value = true; treeState.value = 'loading'; tree.value = []; selectedRow.value = null; selectedMedia.value = null; mediaItems.value = []; error.value = ''; try { tree.value = ((await catalogApi.tree(comicId.value)).data || []) as CatalogNode[]; treeState.value = tree.value.length > 0 ? 'loaded' : 'empty' } catch (reason: unknown) { treeState.value = 'error'; error.value = errorMessage(reason) } finally { loading.value = false } try { storageChapters.value = await storageService.fetchChapters(comicId.value) } catch { storageChapters.value = [] } }
async function submitCatalog(): Promise<void> { try { const id = catalogForm.id ?? 0; switch (catalogForm.action) { case 'create': await catalogManagementApi.create(comicId.value, { title: catalogForm.title.trim(), parentId: catalogForm.parentId ?? null }); break; case 'rename': await catalogManagementApi.rename(comicId.value, id, { title: catalogForm.title.trim() }); break; case 'move': await catalogManagementApi.move(comicId.value, id, { parentId: catalogForm.parentId ?? null }); break; case 'reorder': await catalogManagementApi.reorder(comicId.value, id, { sortOrder: catalogForm.order }); break; case 'delete': await ElMessageBox.confirm('删除目录前请确认重挂目标。', '确认删除', { type: 'warning' }); await catalogManagementApi.delete(comicId.value, id, catalogForm.reparentTo); break; default: assertNever(catalogForm.action) } ElMessage.success('目录操作完成'); await loadTree() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function submitChapter(): Promise<void> { try { const id = chapterForm.id ?? 0; switch (chapterForm.action) { case 'create': await chapterManagementApi.create(comicId.value, { title: chapterForm.title.trim(), chapterNo: chapterForm.chapterNo.trim(), catalogId: chapterForm.catalogId ?? null }); break; case 'rename': await chapterManagementApi.rename(comicId.value, id, { title: chapterForm.title.trim() || undefined, chapterNo: chapterForm.chapterNo.trim() || undefined }); break; case 'move': await chapterManagementApi.move(comicId.value, id, { catalogId: chapterForm.catalogId ?? null }); break; case 'reorder': await chapterManagementApi.reorder(comicId.value, id, { targetGlobalOrder: chapterForm.order }); break; case 'trash': await ElMessageBox.confirm('章节将进入回收站。', '确认回收', { type: 'warning' }); await chapterManagementApi.trash(comicId.value, id); break; default: assertNever(chapterForm.action) } ElMessage.success('章节操作完成'); await loadTree() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function loadMedia(): Promise<void> { try { mediaItems.value = (await readerApi.chapter(mediaChapterId.value)).data.pages; mediaOrder.value = mediaItems.value.map((item) => item.id).join(','); selectedMedia.value = null } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function reorderMedia(): Promise<void> { const mediaIds = mediaOrder.value.split(',').map((value) => Number(value.trim())).filter((id) => Number.isSafeInteger(id) && id > 0); try { await mediaManagementApi.reorder(mediaChapterId.value, { mediaIds }); ElMessage.success('媒体重排完成'); await loadMedia() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function deleteChapterHq(): Promise<void> { if (!selectedRow.value || selectedRow.value.kind !== 'CHAPTER') return; try { await ElMessageBox.confirm('确定删除当前章节的 HQ？LQ 文件会保留。', '删除章节 HQ', { type: 'warning' }); await storageService.executeOperation({ type: StorageOperationType.DeleteHQ, comicId: comicId.value, chapterId: selectedRow.value.id }); ElMessage.success('HQ 删除任务已提交'); await refreshStorage() } catch (reason: unknown) { if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorMessage(reason)) } }
async function generateChapterLq(): Promise<void> { if (!selectedRow.value || selectedRow.value.kind !== 'CHAPTER') return; try { await storageService.executeOperation({ type: StorageOperationType.GenerateLQ, comicId: comicId.value, chapterId: selectedRow.value.id }); ElMessage.success('LQ 生成任务已提交'); await refreshStorage() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function transcodeChapter(): Promise<void> { if (!selectedRow.value || selectedRow.value.kind !== 'CHAPTER') return; try { await ElMessageBox.confirm('确定对当前章节的视频发起转码？', '章节视频转码', { type: 'warning' }); await adminApi.transcodeChapter(selectedRow.value.id); ElMessage.success('章节视频转码任务已提交'); await loadMedia() } catch (reason: unknown) { if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorMessage(reason)) } }
async function transcodeSelectedMedia(): Promise<void> { if (!selectedMedia.value || selectedMedia.value.mediaType !== 'VIDEO') return; try { await ElMessageBox.confirm('确定对当前视频发起转码？', '视频转码', { type: 'warning' }); await hqApi.transcodeMedia(selectedMedia.value.id); ElMessage.success('视频转码任务已提交'); await loadMedia() } catch (reason: unknown) { if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorMessage(reason)) } }
async function trashSelectedMedia(): Promise<void> { if (!selectedMedia.value) return; try { await ElMessageBox.confirm(`确定将「${selectedMedia.value.fileName || '此媒体'}」移入回收站？`, '回收媒体', { type: 'warning' }); await mediaManagementApi.trash(selectedMedia.value.id); ElMessage.success('媒体回收任务已提交'); selectedMedia.value = null; await loadMedia() } catch (reason: unknown) { if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorMessage(reason)) } }
async function refreshStorage(): Promise<void> { try { storageChapters.value = await storageService.fetchChapters(comicId.value) } catch { storageChapters.value = [] } await loadMedia() }

onMounted(() => { void loadTree() })
</script>

<style scoped>
.structure-page { display: grid; gap: var(--space-5); }
.page-header, .form-panel { display: flex; align-items: center; justify-content: space-between; gap: var(--space-4); flex-wrap: wrap; }
.page-heading { display: grid; gap: var(--space-2); }
.eyebrow { color: var(--accent); font-size: var(--text-xs); font-weight: 700; letter-spacing: .12em; }
.page-header h1 { margin: 0; color: var(--text-primary); font-size: var(--text-page); letter-spacing: -.02em; }
.page-header p { margin: 0; color: var(--text-muted); font-size: var(--text-sm); }
.load-card { display: grid; gap: var(--space-2); min-width: 300px; padding: var(--space-4); border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg-surface); }
.load-card label { color: var(--text-muted); font-size: var(--text-xs); }
.load-controls { display: flex; gap: var(--space-2); }
.load-controls :deep(.el-input-number) { width: 150px; }
.structure-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--space-3); }
.structure-summary > div { display: grid; gap: var(--space-1); padding: var(--space-4); border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg-surface); }
.structure-summary span { color: var(--text-muted); font-size: var(--text-xs); }
.structure-summary strong { color: var(--text-primary); font-size: var(--text-lg); }
.summary-hint strong { color: var(--success); font-size: var(--text-sm); }
.structure-table { border: 1px solid var(--border); border-radius: var(--radius-md); overflow: hidden; }
.structure-table :deep(.el-table__header th) { background: var(--bg-elevated); color: var(--text-secondary); font-size: var(--text-xs); }
.structure-table :deep(.el-table__row--level-0) { background: color-mix(in srgb, var(--accent) 3%, var(--bg-surface)); }
.structure-table :deep(.structure-row--catalog .cell) { font-weight: 650; }
.title-cell { color: var(--text-primary); }
.maintenance-tabs { padding: var(--space-2) var(--space-4) var(--space-4); border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg-surface); }
.form-panel { justify-content: flex-start; padding: var(--space-4) 0 var(--space-2); }
.form-panel :deep(.el-input), .form-panel :deep(.el-select), .form-panel :deep(.el-input-number) { width: 220px; }
.structure-header { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--space-5); padding-bottom: var(--space-6); border-bottom: 1px solid var(--border); }
.structure-header h1 { margin: var(--space-2) 0; color: var(--text-primary); font-family: Georgia, 'Times New Roman', serif; font-size: clamp(2rem, 3vw, 2.7rem); letter-spacing: -.04em; }
.structure-header p { color: var(--text-muted); font-size: var(--text-sm); }
.header-tools { display: flex; align-items: center; gap: var(--space-3); }
.comic-ref { color: var(--accent); font: 700 12px var(--mono); }
.panel-kicker { color: var(--accent); font: 800 10px var(--mono); letter-spacing: .16em; }
.structure-browser { display: grid; grid-template-columns: minmax(250px, .78fr) minmax(360px, 1.55fr) minmax(240px, .75fr); gap: var(--space-3); min-height: 560px; }
.tree-panel, .detail-panel, .action-panel { min-width: 0; border: 1px solid var(--border); background: var(--bg-surface); }
.tree-panel, .action-panel { padding: var(--space-4); }
.detail-panel { padding: clamp(var(--space-5), 3vw, var(--space-8)); }
.panel-topline, .selected-header, .media-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-3); }
.panel-topline h2, .selected-header h2 { margin: var(--space-1) 0 0; color: var(--text-primary); font-size: var(--text-lg); }
.node-count, .selected-header p, .media-heading p { color: var(--text-muted); font-size: var(--text-xs); }
.tree-search { margin: var(--space-4) 0; }
.tree-panel .structure-table { height: 455px; }
.tree-panel .structure-table :deep(.el-table__header-wrapper) { display: none; }
.tree-panel .structure-table :deep(.el-table__body-wrapper) { overflow-y: auto; }
.tree-panel .structure-table :deep(.el-table__row) { cursor: pointer; }
.tree-panel .structure-table :deep(.el-table__cell) { padding: 11px 6px; }
.tree-title { display: flex; align-items: center; gap: 8px; min-width: 0; color: var(--text-primary); }
.tree-title > span:last-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-icon { flex: 0 0 auto; color: var(--accent); font-size: 12px; }
.tree-kind { color: var(--text-muted); font-size: 11px; }
.selected-header { padding-bottom: var(--space-5); border-bottom: 1px solid var(--border); }
.selected-header p { margin-top: var(--space-2); }
.child-summary { display: flex; align-items: baseline; gap: var(--space-2); margin: var(--space-8) 0 var(--space-4); }
.child-summary strong { color: var(--accent); font-size: 2.6rem; line-height: 1; }
.child-summary span { color: var(--text-muted); }
.child-list { display: grid; gap: var(--space-2); }
.child-list button { display: grid; grid-template-columns: 18px 1fr auto; align-items: center; gap: var(--space-2); width: 100%; padding: var(--space-3); border: 1px solid var(--border); background: var(--bg-secondary); color: var(--text-primary); text-align: left; cursor: pointer; transition: border-color var(--transition-fast), background-color var(--transition-fast); }
.child-list button:hover { border-color: var(--accent); background: var(--accent-bg); }
.child-list button > span { color: var(--accent); }
.child-list small { color: var(--text-muted); }
.empty-copy, .selection-empty, .action-empty { color: var(--text-muted); font-size: var(--text-sm); }
.selection-empty { display: grid; place-items: center; align-content: center; height: 100%; min-height: 360px; text-align: center; }
.selection-empty h2 { margin: var(--space-3) 0 var(--space-2); color: var(--text-primary); font-size: var(--text-lg); }
.selection-empty p { max-width: 280px; }
.empty-mark { color: var(--accent); font-size: 2rem; }
.media-heading { align-items: center; margin: var(--space-6) 0 var(--space-3); }
.media-heading h3 { margin: 0 0 4px; color: var(--text-primary); }
.media-table { width: 100%; }
.media-table :deep(.media-row--selected > td) { background: color-mix(in srgb, var(--accent) 12%, var(--bg-surface)); }
.media-table :deep(.el-table__row) { cursor: pointer; }
.media-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--space-2); margin: var(--space-4) 0; }
.media-summary > div { display: grid; gap: 3px; padding: var(--space-3); border: 1px solid var(--border); background: var(--bg-secondary); }
.media-summary span, .media-summary small { color: var(--text-muted); font-size: 11px; }
.media-summary strong { color: var(--text-primary); font-size: 15px; }
.media-status { display: inline-flex; align-items: center; min-height: 22px; padding: 0 7px; border: 1px solid transparent; font-size: 11px; white-space: nowrap; }
.media-status.is-ready { border-color: rgb(102 197 139 / 34%); background: rgb(102 197 139 / 10%); color: var(--success); }
.media-status.is-pending { border-color: rgb(216 165 79 / 34%); background: rgb(216 165 79 / 10%); color: var(--warning); }
.media-status.is-deleted { border-color: rgb(142 142 147 / 34%); background: rgb(142 142 147 / 10%); color: var(--text-muted); }
.media-status.is-missing { border-color: var(--accent-border); background: var(--accent-bg); color: var(--accent); }
.media-status.is-na { border-color: var(--border); color: var(--text-muted); }
.media-hint { color: var(--text-muted); font-size: 12px; }
.media-hint.is-ok { color: var(--success); }
.media-hint.is-actionable { color: var(--warning); }
.media-hint.is-alert { color: var(--accent); }
.media-hint.is-info { color: var(--info); }
.action-panel { align-self: start; }
.action-card { padding: var(--space-4); border: 1px solid var(--border); background: var(--bg-secondary); }
.action-card + .action-card { margin-top: var(--space-3); }
.action-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-3); padding-bottom: var(--space-4); border-bottom: 1px solid var(--border); }
.action-card-head h2 { margin: var(--space-1) 0 var(--space-2); color: var(--text-primary); font-size: var(--text-lg); }
.action-card-head h3 { margin: var(--space-1) 0 0; color: var(--text-primary); font-size: var(--text-md); }
.action-card-head p { margin: 0; color: var(--text-muted); font-size: 11px; line-height: 1.5; }
.action-card-head--compact { align-items: center; padding-bottom: var(--space-3); }
.action-id, .media-count { color: var(--accent); font: 700 10px var(--mono); letter-spacing: .08em; white-space: nowrap; }
.media-count { color: var(--text-muted); }
.action-context { display: flex; align-items: center; gap: var(--space-3); margin: var(--space-4) 0 var(--space-5); padding: var(--space-3); border-left: 2px solid var(--accent); background: var(--bg-surface); }
.context-mark { color: var(--accent); font-size: 18px; }
.action-context div { display: grid; gap: 3px; min-width: 0; }
.action-context strong { overflow: hidden; color: var(--text-primary); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.action-context small, .field-help, .media-action-footer small { color: var(--text-muted); font-size: 11px; }
.action-form { margin-top: 0; }
.action-form :deep(.el-form-item) { margin-bottom: var(--space-4); }
.action-form :deep(.el-select), .action-form :deep(.el-input-number) { width: 100%; }
.action-form :deep(.el-input-number .el-input__wrapper) { width: 100%; }
.action-form :deep(.el-button--block) { width: 100%; }
.action-form :deep(.el-form-item__content) { display: grid; gap: 5px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
.action-submit { margin-top: var(--space-2); }
.media-action { display: grid; gap: var(--space-3); }
.media-action p { margin: 0; color: var(--text-muted); font-size: var(--text-xs); line-height: 1.5; }
.media-action-footer { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
.media-action-footer .el-button { flex: 0 0 auto; }
.storage-action { display: grid; gap: var(--space-3); }
.storage-mini-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-2); }
.storage-mini-grid > div { display: grid; gap: 3px; padding: var(--space-3); border: 1px solid var(--border); background: var(--bg-surface); }
.storage-mini-grid small { color: var(--text-muted); font-size: 10px; }
.storage-mini-grid strong { color: var(--text-primary); font-size: 14px; }
.storage-action-buttons { display: grid; gap: var(--space-2); }
.storage-action-buttons .el-button { width: 100%; margin: 0; }
.media-detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-2); margin-bottom: var(--space-3); }
.media-detail-grid > div { display: grid; gap: 4px; padding: var(--space-3); border: 1px solid var(--border); background: var(--bg-surface); }
.media-detail-grid small { color: var(--text-muted); font-size: 10px; }
.media-detail-grid strong { color: var(--text-primary); font-size: 12px; }
.media-operation-note { margin-bottom: var(--space-4); padding: var(--space-3); border: 1px dashed var(--border); color: var(--text-muted); font-size: 11px; line-height: 1.55; }
.media-action-buttons { display: grid; gap: var(--space-2); }
.media-action-buttons .el-button { width: 100%; margin: 0; }
.action-empty { display: grid; place-items: center; min-height: 220px; gap: var(--space-2); text-align: center; }
@media (max-width: 1100px) {
  .structure-browser { grid-template-columns: minmax(220px, .8fr) minmax(320px, 1.4fr); }
  .action-panel { grid-column: 1 / -1; }
  .action-panel .action-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-3); align-items: end; }
  .action-panel .action-form .el-form-item { margin-bottom: 0; }
  .action-panel .form-grid { grid-column: 1 / -1; }
  .media-action { grid-column: 1 / -1; }
}
@media (max-width: 760px) {
  .structure-header { align-items: flex-start; flex-direction: column; }
  .structure-browser { grid-template-columns: 1fr; }
  .detail-panel { min-height: 420px; }
  .action-panel { grid-column: auto; }
  .action-panel .action-form { display: grid; grid-template-columns: 1fr; }
  .load-card { width: 100%; min-width: 0; }
  .structure-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
