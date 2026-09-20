import axios from 'axios'

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

const aiApi = axios.create({
  baseURL: import.meta.env.VITE_AI_API_BASE_URL || 'http://localhost:8020/api',
  timeout: 15_000,
})

export const aiAnalysisApi = {
  async create(sourcePath: string): Promise<{ taskId: number; status: AiTaskStatus }> {
    const response = await aiApi.post('/analysis/tasks', { sourcePath })
    return response.data
  },
  async get(taskId: number): Promise<AiAnalysisTask> {
    const response = await aiApi.get(`/analysis/tasks/${taskId}`)
    return response.data
  },
  async cancel(taskId: number): Promise<void> {
    await aiApi.post(`/analysis/tasks/${taskId}/cancel`)
  },
}
