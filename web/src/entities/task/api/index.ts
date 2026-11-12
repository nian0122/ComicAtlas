import { request } from '@/shared/api/http'
import type { Task, TaskPage, TaskSummary, TaskEventPage, TaskItem, TaskArtifacts, TaskFilters } from '../model/types'
export const taskApi = {
  artifacts: (id: string) => request<TaskArtifacts>(`/tasks/${encodeURIComponent(id)}/artifacts`),
  list: (query: TaskFilters & { page: number; pageSize: number; status?: string }) =>
    request<TaskPage>('/tasks', { query: { ...query } }),
  summary: (filters: Pick<TaskFilters, 'type' | 'keyword'> = {}) =>
    request<TaskSummary>('/tasks/summary', { query: { ...filters } }),
  detail: (id: string) => request<Task>(`/tasks/${encodeURIComponent(id)}`),
  events: (id: string, after = 0) =>
    request<TaskEventPage>(`/tasks/${encodeURIComponent(id)}/events`, { query: { after, limit: 100 } }),
  items: (id: string, page = 1) =>
    request<{ items: TaskItem[]; total: number }>(`/tasks/${encodeURIComponent(id)}/items`, {
      query: { page, pageSize: 100 },
    }),
}
