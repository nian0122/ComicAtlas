import { request } from '@/shared/api/http'
import type { Progress, HistoryPage } from '../model/types'
export const historyApi = {
  page: (page: number, pageSize: number) => request<HistoryPage>('/history', { query: { page, pageSize } }),
  get: (comicId: string) => request<Progress | null>(`/comics/${encodeURIComponent(comicId)}/reading-progress`),
  update: (comicId: string, body: { chapterId: string; mediaId: string }, keepalive = false) =>
    request<Progress>(`/comics/${encodeURIComponent(comicId)}/reading-progress`, { method: 'PUT', body, keepalive }),
}
