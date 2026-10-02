<template>
  <div class="manage-comic-list-page">
    <PageHeader spaced title="漫画管理" eyebrow="CATALOG / CONTROL">
      <template #description>共 {{ store.total }} 部漫画</template>
      <AppButton variant="primary" @click="router.push('/manage/import')">+ 导入漫画</AppButton>
    </PageHeader>

    <StatGrid spaced class="repository-stats" aria-label="仓库统计" :columns="3">
      <StatCard label="匹配漫画" :value="store.total.toLocaleString()" description="当前筛选结果" />
      <StatCard
        label="存储池"
        :value="formatBytes(storageTotalBytes)"
        :description="'HQ ' + formatBytes(storageStats?.hqBytes)"
      />
      <StatCard
        label="低画质缓存"
        :value="formatBytes(storageStats?.lqBytes)"
        :description="'缩略图 ' + formatBytes(storageStats?.thumbBytes)"
      />
    </StatGrid>

    <ManagementComicFilterPanel
      v-model:filters="filters"
      :categories="categoryStore.list"
      :tags="tagStore.list"
      :active-conditions="activeConditions"
      @apply="applyFilters"
      @keyword="scheduleKeywordSearch"
      @reset="resetFilters"
    />
    <div v-if="store.list.length > 0" class="batch-toolbar">
      <el-checkbox :model-value="selectAll" :indeterminate="isIndeterminate" @change="handleSelectAll">
        全选本页 ({{ selectedIds.length }} / {{ store.list.length }})
      </el-checkbox>
      <div class="batch-actions">
        <el-select v-model="selectedBatchOperation" :disabled="selectedIds.length === 0" class="batch-operation-select">
          <el-option label="批量生成低清图" value="LQ_GENERATE" />
          <el-option label="批量刷新元数据" value="METADATA_REFRESH" />
          <el-option label="批量回收到回收站" value="COMIC_DELETE" />
        </el-select>
        <AppButton variant="primary" :disabled="selectedIds.length === 0" @click="showBatchOperationDialog = true">
          执行操作
        </AppButton>
        <AppButton variant="secondary" :disabled="selectedIds.length === 0" @click="showBatchDialog = true">
          批量编辑
        </AppButton>
        <AppButton variant="danger" :disabled="selectedIds.length === 0 || exporting" @click="exportSelectedDirectory">
          {{ exporting ? '创建中…' : '移出并导出文件夹' }}
        </AppButton>
      </div>
    </div>

    <ContentState v-if="store.loading && store.list.length === 0" state="loading" message="加载中..." />

    <ContentState v-else-if="store.error" state="error" :message="store.error">
      <AppButton @click="store.fetchList()">重试</AppButton>
    </ContentState>

    <ContentState v-else-if="store.list.length === 0" state="empty" message="暂无漫画">
      <AppButton variant="primary" @click="router.push('/manage/import')">导入漫画</AppButton>
    </ContentState>

    <section v-else class="comic-table-section">
      <div class="comic-grid">
        <article
          v-for="comic in store.list"
          :key="comic.id"
          class="comic-card"
          :class="{ 'is-selected': selectedIds.includes(comic.id) }"
        >
          <el-checkbox
            class="comic-checkbox"
            :model-value="selectedIds.includes(comic.id)"
            :aria-label="`选择漫画：${comic.title}`"
            @change="() => toggleSelect(comic.id)"
            @click.stop
          />
          <AppButton class="comic-card-open" variant="ghost" @click="goEdit(comic.id)">
            <span class="comic-cover">
              <img
                v-if="comic.coverUrl"
                :src="comic.coverUrl"
                :alt="`${comic.title} 封面`"
                loading="lazy"
                decoding="async"
                @error="hideBrokenImage"
              />
              <span class="comic-status-badge">{{ statusLabel(comic.status) }}</span>
            </span>
            <span class="comic-info">
              <span class="comic-title" :title="comic.title">{{ comic.title }}</span>
              <span class="comic-meta">
                <span>{{ comic.author || '未知作者' }}</span>
                <span>{{ comic.pageCount }} 页</span>
              </span>
            </span>
          </AppButton>
        </article>
      </div>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="store.query.page"
          :page-size="store.query.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="store.total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="onPageChange"
          @update:page-size="onPageSizeChange"
        />
      </div>
    </section>

    <div v-if="storageSummaryError" class="inline-error" role="alert">
      <span>{{ storageSummaryError }}</span>
      <AppButton :disabled="storageSummaryLoading" @click="loadStorageSummary">
        {{ storageSummaryLoading ? '重试中...' : '重试' }}
      </AppButton>
    </div>

    <div v-if="directoryExportTask" class="directory-export-status" role="status">
      <div class="directory-export-status__copy">
        <strong>批量文件夹导出 #{{ directoryExportTask.id }}</strong>
        <span v-if="directoryExportTask.status === 'PENDING'">等待处理</span>
        <span v-else-if="directoryExportTask.status === 'RUNNING'"
          >正在移动媒体目录（{{ directoryExportTask.progress }}%）</span
        >
        <span v-else-if="directoryExportTask.status === 'SUCCESS'"
          >导出完成：{{ directoryExportTask.physicalPath || directoryExportTask.outputPath }}</span
        >
        <span v-else class="directory-export-status__error"
          >导出失败：{{ directoryExportTask.errorMsg || '任务处理失败' }}</span
        >
      </div>
      <AppButton v-if="directoryExportTask.status === 'SUCCESS'" variant="secondary" @click="openDirectoryExport">
        打开导出目录
      </AppButton>
      <AppButton variant="text" aria-label="关闭导出任务状态" @click="clearDirectoryExportTask">关闭</AppButton>
    </div>

    <BatchEditDialog v-model:visible="showBatchDialog" :comic-ids="selectedIds" @saved="onBatchSaved" />
    <BatchComicOperationDialog
      v-model:visible="showBatchOperationDialog"
      :comic-ids="selectedIds"
      :operation="selectedBatchOperation"
      @completed="onBatchOperationCompleted"
    />
  </div>
