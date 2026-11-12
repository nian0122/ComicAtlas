import { request } from '@/shared/api/http'
import type { UploadSession } from '../model/types'
export const uploadApi = {
  session: (id: string) => request<UploadSession>(`/upload-sessions/${encodeURIComponent(id)}`),
}
