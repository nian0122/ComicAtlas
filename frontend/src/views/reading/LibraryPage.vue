<template>
  <div class="comic-list-page">
    <header
      ref="pageHeaderRef"
      class="page-header"
      :class="{ 'desktop-filter-hidden': isDesktopFilterHidden }"
    >
      <div class="title-block">
        <div class="title-row">
          <h1 class="page-title">
            <span class="mobile-page-title" aria-label="筛选结果数量">
              <strong>{{ store.total }}</strong><small>本</small>
            </span>
          </h1>
          <div class="mobile-recent">
            <button
              type="button"
              class="mobile-sort-order"
              :class="{ ascending: order === 'asc' }"
              :aria-label="order === 'asc' ? '当前升序，点击切换为降序' : '当前降序，点击切换为升序'"
              @click="toggleSortOrder"
            >
              <el-icon :size="18"><Sort /></el-icon>
            </button>
            <el-popover
              v-model:visible="isMobileSortOpen"
              placement="bottom-end"
              :width="218"
              trigger="click"
              popper-class="mobile-sort-menu-popper"
            >
              <template #reference>
                <button type="button" class="mobile-sort-trigger" aria-label="选择排序字段">
                  <span>{{ currentSortLabel }}</span>
                  <i aria-hidden="true" />
                </button>
              </template>

              <div class="mobile-sort-menu">
                <div class="mobile-sort-grid" role="group" aria-label="排序字段">
                  <button
                    v-for="option in sortOptions"
                    :key="option.value"
                    type="button"
                    :class="{ active: sort === option.value }"
                    @click="selectMobileSort(option.value)"
                  >
                    {{ option.label }}
                  </button>
                </div>
              </div>
            </el-popover>
          </div>
        </div>
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
              placeholder="搜索"
              aria-label="搜索漫画"
              @input="onKeywordInput"
              @keyup.enter="onSearch"
            >
            <el-icon v-if="keyword" :size="16" class="clear-icon" @click="clearKeyword"><CircleClose /></el-icon>
          </div>

          <div class="desktop-sort-group">
            <div class="filter-select sort-select">
              <el-select v-model="sort" aria-label="排序方式" popper-class="library-filter-popper" @change="onSearch">
                <el-option label="最新添加" value="createdAt" />
                <el-option label="最近更新" value="updatedAt" />
                <el-option label="标题" value="title" />
                <el-option label="页数" value="pageCount" />
                <el-option label="文件大小" value="fileSize" />
                <el-option label="最近阅读" value="lastReadTime" />
              </el-select>
            </div>

            <button
              type="button"
              class="desktop-sort-order"
              :class="{ ascending: order === 'asc' }"
              :aria-label="order === 'asc' ? '当前正序，点击切换为倒序' : '当前倒序，点击切换为正序'"
              :title="order === 'asc' ? '正序' : '倒序'"
              @click="toggleSortOrder"
            >
              <el-icon :size="18"><Sort /></el-icon>
            </button>
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

          <div v-if="selectedTags.length > 1" class="filter-select tag-mode-select">
            <el-select
              v-model="tagMode"
              aria-label="标签匹配方式"
              popper-class="library-filter-popper tag-mode-popper"
              @change="onSearch"
            >
              <el-option label="任一" value="OR" />
              <el-option label="同时" value="AND" />
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
        <div class="mobile-filter-group-row">
          <div class="mobile-filter-options" role="group" aria-label="按分类筛选">
            <button type="button" :class="{ active: !categoryFilter }" @click="selectCategory('')">全部</button>
            <button
              v-for="category in allCategories"
              :key="category.id"
              type="button"
              :class="{ active: categoryFilter === category.name }"
              @click="selectCategory(category.name)"
            >
              {{ category.name }}
            </button>
            <button type="button" :class="{ active: categoryFilter === '_NONE' }" @click="selectCategory('_NONE')">未分类</button>
          </div>
        </div>

        <div class="mobile-filter-group-row">
          <div class="mobile-filter-options" role="group" aria-label="按标签筛选">
            <button
              v-for="tag in allTags"
              :key="tag.id"
              type="button"
              :class="{ active: selectedTags.includes(tag.name) }"
              @click="toggleTag(tag.name)"
            >
              {{ tag.name }}
            </button>
            <button type="button" :class="{ active: selectedTags.includes('_NONE') }" @click="toggleTag('_NONE')">
              无标签
            </button>
          </div>
        </div>

        <div v-if="selectedTags.length > 1" class="mobile-filter-group-row mobile-filter-match-row">
          <div class="mobile-match-control" role="group" aria-label="标签匹配方式">
            <button type="button" :class="{ active: tagMode === 'OR' }" aria-label="任一标签满足" @click="setTagMode('OR')">任一</button>
            <button type="button" :class="{ active: tagMode === 'AND' }" aria-label="所有标签同时满足" @click="setTagMode('AND')">同时</button>
          </div>
        </div>
      </div>
    </header>

    <div v-if="store.loading && store.list.length === 0" class="state loading" aria-label="加载中">
      <div class="spinner" />
    </div>

    <div v-else-if="store.error" class="state error">
      <el-icon :size="48"><WarningFilled /></el-icon>
      <span>{{ store.error }}</span>
      <button class="primary-btn" @click="store.fetchList()">重试</button>
    </div>

    <div v-else-if="store.list.length === 0" class="state empty">
      <el-icon :size="48"><PictureFilled /></el-icon>
      <span>暂无漫画</span>
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
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search, PictureFilled, WarningFilled, CircleClose, Sort } from '@element-plus/icons-vue'
import { useComicStore } from '@/stores/comic-store'
import { readingTagApi, readingCategoryApi } from '@/services/api'
import { useBreakpoint, BREAKPOINTS } from '@/composables/useBreakpoint'
import ComicPoster from '@/components/reading/comic/ComicPoster.vue'
import { toPosterStatus } from '@/components/reading/comic/poster-status'
import type { CategoryDTO, ComicListQuery, ComicListVO, TagDTO } from '@/types'

