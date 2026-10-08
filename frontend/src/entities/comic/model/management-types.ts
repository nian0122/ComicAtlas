import type { ComicStatus } from './types'

/** 管理列表专属协议：生命周期、来源和文件完整性，不依赖阅读列表筛选范围。 */
export interface ManagementComicListQuery {
  keyword?: string
  tag?: string
  tags?: string[]
  tagMode?: 'AND' | 'OR' | 'NOT'
  status?: ComicStatus
  category?: string
  sourceType?: 'ZIP' | 'CBZ' | 'DIRECTORY' | 'EHENTAI'
  hqStatus?:
    | 'HAS_HQ'
    | 'ALL_HQ'
    | 'PARTIAL_HQ'
    | 'NO_HQ'
    | 'PENDING'
    | 'MISSING'
    | 'DELETE_QUEUED'
    | 'DELETING'
    | 'DELETED'
    | 'FAILED'
  lqStatus?:
    'HAS_LQ' | 'ALL_LQ' | 'PARTIAL_LQ' | 'NO_LQ' | 'NOT_GENERATED' | 'QUEUED' | 'GENERATING' | 'MISSING' | 'FAILED'
  sort?: 'createdAt' | 'updatedAt' | 'title' | 'pageCount' | 'fileSize' | 'lastReadTime'
  order?: 'asc' | 'desc'
  page?: number
  size?: number
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

export interface BatchComicUpdateDTO {
  comicIds: number[]
  categoryId?: number | null
  addTagIds?: number[]
}

/** 目录管理请求。 */
export interface CatalogManagementRequest {
  readonly title?: string
  readonly parentId?: number | null
  readonly sortOrder?: number
}

/** 目录管理视图。 */
export interface CatalogVO {
  readonly id: number
  readonly comicId: number
  readonly parentId: number | null
  readonly title: string
  readonly sortOrder: number | null
}

/** 章节管理请求。 */
export interface ChapterManagementRequest {
  readonly title?: string
  readonly chapterNo?: string
  readonly catalogId?: number | null
}

/** 同一父目录下目录与章节的完整顺序。 */
export interface StructureOrderRequest {
  readonly parentCatalogId: number | null
  readonly items: readonly { readonly type: 'CATALOG' | 'CHAPTER'; readonly id: number }[]
}

/** 章节管理视图。 */
export interface ChapterManagementVO {
  readonly id: number
  readonly comicId: number
  readonly catalogId: number | null
  readonly title: string
  readonly chapterNo: string | null
  readonly pageCount: number | null
  readonly sortOrder: number | null
  readonly globalOrder: number | null
  readonly status: string | null
}

/** 媒体重排请求。 */
export interface MediaReorderRequest {
  readonly mediaIds: readonly number[]
}

/** 媒体重排结果项。 */
export interface MediaReorderItem {
  readonly mediaId: number
  readonly pageNumber: number | null
}

/** 媒体重排结果。 */
export interface MediaReorderResult {
  readonly items: readonly MediaReorderItem[]
}
