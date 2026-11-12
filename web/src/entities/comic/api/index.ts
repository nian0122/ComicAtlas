import { request } from '@/shared/api/http'
import type { ComicListVO, ComicDetailVO, ComicListQuery, Term, NodePreview, CoverSelection } from '../model/types'
async function terms(kind: 'categories' | 'tags') {
  const items: Term[] = []
  let page = 1,
    total = 0,
    more = true
  while (more) {
    const result = await request<{ items: Term[]; total: number }>(`/${kind}`, { query: { page, pageSize: 100 } })
    const fresh = result.items.filter((item) => !items.some((existing) => existing.id === item.id))
    items.push(...fresh)
    total = result.total
    more = fresh.length > 0 && items.length < total
    page++
  }
  return { items, total }
}
export const comicApi = {
  coverSelection: (id: string) => request<CoverSelection>(`/comics/${encodeURIComponent(id)}/cover-selection`),
  list: (query: ComicListQuery = {}) =>
    request<{ items: ComicListVO[]; total: number; page: number; pageSize: number }>('/comics', {
      query: { ...query },
    }),
  categories: () => terms('categories'),
  tags: () => terms('tags'),
  detail: (id: string) => request<ComicDetailVO>(`/comics/${encodeURIComponent(id)}`),
  previews: (id: string, nodes: { type: 'catalog' | 'chapter'; id: string }[]) =>
    request<{ items: NodePreview[] }>(`/comics/${encodeURIComponent(id)}/visual-previews`, {
      method: 'POST',
      body: { nodes },
    }),
}
