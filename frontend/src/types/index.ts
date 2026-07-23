/** 默认图片宽高比（3:4），用于宽高未知的页面 */
export const DEFAULT_ASPECT_RATIO = 3 / 4

export interface ComicListQuery {
  keyword?: string
  tag?: string
  tags?: string[]
  tagMode?: 'AND' | 'OR'
  status?: string
  category?: string
  sourceType?: string
  sort?: 'createdAt' | 'updatedAt' | 'title' | 'pageCount' | 'lastReadTime'
  page?: number
  size?: number
}

export interface CategoryDTO {
  id: number
  name: string
  sortOrder: number
}

export interface ComicListVO {
  id: number
  title: string
  author: string
  coverUrl: string
  pageCount: number
  categoryId: number | null
  categoryName: string | null
  status: string
  progressPercent: number
  lastReadChapterId: number
  lastReadPage: number
  createdAt: string
}

export interface ComicDetailVO {
  id: number
  title: string
  titleJpn?: string
  author: string
  description?: string
  coverUrl: string
  pageCount: number
  fileSize: number
  sourceType: string
  sourceRef: string
  categoryId: number | null
  categoryName: string | null
  status: string
  progressPercent: number
  lastReadChapterId: number
  lastReadPage: number
  chapters: ChapterVO[]
  tags: TagRef[]
  createdAt: string
  updatedAt: string
}

export interface ChapterVO {
  id: number
  chapterNo: number
  title: string
  pageCount: number
}

export interface TagRef {
  name: string
  type: string
}

export interface CatalogNode {
  id: number | null
  title: string | null
  children: CatalogNode[]
  chapters: ChapterRef[]
}

export interface ChapterRef {
  id: number
  chapterNo: string
  title: string
  globalOrder: number
  pageCount: number
  status?: string
}

/** 媒体类型：图片或视频 */
export type MediaType = 'IMAGE' | 'VIDEO'

export interface MediaItemInfo {
  id: number
  pageNumber: number
  hqUrl: string
  lqUrl: string
  lqStatus: string
  width: number
  height: number
  /** 媒体类型，缺失时默认按 IMAGE 处理 */
  mediaType?: MediaType
  /** 视频时长（秒），仅 VIDEO 有意义 */
  duration?: number
  /** 视频容器格式，如 mp4/webm/mkv */
  container?: string
  /** 视频编码，如 h264/h265/vp9 */
  videoCodec?: string
  /** 音频编码，如 aac/opus */
  audioCodec?: string
}

export interface ReaderDTO {
  chapterId: number
  comicId: number
  chapterTitle: string
  pages: MediaItemInfo[]
  total: number
  prevChapterId: number | null
  nextChapterId: number | null
}

export interface ChapterPageVO {
  comicId: number
  chapterId: number
  chapterNo: string
  chapterTitle: string
  pages: MediaItemInfo[]
  total: number
  prevChapterId: number | null
  nextChapterId: number | null
}

export interface ImportTaskVO {
  id: number
  comicId: number
  sourceRef: string
  sourceType: string
  sourcePath: string
  status: string
  progress: number
  totalPages: number
  downloadedPages: number
  downloadMethod: string
  downloadSpeed: number
  etaSeconds: number
  batchId?: string
  errorMessage: string
  retryCount: number
  durationMs: number
  startTime: string
  endTime: string
  createdAt: string
}

export interface ImportStatusVO {
  taskId: number
  status: string
  progress: number
}

export interface ScanItemVO {
  name: string
  path: string
  imageCount: number
}

export interface ScanResultVO {
  parentPath: string
  total: number
  items: ScanItemVO[]
}

export interface BatchImportRequest {
  sourceType: string
  sourcePaths: string[]
}

export interface FailedItem {
  sourcePath: string
  errorMessage: string
}

export interface BatchImportResultVO {
  batchId: string
  total: number
  succeeded: ImportTaskVO[]
  failed: FailedItem[]
}

export interface HistoryVO {
  comicId: number
  comicTitle: string
  coverUrl: string
  chapterId: number
  chapterNo: string
  pageNumber: number
  totalPages: number
  progressPercent: number
  updatedAt: string
}

export interface ComicMetadataDTO {
  title: string
  author?: string
  description?: string
  categoryId?: number | null
}

export interface ComicMetadataUpdateDTO {
  title: string
  author?: string
  description?: string
  categoryId?: number | null
}

export interface TagDTO {
  id: number
  name: string
}

export interface TagCreateDTO {
  name: string
}

export interface ComicTagUpdateDTO {
  tagIds: number[]
}

/** 批量更新漫画分类和标签 */
export interface BatchComicUpdateDTO {
  comicIds: number[]
  categoryId?: number | null
  addTagIds?: number[]
}

/** 批量更新结果 */
export interface BatchUpdateResultVO {
  total: number
  succeeded: number
  failed: FailedItem[]
}

export interface FailedItem {
  comicId: number
  title: string | null
  reason: string
}

export const STATUS_COLOR_MAP: Record<string, string> = {
  PENDING: 'info',
  PARSING: 'warning',
  IMPORTING: 'warning',
  DOWNLOADING: 'warning',
  EXTRACTING: 'warning',
  SUCCESS: 'success',
  FAILED: 'danger',
  CANCELLED: 'info',
}

// ========== Storage Management Domain ==========

/** HQ 状态 */
export type HqStatus = 'READY' | 'DELETED' | 'MIXED' | 'EMPTY' | 'PENDING' | 'MISSING'

/** LQ 状态 */
export type LqStatus = 'READY' | 'NOT_GENERATED' | 'MIXED' | 'EMPTY' | 'FAILED' | 'QUEUED' | 'GENERATING'

/** 存储漫画列表项 */
export interface ComicStorageItem {
  comicId: number
  title: string
  coverUrl: string
  totalSize: number
  hqSize: number
  lqSize: number
  hqStatus: HqStatus
  lqStatus: LqStatus
  chapterCount: number
  pageCount: number
}

/** 存储章节列表项 */
export interface ChapterStorageItem {
  chapterId: number
  chapterNo: string
  title: string
  pageCount: number
  hqSize: number
  lqSize: number
  hqStatus: HqStatus
  lqStatus: LqStatus
}

/** 存储统计摘要 */
export interface StorageStats {
  totalBytes: number
  hqBytes: number
  lqBytes: number
  thumbBytes: number
  comicCount: number
}

/** 存储漫画查询参数 */
export interface ComicStorageQuery {
  page?: number
  size?: number
  hqStatus?: 'ALL' | 'HAS_HQ' | 'NO_HQ'
  lqStatus?: 'ALL' | 'NEEDS_LQ' | 'READY'
  sort?: 'totalSize' | 'hqSize' | 'lqSize' | 'title'
  order?: 'asc' | 'desc'
  keyword?: string
}

/** 存储操作类型 */
export enum StorageOperationType {
  DeleteHQ = 'DELETE_HQ',
  GenerateLQ = 'GENERATE_LQ',
}

/** 存储操作参数 */
export interface StorageOperation {
  type: StorageOperationType
  comicId: number
  chapterId?: number
}

// ========== Export Domain ==========

export interface ExportTaskVO {
  taskId: number
  comicId: number
  status: string // PENDING | RUNNING | SUCCESS | FAILED
  outputRoot?: string
  outputPath?: string
  outputSize: number
  physicalPath?: string
  errorMessage?: string
  createdAt: string
  completedAt?: string
}

export const EXPORT_STATUS_COLOR_MAP: Record<string, string> = {
  PENDING: 'info',
  RUNNING: 'warning',
  SUCCESS: 'success',
  FAILED: 'danger',
}
