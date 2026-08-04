import { asNumber, asRecord, asString } from './enums'
import type { MediaType } from '@/types'

/**
 * 媒体管理领域（Task 19）——对应章节媒体列表 / 上传 / 重排 / 回收站。
 * 永不展示客户端绝对路径：文件名一律经 displayFileName 取 basename，
 * 错误文案经 sanitizeErrorMessage 剥离盘符路径。
 */

/** 管理视角的媒体项（在 reader 章节接口返回的 MediaItemInfo 之上补齐管理字段） */
export interface ManagementMediaItem {
  readonly id: number
  readonly pageNumber: number
  readonly name: string
  readonly mediaType: MediaType
  readonly width: number
  readonly height: number
  readonly hqUrl: string
  readonly lqUrl: string
  readonly lqStatus: string
  readonly hqStatus: string
  readonly lifecycle: string
  readonly transcodeStatus: string
  readonly duration?: number
  readonly container?: string
  readonly videoCodec?: string
  readonly audioCodec?: string
}

/** 章节下拉选项 */
export interface MediaChapterOption {
  readonly chapterId: number
  readonly chapterNo: string
  readonly title: string
  readonly pageCount: number
}

/** 章节媒体载荷（GET /chapters/{id}） */
export interface MediaChapterPayload {
  readonly comicId: number
  readonly chapterId: number
  readonly chapterTitle: string
  readonly total: number
  readonly pages: readonly ManagementMediaItem[]
}

/** 上传队列条目状态 */
export type UploadFileStatus =
  | 'queued'
  | 'uploading'
  | 'paused'
  | 'completed'
  | 'failed'
  | 'cancelled'

/** 上传队列条目 */
export interface UploadQueueEntry {
  readonly index: number
  readonly fileId: string
  readonly file: File
  /** 展示名：始终为 basename，绝不包含客户端路径 */
  readonly name: string
  readonly size: number
  readonly mediaType: MediaType
  readonly contentType: string
  status: UploadFileStatus
  receivedBytes: number
  error: string | null
}

// ======================== 路径脱敏 ========================

/** 匹配盘符绝对路径（D:\... / C:/...）或深路径片段 */
const PATH_PATTERN = /(?:[A-Za-z]:[\\/][^\s,;:"]*)|(?:\.[\\/][^\s,;:"]*)/g

/** 文件名只取 basename；客户端绝对路径绝不进入界面 */
export function displayFileName(pathLike: string): string {
  const normalized = pathLike.replace(/\\/g, '/')
  const lastSlash = normalized.lastIndexOf('/')
  const name = lastSlash >= 0 ? normalized.slice(lastSlash + 1) : normalized
  return name.trim() === '' ? '未命名文件' : name
}

/** 错误文案脱敏：剥离盘符绝对路径，避免泄露宿主机布局 */
export function sanitizeErrorMessage(message: string): string {
  const cleaned = message.replace(PATH_PATTERN, '（路径已隐藏）')
  const collapsed = cleaned.replace(/\s{2,}/g, ' ').trim()
  return collapsed === '' ? '发生未知错误' : collapsed
}

// ======================== 边界解析（parse, don't validate） ========================

const VIDEO_EXTENSIONS = new Set(['mp4', 'webm', 'mkv', 'mov', 'm4v', 'avi', 'ts'])

/** 由文件名推断媒体类型（缺失时默认 IMAGE） */
export function detectMediaType(name: string, contentType = ''): MediaType {
  if (contentType.startsWith('video/')) return 'VIDEO'
  const ext = name.toLowerCase().split('.').pop() ?? ''
  return VIDEO_EXTENSIONS.has(ext) ? 'VIDEO' : 'IMAGE'
}

function parseMediaItem(raw: unknown): ManagementMediaItem | null {
  const rec = asRecord(raw)
  if (!rec) return null
  const id = asNumber(rec.id)
  if (id <= 0) return null
  const hqUrl = asString(rec.hqUrl)
  const mediaTypeRaw = asString(rec.mediaType)
  const mediaType: MediaType = mediaTypeRaw === 'VIDEO' ? 'VIDEO' : 'IMAGE'
  const name = asString(rec.name) !== '' ? asString(rec.name) : hqUrl.split('/').pop() ?? ''
  const lqUrl = asString(rec.lqUrl)
  return {
    id,
    pageNumber: asNumber(rec.pageNumber),
    name: displayFileName(name),
    mediaType,
    width: asNumber(rec.width),
    height: asNumber(rec.height),
    hqUrl,
    lqUrl,
    lqStatus: asString(rec.lqStatus) !== '' ? asString(rec.lqStatus) : mediaType === 'VIDEO' ? 'NOT_GENERATED' : 'READY',
    hqStatus: asString(rec.hqStatus) !== '' ? asString(rec.hqStatus) : hqUrl !== '' ? 'READY' : 'MISSING',
    lifecycle: asString(rec.lifecycle) !== '' ? asString(rec.lifecycle) : 'READY',
    transcodeStatus: asString(rec.transcodeStatus) !== '' ? asString(rec.transcodeStatus) : 'NOT_NEEDED',
    duration: typeof rec.duration === 'number' ? rec.duration : undefined,
    container: typeof rec.container === 'string' ? rec.container : undefined,
    videoCodec: typeof rec.videoCodec === 'string' ? rec.videoCodec : undefined,
    audioCodec: typeof rec.audioCodec === 'string' ? rec.audioCodec : undefined,
  }
}

