import { request } from '@/shared/api/http'
import type { Page, Statistics, StorageObject, StorageQuery, ChapterStorageQuery } from '../model/types'
export const storageApi = {
  statistics: () => request<Statistics>('/storage'),
  comics: (query: StorageQuery) => request<Page<StorageObject>>('/storage/comics', { query: { ...query } }),
  comic: (id: string) => request<StorageObject>(`/storage/comics/${encodeURIComponent(id)}`),
  chapters: (id: string, query: ChapterStorageQuery) =>
    request<Page<StorageObject>>(`/storage/comics/${encodeURIComponent(id)}/chapters`, { query: { ...query } }),
  chapter: (id: string) => request<StorageObject>(`/storage/chapters/${encodeURIComponent(id)}`),
}
