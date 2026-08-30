/** 漫画生命周期状态。 */
export type ComicStatus =
  | 'DRAFT'
  | 'IMPORTING'
  | 'IMPORT_FAILED'
  | 'READY'
  | 'RECOVERY_REQUIRED'
  | 'REFRESHING'
  | 'DELETING'
  | 'TRASHING'
  | 'TRASHED'
  | 'RESTORING'
  | 'PURGING'
  | 'DELETED'

export interface ComicListQuery {
  keyword?: string
  tag?: string
  tags?: string[]
  tagMode?: 'AND' | 'OR' | 'NOT'
  status?: string
  category?: string
  sourceType?: string
  sort?: 'createdAt' | 'updatedAt' | 'title' | 'pageCount' | 'lastReadTime' | 'fileSize'
  order?: 'asc' | 'desc'
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
  status: ComicStatus
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
  hqSize: number
  sourceType: string
  sourceRef: string
  categoryId: number | null
  categoryName: string | null
  status: ComicStatus
  progressPercent: number
  lastReadChapterId: number
  lastReadPage: number
  chapters: ChapterVO[]
  tags: TagRef[]
  comicInfo?: ComicInfoVO
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

export interface ComicInfoVO {
  series?: string
  title?: string
  number?: string
  writer?: string
  summary?: string
  tags: string[]
}

export interface CatalogNode {
  id: number | null
  title: string | null
  children: CatalogNode[]
  chapters: ChapterRef[]
  /** 目录在阅读顺序中的锚点（= 其下最小子项 globalOrder），用于与章节混合排布。 */
  globalOrder?: number | null
}

export interface ChapterRef {
  id: number
  chapterNo: string
  title: string
  globalOrder: number
  pageCount: number
  status?: string
}
