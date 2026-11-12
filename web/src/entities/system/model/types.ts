export interface ToolCapability {
  configured: boolean
  available: boolean
  version: string | null
}
export const mediaTools = ['ffmpeg', 'ffprobe', 'cwebp', 'djpeg'] as const
export type MediaTool = (typeof mediaTools)[number]
export type ToolCapabilities = Record<MediaTool, ToolCapability>
export interface SystemIdentity {
  name: string
  apiVersion: string
}
export function validCapabilities(value: ToolCapabilities) {
  return (
    !!value &&
    mediaTools.every((name) => {
      const tool = value[name]
      return (
        !!tool &&
        typeof tool.configured === 'boolean' &&
        typeof tool.available === 'boolean' &&
        (tool.available
          ? tool.configured && typeof tool.version === 'string' && tool.version.length > 0
          : tool.version === null)
      )
    })
  )
}
