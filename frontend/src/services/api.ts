import axios from 'axios'
import type {
  BatchCreateResult,
  BatchImportRequest,
  BatchImportResultVO,
  BatchPreviewResult,
  BatchSubmitRequest,
  CatalogManagementRequest,
  CatalogVO,
  ChapterManagementRequest,
  ChapterManagementVO,
  ComicDetailVO,
  ComicMetadataUpdateDTO,
  CreateManagementTaskRequest,
  CreateUploadSessionRequest,
  CreateUploadSessionResult,
  DirectoryScanTaskVO,
  ExportArtifactVO,
  ExportTaskVO,
  ImportStatusVO,
  ImportTaskQuery,
  ImportTaskVO,
  ManagementTaskItemVO,
  ManagementTaskQuery,
  ManagementTaskVO,
  MediaOperationResult,
  MediaReorderRequest,
  MediaReorderResult,
  MqStats,
  OperationSubmitResult,
  OutboxStats,
  ReconcileResult,
  TagCreateDTO,
  ComicTagUpdateDTO,
  BatchComicUpdateDTO,
  TrashPurgeRequest,
  UploadChunkResult,
  UploadCompleteResult,
  UploadSessionStatus,
} from '@/types'

export type TrashContentVO = {
  readonly targetType: 'COMIC' | 'CHAPTER' | 'MEDIA'
  readonly targetId: number
  readonly comicId: number | null
  readonly chapterId: number | null
  readonly title: string
  readonly subtitle: string | null
  readonly coverUrl: string | null
  readonly status: string
  readonly mediaType: string | null
  readonly pageNumber: number | null
  readonly createdAt: string
  readonly trashedAt: string | null
}

const api = axios.create({ baseURL: '/api' })

/** 后端 Result 业务成功码（Result.ok 恒为 200） */
const RESULT_OK_CODE = 200

api.interceptors.response.use(
  (response) => {
    const data = response.data
    if (data && typeof data === 'object' && 'code' in data) {
      // 后端业务失败以 HTTP 200 + 非 200 code 返回：必须转为 reject，
      // 让各页面的 catch 统一展示后端 message，避免把 null 当成功数据继续使用（曾引发读 null.status / null.pages 崩溃）。
      if (data.code !== RESULT_OK_CODE) {
        const message = typeof data.message === 'string' && data.message ? data.message : '请求失败'
        return Promise.reject(new Error(message))
      }
      if ('data' in data) {
        response.data = data.data
      }
    }
    return response
  },
  (error) => Promise.reject(error)
)

// ========== 阅读域（/api/**，网关路由至 reading-service） ==========

export const comicApi = {
  list: (params: any) => api.get('/comics', { params }),
  detail: (id: number) => api.get<ComicDetailVO>(`/comics/${id}`),
  getMetadata: (id: number) => api.get(`/comics/${id}/metadata`),
  getTags: (id: number) => api.get(`/comics/${id}/tags`),
}

export const catalogApi = {
  tree: (comicId: number) => api.get(`/comics/${comicId}/catalog`),
}

export const managementCatalogApi = {
  tree: (comicId: number) => api.get(`/manage/comics/${comicId}/catalog`),
}

/** 管理域漫画查询，管理页面专用。 */
export const managementComicApi = {
  list: (params: any) => api.get('/manage/comics', { params }),
  detail: (id: number) => api.get<ComicDetailVO>(`/manage/comics/${id}`),
  getMetadata: (id: number) => api.get(`/manage/comics/${id}/metadata`),
  getTags: (id: number) => api.get(`/manage/comics/${id}/tags`),
  delete: (id: number) => api.delete(`/manage/comics/${id}`),
  updateMetadata: (id: number, data: ComicMetadataUpdateDTO) => api.put(`/manage/comics/${id}/metadata`, data),
  updateTags: (id: number, data: ComicTagUpdateDTO) => api.put(`/manage/comics/${id}/tags`, data),
  batchUpdate: (data: BatchComicUpdateDTO) => api.post('/manage/comics/batch/update', data),
}

export const readerApi = {
  chapter: (chapterId: number) => api.get(`/chapters/${chapterId}`),
}

export const managementReaderApi = {
  chapter: (chapterId: number) => api.get(`/manage/chapters/${chapterId}`),
}

export const historyApi = {
  list: () => api.get('/history'),
  page: (page: number, size: number) => api.get('/history/page', { params: { page, size } }),
  get: (comicId: number) => api.get(`/history/${comicId}`),
  update: (comicId: number, data: { chapterId: number; pageNumber: number }) =>
    api.put(`/history/${comicId}`, data),
}

/** 阅读域筛选项查询，供阅读端使用。 */
export const readingTagApi = {
  list: () => api.get('/tags'),
}

export const readingCategoryApi = {
  list: () => api.get('/categories'),
}

