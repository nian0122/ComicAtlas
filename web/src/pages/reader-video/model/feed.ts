import type { MediaItemInfo } from '@/entities/media'

/** 视频与图片共用同一套播放列表：优先使用自动画质，缺失时退回原文件。 */
export function sourceOf(item: MediaItemInfo): string {
  return item.autoContentUrl || item.contentUrl
}

export function isVideoItem(item: MediaItemInfo | undefined | null): boolean {
  return item?.mediaType === 'video'
}

export function playableItems(items: readonly MediaItemInfo[]): MediaItemInfo[] {
  return items.filter((item) => sourceOf(item) !== '')
}

export function indexOfMedia(items: readonly MediaItemInfo[], mediaId: string | null | undefined): number {
  return mediaId ? items.findIndex((item) => item.id === mediaId) : -1
}

export function formatTime(value: number): string {
  if (!Number.isFinite(value) || value < 0) return '00:00'
  const seconds = Math.floor(value)
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}

export function seekTarget(current: number, delta: number, duration: number): number {
  if (!Number.isFinite(duration) || duration <= 0) return 0
  return Math.min(duration, Math.max(0, current + delta))
}
