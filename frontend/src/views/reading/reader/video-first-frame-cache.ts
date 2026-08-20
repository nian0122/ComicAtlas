const MAX_CACHED_FRAMES = 12
const MAX_FRAME_WIDTH = 720
const WEBP_QUALITY = 0.72

interface CachedFrame {
  objectUrl: string
  lastAccessedAt: number
}

const frameCache = new Map<number, CachedFrame>()
const pendingFrames = new Map<number, Promise<string | null>>()

/**
 * 当前浏览器会话内的视频首帧 LRU。
 * 只保存小尺寸 WebP Blob URL，不写入服务器、Cache Storage 或持久化存储。
 */
export function getCachedVideoFirstFrame(mediaId: number): string | null {
  const cached = frameCache.get(mediaId)
  if (!cached) return null
  cached.lastAccessedAt = Date.now()
  return cached.objectUrl
}

export function cacheVideoFirstFrame(mediaId: number, video: HTMLVideoElement): Promise<string | null> {
  const cached = getCachedVideoFirstFrame(mediaId)
  if (cached) return Promise.resolve(cached)

  const pending = pendingFrames.get(mediaId)
  if (pending) return pending

  const promise = captureFrame(video)
    .then((objectUrl) => {
      if (!objectUrl) return null
      frameCache.set(mediaId, { objectUrl, lastAccessedAt: Date.now() })
      evictOldestFrames()
      return objectUrl
    })
    .finally(() => pendingFrames.delete(mediaId))

  pendingFrames.set(mediaId, promise)
  return promise
}

function captureFrame(video: HTMLVideoElement): Promise<string | null> {
  const sourceWidth = video.videoWidth
  const sourceHeight = video.videoHeight
  if (sourceWidth <= 0 || sourceHeight <= 0) return Promise.resolve(null)

  const scale = Math.min(1, MAX_FRAME_WIDTH / sourceWidth)
  const width = Math.max(1, Math.round(sourceWidth * scale))
  const height = Math.max(1, Math.round(sourceHeight * scale))
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height

  const context = canvas.getContext('2d')
  if (!context) return Promise.resolve(null)
  context.drawImage(video, 0, 0, width, height)

  return new Promise((resolve) => {
    canvas.toBlob(
      (blob) => resolve(blob ? URL.createObjectURL(blob) : null),
      'image/webp',
      WEBP_QUALITY,
    )
  })
}

function evictOldestFrames(): void {
  while (frameCache.size > MAX_CACHED_FRAMES) {
    let oldestMediaId: number | null = null
    let oldestAccessedAt = Number.POSITIVE_INFINITY

    for (const [mediaId, cached] of frameCache) {
      if (cached.lastAccessedAt < oldestAccessedAt) {
        oldestMediaId = mediaId
        oldestAccessedAt = cached.lastAccessedAt
      }
    }

    if (oldestMediaId === null) return
    const removed = frameCache.get(oldestMediaId)
    if (removed) URL.revokeObjectURL(removed.objectUrl)
    frameCache.delete(oldestMediaId)
  }
}
