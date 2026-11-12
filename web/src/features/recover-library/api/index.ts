import { request } from '@/shared/api/http'
import type { Task } from '@/entities/task'
import type { RepairVersions } from '@/entities/recovery'
import { repairBody } from '../model/snapshot'
export const recoveryCommands = {
  create: (key: string) => request<Task>('/recoveries', { method: 'POST', body: {}, idempotencyKey: key }),
  repair: (comic: string, versions: RepairVersions, key: string) =>
    request<Task>(`/comics/${encodeURIComponent(comic)}/recovery-placeholder/repair`, {
      method: 'POST',
      body: repairBody(versions),
      idempotencyKey: key,
    }),
  task: (id: string, action: 'retry' | 'cancel') =>
    request<Task>(`/tasks/${encodeURIComponent(id)}/${action}`, { method: 'POST', body: {} }),
}
