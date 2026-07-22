<script setup lang="ts">
import { ref, computed } from 'vue'
import StorageStatusTag from './StorageStatusTag.vue'
import type { ChapterStorageItem } from '@/types'

const props = defineProps<{
  comicId: number | null
  comicTitle: string
  chapters: ChapterStorageItem[]
  busyState: Record<number, boolean>
  visible: boolean
}>()

const emit = defineEmits<{
  close: []
  deleteHq: [chapterId: number]
  generateLq: [chapterId: number]
  batchDeleteHq: [chapterIds: number[]]
  batchGenerateLq: [chapterIds: number[]]
}>()

const drawerSelectedIds = ref<number[]>([])

const drawerTotalHq = computed(() => props.chapters.reduce((sum, c) => sum + (c.hqSize || 0), 0))
const drawerTotalLq = computed(() => props.chapters.reduce((sum, c) => sum + (c.lqSize || 0), 0))

const visibleState = computed({
  get: () => props.visible,
  set: (val) => { if (!val) emit('close') }
})

function formatSize(bytes: number | null): string {
  if (bytes == null || bytes === 0) return '—'
  if (bytes < 1024) return bytes + ' B'
  const units = ['KB', 'MB', 'GB', 'TB']
  let i = -1
  let size = bytes
  do {
    size = size / 1024
    i++
  } while (size >= 1024 && i < units.length - 1)
  return size.toFixed(1) + ' ' + units[i]
}

function onDrawerSelectionChange(rows: ChapterStorageItem[]) {
  drawerSelectedIds.value = rows.map(r => r.chapterId)
}
</script>

<template>
  <el-drawer
    v-model="visibleState"
    :title="`${comicTitle} — 存储详情`"
    size="520px"
    destroy-on-close
    @closed="emit('close')"
  >
    <div class="drawer-stats">
      <div class="drawer-stat">
        <span class="drawer-stat-value">{{ formatSize(drawerTotalHq) }}</span>
        <span class="drawer-stat-label">HQ</span>
      </div>
      <div class="drawer-stat">
        <span class="drawer-stat-value">{{ formatSize(drawerTotalLq) }}</span>
        <span class="drawer-stat-label">LQ</span>
      </div>
      <div class="drawer-stat">
        <span class="drawer-stat-value">{{ chapters.length }}</span>
        <span class="drawer-stat-label">章节</span>
      </div>
    </div>

    <el-table
      :data="chapters"
      size="small"
      @selection-change="onDrawerSelectionChange"
    >
      <el-table-column type="selection" width="40" />
      <el-table-column prop="chapterNo" label="编号" width="60" />
      <el-table-column prop="title" label="章节" min-width="100" show-overflow-tooltip />
      <el-table-column label="页数" width="50" align="center">
        <template #default="{ row }">{{ row.pageCount }}</template>
      </el-table-column>
      <el-table-column label="HQ" width="80" align="right">
        <template #default="{ row }">{{ formatSize(row.hqSize) }}</template>
      </el-table-column>
      <el-table-column label="LQ" width="80" align="right">
        <template #default="{ row }">{{ formatSize(row.lqSize) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="160">
        <template #default="{ row }">
          <StorageStatusTag :status="row.hqStatus" type="hq" />
          <StorageStatusTag :status="row.lqStatus" type="lq" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button
            v-if="row.hqStatus === 'READY' || row.hqStatus === 'MIXED'"
            type="danger"
            size="small"
            :disabled="busyState[comicId!]"
            @click="emit('deleteHq', row.chapterId)"
          >
            删HQ
          </el-button>
          <el-button
            v-if="row.lqStatus === 'NOT_GENERATED' || row.lqStatus === 'MIXED'"
            type="primary"
            size="small"
            :disabled="busyState[comicId!]"
            @click="emit('generateLq', row.chapterId)"
          >
            生LQ
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="drawerSelectedIds.length > 0" class="drawer-batch-bar">
      <span>已选 {{ drawerSelectedIds.length }} 章</span>
      <el-button type="danger" size="small" @click="emit('batchDeleteHq', drawerSelectedIds)">
        删除选中 HQ
      </el-button>
      <el-button type="primary" size="small" @click="emit('batchGenerateLq', drawerSelectedIds)">
        生成选中 LQ
      </el-button>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-stats {
  display: flex;
  gap: var(--space-xl);
  margin-bottom: var(--space-base);
  padding-bottom: var(--space-base);
  border-bottom: 1px solid var(--border);
}

.drawer-stat {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.drawer-stat-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}

.drawer-stat-label {
  font-size: 12px;
  color: var(--text-secondary);
}

.drawer-batch-bar {
  display: flex;
  align-items: center;
  gap: var(--space-base);
  padding: var(--space-base) 0;
  border-top: 1px solid var(--border);
  margin-top: var(--space-base);
}
</style>
