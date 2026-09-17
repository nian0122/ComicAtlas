import { api } from '@/shared/api/http'
import type { CategoryDTO } from '@/entities/category/model/types'

export const categoryApi = {
  list: () => api.get<CategoryDTO[]>('/categories'),
}

export const managementCategoryApi = {
  list: () => api.get<CategoryDTO[]>('/manage/categories'),
  create: (name: string) => api.post<CategoryDTO>('/manage/categories', null, { params: { name } }),
  update: (id: number, name: string) => api.put<CategoryDTO>(`/manage/categories/${id}`, null, { params: { name } }),
  delete: (id: number) => api.delete(`/manage/categories/${id}`),
}