</template>

<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'
import { ContentState } from '@/shared/ui/content-state'
import { StatGrid } from '@/shared/ui/management-panel'
import { StatCard } from '@/shared/ui/management-panel'
import { PageHeader } from '@/shared/ui/page-header'
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { useManagementComicStore } from '@/pages/management/comics/model/management-comic-store'
import { useCategoryStore } from '@/entities/category'
import { useTagStore } from '@/entities/tag'
import { BatchEditDialog } from '@/features/comic-batch-edit'
import { BatchComicOperationDialog } from '@/features/comic-batch-operations'
import { exportApi, type StorageStats } from '@/entities/storage'
import { storageService } from '@/features/storage'
import { COMIC_STATUSES, comicStatusMeta } from '@/entities/comic'

import type { ManagementTaskType } from '@/entities/task'
import { useManagementComicListFilters } from '../model/management-comic-filters'
import ManagementComicFilterPanel from './ManagementComicFilterPanel.vue'

type ComicBatchOperation = Extract<ManagementTaskType, 'LQ_GENERATE' | 'METADATA_REFRESH' | 'COMIC_DELETE'>

const router = useRouter()
const store = useManagementComicStore()
const categoryStore = useCategoryStore()
const tagStore = useTagStore()
const storageStats = ref<StorageStats | null>(null)
const storageSummaryError = ref<string | null>(null)
const storageSummaryLoading = ref(false)
const storageTotalBytes = computed(() => {
  if (!storageStats.value) return undefined
  return storageStats.value.hqBytes + storageStats.value.lqBytes + storageStats.value.thumbBytes
})

function hideBrokenImage(event: Event) {
  const image = event.currentTarget as HTMLImageElement
  image.hidden = true
}

const selectedIds = ref<number[]>([])
const showBatchDialog = ref(false)
const showBatchOperationDialog = ref(false)
const selectedBatchOperation = ref<ComicBatchOperation>('LQ_GENERATE')
const exporting = ref(false)
const directoryExportTask = ref<Awaited<ReturnType<typeof exportApi.getTask>>['data'] | null>(null)
let directoryExportPollTimer: ReturnType<typeof setTimeout> | undefined

