import type { MediaItemInfo } from '@/types'

const VIDEO_EXTENSIONS = new Set(['.mp4', '.m4v', '.webm', '.mov', '.mkv', '.avi'])

export function isVideoMedia(item: Pick<MediaItemInfo, 'mediaType' | 'hqUrl'>): boolean {
  if (item.mediaType === 'VIDEO') return true
  // HQ 被删除后 hqUrl 合法为空，此时不能因为推断媒体类型抛出异常，
  // 图片仍应继续由 ProgressiveImage 使用 lqUrl 渲染。
  const path = (item.hqUrl || '').split(/[?#]/, 1)[0].toLowerCase()
  const extension = path.slice(path.lastIndexOf('.'))
  return VIDEO_EXTENSIONS.has(extension)
}
