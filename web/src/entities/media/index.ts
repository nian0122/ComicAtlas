export interface MediaLqInfo {
  state: string
  available: boolean
  contentUrl: string | null
}

export interface MediaItemInfo {
  id: string
  pageNumber: number
  contentUrl: string
  autoContentUrl: string
  width: number | null
  height: number | null
  mediaType: 'image' | 'video'
  hqState: string
  lq: MediaLqInfo | null
}
