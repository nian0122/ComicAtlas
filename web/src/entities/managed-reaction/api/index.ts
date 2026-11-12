import { request } from '@/shared/api/http'
import type { ReactionQuery, ReactionPage } from '../model/types'
export const managedReactionApi = {
  list: (query: ReactionQuery, signal?: AbortSignal) =>
    request<ReactionPage>('/management/reactions', { query: { ...query }, signal }),
}
