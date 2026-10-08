<script setup lang="ts">
import { formatBytes as formatSize } from '@/shared/lib/format/bytes'
import { reactive, ref } from 'vue'
import { Collection } from '@element-plus/icons-vue'
import type { ComicStorageItem } from '@/entities/storage'
import { StorageStatusTag } from '@/entities/storage'
import { ManagementPanel } from '@/shared/ui/management-panel'

defineProps<{
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
</script>

<template>
  <div class="storage-list">
    <ManagementPanel flush>
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
            />
            <div v-else class="cover-placeholder" aria-label="暂无封面">
              <el-icon :size="20"><Collection /></el-icon>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="漫画名称" min-width="260" show-overflow-tooltip />
        <el-table-column label="存储状态" width="150">
          <template #default="{ row }"
            ><div class="status-stack">
              <StorageStatusTag :status="row.hqStatus" type="hq" /><StorageStatusTag
                :status="row.lqStatus"
                type="lq"
              /></div
          ></template>
        </el-table-column>
        <el-table-column label="类型" width="76" align="center">
          <template #default="{ row }"
            ><span class="media-type">{{
              row.mediaType === 'MIXED' ? '混合' : row.mediaType === 'VIDEO' ? '视频' : '图片'
            }}</span></template
          >
        </el-table-column>
        <el-table-column label="章节数" width="70" align="center">
          <template #default="{ row }">{{ row.chapterCount ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="媒体数" width="90" align="right">
          <template #default="{ row }">{{ row.pageCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="占用情况" width="250" align="right">
          <template #default="{ row }"
            ><div class="storage-cell">
              <div class="storage-cell-head">
                <strong>{{ formatSize(row.totalSize) }}</strong>
              </div>
              <small>HQ {{ formatSize(row.hqSize) }} · LQ {{ formatSize(row.lqSize) }}</small>
            </div></template
          >
        </el-table-column>
      </el-table>
    </ManagementPanel>
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
  </div>
</template>

<style scoped>
.storage-list {
  min-width: 0;
}
.storage-list :deep(.el-table__row) {
  cursor: pointer;
}
.cover-thumb {
  width: 40px;
  height: 54px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  background: var(--bg-secondary);
}

.cover-placeholder {
  display: grid;
  place-items: center;
  width: 40px;
  height: 54px;
  border: 1px solid var(--border);
  border-left: 2px solid var(--accent);
  border-radius: var(--radius-sm);
  background: var(--bg-secondary);
  color: var(--text-muted);
}

.status-stack {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.media-type {
  color: var(--text-secondary);
  font-size: var(--text-sm);
}
.storage-cell {
  display: grid;
  gap: 4px;
  min-width: 160px;
}
.storage-cell-head {
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 8px;
}
.storage-cell-head strong {
  color: var(--text-primary);
  font-size: var(--text-md);
}
.storage-cell small {
  color: var(--text-secondary);
  font-size: var(--text-xs);
}
.pagination-bar {
  margin-top: var(--space-base);
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
}
@media (max-width: 720px) {
  .pagination-bar {
    justify-content: center;
  }
}
</style>
