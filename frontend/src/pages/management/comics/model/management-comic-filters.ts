import { computed, getCurrentScope, onScopeDispose, reactive, watch } from 'vue'
import { COMIC_STATUSES, comicStatusMeta } from '@/entities/comic'
import type { ManagementComicListQuery } from '@/entities/comic'
import type { useManagementComicStore } from './management-comic-store'

type ManagementComicStore = ReturnType<typeof useManagementComicStore>
type FilterOption<TValue extends string> = { value: TValue; label: string }
type Query = ManagementComicListQuery

export const STATUS_OPTIONS = COMIC_STATUSES.map((value) => ({ value, label: comicStatusMeta(value).label }))
export const SOURCE_OPTIONS: FilterOption<NonNullable<Query['sourceType']>>[] = [
  { value: 'DIRECTORY', label: '本地目录' },
  { value: 'ZIP', label: 'ZIP 压缩包' },
  { value: 'CBZ', label: 'CBZ 压缩包' },
  { value: 'EHENTAI', label: 'EHENTAI' },
]
export const HQ_OPTIONS: FilterOption<NonNullable<Query['hqStatus']>>[] = [
  { value: 'ALL_HQ', label: '全部可用' },
  { value: 'PARTIAL_HQ', label: '部分可用' },
  { value: 'HAS_HQ', label: '至少一页可用' },
  { value: 'NO_HQ', label: '没有可用 HQ' },
  { value: 'PENDING', label: '有待处理页' },
  { value: 'DELETE_QUEUED', label: '有删除排队页' },
  { value: 'DELETING', label: '有正在删除页' },
  { value: 'DELETED', label: '有已删除页' },
  { value: 'MISSING', label: '有缺失页' },
  { value: 'FAILED', label: '有失败页' },
]
export const LQ_OPTIONS: FilterOption<NonNullable<Query['lqStatus']>>[] = [
  { value: 'ALL_LQ', label: '全部图片可用' },
  { value: 'PARTIAL_LQ', label: '部分图片可用' },
  { value: 'HAS_LQ', label: '至少一张可用' },
  { value: 'NO_LQ', label: '没有可用 LQ' },
  { value: 'NOT_GENERATED', label: '有未生成图片' },
  { value: 'QUEUED', label: '有排队或生成中图片' },
  { value: 'GENERATING', label: '有正在生成图片' },
  { value: 'MISSING', label: '有缺失图片' },
  { value: 'FAILED', label: '有失败图片' },
]
export const SORT_OPTIONS: FilterOption<NonNullable<Query['sort']>>[] = [
  { value: 'createdAt', label: '创建时间' },
  { value: 'updatedAt', label: '更新时间' },
  { value: 'title', label: '标题' },
  { value: 'pageCount', label: '页数' },
  { value: 'fileSize', label: 'HQ 大小' },
]

const KEYWORD_DEBOUNCE_MS = 300
const NONE_FILTER = '_NONE'

function defaultFilters() {
  return {
    keyword: '',
    category: '',
    status: '' as '' | NonNullable<Query['status']>,
    sourceType: '' as '' | NonNullable<Query['sourceType']>,
    hqStatus: '' as '' | NonNullable<Query['hqStatus']>,
    lqStatus: '' as '' | NonNullable<Query['lqStatus']>,
    tags: [] as string[],
    tagMode: 'OR' as NonNullable<Query['tagMode']>,
    sort: 'createdAt' as NonNullable<Query['sort']>,
    order: 'desc' as NonNullable<Query['order']>,
  }
}

export type ManagementComicFilters = ReturnType<typeof defaultFilters>

