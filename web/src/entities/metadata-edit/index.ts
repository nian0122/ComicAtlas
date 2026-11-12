import { request } from '@/shared/api/http'
import type { MetadataPreview, MetadataItem } from './model/types'
export type {
  MetadataFields,
  MetadataPatch,
  MetadataFilter,
  MetadataSelection,
  MetadataRequest,
  MetadataPreview,
  MetadataItem,
} from './model/types'
export const metadataApi = {
  preview: async (id: string) => {
    const value = await request<MetadataPreview>(`/batch-previews/${encodeURIComponent(id)}`)
    if (value.id !== id || value.operation !== 'metadata_update') throw new Error('此预览不是批量资料编辑')
    return value
  },
  items: (id: string, page: number) =>
    request<{ items: MetadataItem[]; total: number }>(`/batch-previews/${encodeURIComponent(id)}/items`, {
      query: { page, pageSize: 20 },
    }),
}
