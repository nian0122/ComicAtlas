import { api } from '@/shared/api/http'
import type { TagCreateDTO, TagDTO } from '@/entities/tag/model/types'

export const tagApi = {
  list: () => api.get<TagDTO[]>('/tags'),
}

export const managementTagApi = {
  list: () => api.get<TagDTO[]>('/manage/tags'),
  create: (data: TagCreateDTO) => api.post<TagDTO>('/manage/tags', data),
  delete: (id: number) => api.delete(`/manage/tags/${id}`),
}
