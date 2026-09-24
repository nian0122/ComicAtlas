import { api } from '@/shared/api/http'
import type { PageResult } from '@/shared/api/types'
import type { CatalogNode, ComicDetailVO, ComicListQuery, ComicListVO } from '@/entities/comic/model/types'

export const comicApi = {
  list: (params?: ComicListQuery) => api.get<PageResult<ComicListVO>>('/comics', { params }),
  detail: (id: number) => api.get<ComicDetailVO>(`/comics/${id}`),
}

export const catalogApi = {
  tree: (comicId: number) => api.get<readonly CatalogNode[]>(`/comics/${comicId}/catalog`),
}
