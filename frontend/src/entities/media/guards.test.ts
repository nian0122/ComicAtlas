import { describe, expect, it } from 'vitest'
import { isVideoMedia } from './guards'

describe('媒体类型判断', () => {
  it('识别明确视频类型并支持扩展名兜底', () => {
    expect(isVideoMedia({ mediaType: 'VIDEO', hqUrl: 'cover.jpg' })).toBe(true)
    expect(isVideoMedia({ mediaType: 'IMAGE', hqUrl: 'clip.mp4' })).toBe(true)
  })

  it('后端缺少类型时按 HQ 扩展名兜底', () => {
    expect(isVideoMedia({ mediaType: undefined, hqUrl: 'video.MKV?download=1' })).toBe(true)
    expect(isVideoMedia({ mediaType: undefined, hqUrl: '' })).toBe(false)
  })
})