const router = useRouter()
const route = useRoute()
const store = useComicStore()

const keyword = ref('')
const sort = ref<NonNullable<ComicListQuery['sort']>>('createdAt')
const order = ref<NonNullable<ComicListQuery['order']>>('desc')
const selectedTags = ref<string[]>([])
const tagMode = ref<'AND' | 'OR'>('OR')
const allTags = ref<TagDTO[]>([])
const categoryFilter = ref('')
const allCategories = ref<CategoryDTO[]>([])
const pageHeaderRef = ref<HTMLElement | null>(null)
const isDesktopFilterHidden = ref(false)
const isMobileSortOpen = ref(false)

const sortOptions: Array<{ value: NonNullable<ComicListQuery['sort']>; label: string }> = [
  { value: 'lastReadTime', label: '最近阅读' },
  { value: 'createdAt', label: '最新添加' },
  { value: 'updatedAt', label: '最近更新' },
  { value: 'title', label: '标题' },
  { value: 'pageCount', label: '页数' },
  { value: 'fileSize', label: '文件大小' },
]

const DESKTOP_FILTER_BREAKPOINT = 1024
const FILTER_HIDE_SCROLL_START = 160
const FILTER_SCROLL_DELTA = 8
let lastWindowScrollY = 0
let scrollAnimationFrame: number | null = null

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
const currentSortLabel = computed(() => sortOptions.find((option) => option.value === sort.value)?.label || '最新添加')

// 响应式视口宽度（resize 防抖更新，组件卸载时自动清理监听）
const viewportWidth = useBreakpoint()

// 海报尺寸随断点响应式推导（替代原先读取一次视口宽度、手动挂 resize 监听的写法）
const posterSize = computed<'sm' | 'md' | 'lg'>(() => {
  if (viewportWidth.value <= BREAKPOINTS.tablet) return 'sm'
  return 'lg'
})

let debounceTimer: ReturnType<typeof setTimeout> | null = null

function updateDesktopFilterVisibility() {
  scrollAnimationFrame = null
  const currentScrollY = Math.max(0, window.scrollY)

  if (window.innerWidth <= DESKTOP_FILTER_BREAKPOINT || currentScrollY <= FILTER_HIDE_SCROLL_START) {
    isDesktopFilterHidden.value = false
    lastWindowScrollY = currentScrollY
    return
  }

  // 用户正在输入或操作下拉框时，筛选栏保持可见。
  const activeElement = document.activeElement
  if (activeElement instanceof Node && pageHeaderRef.value?.contains(activeElement)) {
    isDesktopFilterHidden.value = false
    lastWindowScrollY = currentScrollY
    return
  }

  const scrollDelta = currentScrollY - lastWindowScrollY
  if (scrollDelta >= FILTER_SCROLL_DELTA) {
    isDesktopFilterHidden.value = true
    lastWindowScrollY = currentScrollY
  } else if (scrollDelta <= -FILTER_SCROLL_DELTA) {
    isDesktopFilterHidden.value = false
    lastWindowScrollY = currentScrollY
  }
}

function onWindowScroll() {
  if (scrollAnimationFrame !== null) return
  scrollAnimationFrame = window.requestAnimationFrame(updateDesktopFilterVisibility)
}

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

function setTagMode(mode: 'AND' | 'OR') {
  tagMode.value = mode
  onSearch()
}

function toggleSortOrder() {
  order.value = order.value === 'asc' ? 'desc' : 'asc'
  onSearch()
}

