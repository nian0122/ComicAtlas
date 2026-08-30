import type { ComicStatus } from '@/entities/comic/types'

export type ComicStatusMeta = {
  readonly label: string
  readonly description: string
  readonly tone: 'success' | 'warning' | 'danger' | 'info'
  readonly transient: boolean
}

export const COMIC_STATUSES = [
  'DRAFT', 'IMPORTING', 'IMPORT_FAILED', 'READY', 'RECOVERY_REQUIRED', 'REFRESHING',
  'DELETING', 'TRASHING', 'TRASHED', 'RESTORING', 'PURGING', 'DELETED',
] as const satisfies readonly ComicStatus[]

export const COMIC_STATUS_META: Readonly<Record<ComicStatus, ComicStatusMeta>> = {
  DRAFT: { label: '草稿', description: '记录已创建，内容尚未就绪', tone: 'info', transient: false },
  IMPORTING: { label: '导入中', description: 'Worker 正在处理文件或等待导入最终化', tone: 'warning', transient: true },
  IMPORT_FAILED: { label: '导入失败', description: '导入未完成，需要查看任务错误后重试', tone: 'danger', transient: false },
  READY: { label: '可阅读', description: '漫画与章节均已就绪', tone: 'success', transient: false },
  RECOVERY_REQUIRED: { label: '需要恢复', description: '存储存在，但数据库记录需要恢复', tone: 'danger', transient: false },
  REFRESHING: { label: '刷新元数据中', description: '正在重新扫描并合并元数据', tone: 'warning', transient: true },
  DELETING: { label: '删除排队中', description: '删除任务已入队，Worker 尚未开始', tone: 'warning', transient: true },
  TRASHING: { label: '回收中', description: '文件正在移入回收站', tone: 'warning', transient: true },
  TRASHED: { label: '已回收', description: '已软删除，可在保留期内恢复', tone: 'info', transient: false },
  RESTORING: { label: '恢复中', description: '文件正在从回收站恢复', tone: 'warning', transient: true },
  PURGING: { label: '永久清理中', description: '文件正在永久删除，此操作不可恢复', tone: 'danger', transient: true },
  DELETED: { label: '已永久删除', description: '生命周期已结束，内容不可恢复', tone: 'danger', transient: false },
}

export function comicStatusMeta(status: ComicStatus): ComicStatusMeta {
  return COMIC_STATUS_META[status]
}
