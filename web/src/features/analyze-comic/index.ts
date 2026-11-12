import { request } from '@/shared/api/http'
import type { Task } from '@/entities/task'
import type { reviewBody } from './model/review'
export { reviewBlocker, reviewBody } from './model/review'
export type { ReviewField, ReviewSelection } from './model/review'
export const analysisCommands = {
  create: (id: string, body: { version: number; structureVersion: number; sampleCount: number }, key: string) =>
    request<Task>(`/comics/${encodeURIComponent(id)}/ai-analyses`, { method: 'POST', body, idempotencyKey: key }),
  action: (id: string, action: 'retry' | 'cancel') =>
    request<Task>(`/tasks/${encodeURIComponent(id)}/${action}`, { method: 'POST' }),
  apply: (id: string, body: ReturnType<typeof reviewBody>) =>
    request<{ id: string; version: number }>(`/comics/${encodeURIComponent(id)}`, { method: 'PUT', body }),
}
