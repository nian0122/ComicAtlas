import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { BatchSelectionType } from '@/types/management/batch'
import type { BatchSelection, BatchSelectionType as BatchSelectionTypeUnion } from '@/types/management/batch'
import type { ComicListQuery } from '@/types'

/**
 * 批量选择 Store（T17）：持有 IDS/FILTER 两种选择模式，
 * 输出可直接提交给 BatchOperationController 的 BatchSelection 判别联合。
 */
export const useBatchSelectionStore = defineStore('batch-selection', () => {
  const mode = ref<BatchSelectionTypeUnion>(BatchSelectionType.Ids)
  const ids = ref<readonly number[]>([])
  const excludedIds = ref<readonly number[]>([])
  const query = ref<ComicListQuery | null>(null)

  const count = computed(() => ids.value.length)
  const hasSelection = computed(() => ids.value.length > 0)

  function setMode(next: BatchSelectionTypeUnion): void {
    mode.value = next
  }

  function toggle(id: number): void {
    ids.value = ids.value.includes(id)
      ? ids.value.filter((existing) => existing !== id)
      : [...ids.value, id]
  }

  function selectIds(next: readonly number[]): void {
    ids.value = [...next]
  }

  function setFilter(next: ComicListQuery, excluded: readonly number[] = []): void {
    query.value = next
    excludedIds.value = [...excluded]
    mode.value = BatchSelectionType.Filter
  }

  function addExcluded(id: number): void {
    if (!excludedIds.value.includes(id)) {
      excludedIds.value = [...excludedIds.value, id]
    }
  }

  function clear(): void {
    mode.value = BatchSelectionType.Ids
    ids.value = []
    excludedIds.value = []
    query.value = null
  }

  const selection = computed<BatchSelection>(() => {
    if (mode.value === BatchSelectionType.Filter && query.value) {
      return {
        type: BatchSelectionType.Filter,
        ...(query.value ? { query: query.value } : {}),
        ...(excludedIds.value.length > 0 ? { excludedIds: excludedIds.value } : {}),
      }
    }
    return { type: BatchSelectionType.Ids, ids: ids.value }
  })

  return {
    mode,
    ids,
    excludedIds,
    query,
    count,
    hasSelection,
    selection,
    setMode,
    toggle,
    selectIds,
    setFilter,
    addExcluded,
    clear,
  }
})
