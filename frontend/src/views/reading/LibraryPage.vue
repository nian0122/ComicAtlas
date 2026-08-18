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
          <span class="mobile-page-count">{{ store.total }} 部作品 · 本页 {{ readingCount }} 部正在阅读</span>
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
            <el-select v-model="sort" aria-label="排序方式" popper-class="library-filter-popper" @change="onSearch">
              <el-option label="最新添加" value="createdAt" />
              <el-option label="最近更新" value="updatedAt" />
              <el-option label="标题" value="title" />
              <el-option label="页数" value="pageCount" />
              <el-option label="最近阅读" value="lastReadTime" />
            </el-select>
          </div>
        </div>

        <!-- 移动端第二行：筛选 chips 横向滚动 -->
        <div class="toolbar-filters">
          <div class="filter-select category-select">
            <el-select v-model="categoryFilter" placeholder="全部分类" aria-label="漫画分类" popper-class="library-filter-popper" @change="onSearch">
              <el-option label="全部分类" value="" />
              <el-option label="未分类" value="_NONE" />
              <el-option v-for="c in allCategories" :key="c.id" :label="c.name" :value="c.name" />
            </el-select>
          </div>

          <div class="filter-select tag-filter">
            <el-select
              v-model="selectedTags"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="标签：选择"
              class="tag-select"
              popper-class="library-filter-popper"
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
            <el-select v-model="tagMode" aria-label="标签匹配方式" popper-class="library-filter-popper" @change="onSearch">
              <el-option label="任一匹配" value="OR" />
              <el-option label="全部匹配" value="AND" />
            </el-select>
          </div>

          <button v-if="hasActiveFilters" type="button" class="filter-reset" @click="clearFilters">清除筛选</button>
        </div>

        <div v-if="hasActiveFilters" class="active-filter-row" aria-label="当前筛选条件">
          <span class="active-filter-label">当前筛选</span>
          <span v-for="item in activeFilterSummary" :key="item" class="active-filter-chip">{{ item }}</span>
          <button type="button" class="active-filter-clear" @click="clearFilters">清除全部</button>
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

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="store.query.page"
          :page-size="store.query.size"
          :total="store.total"
          layout="prev, pager, next"
          small
          hide-on-single-page
          :disabled="store.loading"
          @current-change="onPageChange"
        />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { Search, PictureFilled, WarningFilled, CircleClose, Sort } from '@element-plus/icons-vue'
import { useComicStore } from '@/stores/comic-store'
import { readingTagApi, readingCategoryApi } from '@/services/api'
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

const hasActiveFilters = computed(() => Boolean(keyword.value || categoryFilter.value || selectedTags.value.length))
const activeFilterSummary = computed(() => {
  const summary: string[] = []
  if (keyword.value) summary.push(`搜索：${keyword.value}`)
  if (categoryFilter.value) summary.push(`分类：${categoryFilter.value === '_NONE' ? '未分类' : categoryFilter.value}`)
  if (selectedTags.value.length) {
    const tagText = selectedTags.value.map((tag) => tag === '_NONE' ? '无标签' : tag).join('、')
    summary.push(`标签：${tagText} · ${selectedTags.value.length > 1 && tagMode.value === 'AND' ? '全部匹配' : '任一匹配'}`)
  }
  return summary
})

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

function clearFilters() {
  keyword.value = ''
  categoryFilter.value = ''
  selectedTags.value = []
  tagMode.value = 'OR'
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
    const res = await readingTagApi.list()
    allTags.value = (res.data as TagDTO[]) || []
  } catch (err: unknown) {
    allTags.value = []
  }
}

