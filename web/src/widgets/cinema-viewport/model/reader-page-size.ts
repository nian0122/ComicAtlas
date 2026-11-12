import type { MediaItemInfo } from '@/entities/media'

/** 页面高度与内容宽度的比值，用于虚拟列表计算真实滚动高度。 */
export function readerPageHeightRatio(page: MediaItemInfo) {
  if (page.width && page.height) return page.height / page.width
  return page.mediaType === 'video' ? 9 / 16 : 3 / 2
}

/** 页面自身的宽高比，视频占位框和图片占位保持一致。 */
export function readerPageAspectRatio(page: MediaItemInfo) {
  if (page.width && page.height) return `${page.width} / ${page.height}`
  return page.mediaType === 'video' ? '16 / 9' : '2 / 3'
}

/** 虚拟列表单项高度：内容高度加页间留白。 */
export function readerPageHeight(page: MediaItemInfo, width: number, gap: boolean, first: boolean) {
  const content = Math.max(1, Math.round(Math.max(0, width) * readerPageHeightRatio(page)))
  return content + (gap && !first ? 12 : 0)
}
