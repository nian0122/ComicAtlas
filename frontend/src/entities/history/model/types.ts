export interface HistoryVO {
  comicId: number
  comicTitle: string
  coverUrl: string
  chapterId: number
  chapterNo: string
  pageNumber: number
  totalPages: number
  progressPercent: number
  updatedAt: string
}

export interface HistoryPageVO {
  records: HistoryVO[]
  total: number
  current: number
  size: number
}