/** 解析 GET /chapters/{id} 载荷 */
export function parseChapterPayload(raw: unknown): MediaChapterPayload {
  const rec = asRecord(raw) ?? {}
  const pagesRaw = Array.isArray(rec.pages) ? rec.pages : []
  const pages = pagesRaw
    .map(parseMediaItem)
    .filter((item): item is ManagementMediaItem => item !== null)
  return {
    comicId: asNumber(rec.comicId),
    chapterId: asNumber(rec.chapterId),
    chapterTitle: asString(rec.chapterTitle) || asString(rec.chapterTitle) || '未知章节',
    total: asNumber(rec.total),
    pages,
  }
}

/** 解析 GET /comics/{id} 详情中的章节列表为下拉选项 */
export function parseChapterOptions(raw: unknown): readonly MediaChapterOption[] {
  const rec = asRecord(raw)
  if (!rec) return []
  const chaptersRaw = Array.isArray(rec.chapters) ? rec.chapters : []
  const options: MediaChapterOption[] = []
  for (const chapterRaw of chaptersRaw) {
    const chapter = asRecord(chapterRaw)
    if (!chapter) continue
    const chapterId = asNumber(chapter.id)
    if (chapterId <= 0) continue
    options.push({
      chapterId,
      chapterNo: asString(chapter.chapterNo),
      title: asString(chapter.title),
      pageCount: asNumber(chapter.pageCount),
    })
  }
  return options
}

/** 章节下拉标签（编号 + 标题） */
export function chapterOptionLabel(option: MediaChapterOption): string {
  const numberPart = option.chapterNo !== '' ? `${option.chapterNo} ` : ''
  return `${numberPart}${option.title}`
}

/** 媒体类型中文标签 */
export function mediaTypeLabel(mediaType: MediaType): string {
  return mediaType === 'VIDEO' ? '视频' : '图片'
}

/** 尺寸标签（宽高未知时返回占位） */
export function dimensionLabel(item: ManagementMediaItem): string {
  if (item.width > 0 && item.height > 0) {
    return `${item.width}×${item.height}`
  }
  return '未知尺寸'
}

/** 视频时长 mm:ss */
export function formatDuration(seconds: number | undefined): string {
  if (typeof seconds !== 'number' || !Number.isFinite(seconds)) return ''
  const total = Math.floor(seconds)
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/** 字节大小 */
export function formatBytes(bytes: number): string {
  if (!bytes || bytes < 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i += 1
  }
  return `${size.toFixed(i > 0 ? 1 : 0)} ${units[i]}`
}

// ======================== 状态文案（与颜色同时存在，双通道） ========================

/** HQ 状态 → 中文标签 */
export function hqStatusLabel(status: string): string {
  switch (status) {
    case 'READY':
      return 'HQ 就绪'
    case 'DELETED':
      return 'HQ 已删'
    case 'MISSING':
      return 'HQ 缺失'
    case 'DELETE_QUEUED':
    case 'DELETING':
      return 'HQ 删除中'
    case 'FAILED':
      return 'HQ 失败'
    default:
      return 'HQ 未知'
  }
}

/** LQ 状态 → 中文标签 */
export function lqStatusLabel(status: string): string {
  switch (status) {
    case 'READY':
      return 'LQ 就绪'
    case 'NOT_GENERATED':
      return 'LQ 未生成'
    case 'GENERATING':
    case 'QUEUED':
      return 'LQ 生成中'
    case 'MISSING':
      return 'LQ 缺失'
    case 'FAILED':
      return 'LQ 失败'
    default:
      return 'LQ 未知'
  }
}

/** 转码状态 → 中文标签 */
export function transcodeStatusLabel(status: string): string {
  switch (status) {
    case 'READY':
      return '已转码'
    case 'TRANSCODING':
    case 'QUEUED':
      return '转码中'
    case 'FAILED':
      return '转码失败'
    default:
      return '无需转码'
  }
}

/** 生命周期 → 中文标签 */
export function lifecycleLabel(status: string): string {
  switch (status) {
    case 'READY':
      return '就绪'
    case 'TRASHED':
      return '已回收'
    case 'TRASHING':
    case 'DELETING':
      return '处理中'
    case 'RESTORING':
      return '恢复中'
    case 'STAGING':
      return '暂存'
    case 'DELETED':
      return '已删除'
    case 'PURGING':
      return '清理中'
    default:
      return '未知状态'
  }
}

/** 上传文件状态 → 中文标签 */
export function uploadStatusLabel(status: UploadFileStatus): string {
  switch (status) {
    case 'queued':
      return '等待中'
    case 'uploading':
      return '上传中'
    case 'paused':
      return '已暂停'
    case 'completed':
      return '已完成'
    case 'failed':
      return '失败'
    case 'cancelled':
      return '已取消'
  }
}
