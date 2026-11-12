import { request } from '@/shared/api/http'

export type ReactionValue = 'like' | 'dislike' | 'none'
export type ReactionKind = 'comic' | 'chapter' | 'media'

export interface Reaction {
  targetType: ReactionKind
  targetId: string
  value: ReactionValue
  updatedAt: string | null
}

const resources: Record<ReactionKind, string> = {
  comic: 'comics',
  chapter: 'chapters',
  media: 'media',
}

export const reactionApi = {
  get: (kind: ReactionKind, id: string) => request<Reaction>(`/${resources[kind]}/${encodeURIComponent(id)}/reaction`),
  set: (kind: ReactionKind, id: string, value: ReactionValue) =>
    request<Reaction>(`/${resources[kind]}/${encodeURIComponent(id)}/reaction`, {
      method: 'PUT',
      body: { value },
    }),
}
