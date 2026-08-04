import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { workspaceApi } from '@/services/management/workspace'
import { toErrorMessage } from '@/services/management/http'
import { useBatchSelectionStore } from '@/stores/management/selection'
import type { ComicListParams, ComicListEntry } from '@/types/management/comic'

/**
 * 漫画列表 Store（T18）
 *
 * - 强类型列表（lifecycle/activeTask/allowedOperations 来自 API）
 * - 分页查询 + loading/error/empty 三态
 * - 选择状态托管在 useBatchSelectionStore（跨页保持 + FILTER 模式 + 排除）
 */

const PAGE_SIZE = 24

export interface ComicListUiState {
  readonly list: readonly ComicListEntry[]
  readonly total: number
  readonly loading: boolean
  readonly error: string | null
  readonly page: number
  readonly query: ComicListParams
}

export const useComicListStore = defineStore('comic-list', () => {
  const list = ref<readonly ComicListEntry[]>([])
  const total = ref(0)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const page = ref(1)
  const query = ref<ComicListParams>({ page: 1, size: PAGE_SIZE, sort: 'createdAt' })

  const hasMore = computed(() => list.value.length < total.value)
  const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

  const selection = useBatchSelectionStore()

  /** 当前筛选条件（选择筛选全部时作为 FILTER query 快照） */
  const filterQuery = computed<ComicListParams>(() => ({ ...query.value, page: undefined, size: undefined }))

  function applyQuery(patch: Partial<ComicListParams>): void {
    query.value = { ...query.value, ...patch }
  }

  async function fetchList(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const result = await workspaceApi.list(query.value)
      list.value = result.records
      total.value = result.total
    } catch (err: unknown) {
      error.value = toErrorMessage(err, '加载漫画列表失败')
      list.value = []
      total.value = 0
    } finally {
      loading.value = false
    }
  }

  /** 搜索：重置到第一页并携带筛选 */
  async function search(patch: Partial<ComicListParams>): Promise<void> {
    applyQuery({ ...patch, page: 1 })
    page.value = 1
    await fetchList()
  }

  async function goToPage(next: number): Promise<void> {
    page.value = next
    applyQuery({ page: next })
    await fetchList()
  }

  function reset(): void {
    query.value = { page: 1, size: PAGE_SIZE, sort: 'createdAt' }
    page.value = 1
    list.value = []
    total.value = 0
    error.value = null
  }

  // ========== 选择（委托 batch-selection store） ==========

  /** 选中当前页全部（IDS 模式） */
  function selectCurrentPage(): void {
    selection.selectIds(list.value.map((entry) => entry.id))
  }

  /** 选择筛选全部：切 FILTER 模式，排除当前页已取消的项 */
  function selectAllFiltered(): void {
    const currentIds = new Set(list.value.map((entry) => entry.id))
    const excluded = selection.excludedIds.filter((id) => !currentIds.has(id))
    selection.setFilter(filterQuery.value, excluded)
  }

  function clearSelection(): void {
    selection.clear()
  }

  return {
    list,
    total,
    loading,
    error,
    page,
    query,
    hasMore,
    totalPages,
    filterQuery,
    applyQuery,
    fetchList,
    search,
    goToPage,
    reset,
    selectCurrentPage,
    selectAllFiltered,
    clearSelection,
  }
})
