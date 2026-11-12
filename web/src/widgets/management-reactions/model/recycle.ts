import type { ReactionType, ReactionValue } from '@/entities/managed-reaction'

export interface RecyclableTarget {
  targetType: ReactionType
  value?: ReactionValue
  hidden: boolean
  canChange: boolean
}

export interface RecyclePreview {
  id: string
  url: string | null
}

export interface RecycleRequest {
  scope: 'ids' | 'filter'
  targetType: ReactionType
  mediaType: '' | 'image' | 'video'
  ids: string[]
  previews: RecyclePreview[]
}

export function canRecycleDislike(item: RecyclableTarget) {
  return item.value === 'dislike' && !item.hidden && item.canChange
}

export function recyclableDislikes<T extends RecyclableTarget>(items: T[]) {
  return items.filter(canRecycleDislike)
}
