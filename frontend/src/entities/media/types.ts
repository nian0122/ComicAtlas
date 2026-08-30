/** 媒体类型：图片或视频。 */
export type MediaType = 'IMAGE' | 'VIDEO'

export interface MediaItemInfo {
  id: number
  pageNumber: number
  /** HQ 存储文件名，用于管理端媒体维护展示。 */
  fileName?: string
  hqUrl: string
  /** HQ 文件状态，不能用 hqUrl 是否存在推断。 */
  hqStatus?: string
  /** 后端根据实际 LQ 产物返回的完整 URL，前端不得拼接或猜测扩展名。 */
  lqUrl: string | null
  lqStatus: string
  width: number
  height: number
  hqSize?: number
  lqSize?: number
  transcodeStatus?: string
  /** 媒体类型，缺失时默认按 IMAGE 处理。 */
  mediaType?: MediaType
  /** 视频时长（秒），仅 VIDEO 有意义。 */
  duration?: number
  /** 视频容器格式，如 mp4/webm/mkv。 */
  container?: string
  /** 视频编码，如 h264/h265/vp9。 */
  videoCodec?: string
  /** 音频编码，如 aac/opus。 */
  audioCodec?: string
}
