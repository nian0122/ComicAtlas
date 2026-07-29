import api from '@/services/api'
import type { RecoveryTaskVO } from '@/types'

export const recoveryApi = {
  create: () => api.post<RecoveryTaskVO>('/tasks/recovery'),

  list: (params: { page?: number; size?: number }) =>
    api.get('/tasks/recovery', { params }),

  get: (id: number) => api.get<RecoveryTaskVO>(`/tasks/recovery/${id}`),

  retry: (id: number) => api.post<RecoveryTaskVO>(`/tasks/recovery/${id}/retry`),
}
