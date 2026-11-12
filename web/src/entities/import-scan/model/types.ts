type TaskStatus = 'queued' | 'running' | 'cancelling' | 'cancelled' | 'succeeded' | 'failed'
export interface ImportCandidate {
  id: string
  sourcePath: string
  kind: 'directory' | 'unsupported'
  title: string
  selectable: boolean
}
export interface CandidatePage {
  items: ImportCandidate[]
  page: number
  pageSize: number
  total: number
}
export interface ImportBatch {
  id: string
  taskId: string
  status: TaskStatus
  total: number
  completed: number
  items: {
    referenceId: string
    taskId: string
    status: 'pending' | 'dispatched' | 'succeeded' | 'failed' | 'cancelled'
    errorCode: string | null
  }[]
}
