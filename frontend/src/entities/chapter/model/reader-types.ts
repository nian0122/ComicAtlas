import type { MediaItemInfo } from '@/entities/media'

export interface ReaderDTO {
  chapterId: number
  comicId: number
  chapterTitle: string
  pages: MediaItemInfo[]
  total: number
  prevChapterId: number | null
  nextChapterId: number | null
  reaction?: 'NONE' | 'LIKE' | 'DISLIKE'
  reactionAt?: string | null
}

export interface ChapterPageVO {
  comicId: number
  chapterId: number
  chapterNo: string
  chapterTitle: string
  pages: MediaItemInfo[]
  total: number
  prevChapterId: number | null
  nextChapterId: number | null
}
