<template>
  <div class="comic-list-page">
    <header class="page-header">
      <h1 class="page-title">漫画库</h1>
      <div class="toolbar">
        <div class="search-input">
          <el-icon :size="18"><Search /></el-icon>
          <input
            v-model="keyword"
            type="text"
            placeholder="搜索漫画..."
            @input="onKeywordInput"
            @keyup.enter="onSearch"
          >
          <el-icon v-if="keyword" :size="16" class="clear-icon" @click="clearKeyword"><CircleClose /></el-icon>
        </div>

        <div class="filter-select">
          <select v-model="statusFilter" @change="onSearch">
            <option value="">全部状态</option>
            <option value="READY">已就绪</option>
            <option value="IMPORTING">导入中</option>
            <option value="PENDING">等待中</option>
            <option value="FAILED">失败</option>
          </select>
        </div>

        <div class="filter-select">
          <select v-model="sort" @change="onSearch">
            <option value="createdAt">最新添加</option>
            <option value="updatedAt">最近更新</option>
            <option value="title">标题</option>
            <option value="pageCount">页数</option>
            <option value="lastReadTime">最近阅读</option>
          </select>
        </div>

        <div class="filter-select tag-filter">
          <el-select
            v-model="selectedTags"
            multiple
            collapse-tags
            placeholder="筛选标签"
            class="tag-select"
            @change="onSearch"
          >
            <el-option
              v-for="tag in allTags"
              :key="tag.id"
              :label="tag.name"
              :value="tag.name"
            />
          </el-select>
        </div>

        <div class="filter-select tag-mode-filter">
          <select v-model="tagMode" @change="onSearch">
            <option value="OR">任一标签</option>
            <option value="AND">全部标签</option>
          </select>
        </div>
      </div>
    </header>

    <div v-if="store.loading && store.list.length === 0" class="state loading">
      <div class="spinner" />
      <span>加载中...</span>
    </div>

    <div v-else-if="store.error" class="state error">
      <el-icon :size="48"><WarningFilled /></el-icon>
      <span>{{ store.error }}</span>
      <button class="primary-btn" @click="store.fetchList()">重试</button>
    </div>

    <div v-else-if="store.list.length === 0" class="state empty">
      <el-icon :size="48"><PictureFilled /></el-icon>
      <span>暂无漫画</span>
      <p>点击右上角导入按钮添加漫画</p>
    </div>

    <section v-else class="comic-section">
      <div class="comic-grid">
        <ComicPoster
          v-for="comic in store.list"
          :key="comic.id"
          :id="comic.id"
          :cover-url="comic.coverUrl"
          :title="comic.title"
          :subtitle="posterSubtitle(comic)"
          :progress="comic.progressPercent"
          :status="toPosterStatus(comic.status)"
          :size="posterSize"
          @click="goDetail"
          @continue="continueReading"
          @detail="goDetail"
        />
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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { Search, PictureFilled, WarningFilled, CircleClose } from '@element-plus/icons-vue'
import { useComicStore } from '@/stores/comic-store'
import { tagApi } from '@/services/api'
import ComicPoster from '@/components/comic/ComicPoster.vue'
import { toPosterStatus } from '@/components/comic/poster-status'
import type { ComicListQuery, ComicListVO, TagDTO } from '@/types'

const router = useRouter()
const store = useComicStore()

const keyword = ref('')
const statusFilter = ref('')
const sort = ref<NonNullable<ComicListQuery['sort']>>('createdAt')
const selectedTags = ref<string[]>([])
const tagMode = ref<'AND' | 'OR'>('OR')
const allTags = ref<TagDTO[]>([])
const posterSize = ref<'sm' | 'md' | 'lg'>('lg')

let debounceTimer: ReturnType<typeof setTimeout> | null = null

function updatePosterSize() {
  const width = window.innerWidth
  if (width <= 640) {
    posterSize.value = 'sm'
  } else if (width <= 1024) {
    posterSize.value = 'md'
  } else {
    posterSize.value = 'lg'
  }
}

function onKeywordInput() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(onSearch, 300)
}

function clearKeyword() {
  keyword.value = ''
  onSearch()
}

