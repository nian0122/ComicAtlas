type TaskStatus = 'queued' | 'running' | 'cancelling' | 'cancelled' | 'succeeded' | 'failed'
export interface DirectoryTarget {
  id: string
  title: string
  cover: string | null
}
export interface DirectoryPreview {
  id: string
  taskId: string
  status: TaskStatus
  ready: boolean
  bookCount: number
  confirmationToken?: string
  expiresAt?: string
  fileCount?: number
  bytes?: number
  moveTaskId?: string
}
export interface DirectoryResult {
  position: number
  comicId: string
  source: string
  target: string
  size: number
  sha256: string
  state: string
}
export interface DirectoryResults {
  taskId: string
  status: TaskStatus
  complete: boolean
  directory: string | null
  total: number
  page: number
  pageSize: number
  items: DirectoryResult[]
}
