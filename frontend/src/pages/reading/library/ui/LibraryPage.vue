<template>
  <div class="comic-list-page">
    <header ref="pageHeaderRef" class="page-header" :class="{ 'desktop-filter-hidden': isDesktopFilterHidden }">
      <div class="title-block">
        <div class="title-row">
          <h1 class="page-title">
            <span class="mobile-page-title" aria-label="筛选结果数量">
              <strong>{{ store.total }}</strong
              ><small>本</small>
            </span>
          </h1>
          <div class="mobile-recent">
            <AppButton
              type="button"
              class="mobile-sort-order"
              :class="{ ascending: order === 'asc' }"
              :aria-label="order === 'asc' ? '当前升序，点击切换为降序' : '当前降序，点击切换为升序'"
              @click="toggleSortOrder"
            >
              <el-icon :size="18"><Sort /></el-icon>
            </AppButton>
            <el-popover
              v-model:visible="isMobileSortOpen"
              placement="bottom-end"
              :width="218"
              trigger="click"
              popper-class="mobile-sort-menu-popper"
            >
              <template #reference>
                <AppButton type="button" class="mobile-sort-trigger" aria-label="选择排序字段">
                  <span>{{ currentSortLabel }}</span>
                  <i aria-hidden="true" />
                </AppButton>
              </template>

              <div class="mobile-sort-menu">
                <div class="mobile-sort-grid" role="group" aria-label="排序字段">
                  <AppButton
                    v-for="option in sortOptions"
                    :key="option.value"
                    type="button"
                    :class="{ active: sort === option.value }"
                    @click="selectMobileSort(option.value)"
                  >
                    {{ option.label }}
                  </AppButton>
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
            />
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

            <AppButton
              type="button"
              class="desktop-sort-order"
              :class="{ ascending: order === 'asc' }"
              :aria-label="order === 'asc' ? '当前正序，点击切换为倒序' : '当前倒序，点击切换为正序'"
              :title="order === 'asc' ? '正序' : '倒序'"
              @click="toggleSortOrder"
            >
              <el-icon :size="18"><Sort /></el-icon>
            </AppButton>
          </div>
        </div>

        <!-- 移动端第二行：筛选 chips 横向滚动 -->
        <div class="toolbar-filters">
          <div class="filter-select category-select">
            <el-select
              v-model="categoryFilter"
              placeholder="全部分类"
              aria-label="漫画分类"
              popper-class="library-filter-popper"
              @change="onSearch"
            >
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
              <el-option v-for="tag in allTags" :key="tag.id" :label="tag.name" :value="tag.name" />
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

          <AppButton v-if="hasActiveFilters" type="button" class="filter-reset" @click="clearFilters"
            >清除筛选</AppButton
          >
        </div>

        <div v-if="hasActiveFilters" class="active-filter-row" aria-label="当前筛选条件">
          <span class="active-filter-label">当前筛选</span>
          <span v-for="item in activeFilterSummary" :key="item" class="active-filter-chip">{{ item }}</span>
          <AppButton type="button" class="active-filter-clear" @click="clearFilters">清除全部</AppButton>
        </div>
      </div>

      <div class="mobile-filter-stack" aria-label="漫画筛选">
        <div class="mobile-filter-group-row">
          <div class="mobile-filter-options" role="group" aria-label="按分类筛选">
            <AppButton type="button" :class="{ active: !categoryFilter }" @click="selectCategory('')">全部</AppButton>
            <AppButton
              v-for="category in allCategories"
              :key="category.id"
              type="button"
              :class="{ active: categoryFilter === category.name }"
              @click="selectCategory(category.name)"
            >
              {{ category.name }}
            </AppButton>
            <AppButton type="button" :class="{ active: categoryFilter === '_NONE' }" @click="selectCategory('_NONE')">
              未分类
            </AppButton>
          </div>
        </div>

        <div class="mobile-filter-group-row">
          <div class="mobile-filter-options" role="group" aria-label="按标签筛选">
            <AppButton
              v-for="tag in allTags"
              :key="tag.id"
              type="button"
              :class="{ active: selectedTags.includes(tag.name) }"
              @click="toggleTag(tag.name)"
            >
              {{ tag.name }}
            </AppButton>
            <AppButton type="button" :class="{ active: selectedTags.includes('_NONE') }" @click="toggleTag('_NONE')">
              无标签
            </AppButton>
          </div>
        </div>

        <div v-if="selectedTags.length > 1" class="mobile-filter-group-row mobile-filter-match-row">
          <div class="mobile-match-control" role="group" aria-label="标签匹配方式">
            <AppButton
              type="button"
              :class="{ active: tagMode === 'OR' }"
              aria-label="任一标签满足"
              @click="setTagMode('OR')"
            >
              任一
            </AppButton>
            <AppButton
              type="button"
              :class="{ active: tagMode === 'AND' }"
              aria-label="所有标签同时满足"
              @click="setTagMode('AND')"
            >
              同时
            </AppButton>
          </div>
        </div>
      </div>
    </header>

    <ContentState v-if="store.loading && store.list.length === 0" state="loading" message="加载中..." />
    <ContentState v-else-if="store.error" state="error" :message="store.error">
      <AppButton variant="primary" @click="store.fetchList()">重试</AppButton>
    </ContentState>
    <ContentState v-else-if="store.list.length === 0" state="empty" message="暂无漫画" />

    <section v-else class="comic-section">
      <div class="comic-grid">
        <ComicPoster
          v-for="comic in store.list"
          :id="comic.id"
          :key="comic.id"
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
import { AppButton } from '@/shared/ui/button'
import { ContentState } from '@/shared/ui/content-state'
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search, CircleClose, Sort } from '@element-plus/icons-vue'
import { useComicStore } from '@/pages/reading/library/model/comic-store'
import { categoryApi } from '@/entities/category'
import { tagApi } from '@/entities/tag'
import { useLibraryFilters } from '@/pages/reading/library/model/useLibraryFilters'
import { useLibraryPageLayout } from '../model/useLibraryPageLayout'
import { toPosterStatus } from '@/entities/comic'
import { ComicPoster } from '@/entities/comic/ui'
import type { ComicListQuery, ComicListVO } from '@/entities/comic'
import type { CategoryDTO } from '@/entities/category'
import type { TagDTO } from '@/entities/tag'

