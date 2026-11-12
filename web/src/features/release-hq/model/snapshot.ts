import type { HqReleasePreview } from '@/entities/hq-release'
export function sameHqSnapshot(a: HqReleasePreview, b: HqReleasePreview) {
  return (
    ['scopeType', 'scopeId', 'allowed', 'images', 'available', 'released', 'originalBytes', 'truncated'].every(
      (key) => a[key as keyof HqReleasePreview] === b[key as keyof HqReleasePreview],
    ) && a.blockers.slice().sort().join(',') === b.blockers.slice().sort().join(',')
  )
}
