import type { RepairVersions, RecoveryPlaceholder } from '@/entities/recovery'
export function repairBody(detail: RepairVersions) {
  if (
    !Number.isSafeInteger(detail.version) ||
    detail.version < 1 ||
    !Number.isSafeInteger(detail.structureVersion) ||
    detail.structureVersion < 1
  )
    throw new Error('当前资料版本无效，不能提交占位补全')
  return { version: detail.version, structureVersion: detail.structureVersion }
}
export function repairBinding(detail: RepairVersions, marker: RecoveryPlaceholder) {
  return JSON.stringify([marker.comicId, marker.createdAt, marker.taskId, detail.version, detail.structureVersion])
}
