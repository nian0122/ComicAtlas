import { request } from '@/shared/api/http'
import type { Task } from '@/entities/task'
export const releaseHq = (kind: 'comics' | 'chapters', id: string, key: string) =>
  request<Task>(`/${kind}/${encodeURIComponent(id)}/hq-release`, { method: 'POST', body: {}, idempotencyKey: key })
