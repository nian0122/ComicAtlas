import api from '@/services/api'
import type {
  ComicListQuery,
  ComicListVO,
  CreateUploadSessionRequest,
  CreateUploadSessionResult,
  ManagementTaskItemVO,
  ManagementTaskQuery,
  ManagementTaskVO,
  OperationSubmitResult,
  UploadChunkResult,
  UploadCompleteResult,
} from '@/types'

type UploadChunkRequest = {
  readonly sessionId: string
  readonly fileId: string
  readonly chunk: Blob
  readonly contentRange: string
  readonly signal?: AbortSignal
}

export const comicStatusApi = {
  list: (params: ComicListQuery) =>
    api.get<{ readonly records: readonly ComicListVO[]; readonly total: number }>('/comics', { params }),
}

export const lqOperationApi = {
  generateComic: (comicId: number, regenerate: boolean) =>
    api.post<OperationSubmitResult>(`/storage/lq/comics/${comicId}`, undefined, { params: { regenerate } }),
}

export const trackedTaskApi = {
  list: (params: ManagementTaskQuery) =>
    api.get<{ readonly records: readonly ManagementTaskVO[]; readonly total: number }>(
      '/management/tasks',
      { params },
    ),
  getItems: (id: number) => api.get<readonly ManagementTaskItemVO[]>(`/management/tasks/${id}/items`),
  cancel: (id: number) => api.post<ManagementTaskVO>(`/management/tasks/${id}/cancel`),
  retry: (id: number) => api.post<ManagementTaskVO>(`/management/tasks/${id}/retry`),
}

export const trackedUploadApi = {
  createSession: (data: CreateUploadSessionRequest) =>
    api.post<CreateUploadSessionResult>('/uploads/sessions', data),
  uploadChunk: (request: UploadChunkRequest) =>
    api.put<UploadChunkResult>(
      `/uploads/sessions/${request.sessionId}/files/${request.fileId}`,
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
    api.post<UploadCompleteResult>(`/uploads/sessions/${sessionId}/complete`),
  cancelSession: (sessionId: string) => api.delete(`/uploads/sessions/${sessionId}`),
}
