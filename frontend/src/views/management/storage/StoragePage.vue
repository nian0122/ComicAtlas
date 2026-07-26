<template>
  <div class="storage-page">
    <header class="page-header">
      <h1 class="page-title">存储管理</h1>
    </header>

    <StorageSummary :stats="store.summary" />

    <StorageToolbar
      v-model:filter="filterState"
      v-model:sort="sortState"
      :scanning="scanning"
      :rebuilding="rebuilding"
      @scan-recover="handleScanRecover"
      @rebuild="handleRebuild"
    />

    <StorageBatchBar
      v-if="hasSelection"
      :count="selectionCount"
      @delete-hq="handleBatchDeleteHQ"
      @generate-lq="handleBatchGenerateLQ"
    />

    <StorageTable
      ref="tableRef"
      :list="pagedList"
      :total="pagination.total"
      :current-page="page"
      :page-size="pageSize"
      :busy-state="store.busyState"
      :loading="store.loading"
      :selected-ids="selectedIds"
      :highlighted-id="highlightedId"
      @update:selected-ids="selectedIds = $event"
      @update:current-page="page = $event"
      @update:page-size="pageSize = $event"
      @delete-hq="handleDeleteHQ"
      @generate-lq="handleGenerateLQ"
      @transcode-videos="handleTranscodeVideos"
      @export-zip="handleExportZip"
      @show-chapters="handleShowChapters"
    />

    <StorageChapterDrawer
      :visible="drawerVisible"
      :comic-id="drawerComicId"
      :comic-title="drawerTitle"
      :chapters="store.chapters[drawerComicId ?? 0] ?? []"
      :busy-state="store.busyState"
      @close="drawerVisible = false"
      @delete-hq="handleDeleteHQChapter"
      @generate-lq="handleGenerateLQChapter"
      @batch-delete-hq="handleBatchDeleteDrawerHq"
      @batch-generate-lq="handleBatchGenerateDrawerLq"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useStorageStore } from '@/stores/management/storage'
import { useStorageFilter } from '@/composables/storage/useStorageFilter'
import { useStorageSelection } from '@/composables/storage/useStorageSelection'
import { useStoragePolling } from '@/composables/storage/useStoragePolling'
import { storageService, exportService } from '@/services/storage'
import { StorageOperationType } from '@/types'
import type { StorageOperation } from '@/types'
import StorageSummary from './StorageSummary.vue'
import StorageToolbar from './StorageToolbar.vue'
import StorageBatchBar from './StorageBatchBar.vue'
import StorageTable from './StorageTable.vue'
import StorageChapterDrawer from './StorageChapterDrawer.vue'

const route = useRoute()
const store = useStorageStore()

const {
  filter: filterState,
  sort: sortState,
  page,
  pageSize,
  filteredList,
  pagedList,
  pagination,
  buildQuery,
} = useStorageFilter(() => store.comicList, () => store.serverTotal)

const { selectedIds, hasSelection, count: selectionCount, clear: selectionClear } =
  useStorageSelection(() => filteredList.value)

const polling = useStoragePolling(store)
const tableRef = ref<InstanceType<typeof StorageTable> | null>(null)

const scanning = ref(false)
const rebuilding = ref(false)
const highlightedId = ref<number | null>(null)
const drawerVisible = ref(false)
const drawerComicId = ref<number | null>(null)
const drawerTitle = ref('')

function reload() {
  store.loadComics(buildQuery())
}

watch(
  [() => filterState.hqStatus, () => filterState.lqStatus, () => filterState.keyword, sortState, page, pageSize],
  reload,
  { deep: true },
)

async function executeAndPoll(op: StorageOperation) {
  await store.executeOperation(op)
  polling.start(op.comicId, op.type)
}

async function handleDeleteHQ(comicId: number) {
  try {
    await ElMessageBox.confirm('确认删除该漫画的 HQ 原图？', '删除 HQ', { type: 'warning' })
  } catch { return }
  await executeAndPoll({ type: StorageOperationType.DeleteHQ, comicId })
  ElMessage.success('HQ 删除任务已提交')
}

async function handleGenerateLQ(comicId: number) {
  try {
    await ElMessageBox.confirm('确认为该漫画生成 LQ？', '生成 LQ', { type: 'info' })
  } catch { return }
  await executeAndPoll({ type: StorageOperationType.GenerateLQ, comicId })
  ElMessage.success('LQ 生成任务已提交')
}

async function handleTranscodeVideos(comicId: number) {
  try {
    await ElMessageBox.confirm('确认为该漫画的视频进行转码？', '视频转码', { type: 'info' })
  } catch { return }
  try {
    await storageService.transcodeVideos(comicId)
    ElMessage.success('视频转码任务已提交')
    polling.start(comicId, StorageOperationType.TranscodeVideos)
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '转码失败'
    ElMessage.error(message)
  }
}

