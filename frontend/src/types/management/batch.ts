import type { EnumValue, TaskType } from './enums'
import type { ComicListQuery } from '@/types'
import type { ManagementTaskEntry } from './task'

/**
 * 批量操作领域（T17）——对应后端 BatchOperationController
 */

/** 批量选择的判别联合：按 ID 或按筛选条件 */
export const BatchSelectionType = {
  Ids: 'IDS',
  Filter: 'FILTER',
} as const
export type BatchSelectionType = (typeof BatchSelectionType)[keyof typeof BatchSelectionType]

export type BatchSelection =
  | {
      readonly type: 'IDS'
      readonly ids: readonly number[]
    }
  | {
      readonly type: 'FILTER'
      readonly query?: ComicListQuery
      readonly excludedIds?: readonly number[]
    }

/** 批量操作请求 */
export interface BatchOperationRequest {
  readonly operation: TaskType
  readonly selection: BatchSelection
  readonly payload?: BatchOperationPayload
  readonly previewToken?: string
}

/** 批量元数据操作载荷 */
export interface BatchOperationPayload {
  readonly categoryId?: number
  readonly addTagIds?: readonly number[]
  readonly title?: string
  readonly author?: string
  readonly description?: string
}

/** 批量预览结果 */
export interface BatchPreviewResponse {
  readonly operation: EnumValue<TaskType>
  readonly selectedCount: number
  readonly eligibleCount: number
  readonly blocked: readonly BlockedBatchItem[]
  readonly dangerous: boolean
  readonly previewToken: string
  readonly expiresAt: string
}

/** 批量创建结果 */
export interface BatchCreateResponse {
  readonly task: ManagementTaskEntry
  readonly selectedCount: number
  readonly eligibleCount: number
  readonly blocked: readonly BlockedBatchItem[]
}

/** 被批量操作阻塞的单项 */
export interface BlockedBatchItem {
  readonly comicId: number
  readonly reasonCode: BatchReasonCode
  readonly reason: string
}

/** 批量阻塞原因码（后端 BatchReasonCode） */
export const BatchReasonCode = {
  EMPTY_SELECTION: 'EMPTY_SELECTION',
  BATCH_SIZE_EXCEEDED: 'BATCH_SIZE_EXCEEDED',
  PREVIEW_TOKEN_REQUIRED: 'PREVIEW_TOKEN_REQUIRED',
  PREVIEW_TOKEN_EXPIRED: 'PREVIEW_TOKEN_EXPIRED',
  PREVIEW_CONDITION_CHANGED: 'PREVIEW_CONDITION_CHANGED',
  IDEMPOTENCY_CONFLICT: 'IDEMPOTENCY_CONFLICT',
  OP_NOT_ALLOWED: 'OP_NOT_ALLOWED',
  COMIC_NOT_FOUND: 'COMIC_NOT_FOUND',
} as const
export type BatchReasonCode =
  (typeof BatchReasonCode)[keyof typeof BatchReasonCode]
