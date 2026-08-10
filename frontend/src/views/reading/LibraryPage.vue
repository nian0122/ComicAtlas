<template>
  <div class="comic-list-page">
    <header class="page-header">
      <div class="title-block">
        <div class="title-row">
          <h1 class="page-title">
            <span class="mobile-page-title">我的收藏</span>
          </h1>
          <span class="mobile-recent">
            <el-icon :size="18"><Sort /></el-icon>
            最近阅读
          </span>
        </div>
        <p class="page-count">
          <span class="mobile-page-count">{{ store.total }} 部作品 · {{ readingCount }} 部正在阅读</span>
        </p>
      </div>
      <div class="toolbar">
        <!-- 移动端第一行：搜索 + 排序合并为一行；桌面端 display:contents 平铺回单行布局 -->
        <div class="toolbar-main">
          <div class="search-input">
            <el-icon :size="18"><Search /></el-icon>
            <input
              v-model="keyword"
              data-library-search
              type="text"
              placeholder="搜索漫画..."
              aria-label="搜索漫画"
              @input="onKeywordInput"
              @keyup.enter="onSearch"
            >
            <el-icon v-if="keyword" :size="16" class="clear-icon" @click="clearKeyword"><CircleClose /></el-icon>
          </div>

          <div class="filter-select sort-select">
            <select v-model="sort" aria-label="排序方式" @change="onSearch">
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
            <select v-model="categoryFilter" aria-label="漫画分类" @change="onSearch">
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
            <select v-model="tagMode" aria-label="标签匹配方式" @change="onSearch">
              <option value="OR">任一标签</option>
              <option value="AND">全部标签</option>
            </select>
          </div>
        </div>
      </div>

      <div class="mobile-filter-stack" aria-label="漫画筛选">
        <div class="mobile-filter-row">
          <button
            type="button"
            :class="{ active: !categoryFilter }"
            @click="selectCategory('')"
          >
            全部
          </button>
          <button
            v-for="category in allCategories"
            :key="category.id"
            type="button"
            :class="{ active: categoryFilter === category.name }"
            @click="selectCategory(category.name)"
          >
            {{ category.name }}
          </button>
          <button
            type="button"
            :class="{ active: categoryFilter === '_NONE' }"
            @click="selectCategory('_NONE')"
          >
            未分类
          </button>
        </div>
        <div class="mobile-filter-row mobile-filter-row--secondary">
          <button
            v-for="tag in allTags"
            :key="tag.id"
            type="button"
            :class="{ active: selectedTags.includes(tag.name) }"
            @click="toggleTag(tag.name)"
          >
            {{ tag.name }}
          </button>
          <button
            type="button"
            :class="{ active: selectedTags.includes('_NONE') }"
            @click="toggleTag('_NONE')"
          >
            无标签
          </button>
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
      <p>请在电脑端导入作品，然后回到这里阅读</p>
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

      <div ref="comicSentinel" class="infinite-sentinel" aria-live="polite">
        <span v-if="infiniteLoading">正在加载更多...</span>
        <span v-else-if="!infiniteHasMore">已加载全部漫画</span>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { Search, PictureFilled, WarningFilled, CircleClose, Sort } from '@element-plus/icons-vue'
import { useComicStore } from '@/stores/comic-store'
import { tagApi, categoryApi } from '@/services/management'
import { useBreakpoint, BREAKPOINTS } from '@/composables/useBreakpoint'
import ComicPoster from '@/components/reading/comic/ComicPoster.vue'
import { toPosterStatus } from '@/components/reading/comic/poster-status'
import type { CategoryDTO, ComicListQuery, ComicListVO, TagDTO } from '@/types'
import { useInfiniteScroll } from '@/composables/useInfiniteScroll'

const router = useRouter()
const store = useComicStore()
const { sentinel: comicSentinel, loading: infiniteLoading, hasMore: infiniteHasMore, reset: resetInfinite } = useInfiniteScroll({
  loadMore: async () => {
    if (store.loading || !store.hasMore) return false
    await store.nextPage()
    return store.hasMore
  },
})
void comicSentinel

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
  if (viewportWidth.value <= BREAKPOINTS.tablet) return 'sm'
  return 'lg'
})

const readingCount = computed(() =>
  store.list.filter((comic) => comic.progressPercent > 0 && comic.progressPercent < 100).length
)

let debounceTimer: ReturnType<typeof setTimeout> | null = null

function onKeywordInput() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(onSearch, 300)
}

function clearKeyword() {
  keyword.value = ''
  onSearch()
}

function selectCategory(category: string) {
  categoryFilter.value = category
  onSearch()
}

function toggleTag(tagName: string) {
  if (tagName === '_NONE') {
    selectedTags.value = selectedTags.value.includes('_NONE') ? [] : ['_NONE']
  } else {
    selectedTags.value = selectedTags.value.includes(tagName)
      ? selectedTags.value.filter((name) => name !== tagName)
      : [...selectedTags.value.filter((name) => name !== '_NONE'), tagName]
  }
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
  resetInfinite()
  store.search({
    keyword: keyword.value || undefined,
    category: categoryFilter.value || undefined,
    sort: sort.value,
    tags: selectedTags.value.length > 0 ? selectedTags.value : undefined,
    tagMode: selectedTags.value.length > 1 ? tagMode.value : undefined,
  })
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
  max-width: var(--content-max);
  margin: 0 auto;
}

