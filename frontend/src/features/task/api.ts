import { api } from '@/services/http'
import type {
  BatchCreateResult,
  BatchPreviewResult,
  BatchSubmitRequest,
  CreateManagementTaskRequest,
  ManagementTaskItemVO,
  ManagementTaskQuery,
  ManagementTaskVO,
} from './types'

export const managementTaskApi = {
  list: (params: ManagementTaskQuery) =>
    api.get<{ readonly records: readonly ManagementTaskVO[]; readonly total: number }>('/manage/tasks', { params }),
  get: (id: number) => api.get<ManagementTaskVO>(`/manage/tasks/${id}`),
  getItems: (id: number) => api.get<readonly ManagementTaskItemVO[]>(`/manage/tasks/${id}/items`),
  create: (data: CreateManagementTaskRequest) => api.post<ManagementTaskVO>('/manage/tasks', data),
  cancel: (id: number) => api.post<ManagementTaskVO>(`/manage/tasks/${id}/cancel`),
  retry: (id: number) => api.post<ManagementTaskVO>(`/manage/tasks/${id}/retry`),
}

export const batchApi = {
  preview: (data: BatchSubmitRequest) => api.post<BatchPreviewResult>('/manage/batch/preview', data),
  submit: (data: BatchSubmitRequest) => api.post<BatchCreateResult>('/manage/batch', data),
}
