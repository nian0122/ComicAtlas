import { request } from '@/shared/api/http'
import type { AnalysisResult } from './model/types'
export type { AnalysisResult, AnalysisTerm } from './model/types'
export const analysisApi = {
  result: (id: string) => request<AnalysisResult>(`/tasks/${encodeURIComponent(id)}/ai-result`),
}