function selectMobileSort(nextSort: NonNullable<ComicListQuery['sort']>) {
  sort.value = nextSort
  isMobileSortOpen.value = false
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
    order: order.value,
    tags: selectedTags.value.length > 0 ? selectedTags.value : undefined,
    tagMode: selectedTags.value.length > 1 ? tagMode.value : undefined,
  })
  persistFiltersToRoute()
}

function onPageChange(page: number) {
  store.updateQuery({ page })
  persistFiltersToRoute()
  store.fetchList()
}

function parseRoutePage(): number | undefined {
  const rawPage = Array.isArray(route.query.page) ? route.query.page[0] : route.query.page
  const page = Number(rawPage)
  return Number.isInteger(page) && page > 0 ? page : undefined
}

function restoreFiltersFromStore() {
  const routeTags = route.query.tags
  const hasRouteFilters = ['keyword', 'category', 'tags', 'tagMode', 'sort', 'order']
    .some((key) => route.query[key] !== undefined)
  const tagsFromRoute = Array.isArray(routeTags)
    ? routeTags.map(String)
    : routeTags
      ? [String(routeTags)]
      : undefined
  keyword.value = hasRouteFilters ? String(route.query.keyword || '') : (store.query.keyword || '')
  categoryFilter.value = hasRouteFilters ? String(route.query.category || '') : (store.query.category || '')
  selectedTags.value = hasRouteFilters ? (tagsFromRoute || []) : [...(store.query.tags || [])]
  tagMode.value = (hasRouteFilters ? route.query.tagMode : store.query.tagMode) === 'AND' ? 'AND' : 'OR'
  sort.value = (hasRouteFilters ? route.query.sort : store.query.sort) as NonNullable<ComicListQuery['sort']> || 'createdAt'
  order.value = (hasRouteFilters ? route.query.order : store.query.order) === 'asc' ? 'asc' : 'desc'
  const routePage = parseRoutePage()
  store.updateQuery({
    keyword: keyword.value || undefined,
    category: categoryFilter.value || undefined,
    tags: selectedTags.value.length > 0 ? selectedTags.value : undefined,
    tagMode: selectedTags.value.length > 1 ? tagMode.value : undefined,
    sort: sort.value,
    order: order.value,
    // URL 优先保证刷新可恢复；无 URL 时保留 Pinia 状态，
    // 从详情页返回漫画库也不会跳回第一页。
    page: routePage ?? store.query.page ?? 1,
  })
}

