import { api } from '@/services/http'
import type { OperationSubmitResult } from '@/shared/api/types'
import type { ReconcileResult, TrashContentVO, TrashPurgeRequest } from './types'

export const trashApi = {
  list: (params: { page?: number; size?: number; status?: string; keyword?: string }) =>
    api.get<{ readonly records: readonly TrashContentVO[]; readonly total: number }>('/manage/trash', { params }),
  restoreComic: (comicId: number) => api.post<OperationSubmitResult>(`/manage/trash/comics/${comicId}/restore`),
  restoreChapter: (comicId: number, chapterId: number) =>
    api.post<OperationSubmitResult>(`/manage/trash/comics/${comicId}/chapters/${chapterId}/restore`),
  restoreMedia: (mediaId: number) => api.post<OperationSubmitResult>(`/manage/trash/media/${mediaId}/restore`),
  purgeComic: (comicId: number, token: string) =>
    api.post<OperationSubmitResult>(`/manage/trash/comics/${comicId}/purge`, { token } satisfies TrashPurgeRequest),
  purgeChapter: (comicId: number, chapterId: number, token: string) =>
    api.post<OperationSubmitResult>(`/manage/trash/comics/${comicId}/chapters/${chapterId}/purge`, { token } satisfies TrashPurgeRequest),
  purgeMedia: (mediaId: number, token: string) =>
    api.post<OperationSubmitResult>(`/manage/trash/media/${mediaId}/purge`, { token } satisfies TrashPurgeRequest),
  reconcile: (targetType: string, targetId: number) => api.get<ReconcileResult>(`/manage/trash/${targetType}/${targetId}/reconcile`),
  reconcileAndRepair: (targetType: string, targetId: number) => api.post<ReconcileResult>(`/manage/trash/${targetType}/${targetId}/reconcile`),
}
