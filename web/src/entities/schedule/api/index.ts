import { request } from '@/shared/api/http'
import type { Schedule, ScheduleRun, ScheduleUpdate } from '../model/types'

export const scheduleApi = {
  list: (runs = 5) => request<Schedule[]>('/schedules', { query: { runs } }),
  get: (id: string, runs = 20) => request<Schedule>(`/schedules/${encodeURIComponent(id)}`, { query: { runs } }),
  runs: (id: string, limit = 20) =>
    request<ScheduleRun[]>(`/schedules/${encodeURIComponent(id)}/runs`, { query: { limit } }),
  run: (id: string) => request<ScheduleRun>(`/schedules/${encodeURIComponent(id)}/run`, { method: 'POST', body: {} }),
  update: (id: string, update: ScheduleUpdate) =>
    request<Schedule>(`/schedules/${encodeURIComponent(id)}`, { method: 'PATCH', body: update }),
}
