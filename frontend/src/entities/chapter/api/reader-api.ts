import { api } from '@/shared/api/http'
import type { ReaderDTO } from '@/entities/chapter/model/reader-types'

export const readerApi = {
  chapter: (chapterId: number) => api.get<ReaderDTO>(`/chapters/${chapterId}`),
}
