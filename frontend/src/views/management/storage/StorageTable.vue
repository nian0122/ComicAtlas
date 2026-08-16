<script setup lang="ts">
import { reactive, ref } from 'vue'
import { Collection } from '@element-plus/icons-vue'
import type { ComicStorageItem } from '@/types'
import StorageStatusTag from './StorageStatusTag.vue'

const props = defineProps<{
  list: ComicStorageItem[]
  loading: boolean
  total: number
  currentPage: number
  pageSize: number
}>()

const emit = defineEmits<{
  'update:currentPage': [page: number]
  'update:pageSize': [size: number]
  rowClick: [comicId: number]
}>()

const tableRef = ref()

function clearSelection() {
  tableRef.value?.clearSelection()
}

defineExpose({ clearSelection })

function onRowClick(row: ComicStorageItem) {
  emit('rowClick', row.comicId)
}

const failedCoverIds = reactive(new Set<number>())

function markCoverFailed(comicId: number) {
  failedCoverIds.add(comicId)
}

function formatSize(bytes: number): string {
  if (!bytes || bytes < 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) { size /= 1024; i++ }
  return `${size.toFixed(i > 0 ? 1 : 0)} ${units[i]}`
}

function sizePercent(bytes: number, total: number): number { return total > 0 ? Math.max(0, Math.min(100, Math.round((bytes / total) * 100))) : 0 }
</script>

<template>
  <el-table
    ref="tableRef"
    v-loading="loading"
    :data="list"
    row-key="comicId"
    highlight-current-row
    @row-click="onRowClick"
  >
    <el-table-column label="封面" width="70">
      <template #default="{ row }">
        <img
          v-if="row.coverUrl && !failedCoverIds.has(row.comicId)"
          :src="row.coverUrl"
          class="cover-thumb"
          loading="lazy"
          alt=""
          @error="markCoverFailed(row.comicId)"
        >
        <div v-else class="cover-placeholder" aria-label="暂无封面">
          <el-icon :size="20"><Collection /></el-icon>
        </div>
      </template>
    </el-table-column>
    <el-table-column prop="title" label="漫画名称" min-width="180" show-overflow-tooltip />
    <el-table-column label="存储状态" width="150">
      <template #default="{ row }"><div class="status-stack"><StorageStatusTag :status="row.hqStatus" type="hq" /><StorageStatusTag :status="row.lqStatus" type="lq" /></div></template>
    </el-table-column>
    <el-table-column label="类型" width="76" align="center">
      <template #default="{ row }"><span class="media-type">{{ row.mediaType === 'MIXED' ? '混合' : row.mediaType === 'VIDEO' ? '视频' : '图片' }}</span></template>
    </el-table-column>
    <el-table-column label="章节数" width="70" align="center">
      <template #default="{ row }">{{ row.chapterCount ?? '-' }}</template>
    </el-table-column>
    <el-table-column label="占用情况" min-width="190">
      <template #default="{ row }"><div class="storage-cell"><div class="storage-cell-head"><strong>{{ formatSize(row.totalSize) }}</strong><span>{{ row.pageCount ?? 0 }} 个媒体</span></div><div class="storage-bar"><i class="storage-bar-hq" :style="{ width: `${sizePercent(row.hqSize, row.totalSize)}%` }" /><i class="storage-bar-lq" :style="{ width: `${sizePercent(row.lqSize, row.totalSize)}%` }" /></div><small>HQ {{ formatSize(row.hqSize) }} · LQ {{ formatSize(row.lqSize) }}</small></div></template>
    </el-table-column>
  </el-table>
  <el-pagination
    class="pagination-bar"
    layout="total, sizes, prev, pager, next, jumper"
    :page-sizes="[10, 20, 50, 100]"
    :total="total"
    :current-page="currentPage"
    :page-size="pageSize"
    @update:current-page="emit('update:currentPage', $event)"
    @update:page-size="emit('update:pageSize', $event)"
  />
</template>

<style scoped>
.cover-thumb {
  width: 48px;
  height: 64px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  background: var(--bg-secondary);
}

.cover-placeholder {
  display: grid;
  place-items: center;
  width: 48px;
  height: 64px;
  border: 1px solid var(--border);
  border-left: 2px solid var(--accent);
  border-radius: var(--radius-sm);
  background: var(--bg-secondary);
  color: var(--text-muted);
}

.status-stack { display: flex; flex-wrap: wrap; gap: 4px; }
.media-type { color: var(--text-secondary); font: 700 10px var(--mono); }
.storage-cell { display: grid; gap: 4px; min-width: 160px; }
.storage-cell-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.storage-cell-head strong { color: var(--text-primary); font-size: 13px; }
.storage-cell-head span, .storage-cell small { color: var(--text-muted); font-size: 10px; }
.storage-bar { display: flex; height: 5px; overflow: hidden; background: var(--bg-primary); }
.storage-bar i { display: block; min-width: 0; }
.storage-bar-hq { background: var(--accent); }.storage-bar-lq { background: var(--success); }

.pagination-bar {
  margin-top: var(--space-base);
  display: flex;
  justify-content: flex-end;
}
</style>
