import { computed, getCurrentScope, onScopeDispose, reactive, toRefs, watch } from 'vue'
import type { ComicListQuery } from '@/entities/comic'

export const READING_SORT_OPTIONS: { value: NonNullable<ComicListQuery['sort']>; label: string }[] = [
  { value: 'lastReadTime', label: '最近阅读' },
  { value: 'createdAt', label: '最新添加' },
  { value: 'updatedAt', label: '最近更新' },
  { value: 'title', label: '标题' },
  { value: 'pageCount', label: '页数' },
  { value: 'fileSize', label: '文件大小' },
]

const NONE_FILTER = '_NONE'
const KEYWORD_DEBOUNCE_MS = 300

function defaultFilters() {
  return {
    keyword: '',
    categoryFilter: '',
    selectedTags: [] as string[],
    tagMode: 'OR' as 'OR' | 'AND' | 'NOT',
    sort: 'createdAt' as NonNullable<ComicListQuery['sort']>,
    order: 'desc' as 'asc' | 'desc',
  }
}

export type ReadingComicFilters = ReturnType<typeof defaultFilters>

/** 阅读端只构建查找与阅读排序条件；管理状态及文件操作不进入此模型。 */
export function useReadingComicFilters(onApply: () => void) {
  const filters = reactive(defaultFilters())
  let keywordTimer: ReturnType<typeof setTimeout> | undefined
  watch(
    () => filters.selectedTags,
    (tags, previousTags) => {
      if (tags.includes(NONE_FILTER) && tags.length > 1) {
        filters.selectedTags = previousTags.includes(NONE_FILTER)
          ? tags.filter((tag) => tag !== NONE_FILTER)
          : [NONE_FILTER]
      }
      if (!filters.selectedTags.length || filters.selectedTags.includes(NONE_FILTER)) filters.tagMode = 'OR'
    },
    { flush: 'sync' },
  )

  function cancelPendingSearch() {
    if (keywordTimer !== undefined) clearTimeout(keywordTimer)
    keywordTimer = undefined
  }
  function applyFilters() {
    cancelPendingSearch()
    onApply()
  }
  function scheduleKeywordSearch() {
    cancelPendingSearch()
    keywordTimer = setTimeout(applyFilters, KEYWORD_DEBOUNCE_MS)
  }
  function clearKeyword() {
    filters.keyword = ''
    applyFilters()
  }
  function clearFilters() {
    Object.assign(filters, defaultFilters())
    applyFilters()
  }
  function selectCategory(category: string) {
    filters.categoryFilter = category
    applyFilters()
  }
  function toggleTag(tag: string) {
    if (filters.selectedTags.includes(tag)) filters.selectedTags = filters.selectedTags.filter((name) => name !== tag)
    else if (tag === NONE_FILTER) filters.selectedTags = [NONE_FILTER]
    else filters.selectedTags = [...filters.selectedTags.filter((name) => name !== NONE_FILTER), tag]
    applyFilters()
  }
  function setTagMode(mode: ReadingComicFilters['tagMode']) {
    filters.tagMode = mode
    applyFilters()
  }
  function toggleSortOrder() {
    filters.order = filters.order === 'asc' ? 'desc' : 'asc'
    applyFilters()
  }
  function buildQuery(): Partial<ComicListQuery> {
    const tags = [...new Set(filters.selectedTags.map((tag) => tag.trim()).filter(Boolean))]
    return {
      keyword: filters.keyword.trim() || undefined,
      category: filters.categoryFilter || undefined,
      tags: tags.length ? tags : undefined,
      tag: undefined,
      tagMode: tags.length && !tags.includes(NONE_FILTER) ? filters.tagMode : 'OR',
      sort: filters.sort,
      order: filters.order,
      status: 'READY',
    }
  }
  function restoreFilters(query: Partial<ComicListQuery>) {
    Object.assign(filters, defaultFilters(), {
      keyword: query.keyword || '',
      categoryFilter: query.category || '',
      selectedTags: [...(query.tags || (query.tag ? [query.tag] : []))],
      tagMode: query.tagMode === 'AND' || query.tagMode === 'NOT' ? query.tagMode : 'OR',
      sort: READING_SORT_OPTIONS.some((option) => option.value === query.sort) ? query.sort : 'createdAt',
      order: query.order === 'asc' ? 'asc' : 'desc',
    })
    if (filters.selectedTags.includes(NONE_FILTER)) {
      filters.selectedTags = [NONE_FILTER]
      filters.tagMode = 'OR'
    }
    if (!filters.selectedTags.length) filters.tagMode = 'OR'
  }

  const activeFilterSummary = computed(() => {
    const summary: string[] = []
    if (filters.keyword.trim()) summary.push(`搜索：${filters.keyword.trim()}`)
    if (filters.categoryFilter)
      summary.push(`分类：${filters.categoryFilter === NONE_FILTER ? '未分类' : filters.categoryFilter}`)
    if (filters.selectedTags.length) {
      const mode = { OR: '任一', AND: '全部', NOT: '排除' }[filters.tagMode]
      summary.push(
        filters.selectedTags.includes(NONE_FILTER) ? '无标签' : `标签${mode}：${filters.selectedTags.join('、')}`,
      )
    }
    return summary
  })
  const hasActiveFilters = computed(() => activeFilterSummary.value.length > 0)
  if (getCurrentScope()) onScopeDispose(cancelPendingSearch)
  return {
    filters,
    ...toRefs(filters),
    activeFilterSummary,
    hasActiveFilters,
    buildQuery,
    restoreFilters,
    applyFilters,
    scheduleKeywordSearch,
    clearKeyword,
    clearFilters,
    selectCategory,
    toggleTag,
    setTagMode,
    toggleSortOrder,
  }
}
