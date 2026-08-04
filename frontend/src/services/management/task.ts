import { request } from './http'
import type {
  CreateManagementTaskRequest,
  ManagementTaskListQuery,
  OperationSubmitResult,
} from '@/types/management/task'

/**
 * 管理任务 API（T17）——对应后端 ManagementTaskController /api/management/tasks
 *
 * 列表/条目端点返回 unknown（边界解析在 stores/management/managementTask.ts 进行），
 * 确保未知枚举（如 status=BOGUS）不会伪装成受信任类型。
 */
export const taskApi = {
  list: (params?: ManagementTaskListQuery, signal?: AbortSignal): Promise<unknown> =>
    request<unknown>({ method: 'GET', url: '/management/tasks', params, signal }),

  get: (id: number, signal?: AbortSignal): Promise<unknown> =>
    request<unknown>({ method: 'GET', url: `/management/tasks/${id}`, signal }),

  items: (id: number, signal?: AbortSignal): Promise<unknown> =>
    request<unknown>({ method: 'GET', url: `/management/tasks/${id}/items`, signal }),

  create: (payload: CreateManagementTaskRequest, idempotencyKey?: string) =>
    request<OperationSubmitResult>({
      method: 'POST',
      url: '/management/tasks',
      data: payload,
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
    }),

  cancel: (id: number) =>
    request<OperationSubmitResult>({ method: 'POST', url: `/management/tasks/${id}/cancel` }),

  retry: (id: number) =>
    request<OperationSubmitResult>({ method: 'POST', url: `/management/tasks/${id}/retry` }),
}
