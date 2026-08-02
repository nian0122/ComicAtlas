import axios from 'axios'
import type {
  ComicMetadataUpdateDTO,
  ExportTaskVO,
  TagCreateDTO,
  ComicTagUpdateDTO,
  BatchComicUpdateDTO,
} from '@/types'

export type VideoTranscodeResult = {
  readonly comicId: number
  readonly totalVideoPages: number
  readonly notNeededCount: number
  readonly submittedCount: number
  readonly pendingCount: number
  readonly doneCount: number
  readonly failedCount: number
}

const api = axios.create({ baseURL: '/api' })

api.interceptors.response.use(
  (response) => {
    const data = response.data
    if (data && typeof data === 'object' && 'code' in data && 'data' in data) {
      response.data = data.data
    }
    return response
  },
  (error) => Promise.reject(error)
)

export const comicApi = {
  list: (params: any) => api.get('/comics', { params }),
  detail: (id: number) => api.get(`/comics/${id}`),
  delete: (id: number) => api.delete(`/comics/${id}`),
  getMetadata: (id: number) => api.get(`/comics/${id}/metadata`),
  updateMetadata: (id: number, data: ComicMetadataUpdateDTO) =>
    api.put(`/comics/${id}/metadata`, data),
  getTags: (id: number) => api.get(`/comics/${id}/tags`),
  updateTags: (id: number, data: ComicTagUpdateDTO) =>
    api.put(`/comics/${id}/tags`, data),
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
    api.post('/tasks/import', { sourceType, sourcePath }),
  list: (params: any) => api.get('/tasks/import', { params }),
  detail: (id: number) => api.get(`/tasks/import/${id}`),
  status: (id: number) => api.get(`/tasks/import/${id}/status`),
  cancel: (id: number) => api.post(`/tasks/import/${id}/cancel`),
  retry: (id: number) => api.post(`/tasks/import/${id}/retry`),
  createBatch: (data: { sourceType: string; sourcePaths: string[] }) =>
    api.post('/tasks/import/batch', data),
}

/** 目录扫描异步任务（API 创建 → MQ → Worker 扫描 → 结果回写 → 前端轮询） */
export const directoryScanApi = {
  create: (parentPath: string) =>
    api.post('/tasks/directory-scan', { parentPath }),
  get: (id: number) => api.get(`/tasks/directory-scan/${id}`),
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
  generateComic: (comicId: number) => api.post(`/comics/${comicId}/lq`),
  generateChapter: (chapterId: number) => api.post(`/chapters/${chapterId}/lq`),
}

export const hqApi = {
  deleteComic: (comicId: number) => api.post(`/comics/${comicId}/delete-hq`),
  deleteChapter: (chapterId: number) => api.post(`/chapters/${chapterId}/delete-hq`),
}

export const exportApi = {
  createExport: (comicId: number) => api.post(`/comics/${comicId}/export`),
  listExports: (comicId: number) => api.get<ExportTaskVO[]>(`/comics/${comicId}/exports`),
  getTask: (taskId: number) => api.get<ExportTaskVO>(`/export/${taskId}`),
  download: (taskId: number) => api.get(`/export/${taskId}/download`, { responseType: 'blob' }),
  openDir: (taskId: number) => api.post(`/export/${taskId}/open`),
}

export const adminApi = {
  deleteComic: (id: number, mode: string) => api.delete(`/admin/comics/${id}`, { params: { mode } }),
  refreshMetadata: (id: number) => api.post(`/admin/comics/${id}/refresh-metadata`),
  // scanRecover 已迁移至异步恢复任务中心 POST /api/tasks/recovery
  // 旧同步接口 POST /admin/storage/scan-recover 后端保留供兼容
  stats: () => api.get('/admin/storage/stats'),
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
    api.post<VideoTranscodeResult>(`/admin/storage/comics/${comicId}/transcode-videos`),
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
