import { request } from '@/shared/api/http'
import type { TrashEntry } from '../model/types'
export const trashApi = {
  list: (query: { page: number; pageSize: number; keyword?: string; state?: TrashEntry['state'] }) =>
    request<{ items: TrashEntry[]; total: number }>('/trash', { query }),
  detail: (id: string) => request<TrashEntry>(`/trash/${encodeURIComponent(id)}`),
}