export const managementTagApi = {
  list: () => api.get('/manage/tags'),
  create: (data: TagCreateDTO) => api.post('/manage/tags', data),
  delete: (id: number) => api.delete(`/manage/tags/${id}`),
}

export const managementCategoryApi = {
  list: () => api.get('/manage/categories'),
  create: (name: string) => api.post('/manage/categories', null, { params: { name } }),
  update: (id: number, name: string) => api.put(`/manage/categories/${id}`, null, { params: { name } }),
  delete: (id: number) => api.delete(`/manage/categories/${id}`),
}

// ========== 管理域（/api/manage/**，网关路由至 api-service 管理服务） ==========

export const importApi = {
  create: (sourceType: string, sourcePath: string) =>
    api.post<ImportTaskVO>('/manage/tasks/import', { sourceType, sourcePath }),
  list: (params?: ImportTaskQuery) =>
    api.get<{ records: ImportTaskVO[]; total: number }>('/manage/tasks/import', { params }),
  detail: (id: number) => api.get<ImportTaskVO>(`/manage/tasks/import/${id}`),
  status: (id: number) => api.get<ImportStatusVO>(`/manage/tasks/import/${id}/status`),
  cancel: (id: number) => api.post<void>(`/manage/tasks/import/${id}/cancel`),
  retry: (id: number) => api.post<void>(`/manage/tasks/import/${id}/retry`),
  createBatch: (data: BatchImportRequest) =>
    api.post<BatchImportResultVO>('/manage/tasks/import/batch', data),
}

/** 目录扫描异步任务（API 创建 → MQ → Worker 扫描 → 结果回写 → 前端轮询） */
export const directoryScanApi = {
  create: (parentPath: string) =>
    api.post<DirectoryScanTaskVO>('/manage/tasks/directory-scan', { parentPath }),
  get: (id: number) => api.get<DirectoryScanTaskVO>(`/manage/tasks/directory-scan/${id}`),
}

export const lqApi = {
  generateComic: (comicId: number) => api.post<OperationSubmitResult>(`/manage/storage/lq/comics/${comicId}`),
  generateChapter: (chapterId: number) => api.post<OperationSubmitResult>(`/manage/storage/lq/chapters/${chapterId}`),
}

export const hqApi = {
  deleteComic: (comicId: number) => api.post<OperationSubmitResult>(`/manage/storage/delete-hq/comics/${comicId}`),
  deleteChapter: (chapterId: number) => api.post<OperationSubmitResult>(`/manage/storage/delete-hq/chapters/${chapterId}`),
  transcodeMedia: (mediaId: number) => api.post<OperationSubmitResult>(`/manage/storage/transcode/media/${mediaId}`),
}

export const exportApi = {
  createExport: (comicId: number) => api.post<ExportTaskVO>(`/manage/storage/export/comics/${comicId}`),
  listExports: (comicId: number) => api.get<ExportTaskVO[]>(`/manage/storage/export/comics/${comicId}/tasks`),
  listAllExports: () => api.get<ExportTaskVO[]>('/manage/storage/export/tasks'),
  getTask: (taskId: number) => api.get<ExportTaskVO>(`/manage/storage/export/tasks/${taskId}`),
  getArtifacts: (taskId: number) =>
    api.get<ExportArtifactVO[]>(`/manage/storage/export/tasks/${taskId}/artifacts`),
  openDir: (taskId: number) => api.post(`/manage/storage/export/tasks/${taskId}/open`),
}

/** 统一管理任务中心 */
export const managementTaskApi = {
  list: (params: ManagementTaskQuery) => api.get('/manage/tasks', { params }),
  get: (id: number) => api.get<ManagementTaskVO>(`/manage/tasks/${id}`),
  getItems: (id: number) => api.get<ManagementTaskItemVO[]>(`/manage/tasks/${id}/items`),
  create: (data: CreateManagementTaskRequest) => api.post<ManagementTaskVO>('/manage/tasks', data),
  cancel: (id: number) => api.post<ManagementTaskVO>(`/manage/tasks/${id}/cancel`),
  retry: (id: number) => api.post<ManagementTaskVO>(`/manage/tasks/${id}/retry`),
}

