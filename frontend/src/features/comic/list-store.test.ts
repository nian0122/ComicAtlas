import { AxiosHeaders, type AxiosResponse } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { comicApi } from '@/entities/comic/api'
import type { ComicListVO } from '@/entities/comic/types'
import { managementComicApi } from '@/features/comic/management-api'
import type { PageResult } from '@/shared/api/types'
import { useComicStore } from './store'
import { useManagementComicStore } from './management-store'

function pageResponse(total = 0, current = 1): AxiosResponse<PageResult<ComicListVO>> {
  return {
    data: { records: [], total, current, pages: 10 },
    status: 200,
    statusText: 'OK',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
  }
}

function pendingPage() {
  let resolve!: (response: AxiosResponse<PageResult<ComicListVO>>) => void
  let reject!: (error: Error) => void
  const promise = new Promise<AxiosResponse<PageResult<ComicListVO>>>((onResolve, onReject) => {
    resolve = onResolve
    reject = onReject
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  vi.restoreAllMocks()
  setActivePinia(createPinia())
})

describe.each([
  { name: '阅读列表', useStore: useComicStore, endpoint: comicApi },
  { name: '管理列表', useStore: useManagementComicStore, endpoint: managementComicApi },
])('$name 共享行为', ({ useStore, endpoint }) => {
  it('搜索回到第一页并固定在途请求的标签', async () => {
    const request = vi.spyOn(endpoint, 'list').mockResolvedValue(pageResponse(50))
    const store = useStore()
    store.updateQuery({ page: 4 })
    await store.search({ keyword: '漫画', tags: ['标签一'], tagMode: 'AND' })
    store.query.tags?.push('标签二')
    expect(request.mock.calls[0]?.[0]).toMatchObject({
      page: 1, keyword: '漫画', tags: ['标签一'], tagMode: 'AND',
    })
    expect(store.hasMore).toBe(true)
    await store.nextPage()
    expect(request.mock.calls[1]?.[0]?.page).toBe(2)
    store.resetQuery()
    expect(store.query).toEqual({ page: 1, size: 24, sort: 'createdAt' })
  })

  it('旧成功响应不能覆盖新结果', async () => {
    const older = pendingPage()
    const newer = pendingPage()
    vi.spyOn(endpoint, 'list').mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    const store = useStore()
    const first = store.fetchList()
    const second = store.fetchList()
    newer.resolve(pageResponse(24))
    await second
    older.resolve(pageResponse(99))
    await first
    expect(store.total).toBe(24)
    expect(store.loading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('旧失败响应不能结束新请求的加载状态', async () => {
    const older = pendingPage()
    const newer = pendingPage()
    vi.spyOn(endpoint, 'list').mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    const store = useStore()
    const first = store.fetchList()
    const second = store.fetchList()
    older.reject(new Error('旧请求失败'))
    await first
    expect(store.loading).toBe(true)
    expect(store.error).toBeNull()
    newer.resolve(pageResponse(12))
    await second
    expect(store.total).toBe(12)
    expect(store.loading).toBe(false)
  })

  it('当前失败清空结果且空列表不能继续翻页', async () => {
    const request = vi.spyOn(endpoint, 'list').mockRejectedValue(new Error('加载失败'))
    const store = useStore()
    await store.fetchList()
    expect(store.error).toBe('加载失败')
    expect(store.list).toEqual([])
    expect(store.total).toBe(0)
    expect(store.loading).toBe(false)
    await store.nextPage()
    expect(request).toHaveBeenCalledTimes(1)
  })
})

it('阅读端限定 READY 并回填页码，管理端保留筛选与请求页码', async () => {
  const readingRequest = vi.spyOn(comicApi, 'list').mockResolvedValue(pageResponse(10, 2))
  const managementRequest = vi.spyOn(managementComicApi, 'list').mockResolvedValue(pageResponse(20, 2))
  const reading = useComicStore()
  const management = useManagementComicStore()
  reading.updateQuery({ page: 4, status: 'TRASHED' })
  management.updateQuery({ page: 4, status: 'IMPORT_FAILED' })
  await Promise.all([reading.fetchList(), management.fetchList()])
  expect(readingRequest.mock.calls[0]?.[0]?.status).toBe('READY')
  expect(managementRequest.mock.calls[0]?.[0]?.status).toBe('IMPORT_FAILED')
  expect(reading.query.page).toBe(2)
  expect(management.query.page).toBe(4)
  expect(reading.total).toBe(10)
  expect(management.total).toBe(20)
})
