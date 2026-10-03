import { api } from '@/shared/api/http'

export type FavoriteTarget = 'COMIC' | 'CHAPTER' | 'MEDIA'
export interface FavoriteItem {
  id: number
  targetType: FavoriteTarget
  comicId: number
  comicTitle: string
  chapterId: number | null
  title: string
  pageNumber: number | null
  mediaType: 'IMAGE' | 'VIDEO' | null
  duration: number | null
  coverUrl: string
  previewUrl: string | null
  reactionAt: string | null
  lastReadChapterId: number | null
  lastReadPageNumber: number | null
}
export const favoritesApi = {
  list: (targetType: FavoriteTarget, oldest: boolean, page: number, size: number) =>
    api.get<FavoriteItem[]>('/favorites', { params: { targetType, oldest, page, size } }),
  mark: (item: FavoriteItem, reaction: 'NONE' | 'LIKE') => {
    const resource = { COMIC: 'comics', CHAPTER: 'chapters', MEDIA: 'pages' }[item.targetType]
    return api.put(`/${resource}/${item.id}/reaction`, { reaction })
  },
}
