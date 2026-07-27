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
      @scan-recover="handleScanRecover"
    />

    <StorageTable
      ref="tableRef"
      :list="pagedList"
      :total="pagination.total"
      :current-page="page"
      :page-size="pageSize"
      :loading="store.loading"
      @update:current-page="page = $event"
      @update:page-size="pageSize = $event"
      @row-click="handleShowDetail"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useStorageStore } from '@/stores/management/storage'
import { useStorageFilter } from '@/composables/storage/useStorageFilter'
import { storageService } from '@/services/storage'
import StorageSummary from './StorageSummary.vue'
import StorageToolbar from './StorageToolbar.vue'
import StorageTable from './StorageTable.vue'

const router = useRouter()
const store = useStorageStore()

const {
  filter: filterState,
  sort: sortState,
  page,
  pageSize,
  pagedList,
  pagination,
  buildQuery,
} = useStorageFilter(() => store.comicList, () => store.serverTotal)

const tableRef = ref<InstanceType<typeof StorageTable> | null>(null)
const scanning = ref(false)

function reload() {
  store.loadComics(buildQuery())
}

watch(
  [() => filterState.value.hqStatus, () => filterState.value.lqStatus, () => filterState.value.keyword, sortState, page, pageSize],
  reload,
)

function handleShowDetail(comicId: number) {
  router.push(`/manage/storage/${comicId}`)
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

onMounted(async () => {
  reload()
  await store.loadSummary()
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
