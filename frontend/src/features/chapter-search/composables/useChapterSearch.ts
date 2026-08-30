import { computed, ref, type Ref } from 'vue'
import type { CatalogNode } from '@/entities/comic/types'
import { countTreeChapters, filterChapterTree } from '../domain/chapter-search'

export function useChapterSearch(catalogTree: Ref<CatalogNode[]>) {
  const keyword = ref('')
  const searchTree = computed(() => filterChapterTree(catalogTree.value, keyword.value))
  const isSearching = computed(() => keyword.value.trim().length > 0)
  const filteredTree = computed(() => (isSearching.value ? searchTree.value.tree : catalogTree.value))
  const results = computed(() => searchTree.value.results)
  const resultCount = computed(() => (isSearching.value ? results.value.length : countTreeChapters(catalogTree.value)))
  const expandedNodePaths = computed(() => searchTree.value.expandedNodePaths)

  function clearSearch() {
    keyword.value = ''
  }

  return {
    keyword,
    isSearching,
    filteredTree,
    results,
    resultCount,
    expandedNodePaths,
    clearSearch,
  }
}
