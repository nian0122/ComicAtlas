import { request } from '@/shared/api/http'
import type { RecoveryResults, RecoveryPlaceholder, RecoveryRepairResult } from '../model/types'
export const recoveryApi = {
  results: (id: string, after?: string) =>
    request<RecoveryResults>(`/tasks/${encodeURIComponent(id)}/recovery-results`, { query: { after, limit: 20 } }),
  placeholder: (id: string) => request<RecoveryPlaceholder>(`/comics/${encodeURIComponent(id)}/recovery-placeholder`),
  repairResult: (id: string) => request<RecoveryRepairResult>(`/tasks/${encodeURIComponent(id)}/recovery-repair`),
}
