import { computed, nextTick, ref, watch } from 'vue'
import type { ComicListQuery } from '@/entities/comic/types'

export const LIBRARY_SORT_OPTIONS: Array<{
  value: NonNullable<ComicListQuery['sort']>
  label: string
}> = [
  { value: 'lastReadTime', label: '最近阅读' },
  { value: 'createdAt', label: '最新添加' },
  { value: 'updatedAt', label: '最近更新' },
  { value: 'title', label: '标题' },
  { value: 'pageCount', label: '页数' },
  { value: 'fileSize', label: '文件大小' },
]

export function useLibraryFilters() {
  const keyword = ref('')
  const sort = ref<NonNullable<ComicListQuery['sort']>>('createdAt')
  const order = ref<NonNullable<ComicListQuery['order']>>('desc')
  const selectedTags = ref<string[]>([])
  const tagMode = ref<'AND' | 'OR'>('OR')
  const categoryFilter = ref('')
  const isMobileSortOpen = ref(false)

  const hasActiveFilters = computed(() => Boolean(keyword.value || categoryFilter.value || selectedTags.value.length))
  const activeFilterSummary = computed(() => {
    const summary: string[] = []
    if (keyword.value) summary.push(`搜索：${keyword.value}`)
    if (categoryFilter.value) {
      summary.push(`分类：${categoryFilter.value === '_NONE' ? '未分类' : categoryFilter.value}`)
    }
    if (selectedTags.value.length) {
      const tagText = selectedTags.value.map((tag) => tag === '_NONE' ? '无标签' : tag).join('、')
      const matchLabel = selectedTags.value.length > 1 && tagMode.value === 'AND' ? '全部匹配' : '任一匹配'
      summary.push(`标签：${tagText} · ${matchLabel}`)
    }
    return summary
  })
  const currentSortLabel = computed(
    () => LIBRARY_SORT_OPTIONS.find((option) => option.value === sort.value)?.label || '最新添加',
  )

  function clearKeyword() {
    keyword.value = ''
  }

  function clearFilters() {
    keyword.value = ''
    categoryFilter.value = ''
    selectedTags.value = []
    tagMode.value = 'OR'
  }

  function selectCategory(category: string) {
    categoryFilter.value = category
  }

  function toggleTag(tagName: string) {
    if (tagName === '_NONE') {
      selectedTags.value = selectedTags.value.includes('_NONE') ? [] : ['_NONE']
      return
    }

    selectedTags.value = selectedTags.value.includes(tagName)
      ? selectedTags.value.filter((name) => name !== tagName)
      : [...selectedTags.value.filter((name) => name !== '_NONE'), tagName]
  }

  function setTagMode(mode: 'AND' | 'OR') {
    tagMode.value = mode
  }

  function toggleSortOrder() {
    order.value = order.value === 'asc' ? 'desc' : 'asc'
  }

  function selectMobileSort(nextSort: NonNullable<ComicListQuery['sort']>) {
    sort.value = nextSort
    isMobileSortOpen.value = false
  }

  function buildQuery(): Partial<ComicListQuery> {
    return {
      keyword: keyword.value || undefined,
      category: categoryFilter.value || undefined,
      sort: sort.value,
      order: order.value,
      tags: selectedTags.value.length > 0 ? selectedTags.value : undefined,
      tagMode: selectedTags.value.length > 1 ? tagMode.value : undefined,
    }
  }

  watch(selectedTags, (tags) => {
    if (tags.includes('_NONE') && tags.length > 1) {
      void nextTick(() => {
        selectedTags.value = ['_NONE']
      })
    }
  }, { deep: true })

  return {
    keyword,
    sort,
    order,
    selectedTags,
    tagMode,
    categoryFilter,
    isMobileSortOpen,
    sortOptions: LIBRARY_SORT_OPTIONS,
    hasActiveFilters,
    activeFilterSummary,
    currentSortLabel,
    clearKeyword,
    clearFilters,
    selectCategory,
    toggleTag,
    setTagMode,
    toggleSortOrder,
    selectMobileSort,
    buildQuery,
  }
}
