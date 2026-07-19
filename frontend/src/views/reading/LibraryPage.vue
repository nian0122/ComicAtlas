<template>
  <div class="comic-list-page">
    <header class="page-header">
      <h1 class="page-title">漫画库</h1>
      <div class="toolbar">
        <!-- 移动端第一行：搜索 + 排序合并为一行；桌面端 display:contents 平铺回单行布局 -->
        <div class="toolbar-main">
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

          <div class="filter-select sort-select">
            <select v-model="sort" @change="onSearch">
              <option value="createdAt">最新添加</option>
              <option value="updatedAt">最近更新</option>
              <option value="title">标题</option>
              <option value="pageCount">页数</option>
              <option value="lastReadTime">最近阅读</option>
            </select>
          </div>
        </div>

        <!-- 移动端第二行：筛选 chips 横向滚动 -->
        <div class="toolbar-filters">
          <div class="filter-select category-select">
            <select v-model="categoryFilter" @change="onSearch">
              <option value="">全部分类</option>
              <option value="_NONE">未分类</option>
              <option v-for="c in allCategories" :key="c.id" :value="c.name">{{ c.name }}</option>
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
              <el-option label="无标签" value="_NONE" />
            </el-select>
          </div>

          <div class="filter-select tag-mode-filter">
            <select v-model="tagMode" @change="onSearch">
              <option value="OR">任一标签</option>
              <option value="AND">全部标签</option>
            </select>
          </div>
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
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { Search, PictureFilled, WarningFilled, CircleClose } from '@element-plus/icons-vue'
import { useComicStore } from '@/stores/comic-store'
import { tagApi, categoryApi } from '@/services/management'
import { useBreakpoint, BREAKPOINTS } from '@/composables/useBreakpoint'
import ComicPoster from '@/components/reading/comic/ComicPoster.vue'
import { toPosterStatus } from '@/components/reading/comic/poster-status'
import type { CategoryDTO, ComicListQuery, ComicListVO, TagDTO } from '@/types'

const router = useRouter()
const store = useComicStore()

const keyword = ref('')
const sort = ref<NonNullable<ComicListQuery['sort']>>('createdAt')
const selectedTags = ref<string[]>([])
const tagMode = ref<'AND' | 'OR'>('OR')
const allTags = ref<TagDTO[]>([])
const categoryFilter = ref('')
const allCategories = ref<CategoryDTO[]>([])

// 响应式视口宽度（resize 防抖更新，组件卸载时自动清理监听）
const viewportWidth = useBreakpoint()

// 海报尺寸随断点响应式推导（替代原先读取一次视口宽度、手动挂 resize 监听的写法）
const posterSize = computed<'sm' | 'md' | 'lg'>(() => {
  if (viewportWidth.value <= BREAKPOINTS.mobile) return 'sm'
  if (viewportWidth.value <= BREAKPOINTS.tablet) return 'md'
  return 'lg'
})

let debounceTimer: ReturnType<typeof setTimeout> | null = null

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

async function loadCategories() {
  try {
    const res = await categoryApi.list()
    allCategories.value = (res.data as CategoryDTO[]) || []
  } catch (err: unknown) {
    allCategories.value = []
  }
}

watch(selectedTags, (val) => {
  if (val.includes('_NONE') && val.length > 1) {
    nextTick(() => {
      selectedTags.value = ['_NONE']
    })
  }
}, { deep: true })

function onSearch() {
  store.search({
    keyword: keyword.value || undefined,
    category: categoryFilter.value || undefined,
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
  router.push(`/comic/${id}`)
}

function continueReading(id: string | number) {
  router.push(`/comic/${id}`)
}

function posterSubtitle(comic: ComicListVO): string {
  if (comic.progressPercent > 0) {
    return `已读 ${comic.progressPercent}%`
  }
  return `${comic.pageCount} 页`
}

onMounted(() => {
  loadTags()
  loadCategories()
  store.fetchList()
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

/* 桌面端（>768px）：包装层不参与布局，控件直接平铺进 toolbar，
 * 并用 order 恢复原有控件顺序：搜索 → 分类 → 排序 → 标签 → 标签模式 */
@media (min-width: 769px) {
  .toolbar-main,
  .toolbar-filters {
    display: contents;
  }

  .search-input { order: 1; }
  .category-select { order: 2; }
  .sort-select { order: 3; }
  .tag-filter { order: 4; }
  .tag-mode-filter { order: 5; }
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
  grid-template-columns: repeat(auto-fill, minmax(var(--poster-width-md), 1fr));
}

.comic-grid :deep(.comic-poster) {
  width: 100%;
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

/* ===== 移动端（≤768px）===== */
@media (max-width: 768px) {
  /* 抵消 ReadingLayout 的 32px 页面留白，移动端收窄为 8px，
   * 保证 375px 宽度下网格容纳 3 列（110px × 3 + 8px 间距 × 2 = 346px ≤ 359px） */
  .comic-list-page {
    margin: 0 calc(var(--space-sm) - var(--page-padding));
  }

  .toolbar {
    gap: var(--space-sm);
  }

  /* 搜索 + 排序合并为一行，空间不足时自动换行 */
  .toolbar-main {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-sm);
    width: 100%;
  }

  .search-input {
    flex: 1;
    min-width: 0;
  }

  /* 筛选 chips 行：横向滚动，不换行，隐藏滚动条 */
  .toolbar-filters {
    display: flex;
    align-items: center;
    flex-wrap: nowrap;
    gap: var(--space-sm);
    width: 100%;
    overflow-x: auto;
    white-space: nowrap;
    -webkit-overflow-scrolling: touch;
    scrollbar-width: none;
  }

  .toolbar-filters::-webkit-scrollbar {
    display: none;
  }

  .toolbar-filters .filter-select {
    flex: 0 0 auto;
  }

  /* 最小宽度驱动的自适应网格（设计稿 §5）：375px 宽度下呈现 3 列 */
  .comic-grid {
    grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
    gap: var(--space-sm);
  }
}
</style>
