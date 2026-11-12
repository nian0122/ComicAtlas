import { request } from '@/shared/api/http'
import type { HqReleasePreview } from '../model/types'
export const hqReleaseApi = {
  preview: (kind: 'comics' | 'chapters', id: string) =>
    request<HqReleasePreview>(`/${kind}/${encodeURIComponent(id)}/hq-release`),
}
