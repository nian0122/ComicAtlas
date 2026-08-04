import { request } from './http'
import type { OperationSubmitResult } from '@/types/management/task'
import type { PurgeRequest, TrashReconcileReport, TrashTarget } from '@/types/management/trash'

/**
 * 回收站 API（T17）——对应后端 TrashLifecycleController /api/trash
 */
export const trashApi = {
  restore: async (target: TrashTarget): Promise<OperationSubmitResult> => {
    const raw = await request<unknown>({
      method: 'POST',
      url: `${trashPath(target)}/restore`,
    })
    return raw as OperationSubmitResult
  },

  purge: async (target: TrashTarget, token: string): Promise<OperationSubmitResult> => {
    const body: PurgeRequest = { token }
    const raw = await request<unknown>({
      method: 'POST',
      url: `${trashPath(target)}/purge`,
      data: body,
    })
    return raw as OperationSubmitResult
  },

  reconcile: async (target: TrashTarget): Promise<TrashReconcileReport> => {
    const raw = await request<unknown>({
      method: 'GET',
      url: `/trash/${target.targetType.toLowerCase()}/${target.targetId}/reconcile`,
    })
    return raw as TrashReconcileReport
  },
}

/** 目标 → 回收站端点前缀（comics/chapters/media） */
function trashPath(target: TrashTarget): string {
  const targetType = target.targetType
  switch (targetType) {
    case 'COMIC':
      return `/trash/comics/${target.targetId}`
    case 'CHAPTER':
      return `/trash/comics/${target.comicId}/chapters/${target.targetId}`
    case 'MEDIA':
      return `/trash/media/${target.targetId}`
    default:
      return assertNever(targetType)
  }
}

function assertNever(value: never): never {
  throw new Error(`Unexpected trash target: ${String(value)}`)
}
