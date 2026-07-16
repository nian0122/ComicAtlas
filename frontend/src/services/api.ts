import axios from 'axios'
import type {
  ComicMetadataUpdateDTO,
  TagCreateDTO,
  ComicTagUpdateDTO,
  CoverUpdateDTO,
} from '@/types'

const api = axios.create({ baseURL: '/api' })

api.interceptors.response.use(
  (response) => {
    const data = response.data
    if (data && typeof data === 'object' && 'code' in data && 'data' in data) {
      response.data = data.data
    }
    return response
  },
  (error) => Promise.reject(error)
)

export const comicApi = {
  list: (params: any) => api.get('/comics', { params }),
  detail: (id: number) => api.get(`/comics/${id}`),
  delete: (id: number) => api.delete(`/comics/${id}`),
  getMetadata: (id: number) => api.get(`/comics/${id}/metadata`),
  updateMetadata: (id: number, data: ComicMetadataUpdateDTO) =>
    api.put(`/comics/${id}/metadata`, data),
  getTags: (id: number) => api.get(`/comics/${id}/tags`),
  updateTags: (id: number, data: ComicTagUpdateDTO) =>
    api.put(`/comics/${id}/tags`, data),
  listCoverCandidates: (id: number) => api.get(`/comics/${id}/covers/candidates`),
  updateCover: (id: number, data: CoverUpdateDTO) =>
    api.put(`/comics/${id}/cover`, data),
}

export const catalogApi = {
  tree: (comicId: number) => api.get(`/comics/${comicId}/catalog`),
}

export const readerApi = {
  chapter: (chapterId: number) => api.get(`/chapters/${chapterId}`),
}

export const importApi = {
  create: (sourceType: string, sourcePath: string) =>
    api.post('/tasks/import', { sourceType, sourcePath }),
  list: (params: any) => api.get('/tasks/import', { params }),
  detail: (id: number) => api.get(`/tasks/import/${id}`),
  status: (id: number) => api.get(`/tasks/import/${id}/status`),
  cancel: (id: number) => api.post(`/tasks/import/${id}/cancel`),
  retry: (id: number) => api.post(`/tasks/import/${id}/retry`),
}

export const historyApi = {
  list: () => api.get('/history'),
  get: (comicId: number) => api.get(`/history/${comicId}`),
  update: (comicId: number, data: { chapterId: number; pageNumber: number }) =>
    api.put(`/history/${comicId}`, data),
}

export const tagApi = {
  list: () => api.get('/tags'),
  create: (data: TagCreateDTO) => api.post('/tags', data),
  delete: (id: number) => api.delete(`/tags/${id}`),
}

export const lqApi = {
  generateComic: (comicId: number) => api.post(`/comics/${comicId}/lq`),
  generateChapter: (chapterId: number) => api.post(`/chapters/${chapterId}/lq`),
}

export default api
