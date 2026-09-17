import { AxiosHeaders, type AxiosResponse } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { historyApi } from '@/entities/history/api/history-api'
import type { HistoryVO } from '@/entities/history/model/types'
import { readerApi } from '@/entities/chapter/api/reader-api'
import type { ReaderDTO } from '@/entities/chapter/model/reader-types'
import { useReaderStore } from './reader-store'

vi.mock('@/entities/history/api/history-api', () => ({
  historyApi: {
    get: vi.fn(),
    update: vi.fn(),
  },
}))

vi.mock('@/entities/chapter/api/reader-api', () => ({
  readerApi: {
    chapter: vi.fn(),
  },
}))

const mockedHistoryGet = vi.mocked(historyApi.get)
const mockedChapterGet = vi.mocked(readerApi.chapter)

function apiResponse<T>(data: T): AxiosResponse<T> {
  return {
    data,
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

function chapterResponse(chapterId: number): AxiosResponse<ReaderDTO> {
  return apiResponse({
    chapterId,
    comicId: 10,
    chapterTitle: `第 ${chapterId} 话`,
    pages: Array.from({ length: 50 }, (_, pageIndex) => ({
      id: pageIndex + 1,
      pageNumber: pageIndex + 1,
      hqUrl: `/page/${pageIndex + 1}.jpg`,
      lqUrl: null,
      lqStatus: 'NOT_GENERATED',
      width: 100,
      height: 100,
    })),
    total: 50,
    prevChapterId: null,
    nextChapterId: null,
  })
}

function historyResponse(chapterId: number, pageNumber: number): AxiosResponse<HistoryVO> {
  return apiResponse({
    comicId: 10,
    comicTitle: '测试漫画',
    coverUrl: '/cover.jpg',
    chapterId,
    chapterNo: String(chapterId),
    pageNumber,
    totalPages: 50,
    progressPercent: pageNumber * 2,
    updatedAt: '2026-09-01T00:00:00',
  })
}

describe('阅读器进度恢复', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('历史属于当前章节时恢复章节内页码', async () => {
    mockedChapterGet.mockResolvedValue(chapterResponse(101))
    mockedHistoryGet.mockResolvedValue(historyResponse(101, 40))
    const readerStore = useReaderStore()

    await readerStore.loadChapter(101)
    await readerStore.restoreProgress()

    expect(readerStore.currentPage).toBe(40)
  })

  it('切换到其他章节时不套用上一章节页码', async () => {
    mockedChapterGet.mockResolvedValue(chapterResponse(102))
    mockedHistoryGet.mockResolvedValue(historyResponse(101, 40))
    const readerStore = useReaderStore()

    await readerStore.loadChapter(102)
    await readerStore.restoreProgress()

    expect(readerStore.currentPage).toBe(1)
  })

  it('进度保存失败时保留错误并允许下一次保存恢复', async () => {
    mockedChapterGet.mockResolvedValue(chapterResponse(103))
    mockedHistoryGet.mockResolvedValue(historyResponse(103, 1))
    const mockedHistoryUpdate = vi.mocked(historyApi.update)
    mockedHistoryUpdate.mockRejectedValueOnce(new Error('网络暂不可用')).mockResolvedValueOnce(apiResponse(undefined))
    const readerStore = useReaderStore()

    await readerStore.loadChapter(103)
    readerStore.currentPage = 8
    expect(await readerStore.saveProgress()).toBe(false)
    expect(readerStore.progressSaveError).toBe('网络暂不可用')

    expect(await readerStore.saveProgress()).toBe(true)
    expect(readerStore.progressSaveError).toBeNull()
    expect(mockedHistoryUpdate).toHaveBeenCalledTimes(2)
  })
})
