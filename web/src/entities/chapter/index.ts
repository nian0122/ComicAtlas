import { request } from '@/shared/api/http'
import type { ChapterResponse } from './model/types'
export const readerApi = {
  chapter: (id: string, signal?: AbortSignal) =>
    request<ChapterResponse>(`/chapters/${encodeURIComponent(id)}/reader`, { signal }),
}
export type { ChapterResponse } from './model/types'
