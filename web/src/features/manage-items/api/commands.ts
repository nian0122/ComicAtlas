import { request } from '@/shared/api/http'
import { requireImportPath } from '@/entities/import-scan'
import type { HqReleasePreview } from '@/entities/hq-release'
import type { Term } from '@/entities/comic'
import type { Task } from '@/entities/task'
export interface BatchPreview {
  id: string
  operation: BatchRequest['operation']
  selectedCount: number
  eligibleCount: number
  blockedCount: number
  pendingCount: number
  succeededCount: number
  failedCount: number
  confirmationToken?: string
  expiresAt: string
  taskId: string | null
  confirmationEffect?: string
  activeCount: number
  cancelledCount: number
  createdAt: string
  taskStatus: Task['status'] | null
  completedAt: string | null
  hq?: { images: number; originalBytes: number; effect: 'delete_hq_keep_lq' }
  purge?: {
    rootCount: number
    coveredCount: number
    entryCount: number
    fileCount: number
    registeredBytes: number
    unmeasuredFiles: number
    retentionDays: number | null
  }
}
export interface BatchItem {
  position: number
  title: string
  status: string
  errorCode: string | null
  childTaskId: string | null
  targetType: string
  targetId: string
  comicId: string
  waitReason: string | null
  updatedVersion: number | null
  imageCount?: number
  videoCount?: number
  coveredBy?: number
  coveredTaskId?: string
  entryId?: string
  dependencies?: number[]
  hq?: HqReleasePreview
}
export type BatchOperation = 'image_lq' | 'metadata_refresh' | 'video_transcode' | 'comic_trash' | 'hq_release'
export type TrashOperation = 'trash_restore' | 'trash_purge'
export interface TargetFilter {
  keyword?: string
  comicId?: string
  chapterId?: string
  reaction?: 'like' | 'dislike' | 'any'
  mediaType?: 'image' | 'video'
}
export type TargetSelection =
  { mode: 'ids'; ids: string[] } | { mode: 'filter'; filter: TargetFilter; excludedIds?: string[] }
export type TrashTargetKind = 'chapter' | 'media'
export function dislikedTrashFilter(kind: TrashTargetKind, mediaType: '' | 'image' | 'video' = ''): TargetFilter {
  return { reaction: 'dislike', ...(kind === 'media' && mediaType ? { mediaType } : {}) }
}
export type BatchRequest =
  | { operation: 'image_lq'; selection: { mode: 'ids'; ids: string[] }; lq?: { force: boolean } }
  | { operation: Exclude<BatchOperation, 'image_lq'>; selection: { mode: 'ids'; ids: string[] } }
  | { operation: 'chapter_trash' | 'media_trash'; targetSelection: TargetSelection }
  | { operation: TrashOperation; trashSelection: { mode: 'ids'; ids: string[] } }
export function requiresBatchConfirmation(operation: BatchRequest['operation']) {
  return ['hq_release', 'comic_trash', 'chapter_trash', 'media_trash', 'trash_restore', 'trash_purge'].includes(
    operation,
  )
}
export const managementCommands = {
  saveTerm: (kind: 'categories' | 'tags', id: string | null, name: string, version: number) =>
    request<Term>(`/${kind}${id ? '/' + encodeURIComponent(id) : ''}`, {
      method: id ? 'PUT' : 'POST',
      body: id ? { name, version } : { name },
    }),
  deleteTerm: (kind: 'categories' | 'tags', id: string, version: number) =>
    request<void>(`/${kind}/${encodeURIComponent(id)}`, { method: 'DELETE', query: { version }, response: 'empty' }),
  deleteCatalog: (comic: string, id: string, version: number, reparentTo: string | null) =>
    request<{ version: number }>(`/comics/${encodeURIComponent(comic)}/catalogs/${encodeURIComponent(id)}`, {
      method: 'DELETE',
      body: { version, reparentTo },
    }),
  transcode: (kind: 'comics' | 'chapters' | 'media', id: string, key: string) =>
    request<Task>(`/${kind}/${encodeURIComponent(id)}/transcode`, { method: 'POST', body: {}, idempotencyKey: key }),
  exportComic: (id: string, body: { format: 'zip' | 'cbz'; partBytes: number }, key: string) =>
    request<Task>(`/comics/${encodeURIComponent(id)}/exports`, { method: 'POST', body, idempotencyKey: key }),
  generateLq: (kind: 'comics' | 'chapters', id: string, key: string, force = false) =>
    request<Task>(`/${kind}/${encodeURIComponent(id)}/lq`, {
      method: 'POST',
      body: { force },
      idempotencyKey: key,
    }),
  refreshComic: (id: string, key: string) =>
    request<Task>(`/comics/${encodeURIComponent(id)}/refresh`, { method: 'POST', body: {}, idempotencyKey: key }),
  import: (kind: 'directories' | 'archives', sourcePath: string, title: string, key: string) =>
    request<Task>(`/imports/${kind}`, {
      method: 'POST',
      body: { sourcePath: requireImportPath(sourcePath), ...(title ? { title } : {}) },
      idempotencyKey: key,
    }),
  edit: (
    id: string,
    body: {
      version: number
      originalTitle: string | null
      title: string
      author: string
      description: string
      categoryId: string | null
      tagIds: string[]
    },
  ) => request<{ id: string; version: number }>(`/comics/${encodeURIComponent(id)}`, { method: 'PUT', body }),
  node: (
    comic: string,
    kind: 'catalogs' | 'chapters',
    id: string | null,
    body: { version: number; title: string; parentId: string | null },
  ) =>
    request(`/comics/${encodeURIComponent(comic)}/${kind}${id ? '/' + encodeURIComponent(id) : ''}`, {
      method: id ? 'PUT' : 'POST',
      body,
    }),
  mediaOrder: (chapter: string, version: number, ids: string[]) =>
    request(`/chapters/${encodeURIComponent(chapter)}/media/order`, { method: 'PUT', body: { version, ids } }),
  structureOrder: (
    comic: string,
    version: number,
    parentId: string | null,
    items: { type: 'catalog' | 'chapter'; id: string }[],
  ) =>
    request(`/comics/${encodeURIComponent(comic)}/structure/order`, {
      method: 'PUT',
      body: { version, parentId, items },
    }),
  move: (
    comic: string,
    version: number,
    parentId: string | null,
    items: { type: 'catalog' | 'chapter'; id: string }[],
  ) =>
    request<{ version: number }>(`/comics/${encodeURIComponent(comic)}/structure/move`, {
      method: 'PUT',
      body: { version, parentId, items },
    }),
  preview: (body: BatchRequest) => request<BatchPreview>('/batch-previews', { method: 'POST', body }),
  previewDetail: (id: string) => request<BatchPreview>(`/batch-previews/${encodeURIComponent(id)}`),
  previewItems: (id: string, page = 1) =>
    request<{ items: BatchItem[]; total: number }>(`/batch-previews/${encodeURIComponent(id)}/items`, {
      query: { page, pageSize: 100 },
    }),
  confirm: (preview: BatchPreview, key: string) =>
    request<Task>(`/batch-previews/${encodeURIComponent(preview.id)}/confirm`, {
      method: 'POST',
      body: { confirm: true, ...(preview.confirmationToken ? { confirmationToken: preview.confirmationToken } : {}) },
      idempotencyKey: key,
    }),
  refreshStorage: (key: string) => request<Task>('/storage/refresh', { method: 'POST', body: {}, idempotencyKey: key }),
  task: (id: string, action: 'cancel' | 'retry') =>
    request<Task>(`/tasks/${encodeURIComponent(id)}/${action}`, { method: 'POST', body: {} }),
}
