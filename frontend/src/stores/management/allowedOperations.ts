import { defineStore } from 'pinia'
import { ref } from 'vue'
import { operationApi } from '@/services/management/operation'
import { ComicLifecycleStatus, OperationName, assertNever } from '@/types/management/enums'
import type {
  ComicLifecycleStatus as ComicLifecycleStatusType,
  OperationName as OperationNameType,
} from '@/types/management/enums'
import type { AllowedOperations, OperationTarget } from '@/types/management/operation'

/**
 * 生命周期 → 操作策略映射（穷举 switch + assertNever）
 *
 * 依据后端 ManagementStateMachine 的合法迁移推导：只有能迁出的状态才允许对应操作，
 * 迁移中/终态（DELETING/TRASHING/RESTORING/PURGING/DELETED）无可用操作。
 */
export function lifecycleToAllowedOperations(
  lifecycle: ComicLifecycleStatusType,
): readonly OperationNameType[] {
  switch (lifecycle) {
    case ComicLifecycleStatus.DRAFT:
      return [OperationName.IMPORT, OperationName.EDIT, OperationName.DELETE, OperationName.METADATA_REFRESH]
    case ComicLifecycleStatus.IMPORTING:
      return [OperationName.EDIT, OperationName.DELETE]
    case ComicLifecycleStatus.IMPORT_FAILED:
      return [
        OperationName.RETRY_IMPORT,
        OperationName.EDIT,
        OperationName.DELETE,
        OperationName.METADATA_REFRESH,
      ]
    case ComicLifecycleStatus.READY:
      return [
        OperationName.EDIT,
        OperationName.LQ_GENERATE,
        OperationName.LQ_REGENERATE,
        OperationName.HQ_DELETE,
        OperationName.TRANSCODE,
        OperationName.METADATA_REFRESH,
        OperationName.DELETE,
        OperationName.RECONCILE,
      ]
    case ComicLifecycleStatus.RECOVERY_REQUIRED:
      return [OperationName.RECOVER, OperationName.RECONCILE, OperationName.DELETE]
    case ComicLifecycleStatus.TRASHED:
      return [OperationName.RECOVER, OperationName.PURGE, OperationName.RECONCILE]
    case ComicLifecycleStatus.DELETING:
    case ComicLifecycleStatus.TRASHING:
    case ComicLifecycleStatus.RESTORING:
    case ComicLifecycleStatus.PURGING:
    case ComicLifecycleStatus.DELETED:
      return []
    default:
      return assertNever(lifecycle)
  }
}

/** 允许操作 Store：按目标缓存 + 查询/判定 */
export const useAllowedOperationsStore = defineStore('allowed-operations', () => {
  const cache = ref<Readonly<Record<string, AllowedOperations>>>({})

  function cacheKey(target: OperationTarget): string {
    return `${target.targetType}:${target.targetId}`
  }

  function fromCache(target: OperationTarget): AllowedOperations | null {
    const entry = cache.value[cacheKey(target)]
    return entry ?? null
  }

  async function fetchAllowed(
    target: OperationTarget,
    force = false,
  ): Promise<AllowedOperations> {
    const cached = fromCache(target)
    if (cached && !force) return cached
    const ops = await operationApi.allowedOperations(target)
    cache.value = { ...cache.value, [cacheKey(target)]: ops }
    return ops
  }

  function can(ops: AllowedOperations, operation: OperationNameType): boolean {
    return ops.allowed.includes(operation)
  }

  function blockedReason(
    ops: AllowedOperations,
    operation: OperationNameType,
  ): string | null {
    const direct = ops.blockedReasons[operation]
    if (direct) return direct
    return ops.blockedReasons['*'] ?? null
  }

  function clear(): void {
    cache.value = {}
  }

  return { cache, fetchAllowed, fromCache, can, blockedReason, clear }
})
