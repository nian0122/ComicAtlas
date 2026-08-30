<template>
  <div class="trash-page">
    <header class="page-header">
      <div>
        <p class="page-eyebrow">LIFECYCLE / TRASH</p>
        <h1 class="page-title">回收站</h1>
        <p class="page-subtitle">统一查看已回收的漫画、章节和媒体，并在保留期内恢复或永久清理。</p>
      </div>
      <el-button :loading="loading" @click="loadItems">刷新</el-button>
    </header>

    <section class="filter-toolbar" aria-label="回收站筛选">
      <el-input
        v-model="keyword"
        class="filter-input"
        placeholder="搜索漫画、章节或媒体"
        clearable
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      />
      <el-select v-model="status" class="filter-select" @change="applyFilters">
        <el-option v-for="option in STATUS_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-button text @click="resetFilters">重置</el-button>
    </section>

    <el-alert v-if="error" :title="error" type="error" show-icon />

    <section class="trash-card" aria-label="回收站内容">
      <div class="section-heading">
        <div>
          <h2>回收内容</h2>
          <span>{{ total }} 项回收内容</span>
        </div>
        <div class="section-actions">
          <span v-if="selectedItems.length" class="selection-count">已选 {{ selectedItems.length }} 项</span>
          <el-button v-if="selectedItems.length" text @click="clearSelection">清空选择</el-button>
          <el-button
            v-if="selectedItems.length"
            type="primary"
            plain
            :loading="batchBusy"
            @click="restoreSelected"
          >
            批量恢复
          </el-button>
          <el-button
            v-if="selectedItems.length"
            type="danger"
            plain
            :loading="batchBusy"
            @click="purgeSelected"
          >
            批量永久清理
          </el-button>
          <span v-else class="retention-note">勾选内容后可批量恢复或永久清理</span>
        </div>
      </div>

      <el-table
        ref="trashTableRef"
        v-loading="loading"
        :data="items"
        :row-key="rowKey"
        empty-text="当前没有匹配的回收内容"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="48" reserve-selection :selectable="isSelectable" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ targetTypeLabel(row.targetType) }}</template>
        </el-table-column>
        <el-table-column prop="targetId" label="目标 ID" width="100" />
        <el-table-column label="内容" min-width="300">
          <template #default="{ row }">
            <div class="comic-cell">
              <img v-if="coverUrl(row)" :src="coverUrl(row) || undefined" alt="" @error="hideBrokenImage">
              <div>
                <strong>{{ row.title }}</strong>
                <span>{{ row.subtitle || '—' }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="生命周期" width="150">
          <template #default="{ row }">{{ statusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column label="关联 ID" width="130">
          <template #default="{ row }">{{ relatedId(row) }}</template>
        </el-table-column>
        <el-table-column label="回收时间" width="170">
          <template #default="{ row }">{{ formatDate(row.trashedAt || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="busyId === row.id" :disabled="row.status !== 'TRASHED'" @click="restore(row)">恢复</el-button>
            <el-button link type="danger" :loading="busyId === row.id" :disabled="row.status !== 'TRASHED'" @click="purge(row)">永久清理</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > pageSize"
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        background
        @current-change="loadItems"
      />
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { TrashContentVO } from '@/features/trash/types'
import { trashApi } from '@/features/trash/api'

const STATUS_OPTIONS = [
  { value: 'TRASHED', label: '已回收' },
  { value: 'TRASHING', label: '回收中' },
  { value: 'RESTORING', label: '恢复中' },
  { value: 'PURGING', label: '永久清理中' },
] as const
const pageSize = 20
const items = ref<readonly TrashContentVO[]>([])
const selectedItems = ref<TrashContentVO[]>([])
const trashTableRef = ref<{ clearSelection: () => void } | null>(null)
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const status = ref<(typeof STATUS_OPTIONS)[number]['value']>('TRASHED')
const loading = ref(false)
const error = ref('')
const busyId = ref<number | null>(null)
const batchBusy = ref(false)

function statusLabel(value: string): string {
  return ({ TRASHED: '已回收', TRASHING: '回收中', RESTORING: '恢复中', PURGING: '永久清理中' } as Record<string, string>)[value] || value
}
function targetTypeLabel(value: TrashContentVO['targetType']): string { return ({ COMIC: '漫画', CHAPTER: '章节', MEDIA: '媒体' })[value] }
function relatedId(row: TrashContentVO): string { return row.targetType === 'COMIC' ? '—' : row.targetType === 'CHAPTER' ? `漫画 ${row.comicId}` : `章节 ${row.chapterId}` }
function rowKey(row: TrashContentVO): string { return `${row.targetType}-${row.targetId}` }
function isSelectable(row: TrashContentVO): boolean { return row.status === 'TRASHED' }
function handleSelectionChange(rows: TrashContentVO[]): void { selectedItems.value = rows.filter((row) => row.status === 'TRASHED') }
function clearSelection(): void {
  trashTableRef.value?.clearSelection()
  selectedItems.value = []
}
function coverUrl(row: TrashContentVO): string | null {
  return row.coverUrl || null
}
function hideBrokenImage(event: Event): void { (event.currentTarget as HTMLImageElement).hidden = true }
function formatDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (part: number): string => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
function errorMessage(reason: unknown): string { return reason instanceof Error ? reason.message : '回收站加载失败' }

async function loadItems(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const response = await trashApi.list({ page: page.value, size: pageSize, keyword: keyword.value.trim() || undefined, status: status.value })
    items.value = response.data.records
    total.value = response.data.total
    clearSelection()
  } catch (reason: unknown) {
    error.value = errorMessage(reason)
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function submitRestore(row: TrashContentVO): Promise<void> {
  if (row.targetType === 'COMIC') await trashApi.restoreComic(row.targetId)
  else if (row.targetType === 'CHAPTER') await trashApi.restoreChapter(row.comicId!, row.chapterId!)
  else await trashApi.restoreMedia(row.targetId)
}

async function submitPurge(row: TrashContentVO, token: string): Promise<void> {
  if (row.targetType === 'COMIC') await trashApi.purgeComic(row.targetId, token)
  else if (row.targetType === 'CHAPTER') await trashApi.purgeChapter(row.comicId!, row.chapterId!, token)
  else await trashApi.purgeMedia(row.targetId, token)
}

async function restoreSelected(): Promise<void> {
  const rows = selectedItems.value.filter((row) => row.status === 'TRASHED')
  if (!rows.length) return
  try {
    await ElMessageBox.confirm(`确定恢复已选的 ${rows.length} 项内容？`, '批量恢复', { type: 'warning', confirmButtonText: '恢复' })
    batchBusy.value = true
    const results = await Promise.allSettled(rows.map((row) => submitRestore(row)))
    const successCount = results.filter((result) => result.status === 'fulfilled').length
    const failureCount = results.length - successCount
    ElMessage[failureCount ? 'warning' : 'success'](failureCount ? `${successCount} 项已提交，${failureCount} 项失败` : `${successCount} 项恢复任务已提交`)
    await loadItems()
  } catch (reason: unknown) {
    if (reason !== 'cancel') ElMessage.error(errorMessage(reason))
  } finally { batchBusy.value = false }
}

async function purgeSelected(): Promise<void> {
  const rows = selectedItems.value.filter((row) => row.status === 'TRASHED')
  if (!rows.length) return
  try {
    const result = await ElMessageBox.prompt(
      `请输入确认 token，将永久清理已选的 ${rows.length} 项内容。此操作不可恢复。`,
      '批量永久清理',
      { type: 'error', inputPlaceholder: '确认 token' },
    )
    batchBusy.value = true
    const token = result.value.trim()
    const results = await Promise.allSettled(rows.map((row) => submitPurge(row, token)))
    const successCount = results.filter((result) => result.status === 'fulfilled').length
    const failureCount = results.length - successCount
    ElMessage[failureCount ? 'warning' : 'success'](failureCount ? `${successCount} 项已提交，${failureCount} 项失败` : `${successCount} 项永久清理任务已提交`)
    await loadItems()
  } catch (reason: unknown) {
    if (reason !== 'cancel') ElMessage.error(errorMessage(reason))
  } finally { batchBusy.value = false }
}

function applyFilters(): void { page.value = 1; void loadItems() }
function resetFilters(): void { keyword.value = ''; status.value = 'TRASHED'; applyFilters() }

async function restore(row: TrashContentVO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定恢复「${row.title}」？`, `恢复${targetTypeLabel(row.targetType)}`, { type: 'warning', confirmButtonText: '恢复' })
    busyId.value = row.targetId
    await submitRestore(row)
    ElMessage.success('恢复任务已提交')
    await loadItems()
  } catch (reason: unknown) {
    if (reason !== 'cancel') ElMessage.error(errorMessage(reason))
  } finally { busyId.value = null }
}

async function purge(row: TrashContentVO): Promise<void> {
  try {
    const result = await ElMessageBox.prompt('请输入永久清理确认 token。永久清理不可恢复。', `永久清理${targetTypeLabel(row.targetType)}`, { type: 'error', inputPlaceholder: '确认 token' })
    busyId.value = row.targetId
    await submitPurge(row, result.value.trim())
    ElMessage.success('永久清理任务已提交')
    await loadItems()
  } catch (reason: unknown) {
    if (reason !== 'cancel') ElMessage.error(errorMessage(reason))
  } finally { busyId.value = null }
}

onMounted(() => { void loadItems() })
</script>

<style scoped>
.trash-page { display: grid; gap: var(--space-6); }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-6); }
.filter-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-3); }
.filter-input { width: min(100%, 280px); }
.filter-select { width: 160px; }
.trash-card { overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-surface); }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--space-4); padding: var(--space-5) var(--space-6); border-bottom: 1px solid var(--border); }
.section-heading h2 { margin: 0; color: var(--text-primary); font-size: var(--text-lg); }
.section-heading span { color: var(--text-muted); font-size: var(--text-sm); }
.section-heading > div { display: flex; align-items: baseline; gap: var(--space-3); }
.section-actions { display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: var(--space-2); }
.selection-count { color: var(--text-primary) !important; font-weight: 600; }
.retention-note { color: var(--text-secondary) !important; }
.comic-cell { display: flex; align-items: center; gap: var(--space-3); min-width: 0; }
.comic-cell img { width: 38px; height: 52px; flex: 0 0 auto; border-radius: var(--radius-xs); object-fit: cover; background: var(--bg-secondary); }
.comic-cell div { display: grid; gap: var(--space-1); min-width: 0; }
.comic-cell strong { overflow: hidden; color: var(--text-primary); text-overflow: ellipsis; white-space: nowrap; }
.comic-cell span { color: var(--text-muted); font-size: var(--text-sm); }
.trash-card :deep(.el-pagination) { justify-content: flex-end; padding: var(--space-5) var(--space-6); }
@media (max-width: 700px) { .section-heading { align-items: flex-start; flex-direction: column; } .retention-note { display: none; } }
</style>
