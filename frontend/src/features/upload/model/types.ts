export type UploadSessionState = 'ACTIVE' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED' | 'FAILED'

export interface UploadFileStatus {
  readonly fileId: string
  readonly storageName: string
  readonly receivedBytes: number
  readonly sizeBytes: number
  readonly complete: boolean
  readonly receivedRanges: string
}

export interface UploadSessionStatus {
  readonly sessionId: string
  readonly status: UploadSessionState
  readonly totalBytes: number
  readonly totalFiles: number
  readonly expiresAt: string
  readonly completedAt: string | null
  readonly files: readonly UploadFileStatus[]
}

export interface UploadFileManifest {
  readonly fileId: string
  readonly name: string
  readonly contentType: string
  readonly size: number
  readonly sha256: string
}

export interface CreateUploadSessionRequest {
  readonly comicId: number
  readonly chapterId: number
  readonly replaceMediaId?: number | null
  readonly files: readonly UploadFileManifest[]
}

export interface CreateUploadSessionResult {
  readonly sessionId: string
  readonly chunkSize: number
  readonly expiresAt: string
  readonly totalBytes: number
  readonly files: readonly UploadFileStatus[]
}

export interface UploadChunkResult {
  readonly fileId: string
  readonly receivedBytes: number
  readonly complete: boolean
  readonly receivedRanges: string
}

export interface UploadCompleteResult {
  readonly taskId: number | null
  readonly taskType: string
  readonly status: string | null
  readonly itemCount: number | null
  readonly mediaIds: readonly number[]
}
