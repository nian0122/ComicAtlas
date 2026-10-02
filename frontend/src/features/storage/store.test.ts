import { createPinia, setActivePinia } from 'pinia'
import { effectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { StorageOperationType, type ComicStorageItem } from '@/entities/storage'
import { storageService } from './service'
import { useStorageStore } from './store'
import { useStoragePolling } from './composables/useStoragePolling'

const comicFixture: ComicStorageItem = {
  comicId: 42,
  title: '不含数字的漫画标题',
  coverUrl: '',
  totalSize: 100,
  hqSize: 100,
  lqSize: 0,
  hqStatus: 'READY',
  lqStatus: 'NOT_GENERATED',
  mediaType: 'IMAGE',
  transcodeStatus: 'NOT_NEEDED',
  chapterCount: 1,
  pageCount: 1,
}

beforeEach(() => setActivePinia(createPinia()))
afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('存储行刷新与轮询', () => {
  it('按漫画 ID 更新行，不依赖标题搜索命中', async () => {
    const store = useStorageStore()
    store.comicList = [comicFixture]
    const fetchComic = vi.spyOn(storageService, 'fetchComic').mockResolvedValue({ ...comicFixture, lqStatus: 'READY' })
    const fetchComics = vi.spyOn(storageService, 'fetchComics')
    await store.refreshRow(42)
    expect(fetchComic).toHaveBeenCalledWith(42)
    expect(fetchComics).not.toHaveBeenCalled()
    expect(store.comicList[0]?.lqStatus).toBe('READY')
  })

  it('慢请求期间不叠加刷新，销毁后在途请求不能重建定时器', async () => {
    vi.useFakeTimers()
    const store = useStorageStore()
    let completeRefresh!: () => void
    const refresh = vi.spyOn(store, 'refreshRow').mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          completeRefresh = resolve
        }),
    )
    const scope = effectScope()
    const polling = scope.run(() => useStoragePolling(store))!
    polling.start(42, StorageOperationType.GenerateLQ)
    await vi.advanceTimersByTimeAsync(15000)
    expect(refresh).toHaveBeenCalledTimes(1)
    scope.stop()
    completeRefresh()
    await Promise.resolve()
    expect(vi.getTimerCount()).toBe(0)
    expect(store.busyState[42]).toBe(false)
  })
})
