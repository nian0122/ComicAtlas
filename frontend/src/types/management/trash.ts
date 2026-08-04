import type { TrashManifestStatus } from './enums'
import type { TargetType } from './enums'

/**
 * 回收站领域（T17）——对应后端 TrashLifecycleController + TrashManifestService
 */

/** 回收站操作目标：漫画/章节/媒体（章节目标需携带所属 comicId） */
export type TrashTarget =
  | { readonly targetType: Extract<TargetType, 'COMIC'>; readonly targetId: number }
  | { readonly targetType: Extract<TargetType, 'CHAPTER'>; readonly comicId: number; readonly targetId: number }
  | { readonly targetType: Extract<TargetType, 'MEDIA'>; readonly targetId: number }

/** 永久删除请求体（需前端生成的确认令牌） */
export interface PurgeRequest {
  readonly token: string
}

/** 回收清单（TrashManifest） */
export interface TrashManifest {
  readonly version: number
  readonly targetType: string
  readonly targetId: number
  readonly taskId: number
  readonly createdAt: string
  readonly entries: readonly TrashManifestEntry[]
}

export interface TrashManifestEntry {
  readonly rootKey: string
  readonly sourceRelativePath: string
  readonly trashRelativePath: string
}

/** 回收清单实际状态（TrashManifestActual） */
export interface TrashManifestActual {
  readonly version: number
  readonly targetType: string
  readonly targetId: number
  readonly taskId: number
  readonly status: TrashManifestStatus
  readonly errorMessage: string
  readonly completedAt: string
  readonly entries: readonly TrashManifestActualEntry[]
}

export interface TrashManifestActualEntry {
  readonly rootKey: string
  readonly sourceRelativePath: string
  readonly trashRelativePath: string
  readonly state: string
  readonly detail: string
}

/** 回收一致性报告（TrashReconcileReport） */
export interface TrashReconcileReport {
  readonly targetType: string
  readonly targetId: number
  readonly dbStatus: string
  readonly manifestTaskId: number | null
  readonly manifestStatus: string | null
  readonly consistent: boolean
  readonly entries: readonly TrashReconcileEntry[]
}

export interface TrashReconcileEntry {
  readonly rootKey: string
  readonly sourceRelativePath: string
  readonly sourceExists: boolean
  readonly trashExists: boolean
  readonly state: string
}

/** 回收站列表项（来自 GET /comics?status=TRASHED） */
export interface TrashComicItem {
  readonly id: number
  readonly title: string
  readonly coverUrl: string
  readonly pageCount: number
  readonly categoryId: number | null
  readonly categoryName: string | null
  readonly trashedAt: string
}
