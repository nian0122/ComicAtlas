import { request } from './http'
import { parseAllowedOperations } from './parse'
import type { AllowedOperations, OperationTarget } from '@/types/management/operation'
import { operationPath } from '@/types/management/operation'

/**
 * 允许操作 API（T17）——对应后端 MediaOperationController /api/management/operations
 */
export const operationApi = {
  allowedOperations: async (target: OperationTarget): Promise<AllowedOperations> => {
    const raw = await request<unknown>({ method: 'GET', url: operationPath(target) })
    return parseAllowedOperations(raw)
  },
}
