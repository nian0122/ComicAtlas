import { request } from '@/shared/api/http'
import type { Task } from '@/entities/task'
import type { SidecarStatus } from '@/entities/sidecar'
export function sidecarBinding(value: SidecarStatus) {
  return JSON.stringify([value.comicId, value.revision, value.publishedRevision, value.state, value.taskId])
}
export const sidecarCommands = {
  sync: (id: string, key: string) =>
    request<Task>(`/comics/${encodeURIComponent(id)}/sidecar/sync`, { method: 'POST', body: {}, idempotencyKey: key }),
  retry: (id: string) => request<Task>(`/tasks/${encodeURIComponent(id)}/retry`, { method: 'POST', body: {} }),
}
