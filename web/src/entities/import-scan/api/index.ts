import { request } from '@/shared/api/http'
import type { CandidatePage, ImportBatch } from '../model/types'
export const importScanApi = {
  candidates: (id: string, page = 1) =>
    request<CandidatePage>(`/directory-scans/${encodeURIComponent(id)}/candidates`, { query: { page, pageSize: 20 } }),
  batch: (id: string) => request<ImportBatch>(`/import-batches/${encodeURIComponent(id)}`),
}
