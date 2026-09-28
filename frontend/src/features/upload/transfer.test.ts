import { describe, expect, it } from 'vitest'
import { formatUploadContentRange } from './model/transfer'

describe('媒体上传分片协议', () => {
  it('生成包含闭区间末端的 Content-Range', () => {
    expect(formatUploadContentRange(0, 1024, 4096)).toBe('bytes 0-1023/4096')
    expect(formatUploadContentRange(2048, 4096, 4096)).toBe('bytes 2048-4095/4096')
  })
})
