import { request } from '@/shared/api/http'
import type { Task } from '@/entities/task'
import { requireImportPath, type ImportBatch } from '@/entities/import-scan'
export const importCommands = {
  scan: (sourcePath: string, key: string) =>
    request<Task>('/directory-scans', {
      method: 'POST',
      body: { sourcePath: requireImportPath(sourcePath) },
      idempotencyKey: key,
    }),
  import: (scanId: string, candidateIds: string[], key: string) =>
    request<ImportBatch>(`/directory-scans/${encodeURIComponent(scanId)}/imports`, {
      method: 'POST',
      body: { candidateIds },
      idempotencyKey: key,
    }),
  archives: (sourcePaths: string[], key: string) =>
    request<ImportBatch>('/imports/archives/batch', {
      method: 'POST',
      body: { sourcePaths },
      idempotencyKey: key,
    }),
}
