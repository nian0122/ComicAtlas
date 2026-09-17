import { describe, expect, it } from 'vitest'
import { managementTaskStatusLabel, managementTaskStatusTone } from './labels'
import type { ManagementTaskStatus } from './types'

describe('任务状态展示', () => {
  it.each<[ManagementTaskStatus, string, string]>([
    ['QUEUED', '排队中', 'info'],
    ['RUNNING', '执行中', 'warning'],
    ['CANCELLING', '取消中', 'warning'],
    ['CANCELLED', '已取消', 'danger'],
    ['SUCCEEDED', '成功', 'success'],
    ['PARTIALLY_SUCCEEDED', '部分成功', 'danger'],
    ['FAILED', '失败', 'danger'],
  ])('%s 使用一致的文案与标签色调', (status, label, tone) => {
    expect(managementTaskStatusLabel(status)).toBe(label)
    expect(managementTaskStatusTone(status)).toBe(tone)
  })

  it('摘要弱化已取消状态，仍强调失败任务', () => {
    expect(managementTaskStatusTone('CANCELLED', 'dot')).toBe('info')
    expect(managementTaskStatusTone('FAILED', 'dot')).toBe('danger')
    expect(managementTaskStatusTone('PARTIALLY_SUCCEEDED', 'dot')).toBe('danger')
  })
})