async function loadTags() {
  try {
    const res = await tagApi.list()
    allTags.value = (res.data as TagDTO[]) || []
  } catch (err: unknown) {
    allTags.value = []
  }
}

function onSearch() {
  store.search({
    keyword: keyword.value || undefined,
    status: statusFilter.value || undefined,
    sort: sort.value,
    tags: selectedTags.value.length > 0 ? selectedTags.value : undefined,
    tagMode: selectedTags.value.length > 1 ? tagMode.value : undefined,
  })
}

function onPageChange(page: number) {
  store.updateQuery({ page })
  store.fetchList()
}

function goDetail(id: string | number) {
  router.push(`/comics/${id}`)
}

function continueReading(id: string | number) {
  router.push(`/comics/${id}`)
}

function posterSubtitle(comic: ComicListVO): string {
  if (comic.progressPercent > 0) {
    return `已读 ${comic.progressPercent}%`
  }
  return `${comic.pageCount} 页`
}

onMounted(() => {
  updatePosterSize()
  window.addEventListener('resize', updatePosterSize)
  loadTags()
  store.fetchList()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updatePosterSize)
})
</script>

<style scoped>
.comic-list-page {
  max-width: var(--page-width);
  margin: 0 auto;
}

.page-header {
  position: sticky;
  top: var(--nav-height);
  z-index: 50;
  padding: var(--space-lg) 0 var(--space-base);
  margin-bottom: var(--space-base);
  background: var(--bg-primary);
  border-bottom: 1px solid var(--border);
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-lg);
  letter-spacing: -0.02em;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-base);
  flex-wrap: wrap;
}

.search-input {
  flex: 1;
  min-width: 240px;
  max-width: 400px;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  height: 40px;
  padding: 0 var(--space-base);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  transition: border-color var(--transition-fast);
}

.search-input:focus-within {
  border-color: var(--border-strong);
}

.search-input input {
  flex: 1;
  background: transparent;
  border: none;
  outline: none;
  color: var(--text-primary);
  font-size: 14px;
}

.search-input input::placeholder {
  color: var(--text-muted);
}

.clear-icon {
  cursor: pointer;
  color: var(--text-muted);
  transition: color var(--transition-fast);
}

.clear-icon:hover {
  color: var(--text-primary);
}

.filter-select select {
  height: 40px;
  padding: 0 var(--space-base);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  font-size: 14px;
  outline: none;
  cursor: pointer;
  transition: border-color var(--transition-fast);
}

.filter-select select:focus {
  border-color: var(--border-strong);
}

.tag-filter {
  min-width: 160px;
}

.tag-filter :deep(.el-input__wrapper) {
  background: var(--bg-surface);
  box-shadow: 0 0 0 1px var(--border) inset;
  border-radius: var(--radius-sm);
  min-height: 40px;
}

.tag-filter :deep(.el-input__inner) {
  color: var(--text-primary);
}

.tag-filter :deep(.el-select__tags) {
  color: var(--text-primary);
}

.tag-mode-filter {
  min-width: 110px;
}

.comic-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-xl);
  padding-bottom: var(--space-xl);
}

.comic-grid {
  display: grid;
  gap: var(--poster-gap);
  grid-template-columns: repeat(3, 1fr);
}

.comic-grid :deep(.comic-poster) {
  width: 100%;
}

@media (min-width: 641px) {
  .comic-grid {
    grid-template-columns: repeat(auto-fill, minmax(var(--poster-width-md), 1fr));
  }
}

@media (min-width: 1025px) {
  .comic-grid {
    grid-template-columns: repeat(auto-fill, minmax(var(--poster-width-lg), 1fr));
  }
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
  color: var(--text-secondary);
}

.state.empty p {
  color: var(--text-muted);
  font-size: 13px;
}

.state.error {
  color: var(--danger);
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

.primary-btn {
  padding: var(--space-sm) var(--space-lg);
  background: var(--accent);
  color: var(--text-primary);
  border: none;
  border-radius: var(--radius-sm);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.primary-btn:hover {
  background: var(--accent-hover);
}
</style>
