import { api } from '@/shared/api/http'
import type { MediaReaction } from '../types'

export interface MediaReactionVO {
  id: number
  chapterId: number
  pageNumber: number
  mediaType: 'IMAGE' | 'VIDEO'
  reaction: MediaReaction
  reactionAt: string | null
  status: string | null
}

export const mediaReactionApi = {
  list: (params?: {
    reaction?: Exclude<MediaReaction, 'NONE'>
    mediaType?: 'IMAGE' | 'VIDEO'
    includeTrashed?: boolean
  }) => api.get<MediaReactionVO[]>('/manage/media/reactions', { params }),
  updateBatch: (mediaIds: number[], reaction: MediaReaction) =>
    api.put<number>('/manage/media/reactions/batch', { mediaIds, reaction }),
  trashBatch: (mediaIds: number[]) => api.post('/manage/media/reactions/batch/trash', { mediaIds }),
}
