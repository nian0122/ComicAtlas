import { api } from '@/services/http'
import type {
  CatalogManagementRequest,
  CatalogVO,
  ChapterManagementRequest,
  ChapterManagementVO,
  MediaReorderRequest,
  MediaReorderResult,
} from '@/features/comic/management-types'
import type {
  DlqMessageVO,
  DlqPurgeResult,
  DlqQueueVO,
  DlqReplayResult,
  MediaOperationResult,
  MqStats,
  OutboxStats,
} from '@/features/management/types'
import type { OperationSubmitResult } from '@/features/storage/types'

export {
  catalogApi,
  comicApi,
  managementCatalogApi,
  managementCategoryApi,
  managementComicApi,
  managementTagApi,
  readingCategoryApi,
  readingTagApi,
} from '@/entities/comic/api'
export { historyApi, managementReaderApi, readerApi } from '@/features/reader/api'
export { directoryScanApi, importApi } from '@/features/import/api'
export { exportApi, hqApi, lqApi } from '@/features/storage/api'
export { batchApi, managementTaskApi } from '@/features/task/api'
export { trashApi } from '@/features/trash/api'

// ========== 管理域（/api/manage/**，网关路由至 api-service 管理服务） ==========

/** 目录管理（create / rename / move / reorder / delete） */
export const catalogManagementApi = {
  create: (comicId: number, data: CatalogManagementRequest) =>
    api.post<CatalogVO>(`/manage/comics/${comicId}/catalogs`, data),
  rename: (comicId: number, catalogId: number, data: CatalogManagementRequest) =>
    api.patch<CatalogVO>(`/manage/comics/${comicId}/catalogs/${catalogId}`, data),
  move: (comicId: number, catalogId: number, data: CatalogManagementRequest) =>
    api.put<CatalogVO>(`/manage/comics/${comicId}/catalogs/${catalogId}/move`, data),
  reorder: (comicId: number, catalogId: number, data: CatalogManagementRequest) =>
    api.put(`/manage/comics/${comicId}/catalogs/${catalogId}/reorder`, data),
  delete: (comicId: number, catalogId: number, reparentTo?: number) =>
    api.delete(`/manage/comics/${comicId}/catalogs/${catalogId}`, { params: { reparentTo } }),
}

/** 章节管理（create / rename / move / reorder / trash） */
export const chapterManagementApi = {
  create: (comicId: number, data: ChapterManagementRequest) =>
    api.post<ChapterManagementVO>(`/manage/comics/${comicId}/chapters`, data),
  rename: (comicId: number, chapterId: number, data: ChapterManagementRequest) =>
    api.patch<ChapterManagementVO>(`/manage/comics/${comicId}/chapters/${chapterId}`, data),
  move: (comicId: number, chapterId: number, data: ChapterManagementRequest) =>
    api.put<ChapterManagementVO>(`/manage/comics/${comicId}/chapters/${chapterId}/move`, data),
  reorder: (comicId: number, chapterId: number, data: ChapterManagementRequest) =>
    api.put<ChapterManagementVO>(`/manage/comics/${comicId}/chapters/${chapterId}/reorder`, data),
  trash: (comicId: number, chapterId: number) =>
    api.delete(`/manage/comics/${comicId}/chapters/${chapterId}`),
}

/** 媒体管理（章节内重排 / 回收） */
export const mediaManagementApi = {
  reorder: (chapterId: number, data: MediaReorderRequest) =>
    api.post<MediaReorderResult>(`/manage/chapters/${chapterId}/media/reorder`, data),
  trash: (mediaId: number) =>
    api.delete<OperationSubmitResult>(`/manage/media/${mediaId}`),
}

/** 允许操作查询（按钮权限以后端判定为准） */
export const mediaOperationApi = {
  forComic: (comicId: number) => api.get<MediaOperationResult>(`/manage/operations/comics/${comicId}`),
  forChapter: (chapterId: number) => api.get<MediaOperationResult>(`/manage/operations/chapters/${chapterId}`),
  forMedia: (mediaId: number) => api.get<MediaOperationResult>(`/manage/operations/media/${mediaId}`),
}

/** Outbox 积压统计 */
export const outboxApi = {
  stats: () => api.get<OutboxStats>('/manage/outbox/stats'),
}

/** MQ 积压与死信统计（消费层失败与堆积，覆盖僵尸队列） */
export const mqApi = {
  stats: () => api.get<MqStats>('/manage/mq/stats'),
}

export const adminApi = {
  deleteComic: (id: number, mode: string) => api.delete(`/manage/admin/comics/${id}`, { params: { mode } }),
  scanRecover: () => api.post('/manage/admin/storage/scan-recover'),
  // scanRecover 已迁移至异步恢复任务中心 POST /api/manage/tasks/recovery
  // 旧同步接口 POST /manage/admin/storage/scan-recover 后端保留供兼容
  dlqQueues: () =>
    api.get<readonly DlqQueueVO[]>('/manage/admin/dlq/queues'),
  dlqMessages: (queueName: string, count = 20) =>
    api.get<readonly DlqMessageVO[]>(
      `/manage/admin/dlq/queues/${encodeURIComponent(queueName)}/messages`,
      { params: { count } },
    ),
  dlqReplay: (queueName: string, maxMessages = 100) =>
    api.post<DlqReplayResult>(
      `/manage/admin/dlq/queues/${encodeURIComponent(queueName)}/replay`,
      undefined,
      { params: { maxMessages } },
    ),
  dlqPurge: (queueName: string) =>
    api.delete<DlqPurgeResult>(
      `/manage/admin/dlq/queues/${encodeURIComponent(queueName)}/messages`,
    ),
}

export const settingsApi = {
  get: () => api.get('/manage/settings'),
  update: (data: Record<string, unknown>) => api.put('/manage/settings', data),
}
