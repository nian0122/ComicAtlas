import { api } from '@/services/http'
import type { HistoryPageVO, HistoryVO } from '@/features/history/types'

export const historyApi = {
  list: () => api.get<HistoryVO[]>('/history'),
  page: (page: number, size: number) => api.get<HistoryPageVO>('/history/page', { params: { page, size } }),
  get: (comicId: number) => api.get<HistoryVO | null>(`/history/${comicId}`),
  update: (comicId: number, data: { chapterId: number; pageNumber: number }) =>
    api.put(`/history/${comicId}`, data),
}
