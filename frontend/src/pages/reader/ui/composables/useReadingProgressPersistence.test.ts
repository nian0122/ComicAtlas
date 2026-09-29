import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReadingProgressPayload } from './useReadingProgressPersistence'

const { mountedCallbacks, beforeUnmountCallbacks, windowListeners, recordProgressMock, keepaliveMock } = vi.hoisted(
  () => ({
    mountedCallbacks: [] as Array<() => void>,
    beforeUnmountCallbacks: [] as Array<() => void>,
    windowListeners: new Map<string, EventListener>(),
    recordProgressMock: vi.fn(),
    keepaliveMock: vi.fn(),
  }),
)

vi.mock('vue', async (importOriginal) => {
  const vue = await importOriginal<typeof import('vue')>()
  return {
    ...vue,
    onMounted: (callback: () => void) => mountedCallbacks.push(callback),
    onBeforeUnmount: (callback: () => void) => beforeUnmountCallbacks.push(callback),
  }
})

vi.mock('@/entities/history', () => ({
  useHistoryStore: () => ({
    recordProgress: recordProgressMock,
    recordProgressKeepalive: keepaliveMock,
  }),
}))

import { useReadingProgressPersistence } from './useReadingProgressPersistence'

function createProgress(pageNumber: number): ReadingProgressPayload {
  return { comicId: 10, chapterId: 101, pageNumber, totalPages: 40 }
}

describe('阅读进度持久化', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    mountedCallbacks.length = 0
    beforeUnmountCallbacks.length = 0
    windowListeners.clear()
    vi.stubGlobal('window', {
      setTimeout: globalThis.setTimeout.bind(globalThis),
      clearTimeout: globalThis.clearTimeout.bind(globalThis),
      addEventListener: (eventName: string, callback: EventListener) => windowListeners.set(eventName, callback),
      removeEventListener: vi.fn(),
    })
    vi.stubGlobal('document', {
      visibilityState: 'visible',
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })
    recordProgressMock.mockResolvedValue(undefined)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('防抖期间只保存最新进度', async () => {
    let progress = createProgress(2)
    const persistence = useReadingProgressPersistence(() => progress, 300)

    persistence.scheduleSave()
    progress = createProgress(7)
    persistence.scheduleSave()
    await vi.advanceTimersByTimeAsync(300)

    expect(recordProgressMock).toHaveBeenCalledTimes(1)
    expect(recordProgressMock).toHaveBeenCalledWith(10, 101, 7, 40)
  })

  it('离开页面时用 keepalive 提交尚未保存的最新进度', () => {
    let progress = createProgress(3)
    const persistence = useReadingProgressPersistence(() => progress, 300)
    mountedCallbacks.forEach((callback) => callback())

    progress = createProgress(9)
    persistence.scheduleSave()
    const pageHideHandler = windowListeners.get('pagehide')
    expect(pageHideHandler).toBeDefined()
    pageHideHandler?.(new Event('pagehide'))

    expect(keepaliveMock).toHaveBeenCalledWith(createProgress(9))
    expect(recordProgressMock).not.toHaveBeenCalled()
  })

  it('卸载阅读页面时立即冲刷尚未落库的进度', async () => {
    const progress = createProgress(11)
    const persistence = useReadingProgressPersistence(() => progress, 300)
    persistence.scheduleSave()

    beforeUnmountCallbacks.forEach((callback) => callback())
    await Promise.resolve()

    expect(recordProgressMock).toHaveBeenCalledWith(10, 101, 11, 40)
  })
})
