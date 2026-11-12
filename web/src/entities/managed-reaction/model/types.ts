export type ReactionType = 'comic' | 'chapter' | 'media'
export type ReactionValue = 'like' | 'dislike' | 'none'
export interface SavedReaction {
  targetType: ReactionType
  targetId: string
  value: ReactionValue
  updatedAt: string | null
}
export interface ReactionTarget {
  targetType: ReactionType
  targetId: string
  title: string
  comicTitle?: string
  hidden: boolean
  canChange: boolean
  previewUrl?: string
  value?: ReactionValue
}
export interface ManagedReaction extends SavedReaction {
  value: 'like' | 'dislike'
  comicId: string
  comicTitle: string
  chapterId: string | null
  title: string
  mediaType: 'image' | 'video' | null
  pageNumber: number | null
  lifecycleState: string
  hidden: boolean
  canChange: boolean
  previewUrl?: string
  contentUrl?: string
}
export interface ReactionQuery {
  page: number
  pageSize: number
  targetType?: ReactionType | ''
  value?: 'like' | 'dislike' | ''
  mediaType?: 'image' | 'video' | ''
  includeTrashed: boolean
  oldest: boolean
}
export interface ReactionPage {
  items: ManagedReaction[]
  page: number
  pageSize: number
  total: number
}
export const reactionTypes: Record<ReactionType, string> = { comic: '漫画', chapter: '章节', media: '媒体' }
export function reactionKey(target: { targetType: ReactionType; targetId: string }) {
  return target.targetType + ':' + target.targetId
}
