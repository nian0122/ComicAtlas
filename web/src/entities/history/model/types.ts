export interface Progress {
  comicId: string
  chapterId: string
  mediaId: string
  pageNumber: number
  updatedAt: string
}
export interface HistoryVO extends Progress {
  completed: boolean
  title: string
  coverUrl: string
  chapterTitle: string
  contentUrl: string
}
export interface HistoryPage {
  items: HistoryVO[]
  total: number
  page: number
  pageSize: number
}
