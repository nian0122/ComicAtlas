<template>
  <div class="manage-comic-list-page">
    <PageHeader spaced title="漫画管理" eyebrow="CATALOG / CONTROL">
      <template #description>共 {{ store.total }} 部漫画</template>
      <div class="header-actions">
        <AppButton variant="primary" @click="router.push('/manage/import')">+ 导入漫画</AppButton>
      </div>
    </PageHeader>

    <StatGrid spaced class="repository-stats" aria-label="仓库统计" :columns="3">
      <StatCard label="已索引漫画" :value="store.total.toLocaleString()" description="来自当前漫画目录" />
      <StatCard
        label="存储池"
        :value="formatBytes(storageTotalBytes)"
        :description="'HQ ' + formatBytes(storageStats?.hqBytes)"
      />
      <StatCard
        label="低画质缓存"
        :value="formatBytes(storageStats?.lqBytes)"
        :description="'缩略图 ' + formatBytes(storageStats?.thumbBytes)"
      />
    </StatGrid>

    <div class="filter-toolbar">
      <el-input
        v-model="filters.keyword"
        placeholder="搜索标题/作者/标签"
        clearable
        class="filter-input"
        @input="scheduleKeywordSearch"
        @keyup.enter="applyKeywordSearchImmediately"
        @clear="applyKeywordSearchImmediately"
      />
      <el-select v-model="filters.category" placeholder="分类" clearable class="filter-select" @change="applyFilters">
        <el-option label="未分类" value="_NONE" />
        <el-option v-for="c in categoryStore.list" :key="c.id" :label="c.name" :value="c.name" />
      </el-select>
      <el-select v-model="filters.status" placeholder="状态" clearable class="filter-select" @change="applyFilters">
        <el-option v-for="s in STATUS_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-select
        v-model="filters.tags"
        multiple
        collapse-tags
        collapse-tags-tooltip
        placeholder="标签"
        clearable
        class="filter-select--wide"
        @change="applyFilters"
      >
        <el-option v-for="t in tagStore.list" :key="t.id" :label="t.name" :value="t.name" />
        <el-option label="无标签" value="_NONE" />
      </el-select>
      <el-select
        v-if="filters.tags.length > 0"
        v-model="filters.tagMode"
        class="filter-select--mini"
        @change="applyFilters"
      >
        <el-option label="任一" value="OR" />
        <el-option label="全部" value="AND" />
        <el-option label="排除" value="NOT" />
      </el-select>
      <el-select v-model="filters.sort" placeholder="排序" class="filter-select" @change="applyFilters">
        <el-option v-for="s in SORT_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-select v-model="filters.order" placeholder="时间顺序" class="filter-select--mini" @change="applyFilters">
        <el-option label="倒序" value="desc" />
        <el-option label="正序" value="asc" />
      </el-select>
      <AppButton variant="text" @click="resetFilters">重置</AppButton>
    </div>

    <div v-if="selectedIds.length > 0" class="batch-toolbar">
      <el-checkbox v-model="selectAll" :indeterminate="isIndeterminate" @change="handleSelectAll">
        全选 ({{ selectedIds.length }} / {{ store.list.length }})
      </el-checkbox>
      <AppButton variant="primary" @click="showBatchDialog = true"> 批量编辑 </AppButton>
    </div>

    <ContentState v-if="store.loading && store.list.length === 0" state="loading" message="加载中..." />

    <ContentState v-else-if="store.error" state="error" :message="store.error">
      <AppButton @click="store.fetchList()">重试</AppButton>
    </ContentState>

    <ContentState v-else-if="store.list.length === 0" state="empty" message="暂无漫画">
      <AppButton variant="primary" @click="router.push('/manage/import')">导入漫画</AppButton>
    </ContentState>

    <section v-else class="comic-table-section">
      <div class="comic-grid">
        <div v-for="comic in store.list" :key="comic.id" class="comic-row" @click="goEdit(comic.id)">
          <el-checkbox
            class="comic-checkbox"
            :model-value="selectedIds.includes(comic.id)"
            @change="() => toggleSelect(comic.id)"
            @click.stop
          />
          <div class="comic-cover">
            <img v-if="comic.coverUrl" :src="comic.coverUrl" alt="" @error="hideBrokenImage" />
          </div>
          <div class="comic-info">
            <h3 class="comic-title">{{ comic.title }}</h3>
            <p class="comic-meta">
              <span>{{ comic.author || '未知作者' }}</span>
              <span>· {{ comic.pageCount }} 页</span>
              <span>· {{ statusLabel(comic.status) }}</span>
            </p>
          </div>
        </div>
      </div>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="store.query.page"
          :page-size="store.query.size"
          :total="store.total"
          layout="prev, pager, next"
          background
          @current-change="onPageChange"
        />
      </div>
    </section>

    <div v-if="storageSummaryError" class="inline-error" role="alert">
      <span>{{ storageSummaryError }}</span>
      <AppButton :disabled="storageSummaryLoading" @click="loadStorageSummary">
        {{ storageSummaryLoading ? '重试中...' : '重试' }}
      </AppButton>
    </div>

    <BatchEditDialog v-model:visible="showBatchDialog" :comic-ids="selectedIds" @saved="onBatchSaved" />
  </div>