async function loadCategories() {
  try {
    const res = await readingCategoryApi.list()
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
  // 重新进入漫画库时清除上一次页面残留的分页条件，避免界面与实际请求不一致。
  store.resetQuery()
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
  /* 聚焦只保留一圈清晰边界，避免胶囊外再出现一圈抢眼红框。 */
  box-shadow: 0 0 0 1px var(--accent) inset;
}

.search-input input {
  flex: 1;
  min-height: 0 !important;
  padding: 0 !important;
  background: transparent !important;
  border: none !important;
  border-radius: 0 !important;
  outline: none !important;
  box-shadow: none !important;
  color: var(--text-primary);
  font-size: 14px;
}

.search-input input:focus,
.search-input input:focus-visible {
  border: none !important;
  outline: none !important;
  box-shadow: none !important;
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

.filter-select :deep(.el-select) { width: 100%; }
.filter-select :deep(.el-select__wrapper) {
  min-height: 44px;
  padding: 0 var(--space-base);
  border-radius: var(--radius-pill);
  background: var(--bg-surface);
  box-shadow: 0 0 0 1px var(--border) inset;
  color: var(--text-primary);
  transition: box-shadow var(--transition-fast), background-color var(--transition-fast);
}
.filter-select :deep(.el-select__wrapper:hover) { box-shadow: 0 0 0 1px var(--border-strong) inset; }
.filter-select :deep(.el-select__wrapper.is-focused) { box-shadow: 0 0 0 1px var(--accent) inset, 0 0 0 3px var(--accent-bg); }
.filter-select :deep(.el-select__selected-item),
.filter-select :deep(.el-select__placeholder) { color: var(--text-primary); font-size: 14px; }

.tag-filter {
  min-width: 170px;
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

.sort-select { min-width: 128px; }
.category-select { min-width: 118px; }

:global(.library-filter-popper.el-popper) {
  padding: 5px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-md);
  background: var(--bg-elevated);
  box-shadow: var(--card-shadow-hover);
}
:global(.library-filter-popper .el-select-dropdown__item) {
  min-height: 36px;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  font-size: 13px;
}
:global(.library-filter-popper .el-select-dropdown__item.hover),
:global(.library-filter-popper .el-select-dropdown__item:hover) { background: var(--accent-bg); color: var(--text-primary); }
:global(.library-filter-popper .el-select-dropdown__item.is-selected) { background: var(--accent-bg); color: var(--accent); font-weight: 650; }

.filter-reset,
.active-filter-clear {
  border: 0;
  background: transparent;
  color: var(--accent);
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}

.filter-reset { height: 44px; padding: 0 4px; }
.filter-reset:hover,
.active-filter-clear:hover { color: var(--text-primary); }

.active-filter-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-basis: 100%;
  min-width: 0;
  padding-top: 2px;
  color: var(--text-muted);
  font-size: 11px;
}

.active-filter-label { color: var(--text-secondary); font-weight: 650; }
.active-filter-chip { max-width: 240px; overflow: hidden; padding: 4px 8px; border: 1px solid var(--border); border-radius: var(--radius-pill); background: var(--bg-surface); text-overflow: ellipsis; white-space: nowrap; }
.active-filter-clear { margin-left: auto; }

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
  .filter-reset { order: 6; }
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
  align-items: center;
  gap: var(--space-3);
  justify-content: center;
  min-height: 36px;
  padding: var(--space-md) 0;
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
    gap: var(--space-4);
    padding: var(--space-4) 0 var(--mobile-library-header-bottom);
    background: var(--mobile-canvas);
    border-bottom: 0;
  }

  .title-block {
    order: 0;
    margin-top: 0;
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
    order: 1;
    width: 100%;
    gap: var(--space-sm);
  }

  /* 移动端搜索进入正常文档流，避免固定定位造成标题错位和顶部空洞。 */
  .toolbar-main {
    display: flex;
    align-items: center;
    gap: var(--space-sm);
    width: 100%;
  }

  .search-input {
    position: static;
    width: 100%;
    min-width: 0;
    max-width: none;
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
    order: 2;
    gap: var(--mobile-library-filter-gap);
    width: calc(100% + var(--mobile-page-gutter) * 2);
    margin-left: calc(var(--mobile-page-gutter) * -1);
    margin-top: 0;
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
    gap: var(--space-4);
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

  /* 固定底部导航不应遮住最后一排卡片和分页。 */
  .comic-section {
    padding-bottom: calc(
      var(--mobile-tabbar-height) + var(--space-8) + env(safe-area-inset-bottom)
    );
  }
}
</style>
