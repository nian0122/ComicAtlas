import type { MediaItemInfo } from '@/entities/media'

export type ReaderImageQuality = 'lq' | 'hq'

/** 可用轻量副本地址；缺失或失效时返回 null，由调用方回落到原件。 */
export function lowQualityImageUrl(media: MediaItemInfo): string | null {
  if (media.mediaType !== 'image' || !media.lq?.available || !media.lq.contentUrl) return null
  return media.lq.contentUrl
}

/** 可读原件地址；已释放或未登记原件时返回 null。 */
export function highQualityImageUrl(media: MediaItemInfo): string | null {
  if (media.mediaType !== 'image' || media.hqState !== 'available' || !media.contentUrl) return null
  return media.contentUrl
}

/**
 * 单页取图：轻量优先默认走 LQ，指定单页高清或高清优先走 HQ；
 * 目标画质不可用时回落到另一种副本，两者都不可用才使用原件地址暴露失败。
 */
export function readerPageSource(
  media: MediaItemInfo,
  quality: ReaderImageQuality,
  highQualityRequested = false,
): string {
  if (media.mediaType === 'video') return media.autoContentUrl
  const low = lowQualityImageUrl(media)
  const high = highQualityImageUrl(media)
  if (quality === 'hq' || highQualityRequested) return high ?? low ?? media.contentUrl
  return low ?? high ?? media.contentUrl
}

/** 只有同时存在 LQ 和 HQ 时，单页双击升级才有意义。 */
export function canUpgradeImage(media: MediaItemInfo): boolean {
  return lowQualityImageUrl(media) !== null && highQualityImageUrl(media) !== null
}
