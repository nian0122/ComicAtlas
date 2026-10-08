export interface RecoveryTaskVO {
  id: number
  status: string
  totalComics: number
  recoveredComics: number
  skippedComics: number
  placeholderComics: number
  errorComics: number
  errorMessage?: string
  errorDetails?: string
  retryCount: number
  createdAt: string
  startedAt?: string
  endedAt?: string
}
