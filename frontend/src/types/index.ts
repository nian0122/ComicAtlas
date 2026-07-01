export interface ComicListQuery {
  keyword?: string
  tag?: string
  status?: string
  category?: string
  sourceType?: string
  sort?: 'createdAt' | 'updatedAt' | 'title' | 'pageCount' | 'lastReadTime'
  page?: number
  size?: number
}

export interface ComicListVO {
  id: number
  title: string
  author: string
  coverUrl: string
  pageCount: number
  category: string
  status: string
  lqStatus: string
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
  coverUrl: string
  pageCount: number
  fileSize: number
  sourceType: string
  sourceRef: string
  category: string
  status: string
  lqStatus: string
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

export interface PageInfo {
  id: number
  pageNumber: number
  hqUrl: string
  lqUrl: string
  lqStatus: string
  width: number
  height: number
}

export interface ReaderDTO {
  chapterId: number
  chapterTitle: string
  pages: PageInfo[]
  total: number
  prevChapterId: number | null
  nextChapterId: number | null
}

export interface ChapterPageVO {
  comicId: number
  chapterId: number
  chapterNo: string
  chapterTitle: string
  pages: PageInfo[]
  total: number
  prevChapterId: number | null
  nextChapterId: number | null
}

export interface ImportTaskVO {
  id: number
  comicId: number
  sourceRef: string
  status: string
  progress: number
  totalPages: number
  downloadedPages: number
  downloadMethod: string
  downloadSpeed: number
  etaSeconds: number
  errorMessage: string
  retryCount: number
  createdAt: string
}

export interface ImportStatusVO {
  taskId: number
  status: string
  progress: number
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

export interface StatisticsVO {
  comicCount: number
  pageCount: number
  tagCount: number
  todayImported: number
  storageUsed: number
  importSuccessCount: number
  importFailedCount: number
  successRate: number
}

export interface OperationLogVO {
  id: number
  traceId: string
  module: string
  action: string
  businessId: string
  detail: string
  createdAt: string
}

export const STATUS_COLOR_MAP: Record<string, string> = {
  PENDING: 'info',
  DOWNLOADING: 'warning',
  EXTRACTING: 'warning',
  PARSING: 'warning',
  LQ_GENERATING: 'warning',
  SUCCESS: 'success',
  FAILED: 'danger',
  CANCELLED: 'info',
}
