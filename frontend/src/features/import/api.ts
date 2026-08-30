import { api } from '@/services/http'
import type {
  BatchImportRequest,
  BatchImportResultVO,
  DirectoryScanTaskVO,
  ImportStatusVO,
  ImportTaskQuery,
  ImportTaskVO,
} from './types'

export const importApi = {
  create: (sourceType: string, sourcePath: string) =>
    api.post<ImportTaskVO>('/manage/tasks/import', { sourceType, sourcePath }),
  list: (params?: ImportTaskQuery) =>
    api.get<{ records: ImportTaskVO[]; total: number }>('/manage/tasks/import', { params }),
  detail: (id: number) => api.get<ImportTaskVO>(`/manage/tasks/import/${id}`),
  status: (id: number) => api.get<ImportStatusVO>(`/manage/tasks/import/${id}/status`),
  cancel: (id: number) => api.post<void>(`/manage/tasks/import/${id}/cancel`),
  retry: (id: number) => api.post<void>(`/manage/tasks/import/${id}/retry`),
  createBatch: (data: BatchImportRequest) =>
    api.post<BatchImportResultVO>('/manage/tasks/import/batch', data),
}

export const directoryScanApi = {
  create: (parentPath: string) =>
    api.post<DirectoryScanTaskVO>('/manage/tasks/directory-scan', { parentPath }),
  get: (id: number) => api.get<DirectoryScanTaskVO>(`/manage/tasks/directory-scan/${id}`),
}
