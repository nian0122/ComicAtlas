import type { StorageQuery } from '@/entities/storage'

export const LIBRARY_FILTERS_KEY = 'comicatlas.management.library-filters'
export const LIBRARY_FILTERS_TAG_LIMIT = 50

export type LibrarySort = NonNullable<StorageQuery['sort']>
export type LibraryTagMode = NonNullable<StorageQuery['tagMode']>
export type LibraryAttention = NonNullable<StorageQuery['attention']>

export interface LibraryFilters {
  keyword: string
  category: string
  uncategorized: boolean
  tagIds: string[]
  tagMode: Exclude<LibraryTagMode, 'none'>
  untagged: boolean
  hq: '' | 'has_hq' | 'no_hq'
  lq: '' | 'needs_lq' | 'ready'
  attention: '' | LibraryAttention
  sort: LibrarySort
  sortOrder: 'asc' | 'desc'
}

const tagModes = new Set<LibraryFilters['tagMode']>(['any', 'all', 'not']),
  sorts = new Set<LibrarySort>(['title', 'createdAt', 'pages', 'totalBytes', 'hqBytes', 'lqBytes']),
  hqValues = new Set<LibraryFilters['hq']>(['', 'has_hq', 'no_hq']),
  lqValues = new Set<LibraryFilters['lq']>(['', 'needs_lq', 'ready']),
  attentions = new Set<LibraryFilters['attention']>(['', 'missing_lq', 'can_release']),
  orders = new Set<LibraryFilters['sortOrder']>(['asc', 'desc'])

export function defaultLibraryFilters(): LibraryFilters {
  return {
    keyword: '',
    category: '',
    uncategorized: false,
    tagIds: [],
    tagMode: 'any',
    untagged: false,
    hq: '',
    lq: '',
    attention: '',
    sort: 'title',
    sortOrder: 'asc',
  }
}

function text(value: unknown) {
  return typeof value === 'string' ? value : ''
}

function pick<T extends string>(value: unknown, allowed: Set<T>, fallback: T): T {
  return typeof value === 'string' && allowed.has(value as T) ? (value as T) : fallback
}

export function parseLibraryFilters(raw: string | null | undefined): LibraryFilters {
  if (!raw) return defaultLibraryFilters()
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    return defaultLibraryFilters()
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return defaultLibraryFilters()
  const record = value as Record<string, unknown>,
    uncategorized = record.uncategorized === true,
    untagged = record.untagged === true,
    tagIds = untagged
      ? []
      : [
          ...new Set(
            (Array.isArray(record.tagIds) ? record.tagIds : []).filter(
              (id): id is string => typeof id === 'string' && id !== '',
            ),
          ),
        ].slice(0, LIBRARY_FILTERS_TAG_LIMIT)
  return {
    keyword: text(record.keyword),
    category: uncategorized ? '' : text(record.category),
    uncategorized,
    tagIds,
    tagMode: tagIds.length ? pick(record.tagMode, tagModes, 'any') : 'any',
    untagged,
    hq: pick(record.hq, hqValues, ''),
    lq: pick(record.lq, lqValues, ''),
    attention: pick(record.attention, attentions, ''),
    sort: pick(record.sort, sorts, 'title'),
    sortOrder: pick(record.sortOrder, orders, 'asc'),
  }
}

export function readLibraryFilters(storage: Pick<Storage, 'getItem'> = localStorage): LibraryFilters {
  try {
    return parseLibraryFilters(storage.getItem(LIBRARY_FILTERS_KEY))
  } catch {
    return defaultLibraryFilters()
  }
}

export function writeLibraryFilters(filters: LibraryFilters, storage: Pick<Storage, 'setItem'> = localStorage): void {
  try {
    storage.setItem(LIBRARY_FILTERS_KEY, JSON.stringify(filters))
  } catch {
    // 本地存储不可用时只影响刷新恢复，不阻断筛选请求。
  }
}
