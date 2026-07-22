import { ref, computed, watch } from 'vue'
import type { ComicStorageItem } from '@/types'

export type HqStatusFilter = 'ALL' | 'HAS_HQ' | 'NO_HQ'
export type LqStatusFilter = 'ALL' | 'NEEDS_LQ' | 'READY'
export type SortField = 'totalSize' | 'hqSize' | 'lqSize' | 'title'

export interface FilterState {
  hqStatus: HqStatusFilter
  lqStatus: LqStatusFilter
  keyword: string
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

export function useStorageFilter(getComicList: () => ComicStorageItem[]) {
  const filter = ref<FilterState>({
    hqStatus: 'ALL',
    lqStatus: 'ALL',
    keyword: '',
  })

  const sort = ref<SortState>({
    field: 'totalSize',
    order: 'desc',
  })

  const page = ref(1)
  const pageSize = ref(20)

  watch([() => filter.value.hqStatus, () => filter.value.lqStatus, () => filter.value.keyword], () => {
    page.value = 1
  })

  const filteredList = computed(() => {
    const list = getComicList()
    if (!Array.isArray(list)) return []
    let result = [...list]

    if (filter.value.keyword) {
      const kw = filter.value.keyword.toLowerCase()
      result = result.filter((item) => item.title.toLowerCase().includes(kw))
    }

    if (filter.value.hqStatus === 'HAS_HQ') {
      result = result.filter((item) => item.hqStatus === 'READY' || item.hqStatus === 'MIXED')
    } else if (filter.value.hqStatus === 'NO_HQ') {
      result = result.filter((item) => item.hqStatus === 'DELETED' || item.hqStatus === 'EMPTY')
    }

    if (filter.value.lqStatus === 'NEEDS_LQ') {
      result = result.filter((item) => item.lqStatus === 'NOT_GENERATED')
    } else if (filter.value.lqStatus === 'READY') {
      result = result.filter((item) => item.lqStatus === 'READY')
    }

    const { field, order } = sort.value
    const multiplier = order === 'asc' ? 1 : -1

    if (field === 'title') {
      result.sort((a, b) => multiplier * a.title.localeCompare(b.title))
    } else {
      result.sort((a, b) => multiplier * ((a[field] as number) - (b[field] as number)))
    }

    return result
  })

  const pagedList = computed(() => {
    const start = (page.value - 1) * pageSize.value
    return filteredList.value.slice(start, start + pageSize.value)
  })

  const pagination = computed<PaginationState>(() => ({
    page: page.value,
    pageSize: pageSize.value,
    total: filteredList.value.length,
  }))

  return { filter, sort, page, pageSize, filteredList, pagedList, pagination }
}
