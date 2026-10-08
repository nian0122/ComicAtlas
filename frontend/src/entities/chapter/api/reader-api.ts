import { api } from '@/shared/api/http'
import type { ReaderDTO } from '@/entities/chapter/model/reader-types'

export const readerApi = {
  chapter: (chapterId: number) => api.get<ReaderDTO>(`/chapters/${chapterId}`),
  updateReaction: (pageId: number, reaction: 'NONE' | 'LIKE' | 'DISLIKE') =>
    api.put<{ pageId: number; reaction: 'NONE' | 'LIKE' | 'DISLIKE'; reactionAt: string | null }>(
      `/pages/${pageId}/reaction`,
      { reaction },
    ),
  updateComicReaction: (comicId: number, reaction: 'NONE' | 'LIKE' | 'DISLIKE') =>
    api.put(`/comics/${comicId}/reaction`, { reaction }),
  updateChapterReaction: (chapterId: number, reaction: 'NONE' | 'LIKE' | 'DISLIKE') =>
    api.put(`/chapters/${chapterId}/reaction`, { reaction }),
}
