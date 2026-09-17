import type { MediaItemInfo } from '@/entities/media/types'

export interface ReaderDTO {
  chapterId: number
  comicId: number
  chapterTitle: string
  pages: MediaItemInfo[]
  total: number
  prevChapterId: number | null
  nextChapterId: number | null
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
