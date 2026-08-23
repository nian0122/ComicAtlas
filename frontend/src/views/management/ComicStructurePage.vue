<template>
  <div class="structure-page">
    <header class="structure-header">
      <div class="page-heading"><span class="eyebrow">COMIC / STRUCTURE</span><h1>目录与媒体</h1><p>从目录树定位章节，再在右侧完成维护。</p></div>
      <div class="header-tools"><span class="comic-ref">漫画 #{{ comicId }}</span><el-button :loading="loading" @click="loadTree">刷新结构</el-button></div>
    </header>
    <section class="structure-summary" aria-label="结构概览">
      <div><span>目录节点</span><strong>{{ catalogCount }}</strong><small>{{ rootChapterCount ? `${rootChapterCount} 个根章节` : '暂无根章节' }}</small></div>
      <div><span>章节总数</span><strong>{{ chapterCount }}</strong><small>包含目录下的全部章节</small></div>
      <div><span>当前章节媒体</span><strong>{{ mediaItems.length || '—' }}</strong><small>{{ selectedRow?.kind === 'CHAPTER' ? selectedRow.title : '选择章节后统计' }}</small></div>
      <div class="summary-hint"><span>目录结构状态</span><strong>{{ treeStateLabel }}</strong><small>{{ structureRows.length }} 个根节点</small></div>
    </section>
    <el-alert v-if="error" :title="error" type="error" show-icon />
    <section v-if="lqIssueChapters.length" class="issue-strip" aria-label="LQ 异常章节">
      <div class="issue-strip-heading"><div><span class="panel-kicker">LQ / ATTENTION</span><strong>{{ lqIssueChapters.length }} 个章节需要检查</strong></div><small>点击章节可直接定位到媒体明细</small></div>
      <div class="issue-chapter-list"><button v-for="chapter in lqIssueChapters" :key="chapter.chapterId" type="button" class="issue-chapter" @click="locateChapter(chapter.chapterId)"><span>{{ chapter.title || `章节 ${chapter.chapterNo}` }}</span><small>{{ mediaLqLabel(chapter.lqStatus) }} · {{ chapter.pageCount }} 个媒体</small></button></div>
    </section>

    <section class="structure-browser">
      <aside class="tree-panel">
        <div class="panel-topline"><div><span class="panel-kicker">NAVIGATOR</span><h2>目录树</h2></div><span class="node-count">{{ structureRows.length }} 个根节点</span></div>
        <el-input v-model="structureKeyword" clearable placeholder="搜索目录或章节" class="tree-search" />
        <el-table v-loading="loading" class="structure-table" :data="filteredStructureRows" row-key="key" :tree-props="{ children: 'children' }" :row-class-name="rowClassName" :empty-text="emptyStateText" highlight-current-row @row-click="selectStructureRow">
          <el-table-column prop="title" min-width="0"><template #default="{ row }"><div class="tree-title"><span class="tree-icon">{{ row.kind === 'CATALOG' ? '▰' : '▱' }}</span><span>{{ row.title }}</span></div></template></el-table-column>
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
            <div class="media-table-scroll">
            <el-table class="media-table" :data="mediaItems" row-key="id" empty-text="该章节暂无媒体" highlight-current-row :row-class-name="mediaRowClassName" @row-click="selectMediaRow">
              <el-table-column prop="pageNumber" label="顺序" width="76" />
              <el-table-column prop="fileName" label="文件名" min-width="190" show-overflow-tooltip />
              <el-table-column label="类型" width="90"><template #default="{ row }">{{ row.mediaType === 'VIDEO' ? 'VIDEO' : 'IMAGE' }}</template></el-table-column>
              <el-table-column :label="mediaStorageColumnLabel" width="120"><template #default="{ row }"><span class="media-status" :class="mediaHqClass(row)">{{ mediaHqLabel(row.hqStatus, row.hqUrl, row.mediaType) }}</span></template></el-table-column>
              <el-table-column label="LQ 状态" width="110"><template #default="{ row }"><span v-if="row.mediaType === 'VIDEO'" class="media-status is-na">不适用</span><span v-else class="media-status" :class="row.lqStatus === 'READY' ? 'is-ready' : 'is-pending'">{{ mediaLqLabel(row.lqStatus) }}</span></template></el-table-column>
              <el-table-column label="处理建议" min-width="150"><template #default="{ row }"><span class="media-hint" :class="mediaHintClass(row)">{{ mediaActionHint(row) }}</span></template></el-table-column>
            </el-table>
            </div>
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
            <div class="media-detail-grid"><div><small>{{ selectedMedia.mediaType === 'VIDEO' ? '源文件' : 'HQ' }}</small><strong>{{ mediaHqLabel(selectedMedia.hqStatus, selectedMedia.hqUrl, selectedMedia.mediaType) }}</strong></div><div><small>LQ</small><strong>{{ selectedMedia.mediaType === 'VIDEO' ? '不适用' : mediaLqLabel(selectedMedia.lqStatus) }}</strong></div><div><small>HQ 大小</small><strong>{{ mediaSizeLabel(selectedMedia) }}</strong></div><div><small>LQ 大小</small><strong>{{ mediaLqSizeLabel(selectedMedia) }}</strong></div><div><small>分辨率</small><strong>{{ mediaResolution(selectedMedia) }}</strong></div><div><small>时长</small><strong>{{ selectedMedia.mediaType === 'VIDEO' ? formatDuration(selectedMedia.duration) : '不适用' }}</strong></div><div><small>容器 / 编码</small><strong>{{ mediaCodec(selectedMedia) }}</strong></div><div><small>转码</small><strong>{{ selectedMedia.mediaType === 'VIDEO' ? transcodeLabel(selectedMedia.transcodeStatus) : '不适用' }}</strong></div></div>
            <div class="media-operation-note">HQ 删除和 LQ 生成属于章节级操作。当前媒体面板只执行针对这一份文件的操作。</div>
            <div class="media-action-buttons"><el-button v-if="selectedMedia.mediaType === 'VIDEO'" type="warning" plain block @click="transcodeSelectedMedia">转码此视频</el-button><el-button type="danger" plain block @click="trashSelectedMedia">回收此媒体</el-button></div>
          </div>
        </template>
        <template v-else-if="selectedRow?.kind === 'CHAPTER'">
          <div class="action-card action-card--chapter">
            <div class="action-card-head"><div><span class="panel-kicker">CHAPTER MAINTENANCE</span><h2>章节操作</h2><p>只修改当前选中的章节，不影响其他章节。</p></div><span class="action-id">CH · {{ selectedRow.id }}</span></div>
            <div class="action-context"><span class="context-mark">▱</span><div><strong>{{ selectedRow.title }}</strong><small>全书顺序 {{ selectedRow.order ?? '—' }}</small></div></div>
            <el-form label-position="top" class="action-form">
              <div class="chapter-action-toolbar"><span>当前章节操作</span><el-button text type="primary" @click="toggleCreateChapter">{{ chapterForm.action === 'create' ? '返回当前章节' : '新建章节' }}</el-button></div>
              <div v-if="chapterForm.action !== 'create'" class="chapter-choice"><label>选择操作</label><div class="chapter-choice-grid"><button v-for="item in CHAPTER_ACTIONS" :key="item.value" type="button" :class="{ 'is-active': chapterForm.action === item.value, 'is-danger': item.value === 'trash' }" @click="selectChapterAction(item.value)">{{ item.label }}</button></div><small class="field-help">{{ chapterActionDescription(chapterForm.action) }}</small></div>
              <div v-else class="create-context"><span class="context-mark">＋</span><div><strong>新建章节</strong><small>将在当前漫画中创建一个新章节</small></div></div>
              <div class="form-grid" v-if="['create', 'rename'].includes(chapterForm.action)"><el-form-item label="章节标题"><el-input v-model="chapterForm.title" placeholder="输入章节标题" /></el-form-item><el-form-item label="原始章节编号"><el-input v-model="chapterForm.chapterNo" placeholder="如 01、番外" /></el-form-item></div>
              <el-form-item v-if="chapterForm.action === 'create'" label="目标目录 ID"><el-input-number v-model="chapterForm.catalogId" :min="1" :controls="false" clearable placeholder="留空为根目录" /></el-form-item>
              <el-form-item v-if="chapterForm.action === 'move'" label="移动到目录"><el-select v-model="chapterForm.catalogId" clearable placeholder="选择目标目录，留空为根目录"><el-option label="根目录" :value="null" /><el-option v-for="catalog in catalogOptions" :key="catalog.id" :label="catalog.title" :value="catalog.id" /></el-select></el-form-item>
              <div v-if="chapterForm.action === 'reorder'" class="chapter-reorder-box"><div class="chapter-position"><span>当前位置</span><strong>{{ selectedRow?.order ?? '—' }}</strong></div><span class="position-arrow">→</span><el-form-item label="移动到第几位"><el-input-number v-model="chapterForm.order" :min="1" :controls="true" placeholder="输入新位置" /></el-form-item><small>按全书阅读顺序调整，目标位置不能与当前位置相同。</small></div>
              <el-button class="action-submit" :type="chapterForm.action === 'trash' ? 'danger' : 'primary'" block @click="submitChapter">{{ chapterForm.action === 'trash' ? '回收当前章节' : '执行章节操作' }}</el-button>
            </el-form>
          </div>
          <div class="chapter-feature-grid">
            <section class="chapter-feature-card feature-order">
              <div class="feature-card-top"><span class="panel-kicker">MEDIA ORDER</span><span class="feature-count">{{ mediaOrderItems.length }} 项</span></div>
              <strong>媒体顺序</strong><p>调整本章阅读顺序</p><el-button plain block @click="mediaOrderDialogVisible = true">打开排序面板</el-button>
            </section>
            <section class="chapter-feature-card feature-intake">
              <div class="feature-card-top"><span class="panel-kicker">MEDIA INTAKE</span><span class="feature-mark">＋</span></div>
              <strong>补充媒体</strong><p>追加图片或替换当前媒体</p><div class="feature-button-row"><el-button type="primary" block @click="openUploadDialog()">上传媒体</el-button><el-button v-if="selectedMedia" plain block @click="openReplaceSelectedMedia">替换</el-button></div>
            </section>
            <section class="chapter-feature-card feature-storage">
              <div class="feature-card-top"><span class="panel-kicker">STORAGE</span><StorageStatusTag v-if="selectedStorageChapter" :status="selectedStorageChapter.hqStatus" type="hq" /></div>
              <strong>章节存储</strong><p>HQ {{ formatSize(selectedStorageChapter?.hqSize ?? 0) }} · LQ {{ formatSize(selectedStorageChapter?.lqSize ?? 0) }}</p><div class="feature-button-row"><el-button type="primary" plain :disabled="mediaLqApplicableCount === 0" block @click="generateChapterLq">{{ chapterLqActionLabel }}</el-button><el-button type="danger" plain block @click="deleteChapterHq">删除 HQ</el-button><el-button v-if="mediaVideoCount > 0" type="warning" plain block @click="transcodeChapter">转码视频</el-button></div>
            </section>
          </div>
        </template>
        <div v-else class="action-empty"><span class="empty-mark">＋</span><p>选择节点后显示可用操作。</p></div>
      </aside>
    </section>
  </div>

  <el-dialog v-model="uploadDialogVisible" width="min(720px, calc(100vw - 32px))" class="media-upload-dialog" destroy-on-close>
    <template #header>
      <div class="upload-dialog-heading"><span class="panel-kicker">MEDIA INTAKE</span><h2>{{ uploadReplaceMediaId ? '替换章节媒体' : '上传章节媒体' }}</h2><p>{{ selectedRow?.title }} · 章节 ID {{ selectedRow?.id }}</p></div>
    </template>
    <div class="upload-dialog-body">
      <div class="upload-mode"><span class="mode-mark">{{ uploadReplaceMediaId ? '↻' : '+' }}</span><div><strong>{{ uploadReplaceMediaId ? '替换当前选中的媒体' : '追加到当前章节' }}</strong><small>{{ uploadReplaceMediaId ? '只能选择一个图片或视频文件。' : '可一次选择多个图片或视频文件。' }}</small></div></div>
      <label class="upload-dropzone" :class="{ 'is-ready': uploadRows.length > 0 }"><input type="file" multiple :accept="uploadReplaceMediaId ? 'image/*,video/*' : 'image/*,video/*'" @change="onUploadFilesSelected"><span class="dropzone-icon">↑</span><strong>{{ uploadRows.length ? `已选择 ${uploadRows.length} 个文件` : '选择文件或拖入此处' }}</strong><small>支持图片与视频 · 上传前会校验 SHA-256</small></label>
      <div v-if="uploadRows.length" class="upload-file-list"><div v-for="row in uploadRows" :key="row.id" class="upload-file-row"><div><strong>{{ row.file.name }}</strong><small>{{ formatSize(row.file.size) }} · {{ row.status }}</small></div><el-progress :percentage="row.progress" :show-text="false" /></div></div>
      <div v-if="uploadSessionId" class="upload-session-note">会话 {{ uploadSessionId }} · {{ uploadStatus }}<span v-if="uploadTaskId"> · 任务 #{{ uploadTaskId }}</span></div>
    </div>
    <template #footer><el-button @click="uploadDialogVisible = false">关闭</el-button><el-button v-if="uploadSessionId && uploadRunning" type="danger" plain @click="cancelUpload">取消上传</el-button><el-button type="primary" :loading="uploadRunning" :disabled="!uploadRows.length || uploadRunning" @click="startUpload">开始上传</el-button></template>
  </el-dialog>
  <el-dialog v-model="mediaOrderDialogVisible" width="min(760px, calc(100vw - 32px))" class="media-order-dialog" destroy-on-close>
    <template #header><div class="upload-dialog-heading"><span class="panel-kicker">MEDIA ORDER</span><h2>媒体顺序</h2><p>{{ selectedRow?.title }} · {{ mediaOrderItems.length }} 个媒体</p></div></template>
    <div class="media-order-dialog-body"><div class="order-toolbar"><button type="button" @click="sortMediaByName">按文件名排序</button><button type="button" @click="resetMediaOrder">恢复当前顺序</button><span class="order-dialog-hint">拖动卡片调整阅读顺序</span></div><div class="media-order-list media-order-list--dialog" :class="{ 'is-dirty': mediaOrderDirty }"><div v-for="(item, index) in mediaOrderItems" :key="item.id" class="media-order-item" draggable="true" @dragstart="startMediaDrag(index)" @dragover.prevent @drop="dropMedia(index)"><span class="drag-handle" aria-hidden="true">⠿</span><span class="order-number">{{ String(index + 1).padStart(2, '0') }}</span><span class="order-type">{{ item.mediaType === 'VIDEO' ? 'VID' : 'IMG' }}</span><span class="order-file" :title="item.fileName || `媒体 ${item.id}`">{{ item.fileName || `媒体 ${item.id}` }}</span><span class="order-id">#{{ item.id }}</span></div><div v-if="!mediaOrderItems.length" class="order-empty">当前章节暂无可排序媒体</div></div><details class="advanced-order"><summary>高级编辑：按 ID 输入顺序</summary><p>适合批量处理。ID 必须完整且不重复，提交前会覆盖上方拖拽顺序。</p><el-input v-model="mediaOrder" type="textarea" :rows="3" placeholder="例如 128905,128906,128907" /><el-button text @click="applyAdvancedMediaOrder">应用到列表</el-button></details></div>
    <template #footer><span class="order-dialog-status">{{ mediaOrderDirty ? `已调整 ${mediaOrderChangeCount} 项` : '顺序未修改' }}</span><el-button @click="mediaOrderDialogVisible = false">关闭</el-button><el-button type="primary" :disabled="!mediaOrderDirty" @click="saveMediaOrderAndClose">保存媒体顺序</el-button></template>
  </el-dialog>
  <el-dialog v-model="storageDialogVisible" width="min(520px, calc(100vw - 32px))" class="storage-dialog" destroy-on-close>
    <template #header><div class="upload-dialog-heading"><span class="panel-kicker">STORAGE</span><h2>章节存储</h2><p>{{ selectedRow?.title }} · 查看占用并执行存储操作</p></div></template>
    <div class="storage-dialog-body"><div class="storage-mini-grid"><div><small>HQ 占用</small><strong>{{ formatSize(selectedStorageChapter?.hqSize ?? 0) }}</strong></div><div><small>LQ 占用</small><strong>{{ formatSize(selectedStorageChapter?.lqSize ?? 0) }}</strong></div></div><div class="storage-dialog-status"><span>HQ 状态</span><StorageStatusTag v-if="selectedStorageChapter" :status="selectedStorageChapter.hqStatus" type="hq" /><span>LQ 状态</span><StorageStatusTag v-if="selectedStorageChapter" :status="selectedStorageChapter.lqStatus" type="lq" /></div><div class="storage-action-buttons"><el-button type="danger" plain @click="deleteChapterHq">删除本章 HQ</el-button><el-button v-if="mediaLqApplicableCount > 0" type="primary" plain @click="generateChapterLq">{{ chapterLqActionLabel }}</el-button><el-button v-if="mediaVideoCount > 0" type="warning" plain @click="transcodeChapter">转码本章视频</el-button></div></div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi, managementCatalogApi, catalogManagementApi, chapterManagementApi, hqApi, mediaManagementApi, managementReaderApi } from '@/services/api'
