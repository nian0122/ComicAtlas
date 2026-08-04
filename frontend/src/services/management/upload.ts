import { request } from './http'
import type {
  ChunkUploadParams,
  CreateUploadSessionRequest,
  CreateUploadSessionResponse,
  MediaReorderRequest,
  MediaReorderResponse,
  UploadChunkResponse,
  UploadCompleteResponse,
  UploadSessionStatusResponse,
} from '@/types/management/upload'

/**
 * 上传 API（T17）——对应后端 UploadController /api/uploads + MediaManagementController
 */
export const uploadApi = {
  createSession: async (payload: CreateUploadSessionRequest): Promise<CreateUploadSessionResponse> => {
    const raw = await request<unknown>({ method: 'POST', url: '/uploads/sessions', data: payload })
    return raw as CreateUploadSessionResponse
  },

  sessionStatus: async (sessionId: string): Promise<UploadSessionStatusResponse> => {
    const raw = await request<unknown>({ method: 'GET', url: `/uploads/sessions/${sessionId}` })
    return raw as UploadSessionStatusResponse
  },

  /** 分块上传：Content-Range 格式 `bytes {start}-{end}/{total}`，total 须等于清单声明的 size */
  uploadChunk: async (params: ChunkUploadParams): Promise<UploadChunkResponse> => {
    const raw = await request<unknown>({
      method: 'PUT',
      url: `/uploads/sessions/${params.sessionId}/files/${params.fileId}`,
      data: params.chunk,
      headers: {
        'Content-Type': 'application/octet-stream',
        'Content-Range': `bytes ${params.range.start}-${params.range.end}/${params.range.total}`,
      },
    })
    return raw as UploadChunkResponse
  },

  complete: async (sessionId: string): Promise<UploadCompleteResponse> => {
    const raw = await request<unknown>({ method: 'POST', url: `/uploads/sessions/${sessionId}/complete` })
    return raw as UploadCompleteResponse
  },

  cancel: async (sessionId: string): Promise<void> => {
    await request<null>({ method: 'DELETE', url: `/uploads/sessions/${sessionId}` })
  },

  reorderMedia: async (chapterId: number, payload: MediaReorderRequest): Promise<MediaReorderResponse> => {
    const raw = await request<unknown>({ method: 'POST', url: `/chapters/${chapterId}/media/reorder`, data: payload })
    return raw as MediaReorderResponse
  },

  trashMedia: async (mediaId: number): Promise<unknown> =>
    request<unknown>({ method: 'DELETE', url: `/media/${mediaId}` }),
}
