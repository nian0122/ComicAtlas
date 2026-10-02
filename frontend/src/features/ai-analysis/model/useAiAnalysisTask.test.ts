import { effectScope } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { aiAnalysisApi, type AiAnalysisTask } from '../api'
import { useAiAnalysisTask } from './useAiAnalysisTask'

vi.mock('../api', () => ({ aiAnalysisApi: { create: vi.fn(), get: vi.fn(), cancel: vi.fn() } }))
afterEach(() => {
  vi.clearAllMocks()
  vi.useRealTimers()
})
const taskFixture = (status: AiAnalysisTask['status'], resultJson: string | null = null): AiAnalysisTask => ({
  id: 7,
  status,
  resultJson,
  sourcePath: '',
  progress: 100,
  errorCode: null,
  errorMessage: null,
  attempts: 1,
  createdAt: '',
  startedAt: null,
  finishedAt: null,
})

describe('分析任务共用状态', () => {
  it('首次读取就完成时立即展示结果，不继续轮询', async () => {
    vi.useFakeTimers()
    vi.mocked(aiAnalysisApi.create).mockResolvedValue({ taskId: 7, status: 'QUEUED' })
    vi.mocked(aiAnalysisApi.get).mockResolvedValue(taskFixture('SUCCEEDED', '{"tags":["冒险"]}'))
    const scope = effectScope()
    const analysis = scope.run(useAiAnalysisTask)!
    expect(await analysis.startTask(1)).toBe(7)
    expect(analysis.result.value?.tags).toEqual(['冒险'])
    expect(vi.getTimerCount()).toBe(0)
    scope.stop()
  })
  it('损坏的完成结果明确报错，不无限重新请求任务', async () => {
    vi.useFakeTimers()
    vi.mocked(aiAnalysisApi.create).mockResolvedValue({ taskId: 7, status: 'QUEUED' })
    vi.mocked(aiAnalysisApi.get).mockResolvedValue(taskFixture('SUCCEEDED', 'invalid json'))
    const scope = effectScope()
    const analysis = scope.run(useAiAnalysisTask)!
    await analysis.startTask(1)
    expect(analysis.resultError.value).toContain('格式无效')
    expect(vi.getTimerCount()).toBe(0)
    scope.stop()
  })
  it('任务取消中不能重复提交，离开页面后停止轮询', async () => {
    vi.useFakeTimers()
    vi.mocked(aiAnalysisApi.create).mockResolvedValue({ taskId: 7, status: 'QUEUED' })
    vi.mocked(aiAnalysisApi.get).mockResolvedValue(taskFixture('CANCEL_REQUESTED'))
    const scope = effectScope()
    const analysis = scope.run(useAiAnalysisTask)!
    await analysis.startTask(1)
    expect(await analysis.startTask(1)).toBeNull()
    expect(aiAnalysisApi.create).toHaveBeenCalledTimes(1)
    scope.stop()
    expect(vi.getTimerCount()).toBe(0)
  })
})
