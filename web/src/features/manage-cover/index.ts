import { request } from '@/shared/api/http'
import type { CoverSelection } from '@/entities/comic'

export function selectComicCover(comicId: string, mediaId: string, version: number) {
  return request<CoverSelection>(`/comics/${encodeURIComponent(comicId)}/cover-selection`, {
    method: 'PUT',
    body: { mediaId, version },
  })
}
