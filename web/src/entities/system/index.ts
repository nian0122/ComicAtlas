import { request } from '@/shared/api/http'
import { validCapabilities, type SystemIdentity, type ToolCapabilities } from './model/types'
export type { SystemIdentity, ToolCapabilities, ToolCapability, MediaTool } from './model/types'
export { mediaTools } from './model/types'
export const systemApi = {
  identity: async () => {
    const value = await request<SystemIdentity>('/system')
    if (!value || typeof value.name !== 'string' || typeof value.apiVersion !== 'string')
      throw new Error('系统信息不符合 Go 接口契约')
    return value
  },
  capabilities: async () => {
    const value = await request<ToolCapabilities>('/system/capabilities')
    if (!validCapabilities(value)) throw new Error('媒体工具能力不符合 Go 接口契约')
    return value
  },
}
