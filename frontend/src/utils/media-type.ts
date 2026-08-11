import type { MediaItemInfo } from '@/types'

const VIDEO_EXTENSIONS = new Set(['.mp4', '.m4v', '.webm', '.mov', '.mkv', '.avi'])

export function isVideoMedia(item: Pick<MediaItemInfo, 'mediaType' | 'hqUrl'>): boolean {
  if (item.mediaType === 'VIDEO') return true
  const path = item.hqUrl.split(/[?#]/, 1)[0].toLowerCase()
  const extension = path.slice(path.lastIndexOf('.'))
  return VIDEO_EXTENSIONS.has(extension)
}
