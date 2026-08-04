import { request } from './http'
import { parseBatchPreview } from './parse'
import type {
  BatchCreateResponse,
  BatchOperationRequest,
  BatchPreviewResponse,
} from '@/types/management/batch'

/**
 * 批量操作 API（T17）——对应后端 BatchOperationController /api/management/batch
 */
export const batchApi = {
  preview: async (payload: BatchOperationRequest): Promise<BatchPreviewResponse> => {
    const raw = await request<unknown>({
      method: 'POST',
      url: '/management/batch/preview',
      data: payload,
    })
    return parseBatchPreview(raw)
  },

  create: async (
    payload: BatchOperationRequest,
    idempotencyKey?: string,
  ): Promise<BatchCreateResponse> => {
    const raw = await request<unknown>({
      method: 'POST',
      url: '/management/batch',
      data: payload,
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
    })
    return raw as BatchCreateResponse
  },
}