</template>

<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'
import { ContentState } from '@/shared/ui/content-state'
import { StatGrid } from '@/shared/ui/management-panel'
import { StatCard } from '@/shared/ui/management-panel'
import { PageHeader } from '@/shared/ui/page-header'
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useManagementComicStore } from '@/pages/management/comics/model/management-comic-store'
import { useCategoryStore } from '@/features/category'
import { useTagStore } from '@/features/tag'
import { BatchEditDialog } from '@/features/comic-batch-edit'
import type { StorageStats } from '@/entities/storage'
import { storageService } from '@/features/storage'
import { COMIC_STATUSES, comicStatusMeta } from '@/entities/comic'
import { useManagementComicFilters } from '@/pages/management/comics/model/useManagementComicFilters'

const router = useRouter()
const store = useManagementComicStore()
const categoryStore = useCategoryStore()
const tagStore = useTagStore()
const storageStats = ref<StorageStats | null>(null)
const storageSummaryError = ref<string | null>(null)
const storageSummaryLoading = ref(false)
const storageTotalBytes = computed(() => {
  if (!storageStats.value) return undefined
  return storageStats.value.hqBytes + storageStats.value.lqBytes + storageStats.value.thumbBytes
})

function hideBrokenImage(event: Event) {
  const image = event.currentTarget as HTMLImageElement
  image.hidden = true
}

const STATUS_OPTIONS = COMIC_STATUSES.map((value) => ({
  label: comicStatusMeta(value).label,
  value,
}))

const SORT_OPTIONS = [
  { label: '创建时间', value: 'createdAt' },
  { label: '更新时间', value: 'updatedAt' },
  { label: '标题', value: 'title' },
  { label: '页数', value: 'pageCount' },
  { label: '上次阅读', value: 'lastReadTime' },
]

const selectedIds = ref<number[]>([])
const showBatchDialog = ref(false)

const {
  filters,
  applyFilters,
  scheduleKeywordSearch,
  applyKeywordSearchImmediately,
  resetFilters,
  restoreFiltersFromStore,
} = useManagementComicFilters(store, () => {
  selectedIds.value = []
})

const selectAll = computed(() => store.list.length > 0 && selectedIds.value.length === store.list.length)
const isIndeterminate = computed(() => selectedIds.value.length > 0 && selectedIds.value.length < store.list.length)

function toggleSelect(id: number) {
  const idx = selectedIds.value.indexOf(id)
  if (idx >= 0) {
    selectedIds.value.splice(idx, 1)
  } else {
    selectedIds.value.push(id)
  }
}