const { filters, applyFilters, scheduleKeywordSearch, activeConditions, resetFilters, restoreFilters } =
  useManagementComicListFilters(store, () => {
    selectedIds.value = []
  })

const selectAll = computed(() => store.list.length > 0 && selectedIds.value.length === store.list.length)
const isIndeterminate = computed(() => selectedIds.value.length > 0 && selectedIds.value.length < store.list.length)

function toggleSelect(id: number) {
  const idx = selectedIds.value.indexOf(id)
  if (idx >= 0) {
    selectedIds.value.splice(idx, 1)
  } else {
    selectedIds.value.push(id)
  }
}

function handleSelectAll(val: string | number | boolean) {
  if (val) {
    selectedIds.value = store.list.map((c) => c.id)
  } else {
    selectedIds.value = []
  }
}

function onBatchSaved() {
  selectedIds.value = []
  showBatchDialog.value = false
  store.fetchList()
}

function onBatchOperationCompleted() {
  selectedIds.value = []
  showBatchOperationDialog.value = false
  store.fetchList()
}

async function exportSelectedDirectory(): Promise<void> {
  if (selectedIds.value.length === 0 || exporting.value) return
  try {
    await ElMessageBox.confirm(
      `将移动所选 ${selectedIds.value.length} 本漫画的媒体目录，并从 ComicAtlas 移除这些漫画。完成后无法在本系统阅读或恢复。请确认导出目录可用。`,
      '确认批量移出并导出',
      { type: 'warning', confirmButtonText: '移出并导出', cancelButtonText: '取消' },
    )
    exporting.value = true
    const task = await exportApi.createBatchDirectoryExport([...selectedIds.value])
    directoryExportTask.value = task.data
    scheduleDirectoryExportPoll()
    ElMessage.success(`批量导出任务 ${task.data.id} 已创建，可在任务中心查看进度`)
    selectedIds.value = []
    await store.fetchList()
  } catch (error: unknown) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error && error.message ? error.message : '创建批量导出任务失败')
    }
  } finally {
    exporting.value = false
  }
}

function scheduleDirectoryExportPoll(): void {
  if (directoryExportPollTimer) clearTimeout(directoryExportPollTimer)
  if (!directoryExportTask.value || ['SUCCESS', 'FAILED'].includes(directoryExportTask.value.status)) return
  directoryExportPollTimer = setTimeout(() => void refreshDirectoryExportTask(), 2000)
}

async function refreshDirectoryExportTask(): Promise<void> {
  const taskId = directoryExportTask.value?.id
  if (!taskId) return
  try {
    const response = await exportApi.getTask(taskId)
    directoryExportTask.value = response.data
  } catch (error: unknown) {
    ElMessage.error(error instanceof Error && error.message ? error.message : '读取导出任务状态失败')
  }
  scheduleDirectoryExportPoll()
}

async function openDirectoryExport(): Promise<void> {
  const taskId = directoryExportTask.value?.id
  if (!taskId) return
  try {
    await exportApi.openDir(taskId)
  } catch (error: unknown) {
    ElMessage.error(error instanceof Error && error.message ? error.message : '打开导出目录失败')
  }
}

function clearDirectoryExportTask(): void {
  if (directoryExportPollTimer) clearTimeout(directoryExportPollTimer)
  directoryExportPollTimer = undefined
  directoryExportTask.value = null
}

function statusLabel(status: string) {
  const knownStatus = COMIC_STATUSES.find((value) => value === status)
  return knownStatus ? comicStatusMeta(knownStatus).label : status
}

function goEdit(id: number) {
  router.push(`/manage/comics/${id}?tab=edit`)
}

function onPageChange(page: number) {
  selectedIds.value = []
  store.updateQuery({ page })
  store.fetchList()
}

function onPageSizeChange(size: number) {
  selectedIds.value = []
  store.updateQuery({ page: 1, size })
  store.fetchList()
}

onMounted(() => {
  restoreFilters()
  categoryStore.fetchList()
  tagStore.fetchList()
  store.fetchList()
  void loadStorageSummary()
})

onUnmounted(() => {
  if (directoryExportPollTimer) clearTimeout(directoryExportPollTimer)
})

