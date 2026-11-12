import { request } from '@/shared/api/http'
import type { UploadSession } from '@/entities/upload'
import type { Task } from '@/entities/task'
export interface UploadRequest {
  chapterId: string
  replaceMediaId: string | null
  files: { name: string; size: number; sha256: string }[]
}
export const uploadCommands = {
  create: (body: UploadRequest, key: string, signal?: AbortSignal) =>
    request<UploadSession>('/upload-sessions', { method: 'POST', body, idempotencyKey: key, signal }),
  part: (id: string, fileId: string, body: Blob, start: number, total: number, signal: AbortSignal) =>
    request<{ fileId: string; part: number; size: number; sha256: string }>(
      `/upload-sessions/${encodeURIComponent(id)}/files/${encodeURIComponent(fileId)}`,
      {
        method: 'PUT',
        binary: {
          body,
          headers: { 'Content-Range': `bytes ${start}-${start + body.size - 1}/${total}` },
        },
        signal,
      },
    ),
  complete: (id: string) =>
    request<Task>(`/upload-sessions/${encodeURIComponent(id)}/complete`, { method: 'POST', body: {} }),
  cancel: (id: string) =>
    request<void>(`/upload-sessions/${encodeURIComponent(id)}`, { method: 'DELETE', response: 'empty' }),
}
