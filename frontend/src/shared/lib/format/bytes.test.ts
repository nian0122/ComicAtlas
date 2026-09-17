import { describe, expect, it } from 'vitest'
import { formatBytes } from './bytes'

describe('文件大小展示', () => {
  it.each([
    [0, '0 B'],
    [null, '0 B'],
    [undefined, '0 B'],
    [-1, '0 B'],
    [Number.NaN, '0 B'],
    [Number.POSITIVE_INFINITY, '0 B'],
    [1023, '1023 B'],
    [1024, '1.0 KB'],
    [1536, '1.5 KB'],
    [1024 ** 2, '1.0 MB'],
    [1024 ** 3, '1.0 GB'],
    [1024 ** 4, '1.0 TB'],
    [1024 ** 5, '1024.0 TB'],
  ])('将 %s 展示为 %s', (bytes, expected) => {
    expect(formatBytes(bytes)).toBe(expected)
  })

  it('详情页保留两位小数，字节单位仍为整数', () => {
    expect(formatBytes(1536, 2)).toBe('1.50 KB')
    expect(formatBytes(512, 2)).toBe('512 B')
  })
})