/** 回收站生命周期（恢复 / 永久清理 / 对账） */
export const trashApi = {
  list: (params: { page?: number; size?: number; status?: string; keyword?: string }) =>
    api.get<{ readonly records: readonly TrashContentVO[]; readonly total: number }>('/manage/trash', { params }),
  restoreComic: (comicId: number) =>
    api.post<OperationSubmitResult>(`/manage/trash/comics/${comicId}/restore`),
  restoreChapter: (comicId: number, chapterId: number) =>
    api.post<OperationSubmitResult>(`/manage/trash/comics/${comicId}/chapters/${chapterId}/restore`),
  restoreMedia: (mediaId: number) =>
    api.post<OperationSubmitResult>(`/manage/trash/media/${mediaId}/restore`),
  purgeComic: (comicId: number, token: string) =>
    api.post<OperationSubmitResult>(`/manage/trash/comics/${comicId}/purge`, { token } satisfies TrashPurgeRequest),
  purgeChapter: (comicId: number, chapterId: number, token: string) =>
    api.post<OperationSubmitResult>(
      `/manage/trash/comics/${comicId}/chapters/${chapterId}/purge`,
      { token } satisfies TrashPurgeRequest,
    ),
  purgeMedia: (mediaId: number, token: string) =>
    api.post<OperationSubmitResult>(`/manage/trash/media/${mediaId}/purge`, { token } satisfies TrashPurgeRequest),
  reconcile: (targetType: string, targetId: number) =>
    api.get<ReconcileResult>(`/manage/trash/${targetType}/${targetId}/reconcile`),
  reconcileAndRepair: (targetType: string, targetId: number) =>
    api.post<ReconcileResult>(`/manage/trash/${targetType}/${targetId}/reconcile`),
}

/**
 * 分块上传会话（原始字节流 + Content-Range 头）。
 * 预留接口能力：后端接口已实现且测试可用，但当前无前端页面入口，不属于漫画导入主流程。
 */
export const uploadApi = {
  createSession: (data: CreateUploadSessionRequest) =>
    api.post<CreateUploadSessionResult>('/manage/uploads/sessions', data),
  getSession: (sessionId: string) =>
    api.get<UploadSessionStatus>(`/manage/uploads/sessions/${sessionId}`),
  uploadChunk: (
    sessionId: string,
    fileId: string,
    chunk: Blob,
    contentRange: string,
    chunkSha256?: string,
  ) =>
    api.put<UploadChunkResult>(`/manage/uploads/sessions/${sessionId}/files/${fileId}`, chunk, {
      headers: {
        'Content-Type': 'application/octet-stream',
        'Content-Range': contentRange,
        ...(chunkSha256 ? { 'X-Sha256': chunkSha256 } : {}),
      },
    }),
  completeSession: (sessionId: string) =>
    api.post<UploadCompleteResult>(`/manage/uploads/sessions/${sessionId}/complete`),
  cancelSession: (sessionId: string) =>
    api.delete(`/manage/uploads/sessions/${sessionId}`),
}

/** 跨页批量操作 */
export const batchApi = {
  preview: (data: BatchSubmitRequest) => api.post<BatchPreviewResult>('/manage/batch/preview', data),
  submit: (data: BatchSubmitRequest) => api.post<BatchCreateResult>('/manage/batch', data),
}

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
  refreshMetadata: (id: number) => api.post<OperationSubmitResult>(`/manage/storage/refresh-metadata/comics/${id}`),
  scanRecover: () => api.post('/manage/admin/storage/scan-recover'),
  // scanRecover 已迁移至异步恢复任务中心 POST /api/manage/tasks/recovery
  // 旧同步接口 POST /manage/admin/storage/scan-recover 后端保留供兼容
  stats: () => api.get('/manage/storage/stats'),
  storageComics: (params: {
    page?: number
    size?: number
    hqStatus?: 'ALL' | 'HAS_HQ' | 'NO_HQ'
    lqStatus?: 'ALL' | 'NEEDS_LQ' | 'READY'
    sort?: 'totalSize' | 'hqSize' | 'lqSize' | 'title'
    order?: 'asc' | 'desc'
    keyword?: string
    category?: string
    tag?: string
  }) => api.get('/manage/admin/storage/comics', { params }),
  storageComic: (comicId: number) => api.get(`/manage/admin/storage/comics/${comicId}`),
  storageChapters: (comicId: number) => api.get(`/manage/admin/storage/comics/${comicId}/chapters`),
  transcodeVideos: (comicId: number) =>
    api.post<OperationSubmitResult>(`/manage/storage/transcode/comics/${comicId}`),
  transcodeChapter: (chapterId: number) =>
    api.post<OperationSubmitResult>(`/manage/storage/transcode/chapters/${chapterId}`),
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

export interface DlqQueueVO {
  readonly name: string
  readonly exchange: string
  readonly routingKey: string
  readonly originalQueue: string
  readonly messages: number
  readonly consumers: number
}

export interface DlqCredentials {
  readonly username: string
  readonly password: string
}

export interface DlqMessageVO {
  readonly payload: string
  readonly payloadEncoding: 'string' | 'base64'
  readonly properties: Readonly<Record<string, unknown>>
  readonly messagesRemaining: number
}

export interface DlqReplayResult {
  readonly queue: string
  readonly attempted: number
  readonly replayed: number
  readonly remaining: number
  readonly completed: boolean
  readonly error: string | null
}

export interface DlqPurgeResult {
  readonly queue: string
  readonly purged: number
}

export const settingsApi = {
  get: () => api.get('/manage/settings'),
  update: (data: any) => api.put('/manage/settings', data),
}

export default api
