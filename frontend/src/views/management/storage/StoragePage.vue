<template>
  <div class="storage-page">
    <header class="page-header">
      <div>
        <span class="page-kicker">COMIC / STORAGE</span>
        <h1 class="page-title">存储统计</h1>
        <p class="page-description">查看 HQ、LQ 与缩略图的占用分布，并定位需要处理的漫画。</p>
      </div>
      <div class="page-actions">
        <span class="comic-count">{{ store.serverTotal }} 本漫画</span>
        <el-button :loading="store.loading" @click="reload">刷新统计</el-button>
      </div>
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
  </div>
</template>

<script setup lang="ts">
import { watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useStorageStore } from '@/features/storage/store'
import { useStorageFilter } from '@/features/storage/composables/useStorageFilter'
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
  void store.loadComics(buildQuery())
}

watch(
  [() => filterState.value.hqStatus, () => filterState.value.lqStatus, () => filterState.value.keyword, () => filterState.value.category, () => filterState.value.tag, () => sortState.value.field, () => sortState.value.order, page, pageSize],
  reload,
)

function handleShowDetail(comicId: number) {
  router.push(`/manage/comics/${comicId}?tab=storage`)
}

onMounted(async () => {
  reload()
  await store.loadSummary()
})
</script>

<style scoped>
.storage-page {
  max-width: 1440px;
}

.page-header { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--space-xl); margin-bottom: var(--space-xl); padding-bottom: var(--space-lg); border-bottom: 1px solid var(--border); }
.page-kicker { color: var(--accent); font: 800 10px var(--mono); letter-spacing: .16em; }
.page-description { margin: var(--space-sm) 0 0; color: var(--text-muted); font-size: var(--text-sm); }
.page-actions { display: flex; align-items: center; gap: var(--space-base); }
.comic-count { color: var(--text-secondary); font: 700 11px var(--mono); white-space: nowrap; }

.page-title {
  margin: var(--space-sm) 0 0;
  font-size: clamp(2rem, 3vw, 2.7rem);
  font-family: Georgia, 'Times New Roman', serif;
  font-weight: 700;
  color: var(--text-primary);
}

@media (max-width: 720px) { .page-header { align-items: flex-start; flex-direction: column; } .page-actions { width: 100%; justify-content: space-between; } }
</style>
