export interface Page<T> {
  items: T[]
  page: number
  pageSize: number
  total: number
}
interface Usage {
  files: number
  bytes: number
}
export interface Inventory {
  scopeType: string
  scopeId: string | null
  comics: number
  chapters: number
  images: number
  videos: number
  hq: Usage
  lq: Usage
  covers: Usage
  currentLqImages: number
  availableImages?: number
  availableBytes?: number
  missingLq?: number
  missingSourceSha256Images: number
  releasedImages?: number
  releasingImages?: number
}
interface Capability {
  canSubmit: boolean
  blockers: string[]
}
export interface StorageObject {
  id: string
  comicId: string
  title: string
  globalOrder: number | null
  inventory: Inventory
  totalBytes: number
  hqStatus: string
  lqStatus: string
  operations: { refreshMetadata: Capability; transcode: Capability; generateLq: Capability; releaseHq: Capability }
  activeTasks: {
    id: string
    type: string
    targetType: string
    targetId: string
    status: string
    canCancel: boolean
    cancellationReason: string | null
  }[]
}
export interface Statistics {
  inventory: Inventory
  device: { totalBytes: number; availableBytes: number } | null
  observation: {
    taskId: string
    startedAt: string
    finishedAt: string
    usage: {
      hq: Usage
      lq: Usage
      covers: Usage
      metadata?: Usage
      temp: Usage
      trash: Usage
      exports: Usage
      retired: Usage
      staging: Usage
      other: Usage
      entries: number
      ignoredLinks: number
      ignoredSpecial: number
    }
  } | null
  refresh: {
    taskId: string
    status: string
    completedCount: number
    errorCode: string | null
    canCancel: boolean
    canRetry: boolean
  } | null
}
export interface ChapterStorageQuery extends Omit<StorageQuery, 'sort'> {
  sort?: 'title' | 'totalBytes' | 'hqBytes' | 'lqBytes' | 'globalOrder'
}
export interface StorageQuery {
  page: number
  pageSize: number
  keyword?: string
  categoryId?: string
  uncategorized?: boolean
  tagId?: readonly string[]
  tagMode?: 'any' | 'all' | 'not' | 'none'
  hqStatus?: string
  lqStatus?: string
  attention?: 'missing_lq' | 'can_release' | 'missing_source_sha256'
  sort?: 'title' | 'createdAt' | 'pages' | 'totalBytes' | 'hqBytes' | 'lqBytes'
  order?: 'asc' | 'desc'
}