async function handleExportZip(comicId: number) {
  try {
    await exportService.createExport(comicId)
    ElMessage.success('导出任务已提交，请在导出管理中查看')
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '导出失败'
    ElMessage.error(message)
  }
}

async function handleDeleteHQChapter(chapterId: number) {
  try {
    await ElMessageBox.confirm('确认删除该章节的 HQ？', '删除 HQ', { type: 'warning' })
  } catch { return }
  const comicId = drawerComicId.value!
  await executeAndPoll({ type: StorageOperationType.DeleteHQ, comicId, chapterId })
  ElMessage.success('HQ 删除任务已提交')
  await store.loadChapters(comicId)
}

async function handleGenerateLQChapter(chapterId: number) {
  try {
    await ElMessageBox.confirm('确认为该章节生成 LQ？', '生成 LQ', { type: 'info' })
  } catch { return }
  const comicId = drawerComicId.value!
  await executeAndPoll({ type: StorageOperationType.GenerateLQ, comicId, chapterId })
  ElMessage.success('LQ 生成任务已提交')
  await store.loadChapters(comicId)
}

async function handleBatchDeleteHQ() {
  if (selectedIds.value.length === 0) return
  try {
    await ElMessageBox.confirm(`确认删除 ${selectedIds.value.length} 部漫画的 HQ？`, '批量删除 HQ', { type: 'warning' })
  } catch { return }
  for (const id of selectedIds.value) {
    try {
      await executeAndPoll({ type: StorageOperationType.DeleteHQ, comicId: id })
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : '未知错误'
      ElMessage.warning(`ID:${id} — ${message}`)
    }
  }
  ElMessage.success('批量 HQ 删除任务已提交')
  selectionClear()
  tableRef.value?.clearSelection()
}

async function handleBatchGenerateLQ() {
  if (selectedIds.value.length === 0) return
  try {
    await ElMessageBox.confirm(`确认为 ${selectedIds.value.length} 部漫画生成 LQ？`, '批量生成 LQ', { type: 'info' })
  } catch { return }
  for (const id of selectedIds.value) {
    try {
      await executeAndPoll({ type: StorageOperationType.GenerateLQ, comicId: id })
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : '未知错误'
      ElMessage.warning(`ID:${id} — ${message}`)
    }
  }
  ElMessage.success('批量 LQ 生成任务已提交')
  selectionClear()
  tableRef.value?.clearSelection()
}

async function handleBatchDeleteDrawerHq(chapterIds: number[]) {
  try {
    await ElMessageBox.confirm(`确认删除 ${chapterIds.length} 章的 HQ？`, '批量删除 HQ', { type: 'warning' })
  } catch { return }
  const comicId = drawerComicId.value!
  for (const chapterId of chapterIds) {
    await executeAndPoll({ type: StorageOperationType.DeleteHQ, comicId, chapterId })
  }
  ElMessage.success('批量 HQ 删除任务已提交')
  await store.loadChapters(comicId)
}

async function handleBatchGenerateDrawerLq(chapterIds: number[]) {
  try {
    await ElMessageBox.confirm(`确认为 ${chapterIds.length} 章生成 LQ？`, '批量生成 LQ', { type: 'info' })
  } catch { return }
  const comicId = drawerComicId.value!
  for (const chapterId of chapterIds) {
    await executeAndPoll({ type: StorageOperationType.GenerateLQ, comicId, chapterId })
  }
  ElMessage.success('批量 LQ 生成任务已提交')
  await store.loadChapters(comicId)
}

async function handleShowChapters(comicId: number) {
  const comic = store.comicList.find((c) => c.comicId === comicId)
  drawerComicId.value = comicId
  drawerTitle.value = comic?.title ?? ''
  drawerVisible.value = true
  await store.loadChapters(comicId)
}

async function handleScanRecover() {
  scanning.value = true
  try {
    await storageService.scanRecover()
    ElMessage.success('扫描完成')
    reload()
    await store.loadSummary()
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '扫描失败'
    ElMessage.error(message)
  } finally {
    scanning.value = false
  }
}

async function handleRebuild() {
  rebuilding.value = true
  try {
    await storageService.rebuild()
    ElMessage.success('重建完成')
    reload()
    await store.loadSummary()
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '重建失败'
    ElMessage.error(message)
  } finally {
    rebuilding.value = false
  }
}

onMounted(async () => {
  reload()
  await store.loadSummary()

  const highlight = route.query.highlight
  if (highlight) {
    highlightedId.value = Number(highlight)
    setTimeout(() => { highlightedId.value = null }, 3000)
  }
})

onUnmounted(() => {
  polling.stopAll()
})
</script>

<style scoped>
.storage-page {
  max-width: 1200px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-xl);
}
</style>