import { trackedUploadApi } from '@/services/management-capabilities'
import { storageService } from '@/services/storage'
import StorageStatusTag from './storage/StorageStatusTag.vue'
import type { CatalogNode, ChapterStorageItem, CreateUploadSessionRequest, MediaItemInfo, UploadFileManifest } from '@/types'
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
const CHAPTER_ACTIONS = [{ value: 'rename', label: '重命名章节' }, { value: 'move', label: '移动章节' }, { value: 'reorder', label: '章节重排' }, { value: 'trash', label: '回收章节' }] as const
const route = useRoute()
const comicId = ref(Number(route.params.id) || 1)
const tree = ref<readonly CatalogNode[]>([])
const mediaItems = ref<readonly MediaItemInfo[]>([])
const mediaOrderItems = ref<MediaItemInfo[]>([])
const selectedMedia = ref<MediaItemInfo | null>(null)
const storageChapters = ref<readonly ChapterStorageItem[]>([])
const selectedStorageChapter = computed(() => selectedRow.value?.kind === 'CHAPTER' ? storageChapters.value.find((chapter) => chapter.chapterId === selectedRow.value?.id) ?? null : null)
const mediaChapterId = ref(1)
const mediaOrder = ref('')
const draggingMediaIndex = ref<number | null>(null)
const structureKeyword = ref('')
const selectedRow = ref<StructureRow | null>(null)
const loading = ref(false)
const error = ref('')
type UploadRow = { readonly id: string; readonly file: File; status: string; progress: number; sha256: string }
const uploadDialogVisible = ref(false)
const mediaOrderDialogVisible = ref(false)
const storageDialogVisible = ref(false)
const uploadReplaceMediaId = ref<number | null>(null)
const uploadRows = ref<UploadRow[]>([])
const uploadSessionId = ref('')
const uploadStatus = ref('尚未创建')
const uploadTaskId = ref<number | null>(null)
const uploadRunning = ref(false)
let uploadAbortController: AbortController | undefined
const catalogForm = reactive<{ action: CatalogAction; id?: number; title: string; parentId?: number; order?: number; reparentTo?: number }>({ action: 'create', title: '' })
const chapterForm = reactive<{ action: ChapterAction; id?: number; title: string; chapterNo: string; catalogId?: number; order?: number }>({ action: 'create', title: '', chapterNo: '' })
const structureRows = computed<readonly StructureRow[]>(() => tree.value.flatMap(toStructureRows))
const filteredStructureRows = computed<readonly StructureRow[]>(() => filterStructureRows(structureRows.value, structureKeyword.value.trim().toLowerCase()))
const catalogOptions = computed(() => flattenCatalogOptions(structureRows.value))
const catalogCount = computed(() => countRows(structureRows.value, 'CATALOG'))
const chapterCount = computed(() => countRows(structureRows.value, 'CHAPTER'))
const rootChapterCount = computed(() => structureRows.value.filter((row) => row.kind === 'CHAPTER').length)
const mediaHqReadyCount = computed(() => mediaItems.value.filter((item) => normalizedHqStatus(item) === 'READY').length)
const mediaLqApplicableCount = computed(() => mediaItems.value.filter((item) => item.mediaType !== 'VIDEO').length)
const mediaLqReadyCount = computed(() => mediaItems.value.filter((item) => item.mediaType !== 'VIDEO' && item.lqStatus === 'READY').length)
const lqIssueChapters = computed(() => storageChapters.value.filter((chapter) => ['MIXED', 'NOT_GENERATED', 'FAILED', 'MISSING', 'QUEUED', 'GENERATING'].includes(chapter.lqStatus)))
const chapterLqActionLabel = computed(() => selectedStorageChapter.value?.lqStatus === 'READY' ? '重新生成本章 LQ' : '生成本章 LQ')
const mediaVideoCount = computed(() => mediaItems.value.filter((item) => item.mediaType === 'VIDEO').length)
const mediaOrderDirty = computed(() => mediaOrderItems.value.some((item, index) => item.id !== mediaItems.value[index]?.id))
const mediaOrderChangeCount = computed(() => mediaOrderItems.value.reduce((count, item, index) => count + (item.id !== mediaItems.value[index]?.id ? 1 : 0), 0))
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
function flattenCatalogOptions(rows: readonly StructureRow[]): readonly { id: number; title: string }[] {
  return rows.flatMap((row) => row.kind === 'CATALOG' ? [{ id: row.id, title: row.title }, ...flattenCatalogOptions(row.children ?? [])] : [])
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
  chapterForm.order = undefined
  mediaChapterId.value = row.id
  void loadMedia()
}
function findStructureRow(rows: readonly StructureRow[], chapterId: number): StructureRow | null {
  for (const row of rows) {
    if (row.kind === 'CHAPTER' && row.id === chapterId) return row
    const match = row.children ? findStructureRow(row.children, chapterId) : null
    if (match) return match
  }
  return null
}
function locateChapter(chapterId: number): void {
  const row = findStructureRow(structureRows.value, chapterId)
  if (row) selectStructureRow(row)
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
function mediaSizeLabel(item: MediaItemInfo): string {
  if (normalizedHqStatus(item) === 'DELETED') return '已删除'
  if (item.hqSize) return formatSize(item.hqSize)
  return '未统计'
}
function mediaLqSizeLabel(item: MediaItemInfo): string {
  if (item.mediaType === 'VIDEO') return '不适用'
  if (item.lqSize) return formatSize(item.lqSize)
  return item.lqStatus === 'READY' ? '已生成（未统计）' : '未生成'
}
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
function selectChapterAction(action: ChapterAction): void {
  chapterForm.action = action
  if (action === 'reorder') chapterForm.order = undefined
}
function toggleCreateChapter(): void {
  if (chapterForm.action === 'create') {
    chapterForm.action = 'rename'
    chapterForm.id = selectedRow.value?.kind === 'CHAPTER' ? selectedRow.value.id : undefined
    chapterForm.title = selectedRow.value?.kind === 'CHAPTER' ? selectedRow.value.title : ''
    chapterForm.chapterNo = selectedRow.value?.kind === 'CHAPTER' ? selectedRow.value.chapterNo ?? '' : ''
    return
  }
  chapterForm.action = 'create'
  chapterForm.id = undefined
  chapterForm.title = ''
  chapterForm.chapterNo = ''
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
function openUploadDialog(replaceMediaId?: number): void { if (!selectedRow.value || selectedRow.value.kind !== 'CHAPTER') return; uploadReplaceMediaId.value = replaceMediaId ?? null; uploadRows.value = []; uploadSessionId.value = ''; uploadTaskId.value = null; uploadStatus.value = '尚未创建'; uploadDialogVisible.value = true }
function openReplaceSelectedMedia(): void { if (selectedMedia.value) openUploadDialog(selectedMedia.value.id) }
function onUploadFilesSelected(event: Event): void { const input = event.currentTarget; if (!(input instanceof HTMLInputElement)) return; const files = Array.from(input.files ?? []); uploadRows.value = (uploadReplaceMediaId.value ? files.slice(0, 1) : files).map((file) => ({ id: crypto.randomUUID(), file, status: '等待', progress: 0, sha256: '' })); uploadTaskId.value = null; uploadSessionId.value = ''; uploadStatus.value = '尚未创建' }
function toHex(buffer: ArrayBuffer): string { return Array.from(new Uint8Array(buffer), (byte) => byte.toString(16).padStart(2, '0')).join('') }
async function hashUploadFiles(): Promise<readonly UploadFileManifest[]> { const manifests: UploadFileManifest[] = []; for (const row of uploadRows.value) { row.status = '计算校验值'; row.sha256 = toHex(await crypto.subtle.digest('SHA-256', await row.file.arrayBuffer())); manifests.push({ fileId: row.id, name: row.file.name, contentType: row.file.type || 'application/octet-stream', size: row.file.size, sha256: row.sha256 }) } return manifests }
async function uploadFile(row: UploadRow, chunkSize: number): Promise<void> { let offset = 0; row.status = '上传中'; while (offset < row.file.size) { const endExclusive = Math.min(offset + chunkSize, row.file.size); await trackedUploadApi.uploadChunk({ sessionId: uploadSessionId.value, fileId: row.id, chunk: row.file.slice(offset, endExclusive), contentRange: `bytes=${offset}-${endExclusive - 1}/${row.file.size}`, signal: uploadAbortController?.signal }); offset = endExclusive; row.progress = Math.round((offset / row.file.size) * 100) } row.status = '已上传' }
async function startUpload(): Promise<void> { if (!selectedRow.value || selectedRow.value.kind !== 'CHAPTER') return; uploadRunning.value = true; uploadAbortController = new AbortController(); try { const files = await hashUploadFiles(); const request: CreateUploadSessionRequest = { comicId: comicId.value, chapterId: selectedRow.value.id, ...(uploadReplaceMediaId.value ? { replaceMediaId: uploadReplaceMediaId.value } : {}), files }; const created = (await trackedUploadApi.createSession(request)).data; uploadSessionId.value = created.sessionId; uploadStatus.value = '上传中'; for (const row of uploadRows.value) await uploadFile(row, created.chunkSize); uploadStatus.value = '提交任务'; const completed = (await trackedUploadApi.completeSession(created.sessionId)).data; uploadTaskId.value = completed.taskId; uploadStatus.value = '已提交'; ElMessage.success('媒体上传任务已提交'); await loadMedia(); await refreshStorage() } catch (reason: unknown) { if (!axios.isCancel(reason)) { uploadStatus.value = '失败'; ElMessage.error(errorMessage(reason)) } } finally { uploadRunning.value = false; uploadAbortController = undefined } }
async function cancelUpload(): Promise<void> { uploadAbortController?.abort(); if (uploadSessionId.value) await trackedUploadApi.cancelSession(uploadSessionId.value); uploadStatus.value = '已取消'; uploadRunning.value = false }
function assertNever(value: never): never { throw new TypeError(`未知操作: ${String(value)}`) }
async function loadTree(): Promise<void> { loading.value = true; treeState.value = 'loading'; tree.value = []; selectedRow.value = null; selectedMedia.value = null; mediaItems.value = []; error.value = ''; try { tree.value = ((await managementCatalogApi.tree(comicId.value)).data || []) as CatalogNode[]; treeState.value = tree.value.length > 0 ? 'loaded' : 'empty' } catch (reason: unknown) { treeState.value = 'error'; error.value = errorMessage(reason) } finally { loading.value = false } try { storageChapters.value = await storageService.fetchChapters(comicId.value) } catch { storageChapters.value = [] } const requestedChapterId = Number(route.query.chapterId); if (Number.isSafeInteger(requestedChapterId) && requestedChapterId > 0) locateChapter(requestedChapterId) }
async function submitCatalog(): Promise<void> { try { const id = catalogForm.id ?? 0; switch (catalogForm.action) { case 'create': await catalogManagementApi.create(comicId.value, { title: catalogForm.title.trim(), parentId: catalogForm.parentId ?? null }); break; case 'rename': await catalogManagementApi.rename(comicId.value, id, { title: catalogForm.title.trim() }); break; case 'move': await catalogManagementApi.move(comicId.value, id, { parentId: catalogForm.parentId ?? null }); break; case 'reorder': await catalogManagementApi.reorder(comicId.value, id, { sortOrder: catalogForm.order }); break; case 'delete': await ElMessageBox.confirm('删除目录前请确认重挂目标。', '确认删除', { type: 'warning' }); await catalogManagementApi.delete(comicId.value, id, catalogForm.reparentTo); break; default: assertNever(catalogForm.action) } ElMessage.success('目录操作完成'); await loadTree() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function submitChapter(): Promise<void> { try { const id = chapterForm.id ?? 0; switch (chapterForm.action) { case 'create': await chapterManagementApi.create(comicId.value, { title: chapterForm.title.trim(), chapterNo: chapterForm.chapterNo.trim(), catalogId: chapterForm.catalogId ?? null }); break; case 'rename': await chapterManagementApi.rename(comicId.value, id, { title: chapterForm.title.trim() || undefined, chapterNo: chapterForm.chapterNo.trim() || undefined }); break; case 'move': await chapterManagementApi.move(comicId.value, id, { catalogId: chapterForm.catalogId ?? null }); break; case 'reorder': if (!chapterForm.order) { ElMessage.warning('请输入目标顺序'); return } if (chapterForm.order === selectedRow.value?.order) { ElMessage.info('目标顺序与当前位置相同，无需提交'); return } await chapterManagementApi.reorder(comicId.value, id, { targetGlobalOrder: chapterForm.order }); break; case 'trash': await ElMessageBox.confirm('章节将进入回收站。', '确认回收', { type: 'warning' }); await chapterManagementApi.trash(comicId.value, id); break; default: assertNever(chapterForm.action) } ElMessage.success('章节操作完成'); await loadTree() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function loadMedia(): Promise<void> { try { mediaItems.value = (await managementReaderApi.chapter(mediaChapterId.value)).data.pages; mediaOrderItems.value = [...mediaItems.value]; mediaOrder.value = mediaItems.value.map((item) => item.id).join(','); selectedMedia.value = null } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
function startMediaDrag(index: number): void { draggingMediaIndex.value = index }
function dropMedia(targetIndex: number): void { const sourceIndex = draggingMediaIndex.value; draggingMediaIndex.value = null; if (sourceIndex === null || sourceIndex === targetIndex) return; const nextItems = [...mediaOrderItems.value]; const [movedItem] = nextItems.splice(sourceIndex, 1); if (movedItem) nextItems.splice(targetIndex, 0, movedItem); mediaOrderItems.value = nextItems; mediaOrder.value = nextItems.map((item) => item.id).join(',') }
function resetMediaOrder(): void { mediaOrderItems.value = [...mediaItems.value]; mediaOrder.value = mediaOrderItems.value.map((item) => item.id).join(',') }
function sortMediaByName(): void { mediaOrderItems.value = [...mediaOrderItems.value].sort((a, b) => (a.fileName || '').localeCompare(b.fileName || '', 'zh-CN', { numeric: true, sensitivity: 'base' })); mediaOrder.value = mediaOrderItems.value.map((item) => item.id).join(',') }
function applyAdvancedMediaOrder(): void { const ids = mediaOrder.value.split(',').map((value) => Number(value.trim())).filter((id) => Number.isSafeInteger(id) && id > 0); const itemById = new Map(mediaItems.value.map((item) => [item.id, item])); if (ids.length !== mediaItems.value.length || new Set(ids).size !== ids.length || ids.some((id) => !itemById.has(id))) { ElMessage.warning('媒体 ID 必须完整、有效且不能重复'); return } mediaOrderItems.value = ids.map((id) => itemById.get(id)!).filter(Boolean); ElMessage.success('已应用到排序列表') }
async function reorderMedia(): Promise<void> { const mediaIds = mediaOrderItems.value.map((item) => item.id); if (!mediaIds.length) return; try { await mediaManagementApi.reorder(mediaChapterId.value, { mediaIds }); ElMessage.success('媒体顺序已保存'); await loadMedia() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function saveMediaOrderAndClose(): Promise<void> { await reorderMedia(); if (!mediaOrderDirty.value) mediaOrderDialogVisible.value = false }
async function deleteChapterHq(): Promise<void> { if (!selectedRow.value || selectedRow.value.kind !== 'CHAPTER') return; try { await ElMessageBox.confirm('确定删除当前章节的 HQ？LQ 文件会保留。', '删除章节 HQ', { type: 'warning' }); await storageService.executeOperation({ type: StorageOperationType.DeleteHQ, comicId: comicId.value, chapterId: selectedRow.value.id }); ElMessage.success('HQ 删除任务已提交'); await refreshStorage() } catch (reason: unknown) { if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorMessage(reason)) } }
async function generateChapterLq(): Promise<void> { if (!selectedRow.value || selectedRow.value.kind !== 'CHAPTER') return; try { const regenerate = selectedStorageChapter.value?.lqStatus === 'READY'; await storageService.executeOperation({ type: StorageOperationType.GenerateLQ, comicId: comicId.value, chapterId: selectedRow.value.id, regenerate }); ElMessage.success(regenerate ? '本章 LQ 重建任务已提交' : '本章 LQ 生成任务已提交'); await refreshStorage() } catch (reason: unknown) { ElMessage.error(errorMessage(reason)) } }
async function transcodeChapter(): Promise<void> { if (!selectedRow.value || selectedRow.value.kind !== 'CHAPTER') return; try { await ElMessageBox.confirm('确定对当前章节的视频发起转码？', '章节视频转码', { type: 'warning' }); await adminApi.transcodeChapter(selectedRow.value.id); ElMessage.success('章节视频转码任务已提交'); await loadMedia() } catch (reason: unknown) { if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorMessage(reason)) } }
async function transcodeSelectedMedia(): Promise<void> { if (!selectedMedia.value || selectedMedia.value.mediaType !== 'VIDEO') return; try { await ElMessageBox.confirm('确定对当前视频发起转码？', '视频转码', { type: 'warning' }); await hqApi.transcodeMedia(selectedMedia.value.id); ElMessage.success('视频转码任务已提交'); await loadMedia() } catch (reason: unknown) { if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorMessage(reason)) } }
async function trashSelectedMedia(): Promise<void> { if (!selectedMedia.value) return; try { await ElMessageBox.confirm(`确定将「${selectedMedia.value.fileName || '此媒体'}」移入回收站？`, '回收媒体', { type: 'warning' }); await mediaManagementApi.trash(selectedMedia.value.id); ElMessage.success('媒体回收任务已提交'); selectedMedia.value = null; await loadMedia() } catch (reason: unknown) { if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorMessage(reason)) } }
async function refreshStorage(): Promise<void> { try { storageChapters.value = await storageService.fetchChapters(comicId.value) } catch { storageChapters.value = [] } await loadMedia() }

onMounted(() => { void loadTree() })
</script>

<style scoped>
.structure-page { display: grid; gap: var(--space-5); }
.issue-strip { display: flex; align-items: center; gap: var(--space-4); min-width: 0; padding: 9px 12px; border: 1px solid color-mix(in srgb, var(--warning) 45%, var(--border)); border-left: 3px solid var(--warning); background: color-mix(in srgb, var(--warning) 5%, var(--bg-surface)); }
.issue-strip-heading { display: flex; align-items: center; flex: 0 0 auto; gap: 10px; white-space: nowrap; }.issue-strip-heading > div { display: grid; gap: 2px; }.issue-strip-heading strong { color: var(--text-primary); font-size: 12px; }.issue-strip-heading small { color: var(--text-muted); font-size: 10px; }
.issue-chapter-list { display: flex; flex: 1 1 auto; min-width: 0; gap: 5px; overflow-x: auto; overflow-y: hidden; scrollbar-width: thin; }.issue-chapter { display: grid; flex: 0 0 150px; gap: 2px; min-width: 120px; padding: 6px 8px; border: 1px solid var(--border); background: var(--bg-elevated); color: var(--text-primary); text-align: left; cursor: pointer; }.issue-chapter:hover { border-color: var(--accent); }.issue-chapter span { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.issue-chapter small { overflow: hidden; color: var(--warning); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.upload-action { display: grid; gap: var(--space-3); }
.upload-action p { margin: 0; color: var(--text-muted); font-size: var(--text-xs); line-height: 1.6; }
.upload-action :deep(.el-button + .el-button) { margin-left: 0; }
.upload-dialog-heading { display: grid; gap: 4px; }
.upload-dialog-heading h2 { margin: 0; color: var(--text-primary); font-size: 22px; }
.upload-dialog-heading p { margin: 0; color: var(--text-muted); font-size: var(--text-sm); }
.upload-dialog-body { display: grid; gap: var(--space-4); }
.upload-mode { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-3); border: 1px solid var(--border); background: var(--bg-elevated); }
.mode-mark { display: grid; place-items: center; width: 32px; height: 32px; color: var(--accent); border: 1px solid var(--accent); font-size: 20px; }
.upload-mode strong, .upload-mode small { display: block; }
.upload-mode small { margin-top: 3px; color: var(--text-muted); font-size: var(--text-xs); }
.upload-dropzone { display: grid; justify-items: center; gap: 6px; padding: 30px 20px; border: 1px dashed var(--accent); background: color-mix(in srgb, var(--accent) 4%, var(--bg-surface)); cursor: pointer; text-align: center; transition: background .2s ease, border-color .2s ease; }
.upload-dropzone:hover, .upload-dropzone.is-ready { background: color-mix(in srgb, var(--accent) 10%, var(--bg-surface)); }
.upload-dropzone input { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
.dropzone-icon { color: var(--accent); font-size: 26px; line-height: 1; }
.upload-dropzone strong { color: var(--text-primary); }
.upload-dropzone small { color: var(--text-muted); font-size: var(--text-xs); }
.upload-file-list { display: grid; max-height: 220px; overflow: auto; border: 1px solid var(--border); }
.upload-file-row { display: grid; grid-template-columns: minmax(0, 1fr) 150px; gap: var(--space-4); align-items: center; padding: 10px 12px; border-bottom: 1px solid var(--border); }
.upload-file-row:last-child { border-bottom: 0; }
.upload-file-row strong, .upload-file-row small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.upload-file-row small { margin-top: 3px; color: var(--text-muted); font-size: var(--text-xs); }
.upload-session-note { padding: 10px 12px; color: var(--text-muted); background: var(--bg-elevated); font-size: var(--text-xs); }
.media-order-dialog-body { display: grid; gap: var(--space-4); }
.media-order-list--dialog { max-height: min(52vh, 520px); overflow-y: auto; }
.order-dialog-hint { margin-left: auto; color: var(--text-muted); font-size: 11px; }
.order-dialog-status { margin-right: auto; color: var(--text-muted); font-size: 11px; }
.storage-dialog-body { display: grid; gap: var(--space-4); }
.storage-dialog-status { display: grid; grid-template-columns: 1fr auto; align-items: center; gap: var(--space-3); padding: var(--space-3); border: 1px solid var(--border); background: var(--bg-surface); color: var(--text-muted); font-size: 11px; }
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
.structure-summary small { overflow: hidden; color: var(--text-muted); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
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
.structure-browser {
  display: grid;
  grid-template-columns:
    minmax(clamp(180px, 16vw, 240px), .8fr)
    minmax(clamp(300px, 32vw, 560px), 2.4fr)
    minmax(clamp(220px, 20vw, 300px), 1fr);
  gap: var(--space-3);
  height: clamp(560px, calc(100vh - 270px), 820px);
  min-width: 0;
}
.tree-panel, .detail-panel, .action-panel { min-width: 0; border: 1px solid var(--border); background: var(--bg-surface); }
.tree-panel, .action-panel { align-self: stretch; min-height: 0; overflow: hidden; padding: var(--space-4); }
.action-panel { overflow-y: auto; scrollbar-gutter: stable; }
.tree-panel { display: flex; flex-direction: column; }
.detail-panel { display: flex; min-width: 0; min-height: 0; flex-direction: column; overflow: hidden; padding: clamp(var(--space-5), 3vw, var(--space-8)); }
.panel-topline, .selected-header, .media-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-3); }
.panel-topline h2, .selected-header h2 { margin: var(--space-1) 0 0; color: var(--text-primary); font-size: var(--text-lg); }
.node-count, .selected-header p, .media-heading p { color: var(--text-muted); font-size: var(--text-xs); }
.tree-search { margin: var(--space-4) 0; }
.tree-panel .structure-table { display: flex; flex: 1 1 auto; min-height: 0; height: auto; max-height: none; flex-direction: column; }
.tree-panel .structure-table :deep(.el-table__header-wrapper) { display: none; }
.tree-panel .structure-table :deep(.el-table__header-wrapper), .tree-panel .structure-table :deep(.el-table__body-wrapper) { max-width: 100%; overflow-x: hidden; }
.tree-panel .structure-table :deep(.el-table__inner-wrapper) { display: flex; min-height: 0; height: 100%; flex-direction: column; }
.tree-panel .structure-table :deep(.el-table__body-wrapper) { flex: 1 1 auto; min-height: 0; max-height: none; overflow-y: auto; }
.tree-panel .structure-table :deep(.el-table__cell) { min-width: 0; }
.tree-panel .structure-table :deep(.el-table__row) { cursor: pointer; }
.tree-panel .structure-table :deep(.el-table__cell) { padding: 0 6px; }
.tree-panel .structure-table :deep(.el-table__row) .cell { min-height: 48px; display: flex; align-items: center; }
.tree-panel .structure-table :deep(.el-table__expand-icon) { display: grid; place-items: center; width: 22px; height: 22px; margin-right: 4px; border: 1px solid transparent; border-radius: 4px; color: var(--text-muted); transition: color var(--transition-fast), border-color var(--transition-fast), background-color var(--transition-fast); }
.tree-panel .structure-table :deep(.el-table__expand-icon:hover) { border-color: var(--border-strong); background: var(--bg-elevated); color: var(--text-primary); }
.tree-panel .structure-table :deep(.el-table__expand-icon .el-icon) { font-size: 11px; }
.tree-panel .structure-table :deep(.el-table__row--level-0) .cell { font-weight: 650; }
.tree-panel .structure-table :deep(.el-table__row--level-1) .cell { padding-left: 8px; }
.tree-panel .structure-table :deep(.el-table__row--level-2) .cell { padding-left: 18px; }
.tree-title { display: flex; align-items: center; gap: 8px; min-width: 0; color: var(--text-primary); }
.tree-title > span:last-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-icon { display: inline-grid; place-items: center; flex: 0 0 auto; width: 16px; color: var(--accent); font-size: 11px; }
.tree-kind { padding: 3px 5px; color: var(--text-muted); font-size: 10px; line-height: 1; }
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
.media-table-scroll {
  min-width: 0;
  min-height: 0;
  flex: 1;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-gutter: stable;
}
.media-table {
  width: max-content;
  min-width: 100%;
  height: 100%;
}
.media-table :deep(.el-table__inner-wrapper) {
  display: flex;
  height: 100%;
  flex-direction: column;
}
.media-table :deep(.el-table__header-wrapper),
.media-table :deep(.el-table__body-wrapper) {
  max-width: none;
  overflow-x: visible;
}
.media-table :deep(.el-table__body-wrapper) {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
}
.media-table :deep(.el-table__header-wrapper) {
  position: sticky;
  top: 0;
  z-index: 2;
  background: var(--bg-surface);
}
.media-table :deep(.el-table__header),
.media-table :deep(.el-table__body) {
  width: max-content !important;
  min-width: 100%;
}
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
.action-card { padding: var(--space-4); border: 1px solid var(--border); background: var(--bg-secondary); }
.chapter-feature-grid { display: grid; grid-template-columns: 1fr; gap: var(--space-2); margin-top: var(--space-3); }
.chapter-feature-card { display: grid; align-content: start; gap: 7px; min-width: 0; padding: var(--space-3); border: 1px solid var(--border); background: linear-gradient(145deg, var(--bg-secondary), color-mix(in srgb, var(--bg-surface) 88%, var(--accent) 12%)); }
.chapter-feature-card:hover { border-color: var(--border-strong); }
.feature-card-top { display: flex; align-items: center; justify-content: space-between; gap: 6px; min-height: 16px; }
.chapter-feature-card strong { color: var(--text-primary); font-size: 13px; }
.chapter-feature-card p { min-height: 30px; margin: 0; color: var(--text-muted); font-size: 10px; line-height: 1.45; }
.feature-count, .feature-mark { color: var(--text-muted); font: 700 10px var(--mono); }.feature-mark { color: var(--accent); font-size: 16px; }
.feature-button-row { display: grid; gap: 5px; margin-top: auto; }.feature-button-row .el-button { width: 100%; min-height: 28px; margin: 0; padding: 5px 7px; font-size: 10px; }
.action-card + .action-card { margin-top: var(--space-3); }
.action-details { border: 1px solid var(--border); background: var(--bg-secondary); }
.action-details + .action-details { margin-top: var(--space-2); }
.action-details > summary { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); padding: var(--space-3) var(--space-4); color: var(--text-secondary); cursor: pointer; list-style: none; }
.action-details > summary::-webkit-details-marker { display: none; }
.action-details > summary::after { content: '＋'; color: var(--accent); font-size: 16px; }
.action-details[open] > summary::after { content: '−'; }
.action-details > summary > span:first-child { display: grid; gap: 3px; }
.action-details > summary strong { color: var(--text-primary); font-size: var(--text-sm); }
.action-details > summary > span:last-child { color: var(--text-muted); font-size: 11px; }
.action-details .action-card { border: 0; border-top: 1px solid var(--border); }
.action-details .action-card-head { display: none; }
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
.chapter-action-toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); margin-bottom: var(--space-3); color: var(--text-secondary); font-size: var(--text-sm); }
.chapter-action-toolbar .el-button { margin: 0; padding: 0; }
.chapter-choice { display: grid; gap: var(--space-2); margin-bottom: var(--space-4); }
.chapter-choice > label { color: var(--text-secondary); font-size: var(--text-sm); }
.chapter-choice-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-2); }
.chapter-choice-grid button { min-height: 38px; padding: 8px 10px; border: 1px solid var(--border); background: var(--bg-surface); color: var(--text-secondary); font-size: 12px; text-align: left; cursor: pointer; transition: border-color var(--transition-fast), background-color var(--transition-fast), color var(--transition-fast); }
.chapter-choice-grid button:hover { border-color: var(--accent); color: var(--text-primary); }
.chapter-choice-grid button.is-active { border-color: var(--accent); background: var(--accent-bg); color: var(--accent); box-shadow: inset 2px 0 var(--accent); }
.chapter-choice-grid button.is-danger { color: var(--text-muted); }
.chapter-choice-grid button.is-danger.is-active { border-color: var(--danger, var(--accent)); background: color-mix(in srgb, var(--accent) 12%, var(--bg-surface)); color: var(--accent); }
.create-context { display: flex; align-items: center; gap: var(--space-3); margin-bottom: var(--space-4); padding: var(--space-3); border-left: 2px solid var(--accent); background: var(--bg-surface); }
.create-context div { display: grid; gap: 3px; }
.create-context strong { color: var(--text-primary); font-size: 13px; }
.create-context small { color: var(--text-muted); font-size: 11px; }
.chapter-reorder-box { display: grid; grid-template-columns: minmax(70px, 1fr) 24px minmax(150px, 1.5fr); align-items: end; gap: var(--space-2); margin-bottom: var(--space-4); padding: var(--space-3); border: 1px solid var(--border); background: var(--bg-surface); }
.chapter-position { display: grid; gap: 5px; min-height: 58px; align-content: center; padding-left: 3px; }
.chapter-position span, .chapter-reorder-box .el-form-item__label, .chapter-reorder-box > small { color: var(--text-muted); font-size: 11px; }
.chapter-position strong { color: var(--text-primary); font: 700 22px var(--mono); }
.position-arrow { align-self: center; padding-bottom: 9px; color: var(--accent); font-size: 20px; text-align: center; }
.chapter-reorder-box .el-form-item { margin-bottom: 0; }
.chapter-reorder-box > small { grid-column: 1 / -1; }
.action-form :deep(.el-form-item) { margin-bottom: var(--space-4); }
.action-form :deep(.el-select), .action-form :deep(.el-input-number) { width: 100%; }
.action-form :deep(.el-input-number .el-input__wrapper) { width: 100%; }
.action-form :deep(.el-button--block) { width: 100%; }
.action-form :deep(.el-form-item__content) { display: grid; gap: 5px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
.action-submit { margin-top: var(--space-2); }
.media-action { display: grid; gap: var(--space-3); }
.media-action p { margin: 0; color: var(--text-muted); font-size: var(--text-xs); line-height: 1.5; }
.order-toolbar { display: flex; gap: var(--space-2); }
.order-toolbar button { padding: 5px 8px; border: 1px solid var(--border); background: var(--bg-surface); color: var(--text-secondary); font-size: 10px; cursor: pointer; transition: border-color var(--transition-fast), color var(--transition-fast), background-color var(--transition-fast); }
.order-toolbar button:hover { border-color: var(--accent); background: var(--accent-bg); color: var(--text-primary); }
.media-order-list { display: grid; gap: 3px; max-height: 330px; overflow-y: auto; padding: 3px; border: 1px solid var(--border); background: var(--bg-surface); }
.media-order-item { display: grid; grid-template-columns: 16px 24px 30px minmax(0, 1fr) auto; align-items: center; gap: 5px; min-height: 34px; padding: 5px 6px; border: 1px solid transparent; background: var(--bg-secondary); cursor: grab; transition: border-color var(--transition-fast), background-color var(--transition-fast), transform var(--transition-fast); }
.media-order-item:hover { border-color: var(--border-strong); background: var(--bg-elevated); }
.media-order-item:active { cursor: grabbing; transform: scale(.99); }
.media-order-list.is-dirty .media-order-item { border-left-color: color-mix(in srgb, var(--accent) 55%, transparent); }
.drag-handle { color: var(--text-muted); font-size: 16px; line-height: 1; }
.order-number, .order-type, .order-id { color: var(--text-muted); font: 700 9px var(--mono); }
.order-type { color: var(--accent); }
.order-file { overflow: hidden; color: var(--text-primary); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.order-id { color: var(--text-secondary); }
.order-empty { padding: var(--space-5); color: var(--text-muted); font-size: 11px; text-align: center; }
.media-order-status { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); color: var(--text-muted); font-size: 11px; }
.media-order-status > span { color: var(--warning); }
.media-order-status .el-button { flex: 0 0 auto; margin: 0; }
.advanced-order { border-top: 1px solid var(--border); padding-top: var(--space-2); color: var(--text-muted); font-size: 11px; }
.advanced-order summary { color: var(--text-secondary); cursor: pointer; }
.advanced-order p { margin: var(--space-2) 0; font-size: 10px; }
.advanced-order .el-button { margin-top: var(--space-1); padding: 0; }
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
@media (max-width: 1500px) {
  .structure-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 760px) {
  .issue-strip { align-items: flex-start; flex-direction: column; gap: 7px; }
  .issue-chapter-list { width: 100%; }
  .structure-header { align-items: flex-start; flex-direction: column; }
  .structure-browser { grid-template-columns: 1fr; height: auto; min-height: 0; }
  .detail-panel { min-height: 420px; }
  .action-panel { grid-column: auto; }
  .action-panel .action-form { display: grid; grid-template-columns: 1fr; }
  .load-card { width: 100%; min-width: 0; }
  .structure-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
