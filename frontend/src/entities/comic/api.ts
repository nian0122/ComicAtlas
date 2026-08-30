import { api } from '@/services/http'
import type { BatchComicUpdateDTO, ComicMetadataUpdateDTO } from '@/features/comic/management-types'
import type { PageResult } from '@/shared/api/types'
import type { CategoryDTO, ComicDetailVO, ComicListQuery, ComicListVO } from '@/entities/comic/types'
import type { ComicTagUpdateDTO, TagCreateDTO } from '@/entities/tag/types'

export const comicApi = {
  list: (params?: ComicListQuery) => api.get<PageResult<ComicListVO>>('/comics', { params }),
  detail: (id: number) => api.get<ComicDetailVO>(`/comics/${id}`),
  getMetadata: (id: number) => api.get(`/comics/${id}/metadata`),
  getTags: (id: number) => api.get(`/comics/${id}/tags`),
}

export const catalogApi = {
  tree: (comicId: number) => api.get(`/comics/${comicId}/catalog`),
}

export const managementCatalogApi = {
  tree: (comicId: number) => api.get(`/manage/comics/${comicId}/catalog`),
}

export const managementComicApi = {
  list: (params?: ComicListQuery) => api.get<PageResult<ComicListVO>>('/manage/comics', { params }),
  detail: (id: number) => api.get<ComicDetailVO>(`/manage/comics/${id}`),
  getMetadata: (id: number) => api.get(`/manage/comics/${id}/metadata`),
  getTags: (id: number) => api.get(`/manage/comics/${id}/tags`),
  delete: (id: number) => api.delete(`/manage/comics/${id}`),
  updateMetadata: (id: number, data: ComicMetadataUpdateDTO) => api.put(`/manage/comics/${id}/metadata`, data),
  updateTags: (id: number, data: ComicTagUpdateDTO) => api.put(`/manage/comics/${id}/tags`, data),
  batchUpdate: (data: BatchComicUpdateDTO) => api.post('/manage/comics/batch/update', data),
}

export const readingTagApi = {
  list: () => api.get<TagCreateDTO[]>('/tags'),
}

export const readingCategoryApi = {
  list: () => api.get<CategoryDTO[]>('/categories'),
}

export const managementTagApi = {
  list: () => api.get('/manage/tags'),
  create: (data: TagCreateDTO) => api.post('/manage/tags', data),
  delete: (id: number) => api.delete(`/manage/tags/${id}`),
}

export const managementCategoryApi = {
  list: () => api.get('/manage/categories'),
  create: (name: string) => api.post('/manage/categories', null, { params: { name } }),
  update: (id: number, name: string) => api.put(`/manage/categories/${id}`, null, { params: { name } }),
  delete: (id: number) => api.delete(`/manage/categories/${id}`),
}
