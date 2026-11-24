import type { DirectoryTarget } from '@/entities/directory-move'
export function directoryIds(targets: readonly DirectoryTarget[]) {
  const ids = targets.map((x) => x.id)
  if (!ids.length || ids.length > 10000 || ids.some((x) => !x.trim()) || new Set(ids).size !== ids.length)
    throw new Error('请选择 1 至 10000 部明确且不重复的漫画')
  return ids
}
