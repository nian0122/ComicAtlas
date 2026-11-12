import { request } from '@/shared/api/http'
import type { AuditSummary, AuditPage, FileResultPage } from '../model/types'
export const lifecycleApi = {
  report: (entry: string) => request<AuditSummary>(`/trash/${encodeURIComponent(entry)}/reconcile`),
  observations: (task: string, page = 1) =>
    request<AuditPage>(`/tasks/${encodeURIComponent(task)}/items`, { query: { page, pageSize: 20 } }),
  results: (task: string, page = 1) =>
    request<FileResultPage>(`/tasks/${encodeURIComponent(task)}/items`, { query: { page, pageSize: 20 } }),
}