const router = useRouter()
const route = useRoute()
const store = useComicStore()

const {
  keyword,
  sort,
  order,
  selectedTags,
  tagMode,
  categoryFilter,
  isMobileSortOpen,
  sortOptions,
  hasActiveFilters,
  activeFilterSummary,
  currentSortLabel,
  clearKeyword: resetKeyword,
  clearFilters: resetFilters,
  selectCategory: setCategory,
  toggleTag: updateTagSelection,
  setTagMode: updateTagMode,
  toggleSortOrder: updateSortOrder,
  selectMobileSort: updateMobileSort,
  buildQuery,
} = useLibraryFilters()
const allTags = ref<TagDTO[]>([])
const allCategories = ref<CategoryDTO[]>([])
const pageHeaderRef = ref<HTMLElement | null>(null)
const { posterSize, isDesktopFilterHidden } = useLibraryPageLayout(pageHeaderRef)

let debounceTimer: ReturnType<typeof setTimeout> | null = null

function onKeywordInput() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(onSearch, 300)
}

function clearKeyword() {
  resetKeyword()
  onSearch()
}

function clearFilters() {
  resetFilters()
  onSearch()
}

function selectCategory(category: string) {
  setCategory(category)
  onSearch()
}

function toggleTag(tagName: string) {
  updateTagSelection(tagName)
  onSearch()
}

function setTagMode(mode: 'AND' | 'OR') {
  updateTagMode(mode)
  onSearch()
}

function toggleSortOrder() {
  updateSortOrder()
  onSearch()
}

function selectMobileSort(nextSort: NonNullable<ComicListQuery['sort']>) {
  updateMobileSort(nextSort)
  onSearch()
}

async function loadTags() {
  try {
    const res = await tagApi.list()
    allTags.value = res.data
  } catch {
    allTags.value = []
  }
}

async function loadCategories() {
  try {
    const res = await categoryApi.list()
    allCategories.value = res.data
  } catch {
    allCategories.value = []
  }
}

function onSearch() {
  store.search(buildQuery())
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
  const hasRouteFilters = ['keyword', 'category', 'tags', 'tagMode', 'sort', 'order'].some(
    (key) => route.query[key] !== undefined,
  )
  const tagsFromRoute = Array.isArray(routeTags) ? routeTags.map(String) : routeTags ? [String(routeTags)] : undefined
  keyword.value = hasRouteFilters ? String(route.query.keyword || '') : store.query.keyword || ''
  categoryFilter.value = hasRouteFilters ? String(route.query.category || '') : store.query.category || ''
  selectedTags.value = hasRouteFilters ? tagsFromRoute || [] : [...(store.query.tags || [])]
  tagMode.value = (hasRouteFilters ? route.query.tagMode : store.query.tagMode) === 'AND' ? 'AND' : 'OR'
  sort.value =
    ((hasRouteFilters ? route.query.sort : store.query.sort) as NonNullable<ComicListQuery['sort']>) || 'createdAt'
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
})

onBeforeUnmount(() => {
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer)
    debounceTimer = null
  }
})
</script>

<style scoped src="@/pages/reading/library.css"></style>
