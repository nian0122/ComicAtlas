export type TaskStatus = 'queued' | 'running' | 'cancelling' | 'cancelled' | 'succeeded' | 'failed'
export const taskTypes = [
  'directory_import',
  'archive_import',
  'image_lq',
  'storage_refresh',
  'hq_release',
  'directory_scan',
  'archive_export',
  'video_transcode',
  'media_upload',
  'metadata_refresh',
  'lifecycle',
  'lifecycle_audit',
  'metadata_sync',
  'metadata_batch',
  'command_batch',
  'directory_move',
  'directory_preflight',
  'disk_recovery',
  'recovery_repair',
  'ai_analysis',
  'import_batch',
] as const
export type TaskType = (typeof taskTypes)[number]
export interface TaskFilters {
  type?: TaskType | ''
  keyword?: string
  oldest?: boolean
}
export interface Task {
  id: string
  type: TaskType
  title: string
  comicId: string | null
  targetType: string | null
  targetId: string | null
  status: TaskStatus
  stage: string
  attempt: number
  totalCount: number | null
  completedCount: number
  canCancel: boolean
  canRetry: boolean
  cancellationReason: string | null
  errorCode: string | null
  failure: { message: string; action: string } | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}
export interface TaskPage {
  items: Task[]
  page: number
  pageSize: number
  total: number
}
export interface TaskSummary {
  total: number
  queued: number
  running: number
  cancelling: number
  cancelled: number
  succeeded: number
  failed: number
}
export interface TaskEvent {
  id: number
  type: string
  status: string
  stage: string
  attempt: number
  totalCount: number | null
  completedCount: number
  errorCode: string | null
  occurredAt: string
}
export interface TaskEventPage {
  taskId: string
  items: TaskEvent[]
  nextAfter: number | null
}
/** 各任务类型的逐项报告由各自业务模块定义，保留接口返回的完整字段。 */
export type TaskItem = Record<string, unknown>

export interface TaskArtifacts {
  taskId: string
  status: TaskStatus
  published: boolean
  directory: string | null
  items: { fileName: string; size: number; sha256: string; lastSegment: boolean; path?: string }[]
}
