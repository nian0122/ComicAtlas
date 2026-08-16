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
    api.post<OperationSubmitResult>(`/manage/storage/lq/comics/${comicId}`, undefined, { params: { regenerate } }),
}

export const trackedTaskApi = {
  list: (params: ManagementTaskQuery) =>
    api.get<{ readonly records: readonly ManagementTaskVO[]; readonly total: number }>(
      '/manage/tasks',
      { params },
    ),
  getItems: (id: number) => api.get<readonly ManagementTaskItemVO[]>(`/manage/tasks/${id}/items`),
  cancel: (id: number) => api.post<ManagementTaskVO>(`/manage/tasks/${id}/cancel`),
  retry: (id: number) => api.post<ManagementTaskVO>(`/manage/tasks/${id}/retry`),
}

export const trackedUploadApi = {
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
