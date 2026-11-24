import { request } from '@/shared/api/http'
import type { DirectoryResults } from '../model/types'
export const directoryMoveApi = {
  results: (id: string, page = 1) =>
    request<DirectoryResults>(`/tasks/${encodeURIComponent(id)}/directory-move-results`, {
      query: { page, pageSize: 20 },
    }),
}
