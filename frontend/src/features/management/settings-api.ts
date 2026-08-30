import { api } from '@/services/http'

export interface ManagementSettings {
  defaultQuality: string
  defaultFit: string
  defaultDirection: string
}

export const settingsApi = {
  get: () => api.get<ManagementSettings>('/manage/settings'),
  update: (data: Record<string, unknown>) => api.put('/manage/settings', data),
}