function persistFiltersToRoute() {
  void router.replace({
    query: {
      ...route.query,
      keyword: keyword.value || undefined,
      category: categoryFilter.value || undefined,
      tags: selectedTags.value.length > 0 ? selectedTags.value : undefined,
      tagMode: selectedTags.value.length > 1 ? tagMode.value : undefined,
      sort: sort.value || undefined,
      order: order.value === 'asc' ? 'asc' : undefined,
      page: (store.query.page || 1) > 1 ? store.query.page : undefined,
    },
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
  // 返回漫画库时恢复 Store 中的筛选条件，避免控件与实际查询状态不一致。
  restoreFiltersFromStore()
  loadTags()
  loadCategories()
  store.fetchList()
  lastWindowScrollY = Math.max(0, window.scrollY)
  window.addEventListener('scroll', onWindowScroll, { passive: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onWindowScroll)
  if (scrollAnimationFrame !== null) {
    window.cancelAnimationFrame(scrollAnimationFrame)
    scrollAnimationFrame = null
  }
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer)
    debounceTimer = null
  }
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

@media (min-width: 1025px) {
  .page-header {
    transform: translate3d(0, 0, 0);
    opacity: 1;
    transition:
      transform 220ms cubic-bezier(0.22, 1, 0.36, 1),
      opacity 160ms ease;
    will-change: transform, opacity;
  }

  .page-header.desktop-filter-hidden {
    transform: translate3d(0, calc(-100% - 1px), 0);
    opacity: 0;
    pointer-events: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .page-header {
    transition: none;
  }
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
.mobile-filter-stack {
  display: none;
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

.tag-mode-select {
  width: 88px;
  min-width: 88px;
}

.tag-mode-select :deep(.el-select__wrapper) {
  padding-inline: 14px 10px;
}

.tag-mode-select :deep(.el-select__selected-item) {
  font-size: 13px;
  font-weight: 600;
}

.sort-select { min-width: 128px; }
.category-select { min-width: 118px; }

.desktop-sort-group {
  display: contents;
}

.desktop-sort-order {
  display: none;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--bg-surface);
  color: var(--text-secondary);
  cursor: pointer;
  transition: border-color var(--transition-fast), color var(--transition-fast), background-color var(--transition-fast);
}

.desktop-sort-order:hover {
  border-color: var(--border-strong);
  color: var(--text-primary);
}

.desktop-sort-order.ascending :deep(svg) {
  transform: rotate(180deg);
}

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
:global(.tag-mode-popper) { min-width: 88px !important; }

:global(.el-popper.is-light.mobile-sort-menu-popper) {
  --el-popover-bg-color: #0d0d0d;
  padding: 5px;
  border: 1px solid var(--border-strong) !important;
  border-radius: 13px;
  background: #0d0d0d !important;
  background-color: #0d0d0d !important;
  box-shadow: 0 16px 44px rgb(0 0 0 / 72%) !important;
}

:global(.mobile-sort-menu-popper .el-popper__arrow) {
  display: none;
}

.mobile-sort-grid button {
  min-height: 30px;
  border: 0;
  border-radius: var(--radius-pill);
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 600;
}

.mobile-sort-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px;
}

.mobile-sort-grid button {
  background: var(--bg-surface);
}

.mobile-sort-grid button.active {
  background: var(--text-primary);
  color: var(--bg-primary);
}

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

/* 桌面端（>1024px）：包装层不参与布局，控件直接平铺进 toolbar，
 * 并用 order 恢复原有控件顺序：搜索 → 分类 → 排序 → 标签 → 标签模式 */
@media (min-width: 1025px) {
  .toolbar-main,
  .toolbar-filters {
    display: contents;
  }

  .search-input { order: 1; }
  .category-select { order: 2; }
  .desktop-sort-group {
    display: inline-flex;
    align-items: center;
    gap: var(--space-sm);
    order: 3;
  }
  .desktop-sort-group .desktop-sort-order { display: inline-flex; }
  .sort-select,
  .desktop-sort-order { order: unset; }
  .tag-filter { order: 5; }
  .tag-mode-select { order: 6; }
  .filter-reset { order: 7; }
  /* 桌面端已在筛选控件内展示当前值，避免再重复占一整行摘要。 */
  .active-filter-row { display: none; }
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

  .mobile-page-title strong {
    font-size: 30px;
    font-variant-numeric: tabular-nums;
    letter-spacing: -0.05em;
  }

  .mobile-page-title small {
    margin-left: 4px;
    color: var(--text-muted);
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0;
  }

  .desktop-page-count {
    display: none;
  }

  .mobile-recent {
    display: inline-flex;
    align-items: center;
    gap: 2px;
    color: var(--text-secondary);
  }

  /* 移动/平板端排序已由标题行的 mobile-recent 提供，避免与桌面排序组重复。 */
  .desktop-sort-group {
    display: none;
  }

  .mobile-sort-order {
    display: grid;
    width: 44px;
    height: 44px;
    padding: 0;
    place-items: center;
    border: 0;
    background: transparent;
    color: var(--text-secondary);
    cursor: pointer;
  }

  .mobile-sort-order :deep(svg) {
    transition: transform 180ms ease;
  }

  .mobile-sort-order.ascending :deep(svg) {
    transform: rotate(180deg);
  }

  .mobile-sort-trigger {
    display: inline-flex;
    align-items: center;
    justify-content: flex-end;
    gap: 6px;
    min-width: 78px;
    min-height: 44px;
    padding: 0;
    border: 0;
    background: transparent;
    color: var(--text-secondary);
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
  }

  .mobile-sort-trigger span {
    color: var(--text-primary);
  }

  .mobile-sort-trigger i {
    width: 6px;
    height: 6px;
    margin: 0 2px 4px 0;
    border-right: 1px solid currentColor;
    border-bottom: 1px solid currentColor;
    transform: rotate(45deg);
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
  .toolbar-filters,
  .active-filter-row {
    display: none;
  }

  .mobile-filter-stack {
    display: flex;
    flex-direction: column;
    order: 2;
    gap: 10px;
    width: calc(100% + var(--mobile-page-gutter));
    overflow: hidden;
  }

  .mobile-filter-group-row {
    display: block;
    min-height: 36px;
  }

  .mobile-filter-options {
    display: flex;
    align-items: center;
    gap: 7px;
    overflow-x: auto;
    padding-right: var(--mobile-page-gutter);
    white-space: nowrap;
    scrollbar-width: none;
  }

  .mobile-filter-options::-webkit-scrollbar {
    display: none;
  }

  .mobile-filter-options button,
  .mobile-match-control button {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    min-height: 34px;
    padding: 0 14px;
    border: 0;
    border-radius: var(--radius-pill);
    background: var(--bg-surface);
    color: var(--text-secondary);
    font-size: 12px;
    font-weight: 600;
  }

  .mobile-filter-options button.active,
  .mobile-match-control button.active {
    background: var(--text-primary);
    color: var(--mobile-canvas);
  }

  .mobile-match-control {
    display: inline-flex;
    width: fit-content;
    padding: 2px;
    border: 1px solid var(--border);
    border-radius: var(--radius-pill);
  }

  .mobile-match-control button {
    min-height: 28px;
    padding-inline: 12px;
    background: transparent;
    font-size: 11px;
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
