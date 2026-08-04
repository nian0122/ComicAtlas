import type { UploadSessionStatus } from './enums'

/**
 * 上传领域（T17）——对应后端 UploadController + MediaManagementController
 */

/** 创建上传会话请求 */
export interface CreateUploadSessionRequest {
  readonly comicId: number
  readonly chapterId: number
  readonly replaceMediaId?: number
  readonly files: readonly UploadFileManifest[]
}

/** 文件清单项 */
export interface UploadFileManifest {
  readonly fileId: string
  readonly name: string
  readonly contentType: string
  readonly size: number
  readonly sha256: string
}

/** 创建上传会话响应 */
export interface CreateUploadSessionResponse {
  readonly sessionId: string
  readonly chunkSize: number
  readonly expiresAt: string
  readonly totalBytes: number
  readonly files: readonly UploadFileResponse[]
}

/** 上传会话状态查询响应 */
export interface UploadSessionStatusResponse {
  readonly sessionId: string
  readonly status: UploadSessionStatus
  readonly totalBytes: number
  readonly totalFiles: number
  readonly expiresAt: string
  readonly completedAt: string
  readonly files: readonly UploadFileResponse[]
}

/** 上传文件状态 */
export interface UploadFileResponse {
  readonly fileId: string
  readonly storageName: string
  readonly receivedBytes: number
  readonly sizeBytes: number
  readonly complete: boolean
  readonly receivedRanges: string
}

/** 分块上传响应 */
export interface UploadChunkResponse {
  readonly fileId: string
  readonly receivedBytes: number
  readonly complete: boolean
  readonly receivedRanges: string
}

/** 分块区间（bytes start-end/total，total 须等于清单声明的 size） */
export interface ChunkRange {
  readonly start: number
  readonly end: number
  readonly total: number
}

/** 分块上传参数（会话 + 文件 + 数据块 + 区间） */
export interface ChunkUploadParams {
  readonly sessionId: string
  readonly fileId: string
  readonly chunk: Blob
  readonly range: ChunkRange
}

/** 上传完成响应 */
export interface UploadCompleteResponse {
  readonly taskId: number
  readonly taskType: string
  readonly status: string
  readonly itemCount: number
  readonly mediaIds: readonly number[]
}

/** 媒体重排请求 */
export interface MediaReorderRequest {
  readonly mediaIds: readonly number[]
}

/** 媒体重排条目 */
export interface MediaReorderItem {
  readonly mediaId: number
  readonly pageNumber: number
}

/** 媒体重排响应 */
export interface MediaReorderResponse {
  readonly items: readonly MediaReorderItem[]
}
