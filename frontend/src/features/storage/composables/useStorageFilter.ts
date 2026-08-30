import { ref, computed, watch } from 'vue'
import type { ComicStorageItem, ComicStorageQuery } from '@/features/storage/types'

export type HqStatusFilter = 'ALL' | 'HAS_HQ' | 'NO_HQ'
export type LqStatusFilter = 'ALL' | 'NEEDS_LQ' | 'READY'
export type SortField = 'totalSize' | 'hqSize' | 'lqSize' | 'title'

export interface FilterState {
  hqStatus: HqStatusFilter
  lqStatus: LqStatusFilter
  keyword: string
  category: string
  tag: string
}

export interface SortState {
  field: SortField
  order: 'asc' | 'desc'
}

export interface PaginationState {
  page: number
  pageSize: number
  total: number
}

export function useStorageFilter(
  getComicList: () => ComicStorageItem[],
  getServerTotal: () => number,
) {
  const filter = ref<FilterState>({
    hqStatus: 'ALL',
    lqStatus: 'ALL',
    keyword: '',
    category: '',
    tag: '',
  })

  const sort = ref<SortState>({
    field: 'totalSize',
    order: 'desc',
  })

  const page = ref(1)
  const pageSize = ref(20)

  watch(
    [() => filter.value.hqStatus, () => filter.value.lqStatus, () => filter.value.keyword, () => filter.value.category, () => filter.value.tag],
    () => {
      page.value = 1
    },
  )

  watch(pageSize, () => {
    page.value = 1
  })

  watch([() => sort.value.field, () => sort.value.order], () => {
    page.value = 1
  })

  const filteredList = computed(() => {
    const list = getComicList()
    return Array.isArray(list) ? list : []
  })

  const pagedList = computed(() => filteredList.value)

  const pagination = computed<PaginationState>(() => ({
    page: page.value,
    pageSize: pageSize.value,
    total: getServerTotal(),
  }))

  function buildQuery(): ComicStorageQuery {
    return {
      page: page.value,
      size: pageSize.value,
      hqStatus: filter.value.hqStatus,
      lqStatus: filter.value.lqStatus,
      sort: sort.value.field,
      order: sort.value.order,
      keyword: filter.value.keyword || undefined,
      category: filter.value.category || undefined,
      tag: filter.value.tag || undefined,
    }
  }

  return { filter, sort, page, pageSize, filteredList, pagedList, pagination, buildQuery }
}
