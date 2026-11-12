export interface FileRow {
  id: string
  title: string
  kind: 'comic' | 'catalog' | 'chapter' | 'image' | 'video'
  subtitle: string
  cover: string | null
  previewImages: string[]
  bytes: number | null
  count: number | null
  hq: string | null
  lq: string | null
}
