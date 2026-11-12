export interface MetadataFields {
  id: string
  version: number
  originalTitle: string | null
  title: string
  author: string
  description: string
  categoryId: string | null
  tagIds: string[]
}
export interface MetadataPatch {
  title?: string
  author?: string
  description?: string
  categoryId?: string
  clearCategory?: boolean
  addTagIds?: string[]
  removeTagIds?: string[]
}
export interface MetadataFilter {
  keyword?: string
  categoryId?: string
  tagId?: string
  readStatus?: '' | 'read' | 'unread' | 'reading' | 'completed'
  reaction?: '' | 'any' | 'like' | 'dislike'
}
export type MetadataSelection =
  { mode: 'ids'; ids: string[] } | { mode: 'filter'; filter: MetadataFilter; excludedIds: string[] }
export interface MetadataRequest {
  operation: 'metadata_update'
  selection: MetadataSelection
  patch: MetadataPatch
}
export interface MetadataPreview {
  id: string
  operation: 'metadata_update'
  patch: MetadataPatch
  selectedCount: number
  eligibleCount: number
  blockedCount: number
  pendingCount: number
  succeededCount: number
  failedCount: number
  cancelledCount: number
  activeCount: number
  createdAt: string
  expiresAt: string
  taskId: string | null
  taskStatus: string | null
  completedAt: string | null
}
export interface MetadataItem {
  position: number
  comicId: string
  title: string
  version: number
  updatedVersion: number | null
  status: string
  errorCode: string | null
}
