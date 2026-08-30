import { onBeforeUnmount, reactive, watch } from 'vue'
import type { ComicListQuery } from '@/entities/comic/types'
import type { useManagementComicStore } from '@/features/comic/management-store'

type ManagementComicStore = ReturnType<typeof useManagementComicStore>

const KEYWORD_SEARCH_DEBOUNCE_MS = 300

export function useManagementComicFilters(
  comicStore: ManagementComicStore,
  onApply?: () => void,
) {
  const filters = reactive({
    keyword: '',
    category: '',
    status: '',
    tags: [] as string[],
    tagMode: 'OR' as 'AND' | 'OR' | 'NOT',
    sort: 'createdAt',
    order: 'desc' as 'asc' | 'desc',
  })

  let keywordSearchTimer: ReturnType<typeof setTimeout> | null = null

  watch(() => filters.tags, (tags) => {
    if (tags.includes('_NONE') && tags.length > 1) filters.tags = ['_NONE']
    if (tags.includes('_NONE') && filters.tagMode === 'NOT') filters.tagMode = 'OR'
    if (tags.length === 0 && filters.tagMode !== 'OR') filters.tagMode = 'OR'
  }, { deep: true })

  function cancelPendingKeywordSearch() {
    if (keywordSearchTimer === null) return
    clearTimeout(keywordSearchTimer)
    keywordSearchTimer = null
  }

  function buildQuery(): Partial<ComicListQuery> {
    const normalizedTags = filters.tags.includes('_NONE') ? ['_NONE'] : [...filters.tags]
    return {
      keyword: filters.keyword || undefined,
      category: filters.category || undefined,
      status: filters.status || undefined,
      tags: normalizedTags.length > 0 ? normalizedTags : undefined,
      tagMode: normalizedTags.length === 0 || normalizedTags.includes('_NONE') ? 'OR' : filters.tagMode,
      sort: filters.sort as ComicListQuery['sort'],
      order: filters.order,
    }
  }

  function applyFilters() {
    cancelPendingKeywordSearch()
    const normalizedTags = filters.tags.includes('_NONE') ? ['_NONE'] : [...filters.tags]
    filters.tags = normalizedTags
    if (normalizedTags.length === 0 || normalizedTags.includes('_NONE')) filters.tagMode = 'OR'
    onApply?.()
    void comicStore.search(buildQuery())
  }

  function scheduleKeywordSearch() {
    cancelPendingKeywordSearch()
    keywordSearchTimer = setTimeout(() => {
      keywordSearchTimer = null
      applyFilters()
    }, KEYWORD_SEARCH_DEBOUNCE_MS)
  }

  function applyKeywordSearchImmediately() {
    cancelPendingKeywordSearch()
    applyFilters()
  }

  function resetFilters() {
    cancelPendingKeywordSearch()
    filters.keyword = ''
    filters.category = ''
    filters.status = ''
    filters.tags = []
    filters.tagMode = 'OR'
    filters.sort = 'createdAt'
    filters.order = 'desc'
    onApply?.()
    comicStore.resetQuery()
    void comicStore.fetchList()
  }

  function restoreFiltersFromStore() {
    filters.keyword = comicStore.query.keyword || ''
    filters.category = comicStore.query.category || ''
    filters.status = comicStore.query.status || ''
    filters.tags = [...(comicStore.query.tags || [])]
    filters.tagMode = comicStore.query.tagMode === 'AND' || comicStore.query.tagMode === 'NOT'
      ? comicStore.query.tagMode
      : 'OR'
    filters.sort = comicStore.query.sort || 'createdAt'
    filters.order = comicStore.query.order || 'desc'
  }

  onBeforeUnmount(cancelPendingKeywordSearch)

  return {
    filters,
    buildQuery,
    applyFilters,
    scheduleKeywordSearch,
    applyKeywordSearchImmediately,
    resetFilters,
    restoreFiltersFromStore,
    cancelPendingKeywordSearch,
  }
}
