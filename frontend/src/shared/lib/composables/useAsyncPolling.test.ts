import { effectScope } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAsyncPolling } from './useAsyncPolling'

afterEach(() => vi.useRealTimers())

describe('异步轮询作用域', () => {
  it('刷新异常可被调用方观察，并停止自动重试', async () => {
    vi.useFakeTimers()
    const failure = new Error('查询失败')
    const refresh = vi.fn().mockRejectedValue(failure)
    const scope = effectScope()
    const polling = scope.run(() => useAsyncPolling(refresh, 100))!
    polling.start()
    await vi.advanceTimersByTimeAsync(1000)
    expect(polling.error.value).toBe(failure)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
    scope.stop()
  })
  it('销毁时即使请求稍后完成也不再安排下一轮', async () => {
    vi.useFakeTimers()
    let complete!: (value: boolean) => void
    const refresh = vi.fn(
      () =>
        new Promise<boolean>((resolve) => {
          complete = resolve
        }),
    )
    const scope = effectScope()
    const polling = scope.run(() => useAsyncPolling(refresh, 100))!
    polling.start()
    await vi.advanceTimersByTimeAsync(100)
    scope.stop()
    complete(true)
    await vi.advanceTimersByTimeAsync(1000)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('等待请求完成才开始下一轮，停止旧请求后不会恢复', async () => {
    vi.useFakeTimers()
    let complete!: (value: boolean) => void
    const refresh = vi.fn(
      () =>
        new Promise<boolean>((resolve) => {
          complete = resolve
        }),
    )
    const scope = effectScope()
    const polling = scope.run(() => useAsyncPolling(refresh, 100))!
    polling.start()
    await vi.advanceTimersByTimeAsync(500)
    expect(refresh).toHaveBeenCalledTimes(1)
    polling.stop()
    complete(true)
    await vi.advanceTimersByTimeAsync(1000)
    expect(refresh).toHaveBeenCalledTimes(1)
    scope.stop()
  })
})
