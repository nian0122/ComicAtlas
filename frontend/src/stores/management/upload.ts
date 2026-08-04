import { defineStore } from 'pinia'
import { ref } from 'vue'
import { uploadApi } from '@/services/management/upload'
import { toErrorMessage } from '@/services/management/http'
import type {
  ChunkRange,
  CreateUploadSessionRequest,
  CreateUploadSessionResponse,
  UploadChunkResponse,
  UploadCompleteResponse,
  UploadSessionStatusResponse,
} from '@/types/management/upload'

/**
 * 上传会话 Store（T17）：创建会话 → 分块上传 → 完成/取消。
 * 分块上传依赖浏览器 File 切片，实际切片逻辑由视图层负责，Store 只维护会话状态与进度。
 */
export const useUploadStore = defineStore('upload', () => {
  const session = ref<CreateUploadSessionResponse | null>(null)
  const status = ref<UploadSessionStatusResponse | null>(null)
  const uploading = ref(false)
  const error = ref<string | null>(null)

  async function createSession(req: CreateUploadSessionRequest): Promise<CreateUploadSessionResponse> {
    uploading.value = true
    error.value = null
    try {
      session.value = await uploadApi.createSession(req)
      return session.value
    } catch (err: unknown) {
      error.value = toErrorMessage(err, '创建上传会话失败')
      throw err
    } finally {
      uploading.value = false
    }
  }

  async function refreshStatus(sessionId: string): Promise<UploadSessionStatusResponse> {
    status.value = await uploadApi.sessionStatus(sessionId)
    return status.value
  }

  async function uploadChunk(
    fileId: string,
    chunk: Blob,
    range: ChunkRange,
  ): Promise<UploadChunkResponse> {
    const current = session.value
    if (!current) throw new Error('上传会话未创建')
    return uploadApi.uploadChunk({ sessionId: current.sessionId, fileId, chunk, range })
  }

  async function complete(sessionId: string): Promise<UploadCompleteResponse> {
    return uploadApi.complete(sessionId)
  }

  async function cancel(sessionId: string): Promise<void> {
    await uploadApi.cancel(sessionId)
    reset()
  }

  function reset(): void {
    session.value = null
    status.value = null
    error.value = null
    uploading.value = false
  }

  return {
    session,
    status,
    uploading,
    error,
    createSession,
    refreshStatus,
    uploadChunk,
    complete,
    cancel,
    reset,
  }
})
