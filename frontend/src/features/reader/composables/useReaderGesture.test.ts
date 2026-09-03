import { afterEach, describe, expect, it, vi } from 'vitest'
import { createTouchTapResolver } from './useReaderGesture'

describe('createTouchTapResolver', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('同一图片的触控双击只派发双击事件', () => {
    vi.useFakeTimers()
    const singleTap = vi.fn()
    const doubleTap = vi.fn()
    const resolver = createTouchTapResolver(singleTap, doubleTap)
    const imageTarget = {} as Element

    resolver.handleTouchTap({
      point: { x: 100, y: 200 },
      timeStamp: 1_000,
      imageTarget,
    })
    resolver.handleTouchTap({
      point: { x: 108, y: 207 },
      timeStamp: 1_220,
      imageTarget,
    })
    vi.runAllTimers()

    expect(singleTap).not.toHaveBeenCalled()
    expect(doubleTap).toHaveBeenCalledOnce()
    expect(doubleTap).toHaveBeenCalledWith({ x: 108, y: 207 })
  })

  it('图片触控单击等待双击窗口后正常派发', () => {
    vi.useFakeTimers()
    const singleTap = vi.fn()
    const doubleTap = vi.fn()
    const resolver = createTouchTapResolver(singleTap, doubleTap)

    resolver.handleTouchTap({
      point: { x: 80, y: 160 },
      timeStamp: 2_000,
      imageTarget: {} as Element,
    })

    expect(singleTap).not.toHaveBeenCalled()
    vi.advanceTimersByTime(300)
    expect(singleTap).toHaveBeenCalledOnce()
    expect(doubleTap).not.toHaveBeenCalled()
  })
})
