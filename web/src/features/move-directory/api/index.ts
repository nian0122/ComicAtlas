import { request } from '@/shared/api/http'
import type { DirectoryTarget } from '@/entities/directory-move'
import type { Task } from '@/entities/task'
import { directoryIds } from '../model/consent'
export const directoryMoveCommands = {
  create: (targets: readonly DirectoryTarget[], key: string) =>
    request<Task>('/directory-moves', {
      method: 'POST',
      body: { comicIds: directoryIds(targets) },
      idempotencyKey: key,
    }),
  retry: (id: string) => request<Task>(`/tasks/${encodeURIComponent(id)}/retry`, { method: 'POST', body: {} }),
}
