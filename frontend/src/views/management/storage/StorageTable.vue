<script setup lang="ts">
import type { ComicStorageItem } from '@/types'
import StorageStatusTag from './StorageStatusTag.vue'

const props = defineProps<{
  list: ComicStorageItem[]
  busyState: Record<number, boolean>
  loading: boolean
  selectedIds: number[]
  highlightedId: number | null
  total: number
  currentPage: number
  pageSize: number
}>()

const emit = defineEmits<{
  'update:selectedIds': [ids: number[]]
  'update:currentPage': [page: number]
  'update:pageSize': [size: number]
  deleteHq: [comicId: number]
  generateLq: [comicId: number]
  exportZip: [comicId: number]
  showChapters: [comicId: number]
  pageChange: []
}>()

function formatSize(bytes: number): string {
  if (!bytes || bytes < 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) { size /= 1024; i++ }
  return `${size.toFixed(i > 0 ? 1 : 0)} ${units[i]}`
}

function onSelectionChange(rows: ComicStorageItem[]) {
  emit('update:selectedIds', rows.map(r => r.comicId))
}

function rowClassName({ row }: { row: ComicStorageItem }) {
  if (props.highlightedId !== null && row.comicId === props.highlightedId) {
    return 'highlighted-row'
  }
  return ''
}
</script>

<template>
  <el-table
    v-loading="loading"
    :data="list"
    row-key="comicId"
    :row-class-name="rowClassName"
    @selection-change="onSelectionChange"
  >
    <el-table-column type="selection" width="40" />
    <el-table-column label="封面" width="70">
      <template #default="{ row }">
        <img :src="row.coverUrl || '/placeholder-cover.png'" class="cover-thumb" loading="lazy" alt="" />
      </template>
    </el-table-column>
    <el-table-column prop="title" label="标题" min-width="150" show-overflow-tooltip />
    <el-table-column label="HQ" width="100" align="right">
      <template #default="{ row }">{{ formatSize(row.hqSize) }}</template>
    </el-table-column>
    <el-table-column label="LQ" width="100" align="right">
      <template #default="{ row }">{{ formatSize(row.lqSize) }}</template>
    </el-table-column>
    <el-table-column label="HQ 状态" width="100">
      <template #default="{ row }"><StorageStatusTag :status="row.hqStatus" type="hq" /></template>
    </el-table-column>
    <el-table-column label="LQ 状态" width="100">
      <template #default="{ row }"><StorageStatusTag :status="row.lqStatus" type="lq" /></template>
    </el-table-column>
    <el-table-column label="操作" width="260" fixed="right">
      <template #default="{ row }">
        <el-button v-if="row.hqStatus === 'READY' || row.hqStatus === 'MIXED'" type="danger" size="small" :disabled="busyState[row.comicId]" @click="emit('deleteHq', row.comicId)">删HQ</el-button>
        <el-button v-if="row.lqStatus === 'NOT_GENERATED' || row.lqStatus === 'MIXED'" type="primary" size="small" :disabled="busyState[row.comicId]" @click="emit('generateLq', row.comicId)">生LQ</el-button>
        <el-button size="small" type="success" :disabled="busyState[row.comicId]" @click="emit('exportZip', row.comicId)">导出 ZIP</el-button>
        <el-button size="small" @click="emit('showChapters', row.comicId)">详情</el-button>
      </template>
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
    @change="emit('pageChange')"
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

.pagination-bar {
  margin-top: var(--space-base);
  display: flex;
  justify-content: flex-end;
}

:deep(.highlighted-row) {
  background-color: var(--bg-secondary) !important;
  transition: background-color 0.5s ease;
}
</style>
