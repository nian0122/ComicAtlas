import type { DirectoryPreview, DirectoryTarget } from '@/entities/directory-move'
export function directoryIds(targets: readonly DirectoryTarget[]) {
  const ids = targets.map((x) => x.id)
  if (!ids.length || ids.length > 10000 || ids.some((x) => !x.trim()) || new Set(ids).size !== ids.length)
    throw new Error('请选择 1 至 10000 部明确且不重复的漫画')
  return ids
}
export function confirmable(preview: DirectoryPreview | null, token: string, now: number) {
  return (
    !!preview &&
    preview.status === 'succeeded' &&
    preview.ready &&
    !preview.moveTaskId &&
    !!token &&
    typeof preview.fileCount === 'number' &&
    typeof preview.bytes === 'number' &&
    !!preview.expiresAt &&
    Date.parse(preview.expiresAt) > now
  )
}
