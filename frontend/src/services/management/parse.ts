import {
  asBoolean,
  asNumber,
  asRecord,
  asString,
  asStringArray,
  asStringMap,
  isManagementTaskStatus,
  isOperationName,
  isTargetType,
  isTaskStage,
  isTaskType,
  parseEnum,
} from '@/types/management/enums'
import type {
  ManagementTaskEntry,
  ManagementTaskItemEntry,
  PaginatedPage,
} from '@/types/management/task'
import type { AllowedOperations } from '@/types/management/operation'
import type { BlockedBatchItem, BatchPreviewResponse } from '@/types/management/batch'
import type { OperationName } from '@/types/management/enums'

/**
 * 管理领域边界解析（T17）：把未知 wire 值解析成规范化强类型模型。
 * 所有枚举字段经 parseEnum 得到 known|unknown，未知值不崩溃、保留原始值。
 */

function parseTask(raw: unknown): ManagementTaskEntry {
  const rec = asRecord(raw)
  if (!rec) throw new Error('任务数据格式无效')
  return {
    id: asNumber(rec.id),
    taskType: parseEnum(rec.taskType, isTaskType),
    operation: parseEnum(rec.operation, isOperationName),
    targetType: parseEnum(rec.targetType, isTargetType),
    batchId: asString(rec.batchId),
    isBatch: asBoolean(rec.isBatch),
    status: parseEnum(rec.status, isManagementTaskStatus),
    stage: rec.stage == null ? null : parseEnum(rec.stage, isTaskStage),
    progress: asNumber(rec.progress),
    totalCount: asNumber(rec.totalCount),
    successCount: asNumber(rec.successCount),
    failureCount: asNumber(rec.failureCount),
    cancelledCount: asNumber(rec.cancelledCount),
    errorMessage: asString(rec.errorMessage),
    attempt: asNumber(rec.attempt),
    version: asNumber(rec.version),
    createdAt: asString(rec.createdAt),
    updatedAt: asString(rec.updatedAt),
    startedAt: asString(rec.startedAt),
    completedAt: asString(rec.completedAt),
  }
}

function parseTaskItem(raw: unknown): ManagementTaskItemEntry {
  const rec = asRecord(raw)
  if (!rec) throw new Error('任务条目数据格式无效')
  return {
    id: asNumber(rec.id),
    taskId: asNumber(rec.taskId),
    targetType: parseEnum(rec.targetType, isTargetType),
    targetId: asNumber(rec.targetId),
    operationType: parseEnum(rec.operationType, isTaskType),
    status: parseEnum(rec.status, isManagementTaskStatus),
    attempt: asNumber(rec.attempt),
    progress: asNumber(rec.progress),
    resultRefType: asString(rec.resultRefType),
    resultRefId: asNumber(rec.resultRefId),
    errorMessage: asString(rec.errorMessage),
    version: asNumber(rec.version),
    createdAt: asString(rec.createdAt),
    updatedAt: asString(rec.updatedAt),
    startedAt: asString(rec.startedAt),
    completedAt: asString(rec.completedAt),
  }
}

/** 任务列表分页解析：records 逐条规范化，结构损坏的条目丢弃 */
export function parseTaskListPage(raw: unknown): PaginatedPage<ManagementTaskEntry> {
  const rec = asRecord(raw)
  if (!rec) throw new Error('任务列表数据格式无效')
  const recordsRaw = Array.isArray(rec.records) ? rec.records : []
  const records = recordsRaw
    .map(parseTask)
    .filter((t): t is ManagementTaskEntry => t.id > 0)
  return {
    records,
    total: asNumber(rec.total),
    size: asNumber(rec.size),
    current: asNumber(rec.current),
    pages: asNumber(rec.pages),
  }
}

/** 任务条目列表解析 */
export function parseTaskItemList(raw: unknown): readonly ManagementTaskItemEntry[] {
  if (!Array.isArray(raw)) return []
  return raw.map(parseTaskItem).filter((t): t is ManagementTaskItemEntry => t.id > 0)
}

/** AllowedOperations 解析：allowed 过滤为已知操作名，blockedReasons 转 string 映射 */
export function parseAllowedOperations(raw: unknown): AllowedOperations {
  const rec = asRecord(raw)
  if (!rec) {
    return { allowed: [], blockedReasons: {} }
  }
  const allowed = asStringArray(rec.allowed).filter(
    (op): op is OperationName => isOperationName(op),
  )
  return { allowed, blockedReasons: asStringMap(rec.blockedReasons) }
}

function parseBlockedItem(raw: unknown): BlockedBatchItem | null {
  const rec = asRecord(raw)
  if (!rec || typeof rec.reasonCode !== 'string') return null
  return {
    comicId: asNumber(rec.comicId),
    reasonCode: rec.reasonCode as BlockedBatchItem['reasonCode'],
    reason: asString(rec.reason),
  }
}

/** 批量预览解析 */
export function parseBatchPreview(raw: unknown): BatchPreviewResponse {
  const rec = asRecord(raw)
  if (!rec) throw new Error('批量预览数据格式无效')
  const blockedRaw = Array.isArray(rec.blocked) ? rec.blocked : []
  const blocked = blockedRaw
    .map(parseBlockedItem)
    .filter((b): b is BlockedBatchItem => b !== null)
  return {
    operation: parseEnum(rec.operation, isTaskType),
    selectedCount: asNumber(rec.selectedCount),
    eligibleCount: asNumber(rec.eligibleCount),
    blocked,
    dangerous: asBoolean(rec.dangerous),
    previewToken: asString(rec.previewToken),
    expiresAt: asString(rec.expiresAt),
  }
}
