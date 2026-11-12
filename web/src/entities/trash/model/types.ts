export interface TrashEntry {
  id: string
  target: {
    targetType: 'comic' | 'chapter' | 'media'
    targetId: string
    comicId: string
    chapterId: string
    title: string
  }
  state: 'trashing' | 'trashed' | 'restoring' | 'restored' | 'purging' | 'purged' | 'detached'
  origin: 'native' | 'imported'
  createdAt: string
  updatedAt: string
  taskId: string
  coverUrl?: string
}
