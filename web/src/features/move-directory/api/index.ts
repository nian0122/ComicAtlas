import { request } from '@/shared/api/http'
import type { DirectoryPreview, DirectoryTarget } from '@/entities/directory-move'
import type { Task } from '@/entities/task'
import { directoryIds } from '../model/consent'
export const directoryMoveCommands = {
  create: (targets: readonly DirectoryTarget[]) =>
    request<DirectoryPreview>('/directory-move-previews', {
      method: 'POST',
      body: { comicIds: directoryIds(targets) },
    }),
  confirm: (id: string, token: string, key: string) =>
    request<Task>(`/directory-move-previews/${encodeURIComponent(id)}/confirm`, {
      method: 'POST',
      body: { confirm: true, confirmationToken: token },
      idempotencyKey: key,
    }),
  task: (id: string, action: 'cancel' | 'retry') =>
    request<Task>(`/tasks/${encodeURIComponent(id)}/${action}`, { method: 'POST', body: {} }),
}
