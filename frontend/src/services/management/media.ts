import { request } from './http'
import { parseChapterPayload } from '@/types/management/media'
import type { MediaChapterPayload } from '@/types/management/media'
import type { OperationSubmitResult } from '@/types/management/task'

/**
 * 媒体管理 API（Task 19）——章节媒体列表 / 回收 / 恢复。
 * 重排复用 T17 uploadApi.reorderMedia；上传复用 uploadApi（会话/分块）。
 */
export const mediaApi = {
  /** 拉取章节媒体列表（GET /chapters/{id}，reader 接口的章节载荷） */
  chapterMedia: async (chapterId: number): Promise<MediaChapterPayload> => {
    const raw = await request<unknown>({ method: 'GET', url: `/chapters/${chapterId}` })
    return parseChapterPayload(raw)
  },

  /** 媒体进回收站（DELETE /media/{id}） */
  trashMedia: async (mediaId: number): Promise<OperationSubmitResult> => {
    const raw = await request<unknown>({ method: 'DELETE', url: `/media/${mediaId}` })
    return raw as OperationSubmitResult
  },

  /** 媒体从回收站恢复（POST /trash/media/{id}/restore） */
  restoreMedia: async (mediaId: number): Promise<OperationSubmitResult> => {
    const raw = await request<unknown>({ method: 'POST', url: `/trash/media/${mediaId}/restore` })
    return raw as OperationSubmitResult
  },
}
