export interface AuditSummary {
  taskId: string
  entryId: string
  ownerTaskId: string
  operation: string
  entryState: string
  ownerState: string
  totalFiles: number
  checkedFiles: number
  issueFiles: number
  entryIssues: number
  complete: boolean
  consistent: boolean
  repairAvailable: boolean
  superseded: boolean
  observedAt: string | null
}
interface FileObservation {
  position: number
  entryId: string
  role: string
  fileName: string
  sourceState: string
  retainedState: string
  sameFile: boolean
  prepared: boolean
  initiallyMissing: boolean
  journalState: string
  journalPhase: string
  consistent: boolean
  resumable: boolean
  errorCode?: string
}
export interface AuditPage {
  items: FileObservation[]
  taskId: string
  complete: boolean
  page: number
  pageSize: number
  total: number
}
interface FileResult {
  position: number
  entryId: string
  role: string
  fileName: string
  operation: string
  phase: string
  state: string
  errorCode?: string
  observedAt: string
}
export interface FileResultPage {
  items: FileResult[]
  taskId: string
  taskStatus: string
  taskAttempt: number
  attempt: number
  operation: string
  recorded: boolean
  complete: boolean
  totalFiles: number | null
  completedFiles: number
  failedFiles: number
  page: number
  pageSize: number
  total: number
}
export const entryStates: Record<string, string> = {
  trashing: '回收未收尾',
  trashed: '已回收',
  restoring: '恢复未收尾',
  restored: '已恢复',
  purging: '清理未收尾',
  purged: '已清理',
  detached: '已移出管理',
}
export const lifecycleOperations: Record<string, string> = {
  trash: '回收',
  restore: '恢复',
  purge: '永久清理',
  import: '导入历史',
}
export const fileFacts: Record<string, string> = {
  absent: '不存在',
  valid: '内容匹配',
  changed: '内容已变化',
  unmeasured: '未记录原始摘要',
  present: '存在（未核对内容）',
  unsafe: '路径不安全',
  unreadable: '无法读取',
  applied: '已达目标状态',
  initially_missing: '确认原本缺失',
  failed: '处理失败',
  missing: '缺失',
  invalid: '无效',
  mismatch: '不匹配',
  prepared: '已准备',
  preparing: '准备清单',
  applying: '处理文件',
  verifying: '完成前核验',
  trashed: '已回收',
  restored: '已恢复',
  purged: '已清理',
  restoring: '恢复中',
  purging: '清理中',
}
export const fileRoles: Record<string, string> = {
  hq: '原件',
  lq: '轻量副本',
  thumbnails: '缩略图',
  metadata: '资料文件',
  retired: '旧版本文件',
}
export const auditIssues: Record<string, string> = {
  OPERATION_PENDING: '原操作尚未完成',
  FILE_LOCATION_CONFLICT: '文件位置冲突',
  FILE_MISSING: '登记文件后来缺失',
  FILE_CHANGED: '文件内容已变化',
  FILE_UNSAFE: '文件路径不安全',
  FILE_UNREADABLE: '文件无法读取',
  JOURNAL_MISSING: '持久清单缺失',
  JOURNAL_INVALID: '持久清单无效',
  JOURNAL_MISMATCH: '持久清单不匹配',
  FILE_ACCESS_DENIED: '无权访问文件',
  FILE_IO_FAILED: '文件读写失败',
}
