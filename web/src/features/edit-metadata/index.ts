import { request } from '@/shared/api/http'
import type { MetadataFields, MetadataPreview, MetadataRequest } from '@/entities/metadata-edit'
import type { Task } from '@/entities/task'
import { atomicBody, validateMetadataRequest } from './model/validation'
export { atomicBody, metadataFieldIssues, validateMetadataRequest, metadataBinding } from './model/validation'
export const metadataCommands = {
  atomic: async (items: MetadataFields[]) => {
    const values = await request<{ id: string; version: number }[]>('/comics/metadata/batch', {
      method: 'PUT',
      body: atomicBody(items),
    })
    if (
      !Array.isArray(values) ||
      values.length !== items.length ||
      new Set(values.map((value) => value.id)).size !== items.length ||
      values.some(
        (value) =>
          !items.some((item) => item.id === value.id) || !Number.isSafeInteger(value.version) || value.version < 1,
      )
    )
      throw new Error('保存响应与提交范围不一致，请重新读取核对')
    return values
  },
  preview: async (body: MetadataRequest) => {
    const value = await request<MetadataPreview>('/batch-previews', {
      method: 'POST',
      body: validateMetadataRequest(body),
    })
    if (value.operation !== 'metadata_update') throw new Error('后端未返回资料编辑预览')
    return value
  },
  confirm: (id: string, key: string) =>
    request<Task>(`/batch-previews/${encodeURIComponent(id)}/confirm`, {
      method: 'POST',
      body: { confirm: true },
      idempotencyKey: key,
    }),
  action: (id: string, action: 'retry' | 'cancel') =>
    request<Task>(`/tasks/${encodeURIComponent(id)}/${action}`, { method: 'POST', body: {} }),
}
