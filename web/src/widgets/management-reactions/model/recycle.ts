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

export interface RecycleGroup {
  targetType: ReactionType
  scope: 'ids' | 'filter'
  ids: string[]
  mediaType: '' | 'image' | 'video'
  previews: RecyclePreview[]
}

export interface RecycleRequest {
  groups: RecycleGroup[]
}

export function canRecycleDislike(item: RecyclableTarget) {
  return item.value === 'dislike' && !item.hidden && item.canChange
}

export function recyclableDislikes<T extends RecyclableTarget>(items: T[]) {
  return items.filter(canRecycleDislike)
}
