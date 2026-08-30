import { api } from '@/services/http'

export const readerApi = {
  chapter: (chapterId: number) => api.get(`/chapters/${chapterId}`),
}

export const managementReaderApi = {
  chapter: (chapterId: number) => api.get(`/manage/chapters/${chapterId}`),
}

export const historyApi = {
  list: () => api.get('/history'),
  page: (page: number, size: number) => api.get('/history/page', { params: { page, size } }),
  get: (comicId: number) => api.get(`/history/${comicId}`),
  update: (comicId: number, data: { chapterId: number; pageNumber: number }) =>
    api.put(`/history/${comicId}`, data),
}
