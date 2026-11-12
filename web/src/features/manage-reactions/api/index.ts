import { request } from '@/shared/api/http'
import { reactionKey, type ReactionTarget, type ReactionValue, type SavedReaction } from '@/entities/managed-reaction'
import { reactionChanges } from '../model/changes'
export async function saveReactions(
  targets: readonly ReactionTarget[],
  value: ReactionValue,
  scope: 'active' | 'management',
) {
  const items = reactionChanges(targets, value, scope)
  const result = await request<SavedReaction[]>(
    scope === 'active' ? '/reactions/batch' : '/management/reactions/batch',
    { method: 'PUT', body: { items } },
  )
  const wanted = new Set(items.map(reactionKey)),
    received = new Set(result.map(reactionKey))
  if (
    result.length !== items.length ||
    received.size !== wanted.size ||
    result.some((item) => !wanted.has(reactionKey(item)) || item.value !== value)
  )
    throw new Error('保存响应与所选范围不一致，请刷新后核对实际结果')
  return result
}
