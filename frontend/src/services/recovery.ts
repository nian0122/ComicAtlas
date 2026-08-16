import api from '@/services/api'
import type { RecoveryTaskVO } from '@/types'

export const recoveryApi = {
  create: () => api.post<RecoveryTaskVO>('/manage/tasks/recovery'),

  list: (params: { page?: number; size?: number }) =>
    api.get('/manage/tasks/recovery', { params }),

  get: (id: number) => api.get<RecoveryTaskVO>(`/manage/tasks/recovery/${id}`),

  retry: (id: number) => api.post<RecoveryTaskVO>(`/manage/tasks/recovery/${id}/retry`),
}
