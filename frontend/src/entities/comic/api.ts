import { api } from '@/services/http'
import type { PageResult } from '@/shared/api/types'
import type { CatalogNode, CategoryDTO, ComicDetailVO, ComicListQuery, ComicListVO } from '@/entities/comic/types'
import type { TagDTO } from '@/entities/tag/types'

export const comicApi = {
  list: (params?: ComicListQuery) => api.get<PageResult<ComicListVO>>('/comics', { params }),
  detail: (id: number) => api.get<ComicDetailVO>(`/comics/${id}`),
  getMetadata: (id: number) => api.get(`/comics/${id}/metadata`),
  getTags: (id: number) => api.get(`/comics/${id}/tags`),
}

export const catalogApi = {
  tree: (comicId: number) => api.get<readonly CatalogNode[]>(`/comics/${comicId}/catalog`),
}

export const readingTagApi = {
  list: () => api.get<TagDTO[]>('/tags'),
}

export const readingCategoryApi = {
  list: () => api.get<CategoryDTO[]>('/categories'),
}
