import { adminApi, exportApi, hqApi, lqApi } from '@/services/api'
import type { ComicStorageQuery, OperationSubmitResult, StorageOperation } from '@/types'
import { StorageOperationType } from '@/types'

function extractMessage(err: unknown): string {
  if (err && typeof err === 'object' && 'response' in err) {
    const axiosErr = err as { response?: { data?: { message?: string } } }
    return axiosErr.response?.data?.message || '操作失败'
  }
  return '操作失败'
}

export const storageService = {
  async fetchComics(params: ComicStorageQuery) {
    const res = await adminApi.storageComics(params)
    return res.data as { records: import('@/types').ComicStorageItem[]; total: number }
  },

  async fetchSummary() {
    const res = await adminApi.stats()
    return res.data as import('@/types').StorageStats
  },

  async fetchComic(comicId: number) {
    const res = await adminApi.storageComic(comicId)
    return res.data as import('@/types').ComicStorageItem
  },

  async fetchChapters(comicId: number) {
    const res = await adminApi.storageChapters(comicId)
    return res.data as import('@/types').ChapterStorageItem[]
  },

  async executeOperation(op: StorageOperation): Promise<void> {
    const { type, comicId, chapterId } = op
    try {
      switch (type) {
        case StorageOperationType.DeleteHQ:
          if (chapterId != null) {
            await hqApi.deleteChapter(chapterId)
          } else {
            await hqApi.deleteComic(comicId)
          }
          break
        case StorageOperationType.GenerateLQ:
          if (chapterId != null) {
            await lqApi.generateChapter(chapterId)
          } else {
            await lqApi.generateComic(comicId)
          }
          break
      }
    } catch (err) {
      throw new Error(extractMessage(err))
    }
  },

  async transcodeVideos(comicId: number): Promise<OperationSubmitResult> {
    const res = await adminApi.transcodeVideos(comicId)
    return res.data
  },
}

export const exportService = {
  async createExport(comicId: number) {
    await exportApi.createExport(comicId)
  },
}
