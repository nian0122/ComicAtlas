import { request } from '@/shared/api/http'
import type { DirectoryPreview, DirectoryResults } from '../model/types'
export const directoryMoveApi = {
  preview: (id: string) => request<DirectoryPreview>(`/directory-move-previews/${encodeURIComponent(id)}`),
  results: (id: string, page = 1) =>
    request<DirectoryResults>(`/tasks/${encodeURIComponent(id)}/directory-move-results`, {
      query: { page, pageSize: 20 },
    }),
}
