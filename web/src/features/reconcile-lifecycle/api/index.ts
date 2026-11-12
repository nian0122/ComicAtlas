import { request } from '@/shared/api/http'
import type { Task } from '@/entities/task'
export const lifecycleCommands = {
  reconcile: (entry: string, key: string) =>
    request<Task>(`/trash/${encodeURIComponent(entry)}/reconcile`, { method: 'POST', body: {}, idempotencyKey: key }),
  repair: (entry: string) => request<Task>(`/trash/${encodeURIComponent(entry)}/repair`, { method: 'POST', body: {} }),
  cancelAudit: (task: string) =>
    request<Task>(`/tasks/${encodeURIComponent(task)}/cancel`, { method: 'POST', body: {} }),
}
