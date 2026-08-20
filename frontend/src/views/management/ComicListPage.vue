<template>
  <div class="manage-comic-list-page">
    <header class="page-header">
      <div class="header-left">
        <p class="page-eyebrow">CATALOG / CONTROL</p>
        <h1 class="page-title">漫画管理</h1>
        <p class="page-subtitle">共 {{ store.total }} 部漫画</p>
      </div>
      <div class="header-actions">
        <button class="primary-btn" @click="router.push('/manage/import')">+ 导入漫画</button>
      </div>
    </header>

    <section class="repository-stats" aria-label="仓库统计">
      <article class="repository-stat repository-stat--wide">
        <span>已索引漫画</span>
        <strong>{{ store.total.toLocaleString() }}</strong>
        <small>来自当前漫画目录</small>
      </article>
      <article class="repository-stat">
        <span>存储池</span>
        <strong>{{ formatBytes(storageTotalBytes) }}</strong>
        <small>HQ {{ formatBytes(storageStats?.hqBytes) }}</small>
      </article>
      <article class="repository-stat">
        <span>低画质缓存</span>
        <strong>{{ formatBytes(storageStats?.lqBytes) }}</strong>
        <small>缩略图 {{ formatBytes(storageStats?.thumbBytes) }}</small>
      </article>
    </section>

    <div class="filter-toolbar">
      <el-input
        v-model="filters.keyword"
        placeholder="搜索标题/作者/标签"
        clearable
        class="filter-input"
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      />
      <el-select v-model="filters.category" placeholder="分类" clearable class="filter-select" @change="applyFilters">
        <el-option label="未分类" value="_NONE" />
        <el-option
          v-for="c in categoryStore.list"
          :key="c.id"
          :label="c.name"
          :value="c.name"
        />
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
        <el-option
          v-for="t in tagStore.list"
          :key="t.id"
          :label="t.name"
          :value="t.name"
        />
        <el-option label="无标签" value="_NONE" />
      </el-select>
      <el-select v-if="filters.tags.length > 0" v-model="filters.tagMode" class="filter-select--mini" @change="applyFilters">
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
      <el-button text @click="resetFilters">重置</el-button>
    </div>

    <div v-if="selectedIds.length > 0" class="batch-toolbar">
      <el-checkbox
        v-model="selectAll"
        :indeterminate="isIndeterminate"
        @change="handleSelectAll"
      >
        全选 ({{ selectedIds.length }} / {{ store.list.length }})
      </el-checkbox>
      <el-button type="primary" @click="showBatchDialog = true">
        批量编辑
      </el-button>
    </div>

    <div v-if="store.loading && store.list.length === 0" class="state loading">
      <div class="spinner" />
      <span>加载中...</span>
    </div>

    <div v-else-if="store.error" class="state error">
      <el-icon :size="32"><WarningFilled /></el-icon>
      <span>{{ store.error }}</span>
      <button class="ghost-btn" @click="store.fetchList()">重试</button>
    </div>

    <div v-else-if="store.list.length === 0" class="state empty">
      <el-icon :size="48"><PictureFilled /></el-icon>
      <span>暂无漫画</span>
      <button class="primary-btn" @click="router.push('/manage/import')">导入漫画</button>
    </div>

    <section v-else class="comic-table-section">
      <div class="comic-grid">
        <div
          v-for="comic in store.list"
          :key="comic.id"
          class="comic-row"
          @click="goEdit(comic.id)"
        >
          <el-checkbox
            class="comic-checkbox"
            :model-value="selectedIds.includes(comic.id)"
            @change="() => toggleSelect(comic.id)"
            @click.stop
          />
          <div class="comic-cover">
            <img
              v-if="comic.coverUrl"
              :src="comic.coverUrl"
              alt=""
              @error="hideBrokenImage"
            >
          </div>
          <div class="comic-info">
            <h3 class="comic-title">{{ comic.title }}</h3>
            <p class="comic-meta">
              <span>{{ comic.author || '未知作者' }}</span>
              <span>· {{ comic.pageCount }} 页</span>
              <span>· {{ statusLabel(comic.status) }}</span>
            </p>
          </div>
          <div class="comic-actions">
            <button class="action-btn" @click.stop="goStorage(comic.id)">存储</button>
            <button class="action-btn" @click.stop="goEdit(comic.id)">编辑</button>
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

    <BatchEditDialog
      v-model:visible="showBatchDialog"
      :comic-ids="selectedIds"
      @saved="onBatchSaved"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { PictureFilled, WarningFilled } from '@element-plus/icons-vue'
import { useManagementComicStore } from '@/stores/management/comic'
import { useCategoryStore } from '@/stores/management/category'
import { useTagStore } from '@/stores/tag-store'
import BatchEditDialog from './BatchEditDialog.vue'
import type { ComicListQuery, StorageStats } from '@/types'
import { storageService } from '@/services/storage'
import { COMIC_STATUSES, comicStatusMeta } from '@/utils/comic-status'

