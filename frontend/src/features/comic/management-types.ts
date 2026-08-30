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

export interface BatchUpdateResultVO {
  total: number
  succeeded: number
  failed: BatchFailedItem[]
}

export interface BatchFailedItem {
  comicId: number
  title: string | null
  reason: string
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
  readonly targetGlobalOrder?: number
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
