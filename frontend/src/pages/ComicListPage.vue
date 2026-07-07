<template>
  <div class="comic-list-page">
    <header class="page-header">
      <h1 class="page-title">漫画库</h1>
      <div class="search-bar">
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
        <ComicCard
          v-for="comic in store.list"
          :key="comic.id"
          :comic="comic"
          @click="goDetail"
          @continue="continueReading"
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
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search, PictureFilled, WarningFilled, CircleClose } from '@element-plus/icons-vue'
import { useComicStore } from '@/stores/comic-store'
import ComicCard from '@/components/comic/ComicCard.vue'
import type { ComicListQuery } from '@/types'

const router = useRouter()
const store = useComicStore()

const keyword = ref('')
const statusFilter = ref('')
const sort = ref<NonNullable<ComicListQuery['sort']>>('createdAt')

let debounceTimer: ReturnType<typeof setTimeout> | null = null

function onKeywordInput() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(onSearch, 300)
}

function clearKeyword() {
  keyword.value = ''
  onSearch()
}

function onSearch() {
  store.search({
    keyword: keyword.value || undefined,
    status: statusFilter.value || undefined,
    sort: sort.value,
  })
}

function onPageChange(page: number) {
  store.updateQuery({ page })
  store.fetchList()
}

function goDetail(id: number) {
  router.push(`/comics/${id}`)
}

function continueReading(id: number) {
  router.push(`/comics/${id}`)
}

onMounted(() => {
  store.fetchList()
})
</script>

<style scoped>
.comic-list-page {
  max-width: 1600px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: var(--space-xl);
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-h);
  margin: 0 0 var(--space-lg);
}

.search-bar {
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
  gap: 10px;
  height: 40px;
  padding: 0 14px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-h);
  transition: border-color 150ms ease;
}

.search-input:focus-within {
  border-color: var(--text-muted);
}

.search-input input {
  flex: 1;
  background: transparent;
  border: none;
  outline: none;
  color: var(--text-h);
  font-size: 14px;
}

.search-input input::placeholder {
  color: var(--text-muted);
}

.clear-icon {
  cursor: pointer;
  color: var(--text-muted);
}

.clear-icon:hover {
  color: var(--text-h);
}

.filter-select select {
  height: 40px;
  padding: 0 12px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-h);
  font-size: 14px;
  outline: none;
  cursor: pointer;
}

.filter-select select:focus {
  border-color: var(--text-muted);
}

.comic-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-xl);
}

.comic-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
}

@media (min-width: 640px) {
  .comic-grid {
    grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
    gap: 16px;
  }
}

@media (min-width: 1024px) {
  .comic-grid {
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
    gap: 20px;
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
  color: var(--text);
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
  padding: 8px 20px;
  background: var(--accent);
  color: #fff;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background 150ms ease;
}

.primary-btn:hover {
  background: var(--accent-hover);
}
</style>
