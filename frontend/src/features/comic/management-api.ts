import { api } from '@/services/http'
import type { OperationSubmitResult, PageResult } from '@/shared/api/types'
import type { CatalogNode, CategoryDTO, ComicDetailVO, ComicListQuery, ComicListVO } from '@/entities/comic/types'
import type { ComicTagUpdateDTO, TagCreateDTO, TagDTO } from '@/entities/tag/types'
import type {
  BatchComicUpdateDTO,
  CatalogManagementRequest,
  CatalogVO,
  ChapterManagementRequest,
  ChapterManagementVO,
  ComicMetadataDTO,
  ComicMetadataUpdateDTO,
  MediaReorderRequest,
  MediaReorderResult,
} from '@/entities/comic/management-types'

export const managementChapterApi = {
  detail: (chapterId: number) => api.get(`/manage/chapters/${chapterId}`),
}

export const managementCatalogApi = {
  tree: (comicId: number) => api.get<readonly CatalogNode[]>(`/manage/comics/${comicId}/catalog`),
}

export const managementComicApi = {
  list: (params?: ComicListQuery) => api.get<PageResult<ComicListVO>>('/manage/comics', { params }),
  detail: (id: number) => api.get<ComicDetailVO>(`/manage/comics/${id}`),
  getMetadata: (id: number) => api.get<ComicMetadataDTO>(`/manage/comics/${id}/metadata`),
  getTags: (id: number) => api.get<number[]>(`/manage/comics/${id}/tags`),
  delete: (id: number) => api.delete(`/manage/comics/${id}`),
  updateMetadata: (id: number, data: ComicMetadataUpdateDTO) => api.put(`/manage/comics/${id}/metadata`, data),
  updateTags: (id: number, data: ComicTagUpdateDTO) => api.put(`/manage/comics/${id}/tags`, data),
  batchUpdate: (data: BatchComicUpdateDTO) => api.post('/manage/comics/batch/update', data),
}

export const managementTagApi = {
  list: () => api.get<TagDTO[]>('/manage/tags'),
  create: (data: TagCreateDTO) => api.post<TagDTO>('/manage/tags', data),
  delete: (id: number) => api.delete(`/manage/tags/${id}`),
}

export const managementCategoryApi = {
  list: () => api.get<CategoryDTO[]>('/manage/categories'),
  create: (name: string) => api.post<CategoryDTO>('/manage/categories', null, { params: { name } }),
  update: (id: number, name: string) => api.put<CategoryDTO>(`/manage/categories/${id}`, null, { params: { name } }),
  delete: (id: number) => api.delete(`/manage/categories/${id}`),
}

export const catalogManagementApi = {
  create: (comicId: number, data: CatalogManagementRequest) =>
    api.post<CatalogVO>(`/manage/comics/${comicId}/catalogs`, data),
  rename: (comicId: number, catalogId: number, data: CatalogManagementRequest) =>
    api.patch<CatalogVO>(`/manage/comics/${comicId}/catalogs/${catalogId}`, data),
  move: (comicId: number, catalogId: number, data: CatalogManagementRequest) =>
    api.put<CatalogVO>(`/manage/comics/${comicId}/catalogs/${catalogId}/move`, data),
  reorder: (comicId: number, catalogId: number, data: CatalogManagementRequest) =>
    api.put(`/manage/comics/${comicId}/catalogs/${catalogId}/reorder`, data),
  delete: (comicId: number, catalogId: number, reparentTo?: number) =>
    api.delete(`/manage/comics/${comicId}/catalogs/${catalogId}`, { params: { reparentTo } }),
}

export const chapterManagementApi = {
  create: (comicId: number, data: ChapterManagementRequest) =>
    api.post<ChapterManagementVO>(`/manage/comics/${comicId}/chapters`, data),
  rename: (comicId: number, chapterId: number, data: ChapterManagementRequest) =>
    api.patch<ChapterManagementVO>(`/manage/comics/${comicId}/chapters/${chapterId}`, data),
  move: (comicId: number, chapterId: number, data: ChapterManagementRequest) =>
    api.put<ChapterManagementVO>(`/manage/comics/${comicId}/chapters/${chapterId}/move`, data),
  reorder: (comicId: number, chapterId: number, data: ChapterManagementRequest) =>
    api.put<ChapterManagementVO>(`/manage/comics/${comicId}/chapters/${chapterId}/reorder`, data),
  trash: (comicId: number, chapterId: number) =>
    api.delete(`/manage/comics/${comicId}/chapters/${chapterId}`),
}

export const mediaManagementApi = {
  reorder: (chapterId: number, data: MediaReorderRequest) =>
    api.post<MediaReorderResult>(`/manage/chapters/${chapterId}/media/reorder`, data),
  trash: (mediaId: number) =>
    api.delete<OperationSubmitResult>(`/manage/media/${mediaId}`),
}
