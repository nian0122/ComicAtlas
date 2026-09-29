import { api } from '@/shared/api/http'
import type {
  BatchCreateResult,
  BatchPreviewResult,
  BatchSubmitRequest,
  CreateManagementTaskRequest,
  ManagementTaskItemVO,
  ManagementTaskQuery,
  ManagementTaskStatusCountsQuery,
  ManagementTaskStatusCountVO,
  ManagementTaskVO,
} from '../model/types'

export const managementTaskApi = {
  list: (params: ManagementTaskQuery) =>
    api.get<{ readonly records: readonly ManagementTaskVO[]; readonly total: number }>('/manage/tasks', { params }),
  statusCounts: (params: ManagementTaskStatusCountsQuery = {}) =>
    api.get<readonly ManagementTaskStatusCountVO[]>('/manage/tasks/status-counts', { params }),
  get: (id: number) => api.get<ManagementTaskVO>(`/manage/tasks/${id}`),
  getItems: (id: number) => api.get<readonly ManagementTaskItemVO[]>(`/manage/tasks/${id}/items`),
  create: (data: CreateManagementTaskRequest) => api.post<ManagementTaskVO>('/manage/tasks', data),
  cancel: (id: number) => api.post<ManagementTaskVO>(`/manage/tasks/${id}/cancel`),
  retry: (id: number) => api.post<ManagementTaskVO>(`/manage/tasks/${id}/retry`),
}

export const batchApi = {
  preview: (data: BatchSubmitRequest) => api.post<BatchPreviewResult>('/manage/batch/preview', data),
  submit: (data: BatchSubmitRequest, idempotencyKey?: string) =>
    api.post<BatchCreateResult>('/manage/batch', data, {
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
    }),
}
