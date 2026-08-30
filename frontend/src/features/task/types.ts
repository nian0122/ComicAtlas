import type { ComicListQuery } from '@/entities/comic/types'

export type ManagementTaskType =
  | 'IMPORT'
  | 'RECOVERY'
  | 'EXPORT'
  | 'DIRECTORY_SCAN'
  | 'LQ_GENERATE'
  | 'LQ_REGENERATE'
  | 'HQ_DELETE'
  | 'TRANSCODE'
  | 'METADATA_REFRESH'
  | 'METADATA_UPDATE'
  | 'COMIC_DELETE'
  | 'MEDIA_UPLOAD'
  | 'MEDIA_REPLACE'
  | 'MEDIA_TRASH'
  | 'CHAPTER_TRASH'
  | 'COMIC_RESTORE'
  | 'CHAPTER_RESTORE'
  | 'MEDIA_RESTORE'
  | 'COMIC_PURGE'
  | 'CHAPTER_PURGE'
  | 'MEDIA_PURGE'

export type ManagementTaskStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'CANCELLING'
  | 'CANCELLED'
  | 'SUCCEEDED'
  | 'PARTIALLY_SUCCEEDED'
  | 'FAILED'

export interface ManagementTaskQuery {
  readonly page?: number
  readonly size?: number
  readonly type?: ManagementTaskType
  readonly status?: ManagementTaskStatus
  readonly batchId?: string
  readonly targetType?: string
  readonly targetId?: number
}

export interface ManagementTaskVO {
  readonly id: number
  readonly taskType: ManagementTaskType
  readonly operation: string
  readonly targetType: string
  readonly targetId: number | null
  readonly targetName: string | null
  readonly batchId: string | null
  readonly isBatch: boolean
  readonly status: ManagementTaskStatus
  readonly stage: string | null
  readonly progress: number | null
  readonly totalCount: number | null
  readonly successCount: number | null
  readonly failureCount: number | null
  readonly cancelledCount: number | null
  readonly errorMessage: string | null
  readonly attempt: number | null
  readonly version: number | null
  readonly createdAt: string
  readonly updatedAt: string
  readonly startedAt: string | null
  readonly completedAt: string | null
}

export interface ManagementTaskItemVO {
  readonly id: number
  readonly taskId: number
  readonly targetType: string
  readonly targetId: number
  readonly operationType: ManagementTaskType
  readonly status: ManagementTaskStatus
  readonly attempt: number | null
  readonly progress: number | null
  readonly resultRefType: string | null
  readonly resultRefId: number | null
  readonly errorMessage: string | null
  readonly version: number | null
  readonly createdAt: string
  readonly updatedAt: string
  readonly startedAt: string | null
  readonly completedAt: string | null
}

export interface ManagementTaskTarget {
  readonly targetType: string
  readonly targetId: number
  readonly operationType?: ManagementTaskType
}

export interface CreateManagementTaskRequest {
  readonly taskType: ManagementTaskType
  readonly operation: string
  readonly targetType?: string
  readonly batchId?: string
  readonly targets?: readonly ManagementTaskTarget[]
}

export interface BatchSelectionIds {
  readonly type: 'IDS'
  readonly ids: readonly number[]
}

export interface BatchSelectionFilter {
  readonly type: 'FILTER'
  readonly query?: ComicListQuery
  readonly excludedIds?: readonly number[]
}

export type BatchSelection = BatchSelectionIds | BatchSelectionFilter

export interface BatchOperationPayload {
  readonly categoryId?: number | null
  readonly addTagIds?: readonly number[]
  readonly title?: string
  readonly author?: string
  readonly description?: string
}

export interface BlockedBatchItem {
  readonly comicId: number
  readonly reasonCode: string
  readonly reason: string
}

export interface BatchPreviewResult {
  readonly operation: ManagementTaskType
  readonly selectedCount: number
  readonly eligibleCount: number
  readonly blocked: readonly BlockedBatchItem[]
  readonly dangerous: boolean
  readonly previewToken: string | null
  readonly expiresAt: string | null
}

export interface BatchSubmitRequest {
  readonly operation: ManagementTaskType
  readonly selection: BatchSelection
  readonly payload?: BatchOperationPayload | null
  readonly previewToken?: string | null
}

export interface BatchCreateResult {
  readonly task: ManagementTaskVO
  readonly selectedCount: number
  readonly eligibleCount: number
  readonly blocked: readonly BlockedBatchItem[]
}