.page-header {
  position: sticky;
  top: var(--nav-height);
  z-index: var(--z-sticky);
  padding: var(--space-2) 0 var(--space-3);
  margin-bottom: var(--space-2);
  background: linear-gradient(to bottom, var(--bg-primary) 86%, transparent);
  border-bottom: 1px solid var(--border);
}

.title-block {
  margin-bottom: var(--space-6);
}

.page-eyebrow {
  margin-bottom: var(--space-2);
  color: var(--accent);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.page-title {
  font-size: var(--text-page);
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-1);
  letter-spacing: -0.02em;
}

.mobile-page-title {
  display: none;
}

.title-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-4);
}

.mobile-recent,
.mobile-page-count,
.mobile-filter-stack {
  display: none;
}

.page-count {
  color: var(--text-muted);
  font-size: var(--text-sm);
  font-variant-numeric: tabular-nums;
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
  height: 44px;
  padding: 0 var(--space-base);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  color: var(--text-primary);
  transition: border-color var(--transition-fast);
}

.search-input:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
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
  height: 44px;
  padding: 0 var(--space-base);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
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
  border-radius: var(--radius-pill);
  min-height: 44px;
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
  grid-template-columns: repeat(
    auto-fit,
    minmax(min(var(--poster-width-md), 100%), 1fr)
  );
}

.comic-grid :deep(.comic-poster) {
  width: 100%;
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

/* ===== 移动阅读端（手机与平板，≤1024px）===== */
@media (max-width: 1024px) {
  .comic-list-page {
    margin: 0;
  }

  .page-header {
    position: static;
    display: flex;
    flex-direction: column;
    padding: var(--mobile-library-filter-offset) 0 var(--mobile-library-header-bottom);
    background: var(--mobile-canvas);
    border-bottom: 0;
  }

  .title-block {
    order: 2;
    margin-top: var(--mobile-library-title-gap);
    margin-bottom: 0;
  }

  .page-eyebrow,
  .desktop-page-title {
    display: none;
  }

  .mobile-page-title {
    display: inline;
  }

  .desktop-page-count {
    display: none;
  }

  .mobile-page-count,
  .mobile-recent {
    display: inline;
  }

  .mobile-recent {
    align-items: center;
    gap: var(--space-1);
    color: var(--text-secondary);
    font-size: var(--text-sm);
  }

  .mobile-recent {
    display: inline-flex;
  }

  .page-title {
    font-size: 24px;
  }

  .toolbar {
    order: 0;
    gap: var(--space-sm);
  }

  /* 搜索 + 排序合并为一行，空间不足时自动换行 */
  .toolbar-main {
    display: flex;
    align-items: center;
    gap: var(--space-sm);
    width: auto;
  }

  .search-input {
    position: fixed;
    top: var(--mobile-search-top);
    right: var(--mobile-page-gutter);
    z-index: calc(var(--z-nav) + 1);
    width: var(--mobile-search-width);
    min-width: var(--mobile-search-width);
    height: var(--mobile-search-height);
    padding-inline: var(--space-3);
    background: var(--color-surface-3);
    border-color: transparent;
  }

  .search-input input {
    min-width: 0;
    font-size: 16px;
  }

  .sort-select,
  .toolbar-filters {
    display: none;
  }

  .mobile-filter-stack {
    display: flex;
    flex-direction: column;
    order: 1;
    gap: var(--mobile-library-filter-gap);
    width: calc(100% + var(--mobile-page-gutter) * 2);
    margin-left: calc(var(--mobile-page-gutter) * -1);
    overflow: hidden;
  }

  .mobile-filter-row {
    display: flex;
    align-items: center;
    flex-wrap: nowrap;
    gap: var(--space-3);
    width: 100%;
    overflow-x: auto;
    padding-inline: var(--mobile-page-gutter);
    white-space: nowrap;
    -webkit-overflow-scrolling: touch;
    scrollbar-width: none;
  }

  .mobile-filter-row::-webkit-scrollbar {
    display: none;
  }

  .mobile-filter-row button {
    flex: 0 0 auto;
    min-width: 82px;
    min-height: 44px;
    padding-inline: var(--space-5);
    border: 0;
    border-radius: var(--radius-pill);
    background: var(--bg-surface);
    color: var(--text-secondary);
    font: inherit;
    font-size: var(--text-sm);
    font-weight: 600;
  }

  .mobile-filter-row--secondary button {
    min-width: 72px;
    min-height: 40px;
  }

  .mobile-filter-row button.active {
    background: var(--text-primary);
    color: var(--mobile-canvas);
  }

  .comic-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--space-8) var(--mobile-library-grid-gap);
  }

  .comic-grid :deep(.poster-frame) {
    border-radius: var(--radius-md);
  }

  .comic-grid :deep(.poster-info) {
    display: none;
  }

  .comic-grid :deep(.poster-progress) {
    height: 4px;
  }
}
</style>
