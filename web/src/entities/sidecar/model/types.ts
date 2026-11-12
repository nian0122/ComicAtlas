export const sidecarStateLabels = {
  pending: '等待同步',
  syncing: '正在同步',
  blocked: '暂不可同步',
  failed: '同步失败',
  synced: '已确认发布',
} as const
export interface SidecarStatus {
  comicId: string
  state: keyof typeof sidecarStateLabels
  revision: number
  publishedRevision: number
  publishedAt: string | null
  taskId: string | null
  retryAt: string | null
  errorCode: string | null
  failure: { message: string; action: string } | null
}
export interface SidecarResult {
  taskId: string
  comicId: string
  requestedRevision: number
  observedRevision: number | null
  observedAt: string | null
  published: boolean
}
export function validSidecarStatus(value: SidecarStatus, comicId: string) {
  return (
    !!value &&
    value.comicId === comicId &&
    Object.hasOwn(sidecarStateLabels, value.state) &&
    Number.isSafeInteger(value.revision) &&
    value.revision > 0 &&
    Number.isSafeInteger(value.publishedRevision) &&
    value.publishedRevision >= 0 &&
    value.publishedRevision <= value.revision
  )
}
