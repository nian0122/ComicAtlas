import { api } from '@/shared/api/http'

export type AiTaskStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCEL_REQUESTED' | 'CANCELLED'

export interface AiAnalysisTask {
  readonly id: number
  readonly sourcePath: string
  readonly status: AiTaskStatus
  readonly progress: number
  readonly resultJson: string | null
  readonly errorCode: string | null
  readonly errorMessage: string | null
  readonly attempts: number
  readonly createdAt: string
  readonly startedAt: string | null
  readonly finishedAt: string | null
}

export const aiAnalysisApi = {
  async create(sourcePath: string): Promise<{ taskId: number; status: AiTaskStatus }> {
    const response = await api.post('/ai/analysis/tasks', { sourcePath })
    return response.data
  },
  async get(taskId: number): Promise<AiAnalysisTask> {
    const response = await api.get(`/ai/analysis/tasks/${taskId}`)
    return response.data
  },
  async cancel(taskId: number): Promise<void> {
    await api.post(`/ai/analysis/tasks/${taskId}/cancel`)
  },
}
