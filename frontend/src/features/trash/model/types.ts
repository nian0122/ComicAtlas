export interface TrashPurgeRequest {
  readonly token: string
}

export interface ReconcileEntry {
  readonly rootKey: string
  readonly sourceRelativePath: string
  readonly sourceExists: boolean
  readonly trashExists: boolean
  readonly state: string
}

export interface ReconcileResult {
  readonly targetType: string
  readonly targetId: number
  readonly dbStatus: string | null
  readonly manifestTaskId: number | null
  readonly manifestStatus: string | null
  readonly consistent: boolean
  readonly entries: readonly ReconcileEntry[]
}

export interface TrashContentVO {
  readonly targetType: 'COMIC' | 'CHAPTER' | 'MEDIA'
  readonly targetId: number
  readonly comicId: number | null
  readonly chapterId: number | null
  readonly title: string
  readonly subtitle: string | null
  readonly coverUrl: string | null
  readonly status: string
  readonly mediaType: string | null
  readonly pageNumber: number | null
  readonly createdAt: string
  readonly trashedAt: string | null
}
