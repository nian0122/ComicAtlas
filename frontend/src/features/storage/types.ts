export type HqStatus = 'READY' | 'DELETED' | 'MIXED' | 'EMPTY' | 'PENDING' | 'MISSING'
export type LqStatus = 'READY' | 'NOT_GENERATED' | 'MIXED' | 'EMPTY' | 'FAILED' | 'QUEUED' | 'GENERATING'

export interface ComicStorageItem {
  comicId: number
  title: string
  coverUrl: string
  totalSize: number
  hqSize: number
  lqSize: number
  hqStatus: HqStatus
  lqStatus: LqStatus
  mediaType: 'IMAGE' | 'VIDEO' | 'MIXED'
  transcodeStatus: 'NOT_NEEDED' | 'PENDING' | 'PROCESSING' | 'DONE' | 'FAILED'
  chapterCount: number
  pageCount: number
}

export interface ChapterStorageItem {
  chapterId: number
  chapterNo: string
  title: string
  pageCount: number
  hqSize: number
  lqSize: number
  hqStatus: HqStatus
  lqStatus: LqStatus
  mediaType: 'IMAGE' | 'VIDEO' | 'MIXED'
}

export interface StorageStats {
  totalBytes: number
  hqBytes: number
  lqBytes: number
  thumbBytes: number
  comicCount: number
}

export interface ComicStorageQuery {
  page?: number
  size?: number
  hqStatus?: 'ALL' | 'HAS_HQ' | 'NO_HQ'
  lqStatus?: 'ALL' | 'NEEDS_LQ' | 'READY'
  sort?: 'totalSize' | 'hqSize' | 'lqSize' | 'title'
  order?: 'asc' | 'desc'
  keyword?: string
  category?: string
  tag?: string
}

export const StorageOperationType = {
  DeleteHQ: 'DELETE_HQ',
  GenerateLQ: 'GENERATE_LQ',
  TranscodeVideos: 'TRANSCODE_VIDEOS',
  RefreshMetadata: 'REFRESH_METADATA',
} as const

export type StorageOperationType =
  (typeof StorageOperationType)[keyof typeof StorageOperationType]

export interface StorageOperation {
  type: StorageOperationType
  comicId: number
  chapterId?: number
  regenerate?: boolean
}

export interface ExportArtifactVO {
  index: number
  fileName: string
  size: number
  lastSegment: boolean
  physicalPath: string
}

export interface ExportTaskVO {
  id: number
  comicId: number
  format?: 'ZIP' | 'CBZ'
  status: string
  progress: number
  outputRoot?: string
  outputPath?: string
  outputSize: number
  errorMsg?: string
  createdAt: string
  completedAt?: string
}

export interface OperationSubmitResult {
  readonly taskId: number | null
  readonly taskType: string
  readonly status: string | null
  readonly itemCount: number
}
