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

const tableRef = ref<InstanceType<typeof import('element-plus').ElTable> | null>(null)

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
    <el-table-column label="HQ 状态" width="90">
      <template #default="{ row }"><StorageStatusTag :status="row.hqStatus" type="hq" /></template>
    </el-table-column>
    <el-table-column label="章节数" width="70" align="center">
      <template #default="{ row }">{{ row.chapterCount ?? '-' }}</template>
    </el-table-column>
    <el-table-column label="HQ 大小" width="100" align="right">
      <template #default="{ row }">{{ formatSize(row.hqSize) }}</template>
    </el-table-column>
    <el-table-column label="LQ 大小" width="100" align="right">
      <template #default="{ row }">{{ formatSize(row.lqSize) }}</template>
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

.pagination-bar {
  margin-top: var(--space-base);
  display: flex;
  justify-content: flex-end;
}
</style>