async function loadStorageSummary(): Promise<void> {
  storageSummaryLoading.value = true
  storageSummaryError.value = null
  try {
    storageStats.value = await storageService.fetchSummary()
  } catch (error: unknown) {
    storageStats.value = null
    storageSummaryError.value = error instanceof Error && error.message ? error.message : '存储统计加载失败，请重试'
  } finally {
    storageSummaryLoading.value = false
  }
}

function formatBytes(bytes: number | undefined): string {
  if (!bytes) return '0 B'
  if (bytes >= 1024 ** 4) return `${(bytes / 1024 ** 4).toFixed(1)} TB`
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`
}
</script>

<style scoped>
.manage-comic-list-page {
  width: 100%;
  max-width: none;
}

.batch-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-md);
  padding: var(--space-md) var(--space-base);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
}

.batch-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-sm);
}

.batch-operation-select {
  width: 190px;
}

.comic-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(205px, 1fr));
  align-items: start;
  gap: var(--space-lg);
  margin-bottom: var(--space-xl);
}

.comic-card {
  position: relative;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--card-radius);
  background: var(--bg-surface);
  box-shadow: var(--card-shadow);
  transition:
    transform var(--transition-fast),
    border-color var(--transition-fast),
    box-shadow var(--transition-fast);
}

.comic-card:hover {
  transform: translateY(-3px);
  border-color: var(--border-strong);
  box-shadow: var(--card-shadow-hover);
}

.comic-card.is-selected {
  border-color: var(--accent);
  box-shadow:
    0 0 0 1px var(--accent),
    var(--card-shadow);
}

.comic-cover {
  position: relative;
  overflow: hidden;
  background: var(--bg-secondary);
  aspect-ratio: 2 / 3;
}

.comic-cover::before {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  content: 'CA';
  color: var(--text-muted);
  font-size: 22px;
  font-weight: 800;
}

.comic-cover img {
  position: relative;
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.comic-cover img[hidden] {
  display: none;
}

.comic-checkbox {
  position: absolute;
  top: var(--space-sm);
  left: var(--space-sm);
  z-index: 2;
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid rgb(255 255 255 / 28%);
  border-radius: var(--radius-sm);
  background: rgb(10 10 10 / 72%);
  backdrop-filter: blur(8px);
}

.comic-checkbox :deep(.el-checkbox__label) {
  display: none;
}

.comic-status-badge {
  position: absolute;
  top: var(--space-sm);
  right: var(--space-sm);
  max-width: calc(100% - 58px);
  overflow: hidden;
  padding: 5px 8px;
  border: 1px solid rgb(255 255 255 / 20%);
  border-radius: var(--radius-pill);
  background: rgb(10 10 10 / 72%);
  color: #f3f3f3;
  font-size: 10px;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
  backdrop-filter: blur(8px);
}

.comic-card-open {
  display: flex;
  width: 100%;
  flex-direction: column;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.comic-card-open:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -3px;
}

.comic-card-open :deep(> span) {
  display: flex;
  flex-direction: column;
  width: 100%;
}

.comic-info {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  min-width: 0;
  padding: var(--space-sm) var(--space-base) var(--space-base);
}

.comic-title {
  display: -webkit-box;
  min-height: 2.7em;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.35;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.comic-meta {
  display: flex;
  justify-content: space-between;
  gap: var(--space-sm);
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  color: var(--text-secondary);
}

.comic-meta span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  padding: var(--space-lg) 0;
}

.inline-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
  padding: var(--space-md);
  color: var(--text-primary);
  background: var(--bg-surface);
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
}

.directory-export-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
  padding: var(--space-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
}

.directory-export-status__copy {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  min-width: 0;
  color: var(--text-secondary);
  overflow-wrap: anywhere;
}

.directory-export-status__copy strong {
  color: var(--text-primary);
}

.directory-export-status__error {
  color: var(--danger);
}

@media (max-width: 680px) {
  .comic-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--space-md);
  }

  .batch-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .batch-actions {
    width: 100%;
    flex-wrap: wrap;
  }

  .batch-operation-select {
    flex: 1 1 100%;
    width: 100%;
  }

  .directory-export-status {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
