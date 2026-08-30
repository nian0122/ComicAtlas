import { describe, expect, it } from 'vitest'
import { sourceTypeLabel } from './source-format'

describe('来源类型显示', () => {
  it('显示已知来源和未知来源', () => {
    expect(sourceTypeLabel('ZIP')).toBe('ZIP 压缩包')
    expect(sourceTypeLabel(null)).toBe('未知')
    expect(sourceTypeLabel('CUSTOM')).toBe('CUSTOM')
  })
})
