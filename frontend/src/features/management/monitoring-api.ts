import { api } from '@/services/http'
import type { MediaOperationResult, MqStats, OutboxStats } from './types'

export const mediaOperationApi = {
  forComic: (comicId: number) => api.get<MediaOperationResult>(`/manage/operations/comics/${comicId}`),
  forChapter: (chapterId: number) => api.get<MediaOperationResult>(`/manage/operations/chapters/${chapterId}`),
  forMedia: (mediaId: number) => api.get<MediaOperationResult>(`/manage/operations/media/${mediaId}`),
}

export const outboxApi = {
  stats: () => api.get<OutboxStats>('/manage/outbox/stats'),
}

export const mqApi = {
  stats: () => api.get<MqStats>('/manage/mq/stats'),
}
