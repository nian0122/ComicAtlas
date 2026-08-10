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
  ComicListQuery,
  ComicListVO,
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
  OperationSubmitResult,
  OutboxStats,
  PageResult,
  ReconcileResult,
  TagCreateDTO,
  BatchComicUpdateDTO,
  TrashPurgeRequest,
  UpdateComicRequest,
  UploadChunkResult,
  UploadCompleteResult,
  UploadSessionStatus,
} from '@/types'

const api = axios.create({ baseURL: '/api' })

/** 业务包装响应（HTTP 200 + code/message/data） */
interface WrappedResponse<T> {
  code: number
  message: string
  data: T
}

api.interceptors.response.use(
  (response) => {
    const data = response.data
    if (data && typeof data === 'object' && 'code' in data && 'data' in data) {
      const wrapped = data as WrappedResponse<unknown>
      if (wrapped.code !== 200) {
        // 业务非 200：在解包前 reject，保留 code/message/response 供调用方区分（如 409 冲突）
        return Promise.reject(buildBusinessError(wrapped, response))
      }
      response.data = wrapped.data
    }
    return response
  },
  (error) => Promise.reject(error)
)

/** 构造保留 code/message/response 的业务错误，供调用方按业务码分支处理。 */
function buildBusinessError(wrapped: WrappedResponse<unknown>, response: unknown): Error & {
  code?: number
  response?: unknown
} {
  const err = new Error(wrapped.message || `业务错误 ${wrapped.code}`) as Error & {
    code?: number
    response?: unknown
  }
  err.code = wrapped.code
  err.response = response
  return err
}

export const comicApi = {
  list: (params: ComicListQuery) => api.get<PageResult<ComicListVO>>('/comics', { params }),
  detail: (id: number) => api.get<ComicDetailVO>(`/comics/${id}`),
  update: (id: number, data: UpdateComicRequest) => api.put<ComicDetailVO>(`/comics/${id}`, data),
  delete: (id: number) => api.delete(`/comics/${id}`),
  /** 批量更新漫画分类和标签 */
  batchUpdate: (data: BatchComicUpdateDTO) =>
    api.post('/comics/batch/update', data),
}

export const catalogApi = {
  tree: (comicId: number) => api.get(`/comics/${comicId}/catalog`),
}

export const readerApi = {
  chapter: (chapterId: number) => api.get(`/chapters/${chapterId}`),
}

export const importApi = {
  create: (sourceType: string, sourcePath: string) =>
    api.post<ImportTaskVO>('/tasks/import', { sourceType, sourcePath }),
  list: (params?: ImportTaskQuery) =>
    api.get<{ records: ImportTaskVO[]; total: number }>('/tasks/import', { params }),
  detail: (id: number) => api.get<ImportTaskVO>(`/tasks/import/${id}`),
  status: (id: number) => api.get<ImportStatusVO>(`/tasks/import/${id}/status`),
  cancel: (id: number) => api.post<void>(`/tasks/import/${id}/cancel`),
  retry: (id: number) => api.post<void>(`/tasks/import/${id}/retry`),
  createBatch: (data: BatchImportRequest) =>
    api.post<BatchImportResultVO>('/tasks/import/batch', data),
}

/** 目录扫描异步任务（API 创建 → MQ → Worker 扫描 → 结果回写 → 前端轮询） */
export const directoryScanApi = {
  create: (parentPath: string) =>
    api.post<DirectoryScanTaskVO>('/tasks/directory-scan', { parentPath }),
  get: (id: number) => api.get<DirectoryScanTaskVO>(`/tasks/directory-scan/${id}`),
}

export const historyApi = {
  list: () => api.get('/history'),
  get: (comicId: number) => api.get(`/history/${comicId}`),
  update: (comicId: number, data: { chapterId: number; pageNumber: number }) =>
    api.put(`/history/${comicId}`, data),
}

export const tagApi = {
  list: () => api.get('/tags'),
  create: (data: TagCreateDTO) => api.post('/tags', data),
  delete: (id: number) => api.delete(`/tags/${id}`),
}

export const categoryApi = {
  list: () => api.get('/categories'),
  create: (name: string) => api.post('/categories', null, { params: { name } }),
  update: (id: number, name: string) => api.put(`/categories/${id}`, null, { params: { name } }),
  delete: (id: number) => api.delete(`/categories/${id}`),
}

export const lqApi = {
  generateComic: (comicId: number) => api.post<OperationSubmitResult>(`/storage/lq/comics/${comicId}`),
  generateChapter: (chapterId: number) => api.post<OperationSubmitResult>(`/storage/lq/chapters/${chapterId}`),
}

export const hqApi = {
  deleteComic: (comicId: number) => api.post<OperationSubmitResult>(`/storage/delete-hq/comics/${comicId}`),
  deleteChapter: (chapterId: number) => api.post<OperationSubmitResult>(`/storage/delete-hq/chapters/${chapterId}`),
}

