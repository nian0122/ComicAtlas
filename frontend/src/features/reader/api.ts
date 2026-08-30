import { api } from '@/services/http'
import type { ReaderDTO } from '@/features/reader/types'

export const readerApi = {
  chapter: (chapterId: number) => api.get<ReaderDTO>(`/chapters/${chapterId}`),
}
