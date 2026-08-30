import { api } from '@/services/http'
import type {
  CreateUploadSessionRequest,
  CreateUploadSessionResult,
  UploadChunkResult,
  UploadCompleteResult,
} from './types'

type UploadChunkRequest = {
  readonly sessionId: string
  readonly fileId: string
  readonly chunk: Blob
  readonly contentRange: string
  readonly signal?: AbortSignal
}

export const uploadApi = {
  createSession: (data: CreateUploadSessionRequest) =>
    api.post<CreateUploadSessionResult>('/manage/uploads/sessions', data),
  uploadChunk: (request: UploadChunkRequest) =>
    api.put<UploadChunkResult>(
      `/manage/uploads/sessions/${request.sessionId}/files/${request.fileId}`,
      request.chunk,
      {
        headers: {
          'Content-Type': 'application/octet-stream',
          'Content-Range': request.contentRange,
        },
        signal: request.signal,
      },
    ),
  completeSession: (sessionId: string) =>
    api.post<UploadCompleteResult>(`/manage/uploads/sessions/${sessionId}/complete`),
  cancelSession: (sessionId: string) => api.delete(`/manage/uploads/sessions/${sessionId}`),
}