export const exportApi = {
  createExport: (comicId: number) => api.post<ExportTaskVO>(`/storage/export/comics/${comicId}`),
  listExports: (comicId: number) => api.get<ExportTaskVO[]>(`/storage/export/comics/${comicId}/tasks`),
  getTask: (taskId: number) => api.get<ExportTaskVO>(`/storage/export/tasks/${taskId}`),
  getArtifacts: (taskId: number) =>
    api.get<ExportArtifactVO[]>(`/storage/export/tasks/${taskId}/artifacts`),
  openDir: (taskId: number) => api.post(`/storage/export/tasks/${taskId}/open`),
}

// ========== Management Domain ==========

/** 统一管理任务中心 */
export const managementTaskApi = {
  list: (params: ManagementTaskQuery) => api.get<PageResult<ManagementTaskVO>>('/management/tasks', { params }),
  get: (id: number) => api.get<ManagementTaskVO>(`/management/tasks/${id}`),
  getItems: (id: number) => api.get<ManagementTaskItemVO[]>(`/management/tasks/${id}/items`),
  create: (data: CreateManagementTaskRequest) => api.post<ManagementTaskVO>('/management/tasks', data),
  cancel: (id: number) => api.post<ManagementTaskVO>(`/management/tasks/${id}/cancel`),
  retry: (id: number) => api.post<ManagementTaskVO>(`/management/tasks/${id}/retry`),
}

/** 回收站生命周期（恢复 / 永久清理 / 对账） */
export const trashApi = {
  restoreComic: (comicId: number) =>
    api.post<OperationSubmitResult>(`/trash/comics/${comicId}/restore`),
  restoreChapter: (comicId: number, chapterId: number) =>
    api.post<OperationSubmitResult>(`/trash/comics/${comicId}/chapters/${chapterId}/restore`),
  restoreMedia: (mediaId: number) =>
    api.post<OperationSubmitResult>(`/trash/media/${mediaId}/restore`),
  purgeComic: (comicId: number, token: string) =>
    api.post<OperationSubmitResult>(`/trash/comics/${comicId}/purge`, { token } satisfies TrashPurgeRequest),
  purgeChapter: (comicId: number, chapterId: number, token: string) =>
    api.post<OperationSubmitResult>(
      `/trash/comics/${comicId}/chapters/${chapterId}/purge`,
      { token } satisfies TrashPurgeRequest,
    ),
  purgeMedia: (mediaId: number, token: string) =>
    api.post<OperationSubmitResult>(`/trash/media/${mediaId}/purge`, { token } satisfies TrashPurgeRequest),
  reconcile: (targetType: string, targetId: number) =>
    api.get<ReconcileResult>(`/trash/${targetType}/${targetId}/reconcile`),
  reconcileAndRepair: (targetType: string, targetId: number) =>
    api.post<ReconcileResult>(`/trash/${targetType}/${targetId}/reconcile`),
}

/**
 * 分块上传会话（原始字节流 + Content-Range 头）。
 * 预留接口能力：后端接口已实现且测试可用，但当前无前端页面入口，不属于漫画导入主流程。
 */
export const uploadApi = {
  createSession: (data: CreateUploadSessionRequest) =>
    api.post<CreateUploadSessionResult>('/uploads/sessions', data),
  getSession: (sessionId: string) =>
    api.get<UploadSessionStatus>(`/uploads/sessions/${sessionId}`),
  uploadChunk: (
    sessionId: string,
    fileId: string,
    chunk: Blob,
    contentRange: string,
    chunkSha256?: string,
  ) =>
    api.put<UploadChunkResult>(`/uploads/sessions/${sessionId}/files/${fileId}`, chunk, {
      headers: {
        'Content-Type': 'application/octet-stream',
        'Content-Range': contentRange,
        ...(chunkSha256 ? { 'X-Sha256': chunkSha256 } : {}),
      },
    }),
  completeSession: (sessionId: string) =>
    api.post<UploadCompleteResult>(`/uploads/sessions/${sessionId}/complete`),
  cancelSession: (sessionId: string) =>
    api.delete(`/uploads/sessions/${sessionId}`),
}

/** 跨页批量操作 */
export const batchApi = {
  preview: (data: BatchSubmitRequest) => api.post<BatchPreviewResult>('/management/batch/preview', data),
  submit: (data: BatchSubmitRequest) => api.post<BatchCreateResult>('/management/batch', data),
}