/** 页面独立实例；所有控件通过同一查询构建和应用入口提交。 */
export function useManagementComicListFilters(store: ManagementComicStore, onApply: () => void) {
  const filters = reactive(defaultFilters())
  let keywordTimer: ReturnType<typeof setTimeout> | undefined

  watch(
    () => filters.tags,
    (tags, previousTags) => {
      if (tags.includes(NONE_FILTER) && tags.length > 1) {
        filters.tags = previousTags.includes(NONE_FILTER) ? tags.filter((tag) => tag !== NONE_FILTER) : [NONE_FILTER]
      }
      if (filters.tags.length === 0 || filters.tags.includes(NONE_FILTER)) filters.tagMode = 'OR'
    },
    { flush: 'sync' },
  )

  function cancelKeywordSearch() {
    if (keywordTimer !== undefined) clearTimeout(keywordTimer)
    keywordTimer = undefined
  }

  function buildQuery(): Partial<Query> {
    const tags = [...new Set(filters.tags.map((tag) => tag.trim()).filter(Boolean))]
    return {
      keyword: filters.keyword.trim() || undefined,
      category: filters.category || undefined,
      status: filters.status || undefined,
      sourceType: filters.sourceType || undefined,
      hqStatus: filters.hqStatus || undefined,
      lqStatus: filters.lqStatus || undefined,
      tags: tags.length ? tags : undefined,
      tag: undefined,
      tagMode: tags.length && !tags.includes(NONE_FILTER) ? filters.tagMode : 'OR',
      sort: filters.sort,
      order: filters.order,
    }
  }

  function applyFilters() {
    cancelKeywordSearch()
    onApply()
    return store.search(buildQuery())
  }

  function scheduleKeywordSearch() {
    cancelKeywordSearch()
    keywordTimer = setTimeout(() => void applyFilters(), KEYWORD_DEBOUNCE_MS)
  }

  function resetFilters() {
    Object.assign(filters, defaultFilters())
    return applyFilters()
  }

  function restoreFilters() {
    const defaults = defaultFilters()
    const query = store.query
    Object.assign(filters, {
      ...defaults,
      keyword: query.keyword || '',
      category: query.category || '',
      status: query.status || '',
      sourceType: query.sourceType || '',
      hqStatus: query.hqStatus || '',
      lqStatus: query.lqStatus || '',
      tags: [...(query.tags || (query.tag ? [query.tag] : []))],
      tagMode: query.tagMode || defaults.tagMode,
      sort: SORT_OPTIONS.some((option) => option.value === query.sort) ? query.sort : defaults.sort,
      order: query.order || defaults.order,
    })
    if (filters.tags.includes(NONE_FILTER)) filters.tags = [NONE_FILTER]
    if (!filters.tags.length || filters.tags.includes(NONE_FILTER)) filters.tagMode = 'OR'
  }

  const activeConditions = computed(() => {
    const conditions: string[] = []
    if (filters.keyword.trim()) conditions.push(`搜索：${filters.keyword.trim()}`)
    if (filters.category) conditions.push(`分类：${filters.category === NONE_FILTER ? '未分类' : filters.category}`)
    if (filters.status) conditions.push(`状态：${comicStatusMeta(filters.status).label}`)
    if (filters.sourceType)
      conditions.push(`来源：${SOURCE_OPTIONS.find((option) => option.value === filters.sourceType)?.label}`)
    if (filters.hqStatus)
      conditions.push(`HQ：${HQ_OPTIONS.find((option) => option.value === filters.hqStatus)?.label}`)
    if (filters.lqStatus)
      conditions.push(`LQ：${LQ_OPTIONS.find((option) => option.value === filters.lqStatus)?.label}`)
    if (filters.tags.length) {
      const mode = { OR: '任一', AND: '全部', NOT: '排除' }[filters.tagMode]
      conditions.push(filters.tags.includes(NONE_FILTER) ? '无标签' : `标签${mode}：${filters.tags.join('、')}`)
    }
    return conditions
  })

  if (getCurrentScope()) onScopeDispose(cancelKeywordSearch)
  return { filters, activeConditions, applyFilters, scheduleKeywordSearch, resetFilters, restoreFilters, buildQuery }
}
