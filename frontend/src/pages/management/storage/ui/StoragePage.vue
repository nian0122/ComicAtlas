<template>
  <div class="storage-page">
    <PageHeader title="存储统计" description="全库容量概览；筛选仅影响下方漫画存储记录。">
      <div class="page-actions">
        <span class="comic-count">{{ store.summary?.comicCount ?? '—' }} 本库内漫画</span>
        <AppButton :loading="isRequestingRefresh" :disabled="isScanning" @click="refreshStatistics">{{
          isScanning ? '容量核对中' : '刷新统计'
        }}</AppButton>
      </div>
    </PageHeader>

    <StorageSummary :stats="store.summary" :loading="store.summaryLoading" :error="store.summaryError" />

    <StorageToolbar
      v-model:filter="filterState"
      v-model:sort="sortState"
      :total="store.serverTotal"
      :loading="store.loading"
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
import { AppButton } from '@/shared/ui/button'
import { PageHeader } from '@/shared/ui/page-header'
import { watch, onMounted, onBeforeUnmount, nextTick, computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQuery } from 'vue-router'
import type { ComicStorageQuery } from '@/entities/storage'
import { useStorageStore } from '@/features/storage'
import { useStorageFilter } from '@/features/storage'
import StorageSummary from './StorageSummary.vue'
import StorageToolbar from './StorageToolbar.vue'
import StorageTable from './StorageTable.vue'

const route = useRoute()
const router = useRouter()
const store = useStorageStore()
const isRequestingRefresh = ref(false)
const isScanning = computed(() => ['PENDING', 'RUNNING'].includes(store.summary?.refreshStatus ?? ''))
let summaryPollTimer: ReturnType<typeof setTimeout> | undefined
let isPageDisposed = false

function scheduleSummaryPoll() {
  if (summaryPollTimer) clearTimeout(summaryPollTimer)
  if (!isScanning.value || isPageDisposed) return
  summaryPollTimer = setTimeout(async () => {
    if (document.visibilityState === 'visible') await store.loadSummary()
    scheduleSummaryPoll()
  }, 2000)
}
watch(isScanning, scheduleSummaryPoll)
onBeforeUnmount(() => {
  isPageDisposed = true
  if (summaryPollTimer) clearTimeout(summaryPollTimer)
})

function queryString(value: LocationQuery[string]): string | undefined {
  return typeof value === 'string' ? value : undefined
}

function positiveInteger(value: LocationQuery[string]): number | undefined {
  const parsedValue = Number(queryString(value))
  return Number.isSafeInteger(parsedValue) && parsedValue > 0 ? parsedValue : undefined
}

function parseStorageQuery(query: LocationQuery): ComicStorageQuery {
  const hqStatusValue = queryString(query.hqStatus)
  const lqStatusValue = queryString(query.lqStatus)
  const sortValue = queryString(query.sort)
  const orderValue = queryString(query.order)
  const pageSizeValue = positiveInteger(query.size)

  return {
    page: positiveInteger(query.page),
    size: pageSizeValue && [10, 20, 50, 100].includes(pageSizeValue) ? pageSizeValue : undefined,
    hqStatus: ['ALL', 'HAS_HQ', 'NO_HQ'].includes(hqStatusValue ?? '')
      ? (hqStatusValue as ComicStorageQuery['hqStatus'])
      : undefined,
    lqStatus: ['ALL', 'NEEDS_LQ', 'READY'].includes(lqStatusValue ?? '')
      ? (lqStatusValue as ComicStorageQuery['lqStatus'])
      : undefined,
    sort: ['totalSize', 'hqSize', 'lqSize', 'title'].includes(sortValue ?? '')
      ? (sortValue as ComicStorageQuery['sort'])
      : undefined,
    order: ['asc', 'desc'].includes(orderValue ?? '') ? (orderValue as ComicStorageQuery['order']) : undefined,
    keyword: queryString(query.keyword),
    category: queryString(query.category),
    tag: queryString(query.tag),
  }
}

const initialQuery = parseStorageQuery(route.query)

const {
  filter: filterState,
  sort: sortState,
  page,
  pageSize,
  pagedList,
  pagination,
  buildQuery,
} = useStorageFilter(
  () => store.comicList,
  () => store.serverTotal,
  initialQuery,
)

function reload() {
  void store.loadComics(buildQuery())
}

async function refreshStatistics() {
  isRequestingRefresh.value = true
  try {
    await Promise.all([store.refreshStatistics(), store.loadComics(buildQuery())])
  } finally {
    isRequestingRefresh.value = false
  }
}

const storageQueryKeys = [
  'page',
  'size',
  'hqStatus',
  'lqStatus',
  'sort',
  'order',
  'keyword',
  'category',
  'tag',
] as const
let isRestoringQuery = false

function syncQueryAndReload() {
  reload()
  if (isRestoringQuery) return

  const query = { ...route.query }
  storageQueryKeys.forEach((key) => delete query[key])
  Object.entries(buildQuery()).forEach(([key, value]) => {
    if (value !== undefined) query[key] = String(value)
  })
  void router.replace({ query })
}

watch(
  () => route.query,
  (query) => {
    const restoredQuery = parseStorageQuery(query)
    const nextFilter = {
      hqStatus: restoredQuery.hqStatus ?? 'ALL',
      lqStatus: restoredQuery.lqStatus ?? 'ALL',
      keyword: restoredQuery.keyword ?? '',
      category: restoredQuery.category ?? '',
      tag: restoredQuery.tag ?? '',
    }
    const nextSort = {
      field: restoredQuery.sort ?? 'totalSize',
      order: restoredQuery.order ?? 'desc',
    }
    if (
      JSON.stringify(filterState.value) === JSON.stringify(nextFilter) &&
      JSON.stringify(sortState.value) === JSON.stringify(nextSort) &&
      page.value === (restoredQuery.page ?? 1) &&
      pageSize.value === (restoredQuery.size ?? 20)
    ) {
      return
    }

    isRestoringQuery = true
    filterState.value = nextFilter
    sortState.value = nextSort
    pageSize.value = restoredQuery.size ?? 20
    void nextTick(() => {
      page.value = restoredQuery.page ?? 1
      void nextTick(() => {
        isRestoringQuery = false
        reload()
      })
    })
  },
)

watch(
  [
    () => filterState.value.hqStatus,
    () => filterState.value.lqStatus,
    () => filterState.value.keyword,
    () => filterState.value.category,
    () => filterState.value.tag,
    () => sortState.value.field,
    () => sortState.value.order,
    page,
    pageSize,
  ],
  syncQueryAndReload,
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
  display: grid;
  gap: var(--space-4);
  width: 100%;
  min-width: 0;
}
.page-actions {
  display: flex;
  align-items: center;
  gap: var(--space-base);
}
.comic-count {
  color: var(--text-secondary);
  font-size: var(--text-sm);
  white-space: nowrap;
}

@media (max-width: 720px) {
  .page-actions {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
