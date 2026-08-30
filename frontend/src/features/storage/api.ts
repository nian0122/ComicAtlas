import { api } from '@/services/http'
import type { PageResult } from '@/shared/api/types'
import type {
  ChapterStorageItem,
  ComicStorageItem,
  ComicStorageQuery,
  ExportArtifactVO,
  ExportTaskVO,
  OperationSubmitResult,
  StorageStats,
} from '@/features/storage/types'

export const lqApi = {
  generateComic: (comicId: number, regenerate = false) =>
    api.post<OperationSubmitResult>(`/manage/storage/lq/comics/${comicId}`, undefined, { params: { regenerate } }),
  generateChapter: (chapterId: number, regenerate = false) =>
    api.post<OperationSubmitResult>(`/manage/storage/lq/chapters/${chapterId}`, undefined, { params: { regenerate } }),
}


export const hqApi = {
  deleteComic: (comicId: number) => api.post<OperationSubmitResult>(`/manage/storage/delete-hq/comics/${comicId}`),
  deleteChapter: (chapterId: number) => api.post<OperationSubmitResult>(`/manage/storage/delete-hq/chapters/${chapterId}`),
  transcodeMedia: (mediaId: number) => api.post<OperationSubmitResult>(`/manage/storage/transcode/media/${mediaId}`),
}

export const storageAdminApi = {
  stats: () => api.get<StorageStats>('/manage/storage/stats'),
  comics: (params: ComicStorageQuery) =>
    api.get<PageResult<ComicStorageItem>>('/manage/admin/storage/comics', { params }),
  comic: (comicId: number) => api.get<ComicStorageItem>(`/manage/admin/storage/comics/${comicId}`),
  chapters: (comicId: number) =>
    api.get<readonly ChapterStorageItem[]>(`/manage/admin/storage/comics/${comicId}/chapters`),
  refreshMetadata: (comicId: number) =>
    api.post<OperationSubmitResult>(`/manage/storage/refresh-metadata/comics/${comicId}`),
  transcodeComic: (comicId: number) =>
    api.post<OperationSubmitResult>(`/manage/storage/transcode/comics/${comicId}`),
  transcodeChapter: (chapterId: number) =>
    api.post<OperationSubmitResult>(`/manage/storage/transcode/chapters/${chapterId}`),
}

export const exportApi = {
  createExport: (comicId: number, format: 'ZIP' | 'CBZ' = 'ZIP') =>
    api.post<ExportTaskVO>(`/manage/storage/export/comics/${comicId}?format=${format}`),
  listExports: (comicId: number) => api.get<ExportTaskVO[]>(`/manage/storage/export/comics/${comicId}/tasks`),
  listAllExports: () => api.get<ExportTaskVO[]>('/manage/storage/export/tasks'),
  getTask: (taskId: number) => api.get<ExportTaskVO>(`/manage/storage/export/tasks/${taskId}`),
  getArtifacts: (taskId: number) => api.get<ExportArtifactVO[]>(`/manage/storage/export/tasks/${taskId}/artifacts`),
  openDir: (taskId: number) => api.post(`/manage/storage/export/tasks/${taskId}/open`),
}
