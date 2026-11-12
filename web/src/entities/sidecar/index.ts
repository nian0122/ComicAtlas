import { request } from '@/shared/api/http'
import { validSidecarStatus, type SidecarStatus, type SidecarResult } from './model/types'
export type { SidecarStatus, SidecarResult } from './model/types'
export { sidecarStateLabels } from './model/types'
export const sidecarApi = {
  status: async (id: string) => {
    const value = await request<SidecarStatus>(`/comics/${encodeURIComponent(id)}/sidecar`)
    if (!validSidecarStatus(value, id)) throw new Error('侧车状态与漫画或同步修订契约不一致')
    return value
  },
  result: async (id: string, comicId: string) => {
    const page = await request<{ items: SidecarResult[]; total: number }>(`/tasks/${encodeURIComponent(id)}/items`, {
      query: { page: 1, pageSize: 1 },
    })
    const value = Array.isArray(page?.items) ? page.items[0] : undefined
    if (
      !page ||
      page.total !== 1 ||
      !Array.isArray(page.items) ||
      page.items.length !== 1 ||
      !value ||
      value.taskId !== id ||
      value.comicId !== comicId ||
      typeof value.published !== 'boolean' ||
      !Number.isSafeInteger(value.requestedRevision) ||
      value.requestedRevision < 1 ||
      (value.observedRevision !== null &&
        (!Number.isSafeInteger(value.observedRevision) || value.observedRevision < 1)) ||
      (value.published && (value.observedRevision === null || typeof value.observedAt !== 'string'))
    )
      throw new Error('侧车任务结果不符合当前任务与修订契约')
    return value
  },
}
