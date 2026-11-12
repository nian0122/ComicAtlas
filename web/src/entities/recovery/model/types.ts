interface RecoverySummary {
  total: number
  processed: number
  restored: number
  placeholders: number
  skipped: number
  empty: number
  failed: number
}
export interface RecoveryItem {
  comicId: string
  status: 'restored' | 'placeholder' | 'skipped' | 'empty' | 'error'
  attempt: number
  tryCount: number
  errorCode: string | null
  updatedAt: string
}
export interface RecoveryResults {
  taskId: string
  sealed: boolean
  candidateCount: number
  ignoredCount: number
  summary: RecoverySummary | null
  items: RecoveryItem[]
  nextAfter: string | null
}
export interface RecoveryPlaceholder {
  comicId: string
  taskId: string | null
  reason: string
  createdAt: string
  presence: { media: number; other: number; directories: number }
}
export interface RepairVersions {
  version: number
  structureVersion: number
}
export interface RecoveryRepairResult {
  taskId: string
  comicId: string
  expectedVersion: number
  expectedStructureVersion: number
  completed: boolean
  completedAt: string | null
  version: number | null
  structureVersion: number | null
  preservedMetadata: boolean | null
}
export const recoveryLabels: Record<RecoveryItem['status'], string> = {
  restored: '结构已恢复',
  placeholder: '待补全占位',
  skipped: '已有记录，跳过',
  empty: '空目录壳',
  error: '恢复失败',
}
export const recoveryErrors: Record<string, string> = {
  RECOVERY_CONFLICT: '资料或身份约束冲突',
  RECOVERY_METADATA_INVALID: '元数据无效',
  RECOVERY_SOURCE_MISSING: '来源文件缺失',
  RECOVERY_SOURCE_CHANGED: '来源读取期间变化',
  RECOVERY_CONTENT_MISMATCH: '文件与登记内容不符',
  RECOVERY_TOOL_UNAVAILABLE: '所需工具不可用',
  RECOVERY_SOURCE_UNSAFE: '来源不安全',
  RECOVERY_SOURCE_LIMIT: '来源超过处理预算',
}
