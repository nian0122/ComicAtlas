import { request } from '@/shared/api/http'
export interface FavoriteItem {
  targetId: string
  targetType: 'comic'
  comicId: string
  comicTitle: string
  previewUrl: string
  lastReadChapterId: string | null
  lastReadPageNumber: number | null
}
export const favoritesApi = {
  list: (oldest: boolean, page: number, pageSize: number) =>
    request<{ items: FavoriteItem[]; total: number; page: number; pageSize: number }>('/favorites', {
      query: { targetType: 'comic', oldest, page, pageSize },
    }),
}
