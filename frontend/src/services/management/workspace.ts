import { request } from './http'
import { toErrorMessage } from './http'
import { parseCatalogTree, parseComicListPage, parseWorkspaceComicDetail, parseTagIds } from '@/types/management/comic'
import type {
  CatalogMutationVO,
  CatalogTreeNode,
  ComicListParams,
  PaginatedComics,
  UpdateComicRequest,
  WorkspaceComicDetail,
} from '@/types/management/comic'

/**
 * 统一漫画工作区 API（T18）
 *
 * 覆盖：
 * - 列表（GET /comics，含 lifecycle/activeTask/allowedOperations）
 * - 详情 / 更新（含 version 乐观锁）/ 删除（回收任务）
 * - metadata / tags 子资源
 * - 目录树 + 目录 CRUD（create/rename/move/reorder/delete）
 * - 章节 CRUD（create/rename/move/reorder/trash）
 *
 * 全部使用 T17 显式边界客户端（services/management/http.ts 的 request），
 * 响应经 types/management/comic.ts 的 parse 函数解析成强类型。
 */

export interface ComicMetadataPayload {
  readonly title: string
  readonly author?: string
  readonly description?: string
  readonly categoryId?: number | null
}

export interface ComicTagUpdatePayload {
  readonly tagIds: readonly number[]
}

export const workspaceApi = {
  // ========== 列表 / 详情 / 更新 / 删除 ==========

  list: async (params: ComicListParams): Promise<PaginatedComics> => {
    const raw = await request<unknown>({ method: 'GET', url: '/comics', params })
    return parseComicListPage(raw)
  },

  detail: async (id: number): Promise<WorkspaceComicDetail> => {
    const raw = await request<unknown>({ method: 'GET', url: `/comics/${id}` })
    const parsed = parseWorkspaceComicDetail(raw)
    if (!parsed || parsed.id <= 0) throw new Error('漫画详情数据格式无效')
    return parsed
  },

  update: async (id: number, payload: UpdateComicRequest): Promise<WorkspaceComicDetail> => {
    const raw = await request<unknown>({ method: 'PUT', url: `/comics/${id}`, data: payload })
    const parsed = parseWorkspaceComicDetail(raw)
    if (!parsed || parsed.id <= 0) throw new Error('漫画更新响应格式无效')
    return parsed
  },

  /** 删除漫画（回收），返回管理任务；HTTP/业务错误以 ApiError 抛出 */
  trashComic: async (id: number, idempotencyKey?: string): Promise<unknown> => {
    return request<unknown>({
      method: 'DELETE',
      url: `/comics/${id}`,
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
    })
  },

  // ========== metadata / tags ==========

  metadata: async (id: number): Promise<ComicMetadataPayload> => {
    const raw = await request<unknown>({ method: 'GET', url: `/comics/${id}/metadata` })
    const rec = raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : {}
    return {
      title: typeof rec.title === 'string' ? rec.title : '',
      author: typeof rec.author === 'string' ? rec.author : undefined,
      description: typeof rec.description === 'string' ? rec.description : undefined,
      categoryId: rec.categoryId == null ? null : Number(rec.categoryId),
    }
  },

  updateMetadata: async (id: number, payload: ComicMetadataPayload): Promise<void> => {
    await request<null>({ method: 'PUT', url: `/comics/${id}/metadata`, data: payload })
  },

  tags: async (id: number): Promise<readonly number[]> => {
    const raw = await request<unknown>({ method: 'GET', url: `/comics/${id}/tags` })
    return parseTagIds(raw)
  },

  updateTags: async (id: number, payload: ComicTagUpdatePayload): Promise<void> => {
    await request<null>({ method: 'PUT', url: `/comics/${id}/tags`, data: payload })
  },

  // ========== 目录树 ==========

  catalogTree: async (comicId: number): Promise<readonly CatalogTreeNode[]> => {
    const raw = await request<unknown>({ method: 'GET', url: `/comics/${comicId}/catalog` })
    return parseCatalogTree(raw)
  },

  // ========== 目录 CRUD ==========

  createCatalog: async (
    comicId: number,
    payload: { readonly title: string; readonly parentId?: number | null; readonly sortOrder?: number },
  ): Promise<CatalogMutationVO> => {
    const raw = await request<unknown>({ method: 'POST', url: `/comics/${comicId}/catalogs`, data: payload })
    return raw as CatalogMutationVO
  },

  renameCatalog: async (comicId: number, catalogId: number, payload: { readonly title: string }): Promise<CatalogMutationVO> => {
    const raw = await request<unknown>({ method: 'PATCH', url: `/comics/${comicId}/catalogs/${catalogId}`, data: payload })
    return raw as CatalogMutationVO
  },

  moveCatalog: async (comicId: number, catalogId: number, payload: { readonly parentId?: number | null }): Promise<CatalogMutationVO> => {
    const raw = await request<unknown>({ method: 'PUT', url: `/comics/${comicId}/catalogs/${catalogId}/move`, data: payload })
    return raw as CatalogMutationVO
  },

  reorderCatalog: async (comicId: number, catalogId: number, payload: { readonly sortOrder: number }): Promise<void> => {
    await request<null>({ method: 'PUT', url: `/comics/${comicId}/catalogs/${catalogId}/reorder`, data: payload })
  },

  deleteCatalog: async (comicId: number, catalogId: number, reparentTo?: number | null): Promise<void> => {
    await request<null>({
      method: 'DELETE',
      url: `/comics/${comicId}/catalogs/${catalogId}`,
      params: reparentTo != null ? { reparentTo } : undefined,
    })
  },

  // ========== 章节 CRUD ==========

  createChapter: async (
    comicId: number,
    payload: { readonly title: string; readonly chapterNo?: string; readonly catalogId?: number | null },
  ): Promise<CatalogMutationVO> => {
    const raw = await request<unknown>({ method: 'POST', url: `/comics/${comicId}/chapters`, data: payload })
    return raw as CatalogMutationVO
  },

  renameChapter: async (
    comicId: number,
    chapterId: number,
    payload: { readonly title?: string; readonly chapterNo?: string },
  ): Promise<CatalogMutationVO> => {
    const raw = await request<unknown>({ method: 'PATCH', url: `/comics/${comicId}/chapters/${chapterId}`, data: payload })
    return raw as CatalogMutationVO
  },

  moveChapter: async (comicId: number, chapterId: number, payload: { readonly catalogId?: number | null }): Promise<CatalogMutationVO> => {
    const raw = await request<unknown>({ method: 'PUT', url: `/comics/${comicId}/chapters/${chapterId}/move`, data: payload })
    return raw as CatalogMutationVO
  },

  reorderChapter: async (comicId: number, chapterId: number, payload: { readonly targetGlobalOrder: number }): Promise<CatalogMutationVO> => {
    const raw = await request<unknown>({ method: 'PUT', url: `/comics/${comicId}/chapters/${chapterId}/reorder`, data: payload })
    return raw as CatalogMutationVO
  },

  trashChapter: async (comicId: number, chapterId: number): Promise<void> => {
    await request<null>({ method: 'DELETE', url: `/comics/${comicId}/chapters/${chapterId}` })
  },
}

/** 统一错误文案提取（供页面展示） */
export function workspaceErrorMessage(err: unknown, fallback: string): string {
  return toErrorMessage(err, fallback)
}
