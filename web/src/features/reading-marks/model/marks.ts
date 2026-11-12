import type { ReactionKind, ReactionValue } from '@/entities/reaction'

export interface ReadingMarkTarget {
  kind: ReactionKind
  id: string
  label: string
  title: string
}

export function nextMark(current: ReactionValue, target: Exclude<ReactionValue, 'none'>): ReactionValue {
  return current === target ? 'none' : target
}

export function markKey(kind: ReactionKind, id: string): string {
  return `${kind}:${id}`
}
