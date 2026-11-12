export interface ChapterResponse {
  comicId: string
  chapter: { id: string; title: string; globalOrder: number }
  items: {
    id: string
    pageNumber: number
    contentUrl: string
    autoContentUrl: string
    width: number | null
    height: number | null
    mediaType: 'image' | 'video'
    fileName: string
    size: number
    hqState: string
    previewUrl: string | null
    lq: { state: string; available: boolean; contentUrl: string | null } | null
  }[]
  previousChapterId: string | null
  nextChapterId: string | null
}
