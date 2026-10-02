import { api } from '@/shared/api/http'
import type { MediaReaction } from '../types'

export type ContentReactionTarget = 'COMIC' | 'CHAPTER'

export interface ContentReactionVO {
  id: number
  targetType: ContentReactionTarget
  comicId?: number
  title: string | null
  reaction: MediaReaction
  reactionAt: string | null
  status: string | null
}

export const contentReactionApi = {
  list: (
    targetType: ContentReactionTarget,
    params?: { reaction?: Exclude<MediaReaction, 'NONE'>; includeTrashed?: boolean },
  ) => api.get<ContentReactionVO[]>('/manage/content/reactions', { params: { targetType, ...params } }),
  updateBatch: (targetType: ContentReactionTarget, ids: number[], reaction: MediaReaction) =>
    api.put<number>('/manage/content/reactions/batch', { ids, reaction }, { params: { targetType } }),
  trashBatch: (targetType: ContentReactionTarget, ids: number[]) =>
    api.post('/manage/content/reactions/batch/trash', { ids }, { params: { targetType } }),
}
