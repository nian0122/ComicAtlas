<template>
  <div class="storage-page">
    <header class="page-header">
      <h1 class="page-title">存储管理</h1>
    </header>

    <StorageSummary :stats="store.summary" />

    <StorageToolbar
      v-model:filter="filterState"
      v-model:sort="sortState"
    />

    <StorageTable
      :list="pagedList"
      :total="pagination.total"
      :current-page="page"
      :page-size="pageSize"
      :loading="store.loading"
      @update:current-page="page = $event"
      @update:page-size="pageSize = $event"
      @row-click="handleShowDetail"
    />
    <div ref="storageSentinel" class="infinite-sentinel" aria-live="polite">
      <span v-if="infiniteLoading">正在加载更多...</span>
      <span v-else-if="!infiniteHasMore">已加载全部漫画</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useStorageStore } from '@/stores/management/storage'
import { useStorageFilter } from '@/composables/storage/useStorageFilter'
import { useInfiniteScroll } from '@/composables/useInfiniteScroll'
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

function reload() {
  store.loadComics(buildQuery())
}

const { sentinel: storageSentinel, loading: infiniteLoading, hasMore: infiniteHasMore } = useInfiniteScroll({
  loadMore: async () => {
    if (store.loading || !store.hasMore) return false
    page.value += 1
    await store.loadComics(buildQuery(), true)
    return store.hasMore
  },
})
void storageSentinel

watch(
  [() => filterState.value.hqStatus, () => filterState.value.lqStatus, () => filterState.value.keyword, sortState, pageSize],
  reload,
)

function handleShowDetail(comicId: number) {
  router.push(`/manage/storage/${comicId}`)
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
