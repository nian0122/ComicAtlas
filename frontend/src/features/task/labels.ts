import type { ManagementTaskStatus, ManagementTaskType } from '@/features/task/types'

export const MANAGEMENT_TASK_TYPES: readonly ManagementTaskType[] = [
  'IMPORT', 'RECOVERY', 'EXPORT', 'DIRECTORY_SCAN', 'LQ_GENERATE', 'LQ_REGENERATE',
  'HQ_DELETE', 'TRANSCODE', 'METADATA_REFRESH', 'METADATA_UPDATE', 'COMIC_DELETE',
  'MEDIA_UPLOAD', 'MEDIA_REPLACE', 'MEDIA_TRASH', 'CHAPTER_TRASH', 'COMIC_RESTORE',
  'CHAPTER_RESTORE', 'MEDIA_RESTORE', 'COMIC_PURGE', 'CHAPTER_PURGE', 'MEDIA_PURGE',
]

const MANAGEMENT_TASK_TYPE_LABELS: Readonly<Record<ManagementTaskType, string>> = {
  IMPORT: '导入漫画', RECOVERY: '恢复任务', EXPORT: '导出', DIRECTORY_SCAN: '目录扫描',
  LQ_GENERATE: '生成 LQ', LQ_REGENERATE: '重新生成 LQ', HQ_DELETE: '删除 HQ', TRANSCODE: '视频转码',
  METADATA_REFRESH: '刷新元数据', METADATA_UPDATE: '更新元数据', COMIC_DELETE: '回收漫画',
  MEDIA_UPLOAD: '媒体上传', MEDIA_REPLACE: '替换媒体', MEDIA_TRASH: '回收媒体', CHAPTER_TRASH: '回收章节',
  COMIC_RESTORE: '恢复漫画', CHAPTER_RESTORE: '恢复章节', MEDIA_RESTORE: '恢复媒体',
  COMIC_PURGE: '永久清理漫画', CHAPTER_PURGE: '永久清理章节', MEDIA_PURGE: '永久清理媒体',
}

export function managementTaskTypeLabel(type: ManagementTaskType): string {
  return MANAGEMENT_TASK_TYPE_LABELS[type]
}

const MANAGEMENT_TASK_STATUS_LABELS: Readonly<Record<ManagementTaskStatus, string>> = {
  QUEUED: '排队中', RUNNING: '执行中', CANCELLING: '取消中', CANCELLED: '已取消',
  SUCCEEDED: '成功', PARTIALLY_SUCCEEDED: '部分成功', FAILED: '失败',
}

export function managementTaskStatusLabel(status: ManagementTaskStatus): string {
  return MANAGEMENT_TASK_STATUS_LABELS[status]
}
