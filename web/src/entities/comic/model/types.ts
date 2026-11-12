export interface ComicListQuery {
  keyword?: string
  categoryId?: string
  tagId?: readonly string[]
  readStatus?: '' | 'read' | 'unread' | 'reading' | 'completed'
  sort?: 'createdAt' | 'title' | 'lastReadTime'
  order?: 'asc' | 'desc'
  page?: number
  pageSize?: number
}
export interface NodePreview {
  type: 'catalog' | 'chapter'
  id: string
  mediaCount: number
  images: { mediaId: string; previewUrl: string }[]
}
export interface Term {
  id: string
  name: string
  version: number
}
export interface ComicListVO {
  id: string
  title: string
  author: string
  coverUrl: string
  categoryName: string
  createdAt: string
  description: string
  tags: (Term & { source: string })[]
  category: Term | null
  version: number
}
export interface Chapter {
  id: string
  globalOrder: number
  title: string
  catalogId: string | null
  sortOrder: number
  mediaVersion: number
}
export interface ComicDetailVO extends ComicListVO {
  originalTitle: string | null
  chapters: Chapter[]
  catalogs: { id: string; parentId: string | null; title: string; sortOrder: number }[]
  structureVersion: number
}

export interface CoverSelection {
  comicId: string
  mediaId: string
  version: number
}
