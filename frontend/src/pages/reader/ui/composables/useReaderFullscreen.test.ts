import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { unmountCallbacks } = vi.hoisted(() => ({ unmountCallbacks: [] as Array<() => void> }))
vi.mock('vue', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue')>()),
  onBeforeUnmount: (callback: () => void) => unmountCallbacks.push(callback),
}))

import { useReaderFullscreen } from './useReaderFullscreen'

describe('阅读器全屏', () => {
  let listeners: Map<string, EventListener>
  let browserDocument: {
    fullscreenElement: object | null
    documentElement: { requestFullscreen?: ReturnType<typeof vi.fn> }
    exitFullscreen: ReturnType<typeof vi.fn>
    addEventListener: (name: string, callback: EventListener) => void
    removeEventListener: (name: string) => void
  }

  beforeEach(() => {
    vi.useFakeTimers()
    listeners = new Map()
    browserDocument = {
      fullscreenElement: null,
      documentElement: {},
      exitFullscreen: vi.fn(async () => {
        browserDocument.fullscreenElement = null
        listeners.get('fullscreenchange')?.(new Event('fullscreenchange'))
      }),
      addEventListener: (name, callback) => listeners.set(name, callback),
      removeEventListener: (name) => {
        listeners.delete(name)
      },
    }
    browserDocument.documentElement.requestFullscreen = vi.fn(async () => {
      browserDocument.fullscreenElement = browserDocument.documentElement
      listeners.get('fullscreenchange')?.(new Event('fullscreenchange'))
    })
    vi.stubGlobal('document', browserDocument)
  })

  afterEach(async () => {
    unmountCallbacks.splice(0).forEach((callback) => callback())
    await vi.runAllTimersAsync()
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('进入整个文档全屏并同步系统退出', async () => {
    const fullscreen = useReaderFullscreen()
    await fullscreen.toggleFullscreen()
    expect(fullscreen.isFullscreen.value).toBe(true)
    expect(browserDocument.documentElement.requestFullscreen).toHaveBeenCalledOnce()
    browserDocument.fullscreenElement = null
    listeners.get('fullscreenchange')?.(new Event('fullscreenchange'))
    expect(fullscreen.isFullscreen.value).toBe(false)
  })

  it('浏览器拒绝全屏时使用沉浸状态并支持 Escape 退出', async () => {
    browserDocument.documentElement.requestFullscreen?.mockRejectedValue(new Error('拒绝'))
    const fullscreen = useReaderFullscreen()
    await fullscreen.toggleFullscreen()
    expect(fullscreen.isFullscreen.value).toBe(true)
    listeners.get('keydown')?.({ key: 'Escape' } as KeyboardEvent)
    expect(fullscreen.isFullscreen.value).toBe(false)
  })

  it('切换阅读模式保持全屏，离开阅读器才退出', async () => {
    const firstReader = useReaderFullscreen()
    await firstReader.toggleFullscreen()
    unmountCallbacks.shift()?.()
    const nextReader = useReaderFullscreen()
    await vi.runAllTimersAsync()
    expect(nextReader.isFullscreen.value).toBe(true)
    expect(browserDocument.exitFullscreen).not.toHaveBeenCalled()
    unmountCallbacks.shift()?.()
    await vi.runAllTimersAsync()
    expect(browserDocument.exitFullscreen).toHaveBeenCalledOnce()
    expect(nextReader.isFullscreen.value).toBe(false)
  })

  it('申请全屏期间离开页面不会遗留全屏状态', async () => {
    let completeRequest = () => {}
    browserDocument.documentElement.requestFullscreen?.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          completeRequest = () => {
            browserDocument.fullscreenElement = browserDocument.documentElement
            resolve()
          }
        }),
    )
    const fullscreen = useReaderFullscreen()
    const request = fullscreen.toggleFullscreen()
    unmountCallbacks.shift()?.()
    await vi.runAllTimersAsync()
    completeRequest()
    await request
    expect(browserDocument.exitFullscreen).toHaveBeenCalledOnce()
    expect(fullscreen.isFullscreen.value).toBe(false)
  })
})