const router = useRouter()
const store = useManagementComicStore()
const categoryStore = useCategoryStore()
const tagStore = useTagStore()
const storageStats = ref<StorageStats | null>(null)
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

const filters = reactive({
  keyword: '',
  category: '',
  status: '',
  tags: [] as string[],
  tagMode: 'OR' as 'AND' | 'OR' | 'NOT',
  sort: 'createdAt',
  order: 'desc' as 'asc' | 'desc',
})

const selectedIds = ref<number[]>([])
const showBatchDialog = ref(false)

const selectAll = computed(() =>
  store.list.length > 0 && selectedIds.value.length === store.list.length
)
const isIndeterminate = computed(() =>
  selectedIds.value.length > 0 && selectedIds.value.length < store.list.length
)

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
    selectedIds.value = store.list.map(c => c.id)
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

function goStorage(id: number) {
  router.push(`/manage/comics/${id}?tab=storage`)
}

watch(() => filters.tags, (val) => {
  if (val.includes('_NONE') && val.length > 1) {
    filters.tags = ['_NONE']
  }
  if (val.includes('_NONE') && filters.tagMode === 'NOT') {
    filters.tagMode = 'OR'
  }
  if (val.length === 0 && filters.tagMode !== 'OR') {
    filters.tagMode = 'OR'
  }
}, { deep: true })

function applyFilters() {
  const normalizedTags = filters.tags.includes('_NONE') ? ['_NONE'] : [...filters.tags]
  filters.tags = normalizedTags
  if (normalizedTags.length === 0 || normalizedTags.includes('_NONE')) {
    filters.tagMode = 'OR'
  }
  selectedIds.value = []
  store.search({
    keyword: filters.keyword || undefined,
    category: filters.category || undefined,
    status: filters.status || undefined,
    tags: normalizedTags.length > 0 ? normalizedTags : undefined,
    tagMode: filters.tagMode,
    sort: filters.sort as ComicListQuery['sort'],
    order: filters.order,
  })
}

function resetFilters() {
  filters.keyword = ''
  filters.category = ''
  filters.status = ''
  filters.tags = []
  filters.tagMode = 'OR'
  filters.sort = 'createdAt'
  filters.order = 'desc'
  selectedIds.value = []
  store.resetQuery()
  store.fetchList()
}

function restoreFiltersFromStore() {
  filters.keyword = store.query.keyword || ''
  filters.category = store.query.category || ''
  filters.status = store.query.status || ''
  filters.tags = [...(store.query.tags || [])]
  filters.tagMode = store.query.tagMode === 'AND' || store.query.tagMode === 'NOT' ? store.query.tagMode : 'OR'
  filters.sort = store.query.sort || 'createdAt'
  filters.order = store.query.order || 'desc'
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
  storageService.fetchSummary().then((stats) => { storageStats.value = stats }).catch(() => { storageStats.value = null })
})

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

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--space-2xl);
  gap: var(--space-base);
  flex-wrap: wrap;
}

.header-left {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.page-eyebrow {
  margin-bottom: var(--space-1);
  color: var(--accent);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.page-title {
  font-size: var(--text-page);
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.page-subtitle {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0;
}

.header-actions {
  display: flex;
  gap: var(--space-sm);
}

.primary-btn {
  padding: 8px 16px;
  background: var(--accent);
  color: var(--text-primary);
  border: none;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.primary-btn:hover {
  background: var(--accent-hover);
}

.ghost-btn {
  padding: 8px 16px;
  background: transparent;
  color: var(--text-primary);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.ghost-btn:hover {
  background: var(--bg-surface);
  border-color: var(--text-muted);
}

.filter-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-lg);
  flex-wrap: wrap;
}

.filter-input {
  width: 200px;
}

.filter-select {
  width: 120px;
}

.filter-select--wide {
  width: 180px;
}

.filter-select--mini {
  width: 90px;
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

.repository-stats {
  display: grid;
  grid-template-columns: 1.5fr repeat(2, minmax(180px, 1fr));
  gap: var(--space-4);
  margin-bottom: var(--space-8);
}

.repository-stat {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-height: 128px;
  padding: var(--space-5);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
}

.repository-stat span,
.repository-stat small { color: var(--text-muted); font-size: var(--text-sm); }
.repository-stat strong { color: var(--text-primary); font-size: clamp(1.75rem, 3vw, 2.5rem); font-variant-numeric: tabular-nums; }

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
  content: "CA";
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

.comic-actions {
  flex-shrink: 0;
}

.action-btn {
  min-height: 36px;
  padding: 6px 12px;
  background: var(--bg-surface);
  color: var(--text-secondary);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.action-btn:hover {
  border-color: var(--text-muted);
  background: var(--surface-highlight);
  color: var(--text-primary);
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

.state.loading {
  color: var(--text-secondary);
}

.state.error {
  color: var(--danger);
}

.state.empty {
  color: var(--text-muted);
}

.spinner {
  width: 40px;
  height: 40px;
  border: 3px solid var(--border-strong);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