/** 目录管理（create / rename / move / reorder / delete） */
export const catalogManagementApi = {
  create: (comicId: number, data: CatalogManagementRequest) =>
    api.post<CatalogVO>(`/comics/${comicId}/catalogs`, data),
  rename: (comicId: number, catalogId: number, data: CatalogManagementRequest) =>
    api.patch<CatalogVO>(`/comics/${comicId}/catalogs/${catalogId}`, data),
  move: (comicId: number, catalogId: number, data: CatalogManagementRequest) =>
    api.put<CatalogVO>(`/comics/${comicId}/catalogs/${catalogId}/move`, data),
  reorder: (comicId: number, catalogId: number, data: CatalogManagementRequest) =>
    api.put(`/comics/${comicId}/catalogs/${catalogId}/reorder`, data),
  delete: (comicId: number, catalogId: number, reparentTo?: number) =>
    api.delete(`/comics/${comicId}/catalogs/${catalogId}`, { params: { reparentTo } }),
}

/** 章节管理（create / rename / move / reorder / trash） */
export const chapterManagementApi = {
  create: (comicId: number, data: ChapterManagementRequest) =>
    api.post<ChapterManagementVO>(`/comics/${comicId}/chapters`, data),
  rename: (comicId: number, chapterId: number, data: ChapterManagementRequest) =>
    api.patch<ChapterManagementVO>(`/comics/${comicId}/chapters/${chapterId}`, data),
  move: (comicId: number, chapterId: number, data: ChapterManagementRequest) =>
    api.put<ChapterManagementVO>(`/comics/${comicId}/chapters/${chapterId}/move`, data),
  reorder: (comicId: number, chapterId: number, data: ChapterManagementRequest) =>
    api.put<ChapterManagementVO>(`/comics/${comicId}/chapters/${chapterId}/reorder`, data),
  trash: (comicId: number, chapterId: number) =>
    api.delete(`/comics/${comicId}/chapters/${chapterId}`),
}

/** 媒体管理（章节内重排 / 回收） */
export const mediaManagementApi = {
  reorder: (chapterId: number, data: MediaReorderRequest) =>
    api.post<MediaReorderResult>(`/chapters/${chapterId}/media/reorder`, data),
  trash: (mediaId: number) =>
    api.delete<OperationSubmitResult>(`/media/${mediaId}`),
}

/** 允许操作查询（按钮权限以后端判定为准） */
export const mediaOperationApi = {
  forComic: (comicId: number) => api.get<MediaOperationResult>(`/management/operations/comics/${comicId}`),
  forChapter: (chapterId: number) => api.get<MediaOperationResult>(`/management/operations/chapters/${chapterId}`),
  forMedia: (mediaId: number) => api.get<MediaOperationResult>(`/management/operations/media/${mediaId}`),
}

/** Outbox 积压统计 */
export const outboxApi = {
  stats: () => api.get<OutboxStats>('/management/outbox/stats'),
}

export const adminApi = {
  deleteComic: (id: number, mode: string) => api.delete(`/admin/comics/${id}`, { params: { mode } }),
  refreshMetadata: (id: number) => api.post<OperationSubmitResult>(`/storage/refresh-metadata/comics/${id}`),
  scanRecover: () => api.post('/admin/storage/scan-recover'),
  // scanRecover 已迁移至异步恢复任务中心 POST /api/tasks/recovery
  // 旧同步接口 POST /admin/storage/scan-recover 后端保留供兼容
  stats: () => api.get('/storage/stats'),
  storageComics: (params: {
    page?: number
    size?: number
    hqStatus?: 'ALL' | 'HAS_HQ' | 'NO_HQ'
    lqStatus?: 'ALL' | 'NEEDS_LQ' | 'READY'
    sort?: 'totalSize' | 'hqSize' | 'lqSize' | 'title'
    order?: 'asc' | 'desc'
    keyword?: string
  }) => api.get('/admin/storage/comics', { params }),
  storageComic: (comicId: number) => api.get(`/admin/storage/comics/${comicId}`),
  storageChapters: (comicId: number) => api.get(`/admin/storage/comics/${comicId}/chapters`),
  transcodeVideos: (comicId: number) =>
    api.post<OperationSubmitResult>(`/storage/transcode/comics/${comicId}`),
  dlqQueues: () =>
    api.get<readonly DlqQueueVO[]>('/admin/dlq/queues'),
  dlqMessages: (queueName: string, count = 20) =>
    api.get<readonly DlqMessageVO[]>(
      `/admin/dlq/queues/${encodeURIComponent(queueName)}/messages`,
      { params: { count } },
    ),
  dlqReplay: (queueName: string, maxMessages = 100) =>
    api.post<DlqReplayResult>(
      `/admin/dlq/queues/${encodeURIComponent(queueName)}/replay`,
      undefined,
      { params: { maxMessages } },
    ),
  dlqPurge: (queueName: string) =>
    api.delete<DlqPurgeResult>(
      `/admin/dlq/queues/${encodeURIComponent(queueName)}/messages`,
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
  get: () => api.get('/settings'),
  update: (data: any) => api.put('/settings', data),
}

export default api