function handleSelectAll(val: string | number | boolean) {
  if (val) {
    selectedIds.value = store.list.map((c) => c.id)
  } else {
    selectedIds.value = []
  }
}

function onBatchSaved() {
  selectedIds.value = []
  showBatchDialog.value = false
  store.fetchList()
}

function statusLabel(s: string) {
  const knownStatus = COMIC_STATUSES.find((value) => value === s)
  return knownStatus ? comicStatusMeta(knownStatus).label : s
}

function goEdit(id: number) {
  router.push(`/manage/comics/${id}?tab=edit`)
}

function onPageChange(page: number) {
  selectedIds.value = []
  store.updateQuery({ page })
  store.fetchList()
}

onMounted(() => {
  restoreFiltersFromStore()
  categoryStore.fetchList()
  tagStore.fetchList()
  store.fetchList()
  void loadStorageSummary()
})

async function loadStorageSummary(): Promise<void> {
  storageSummaryLoading.value = true
  storageSummaryError.value = null
  try {
    storageStats.value = await storageService.fetchSummary()
  } catch (error: unknown) {
    storageStats.value = null
    storageSummaryError.value = error instanceof Error && error.message ? error.message : '存储统计加载失败，请重试'
  } finally {
    storageSummaryLoading.value = false
  }
}

function formatBytes(bytes: number | undefined): string {
  if (!bytes) return '0 B'
  if (bytes >= 1024 ** 4) return `${(bytes / 1024 ** 4).toFixed(1)} TB`
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`
}
</script>

<style scoped>
.manage-comic-list-page {
  width: 100%;
  max-width: none;
}

.header-actions {
  display: flex;
  gap: var(--space-sm);
}

.filter-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-lg);
  flex-wrap: wrap;
}

.filter-input {
  width: 180px;
}

.filter-select {
  width: 108px;
}

.filter-select--wide {
  width: 162px;
}

.filter-select--mini {
  width: 80px;
}

.filter-toolbar > :deep(.filter-input .el-input__wrapper),
.filter-toolbar > :deep(.filter-select .el-select__wrapper),
.filter-toolbar > :deep(.filter-select--wide .el-select__wrapper),
.filter-toolbar > :deep(.filter-select--mini .el-select__wrapper) {
  min-height: 36px;
  border-radius: var(--radius-sm);
}

.comic-grid {
  display: flex;
  flex-direction: column;
  gap: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  margin-bottom: var(--space-xl);
}

.comic-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-base);
  min-height: 82px;
  padding: var(--space-3) var(--space-5);
  background: var(--bg-primary);
  border-bottom: 1px solid var(--border);
  cursor: pointer;
  transition: background-color var(--transition-fast);
}

.comic-row:hover {
  background: var(--bg-surface);
  box-shadow: inset 2px 0 var(--color-brand);
}

.comic-row:last-child {
  border-bottom: 0;
}

.comic-cover {
  position: relative;
  width: 40px;
  height: 60px;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--bg-secondary);
}

.comic-cover::before {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  content: 'CA';
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 800;
}

.comic-cover img {
  position: relative;
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.comic-cover img[hidden] {
  display: none;
}

.comic-info {
  flex: 1;
  min-width: 0;
}

.comic-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 var(--space-xs);
  display: -webkit-box;
  overflow: hidden;
  line-break: strict;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.comic-meta {
  font-size: 12px;
  color: var(--text-secondary);
  margin: 0;
}

.comic-meta span + span {
  margin-left: 6px;
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  padding: var(--space-lg) 0;
}

.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-base);
  padding: var(--space-3xl) 0;
  text-align: center;
}

.inline-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
  padding: var(--space-md);
  color: var(--text-primary);
  background: var(--bg-surface);
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
}

.state.loading {
  color: var(--text-secondary);
}

.state.error {
  color: var(--danger);
}

.state.empty {
  color: var(--text-muted);
}
</style>
