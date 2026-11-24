export type TaskStatus = 'queued' | 'running' | 'cancelling' | 'cancelled' | 'succeeded' | 'failed'
export interface DirectoryTarget {
  id: string
  title: string
  cover: string | null
}
export interface DirectoryResult {
  position: number
  comicId: string
  source: string
  target: string
  size: number
  modified: number
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
