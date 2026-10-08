import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createShortVideoKeyboardHandler } from './useShortVideoKeyboard'

class KeyboardTarget {
  private readonly isEditable: boolean

  constructor(isEditable = false) {
    this.isEditable = isEditable
  }
  closest() {
    return this.isEditable ? this : null
  }
}

describe('短视频键盘控制', () => {
  const options = {
    isVideo: () => true,
    isDialogOpen: () => false,
    move: vi.fn(),
    togglePlayback: vi.fn(),
    seek: vi.fn(),
    toggleMute: vi.fn(),
    toggleFullscreen: vi.fn(),
    closeDialog: vi.fn(),
    showControls: vi.fn(),
  }

  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('Element', KeyboardTarget)
  })
  afterEach(() => vi.unstubAllGlobals())

  function keydown(key: string, overrides: Partial<KeyboardEvent> = {}) {
    const event = { key, code: '', target: null, preventDefault: vi.fn(), ...overrides }
    createShortVideoKeyboardHandler(options)(event as unknown as KeyboardEvent)
    return event
  }

  it('按钮有焦点时空格只切换播放，不触发按钮默认行为', () => {
    const event = keydown(' ', { target: new KeyboardTarget() as unknown as EventTarget })
    expect(event.preventDefault).toHaveBeenCalledOnce()
    expect(options.togglePlayback).toHaveBeenCalledOnce()
    keydown('k')
    expect(options.togglePlayback).toHaveBeenCalledTimes(2)
  })

  it('左右调整进度，Shift 使用十秒步长', () => {
    keydown('ArrowLeft')
    keydown('ArrowRight', { shiftKey: true })
    expect(options.seek.mock.calls).toEqual([[-5], [10]])
  })

  it('上下切换媒体，M 静音，F 切换全屏', () => {
    keydown('ArrowUp')
    keydown('ArrowDown')
    keydown('M')
    keydown('f')
    expect(options.move.mock.calls).toEqual([[-1], [1]])
    expect(options.toggleMute).toHaveBeenCalledOnce()
    expect(options.toggleFullscreen).toHaveBeenCalledOnce()
  })

  it('输入、组合键和局部控件已处理的按键不触发播放', () => {
    keydown('k', { target: new KeyboardTarget(true) as unknown as EventTarget })
    keydown('k', { ctrlKey: true })
    keydown('k', { isComposing: true })
    keydown('k', { defaultPrevented: true })
    expect(options.togglePlayback).not.toHaveBeenCalled()
  })

  it('图片不响应播放键，但仍可上下切换', () => {
    const handler = createShortVideoKeyboardHandler({ ...options, isVideo: () => false })
    handler({ key: 'k', preventDefault: vi.fn() } as unknown as KeyboardEvent)
    handler({ key: 'ArrowDown', preventDefault: vi.fn() } as unknown as KeyboardEvent)
    expect(options.togglePlayback).not.toHaveBeenCalled()
    expect(options.move).toHaveBeenCalledWith(1)
  })

  it('倍速弹窗打开时只处理 Escape 关闭，长按空格不反复切换', () => {
    const handler = createShortVideoKeyboardHandler({ ...options, isDialogOpen: () => true })
    handler({ key: 'k', preventDefault: vi.fn() } as unknown as KeyboardEvent)
    handler({ key: 'Escape', preventDefault: vi.fn() } as unknown as KeyboardEvent)
    keydown(' ', { repeat: true })
    expect(options.togglePlayback).not.toHaveBeenCalled()
    expect(options.closeDialog).toHaveBeenCalledOnce()
  })
})
