export interface OrderRow {
  id: string
  title: string
  kind: 'comic' | 'catalog' | 'chapter' | 'image' | 'video'
  subtitle: string
  bytes: number | null
  count: number | null
}
