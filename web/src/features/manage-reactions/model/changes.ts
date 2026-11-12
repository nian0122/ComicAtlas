import { reactionKey, type ReactionTarget, type ReactionValue } from '@/entities/managed-reaction'
export function reactionChanges(
  targets: readonly ReactionTarget[],
  value: ReactionValue,
  scope: 'active' | 'management',
) {
  if (!['like', 'dislike', 'none'].includes(value) || !targets.length || targets.length > 100)
    throw new Error('请选择 1 至 100 个对象并指定标记操作')
  const keys = new Set<string>()
  return targets.map((target) => {
    if (!target.canChange) throw new Error('选集中包含状态正在变更的对象，请刷新后重新核对')
    if (scope === 'active' && target.hidden) throw new Error('活动内容入口不能修改已回收对象，请到媒体标记查看')
    const key = reactionKey(target)
    if (keys.has(key)) throw new Error('同一对象不能重复选择')
    keys.add(key)
    return { targetType: target.targetType, targetId: target.targetId, value }
  })
}
