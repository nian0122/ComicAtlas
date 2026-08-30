import { exportApi, hqApi, lqApi, storageAdminApi } from '@/features/storage/api'
import { getApiErrorMessage } from '@/services/http'
import type {
  ComicStorageQuery,
  ExportArtifactVO,
  ExportTaskVO,
  OperationSubmitResult,
  StorageOperation,
} from '@/features/storage/types'
import { StorageOperationType } from '@/features/storage/types'

export const storageService = {
  async fetchComics(params: ComicStorageQuery) {
    const res = await storageAdminApi.comics(params)
    return res.data
  },

  async fetchSummary() {
    const res = await storageAdminApi.stats()
    return res.data
  },

  async fetchComic(comicId: number) {
    const res = await storageAdminApi.comic(comicId)
    return res.data
  },

  async fetchChapters(comicId: number) {
    const res = await storageAdminApi.chapters(comicId)
    return res.data
  },

  /**
   * 统一存储操作：按类型分发到对应 API 并透传操作提交结果（taskId 等）。
   * 失败时抛出携带后端 message 的错误（409/404 场景由页面展示）。
   */
  async executeOperation(op: StorageOperation): Promise<OperationSubmitResult> {
    const { type, comicId, chapterId, regenerate = false } = op
    try {
      switch (type) {
        case StorageOperationType.DeleteHQ:
          if (chapterId != null) {
            const res = await hqApi.deleteChapter(chapterId)
            return res.data
          }
          const deleteComicRes = await hqApi.deleteComic(comicId)
          return deleteComicRes.data
        case StorageOperationType.GenerateLQ:
          if (chapterId != null) {
            const res = await lqApi.generateChapter(chapterId, regenerate)
            return res.data
          }
          const generateComicRes = await lqApi.generateComic(comicId, regenerate)
          return generateComicRes.data
        case StorageOperationType.RefreshMetadata:
          return this.requestMetadataRefresh(comicId)
      }
    } catch (err) {
      throw new Error(getApiErrorMessage(err, '操作失败'))
    }
    // StorageOperationType 已穷举，此分支不可达；保留兜底以满足全路径返回
    throw new Error('未知存储操作类型')
  },

  /**
   * 刷新漫画元数据（异步任务）：重读 HQ 目录生成快照并与数据库合并。
   * 成功返回 202 + OperationSubmitResult（taskId）；409/404 抛出带 message 的错误。
   */
  async requestMetadataRefresh(comicId: number): Promise<OperationSubmitResult> {
    try {
      const res = await storageAdminApi.refreshMetadata(comicId)
      return res.data
    } catch (err) {
      throw new Error(getApiErrorMessage(err, '操作失败'))
    }
  },

  async transcodeVideos(comicId: number): Promise<OperationSubmitResult> {
    const res = await storageAdminApi.transcodeComic(comicId)
    return res.data
  },
}

export const exportService = {
  async createExport(comicId: number): Promise<ExportTaskVO> {
    const res = await exportApi.createExport(comicId)
    return res.data
  },

  async fetchArtifacts(taskId: number): Promise<ExportArtifactVO[]> {
    const res = await exportApi.getArtifacts(taskId)
    return res.data
  },
}
