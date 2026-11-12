export type UploadStatus = 'active' | 'submitted' | 'activated' | 'completed' | 'cancelled' | 'expired'
export interface UploadFile {
  id: string
  name: string
  size: number
  sha256: string
  receivedBytes: number
  receivedParts: number[]
}
export interface UploadSession {
  id: string
  comicId: string
  chapterId: string
  replaceMediaId: string | null
  status: UploadStatus
  taskId: string | null
  totalBytes: number
  chunkSize: number
  createdAt: string
  expiresAt: string
  cleanupPending: boolean
  files: UploadFile[]
}
