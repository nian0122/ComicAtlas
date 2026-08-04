import type { ComicListQuery } from '@/types'
import type {
  ComicLifecycleStatus,
  EnumValue,
  ManagementTaskStatus,
  TaskType,
} from './enums'
import type { AllowedOperations } from './operation'
import { parseAllowedOperations } from '@/services/management/parse'
import {
  asNumber,
  asRecord,
  asString,
  isComicLifecycleStatus,
  isManagementTaskStatus,
  isTaskType,
  parseEnum,
} from './enums'

/**
 * 统一漫画工作区领域（T18）
 *
 * 覆盖列表（含 lifecycle/activeTask/allowedOperations）、工作区详情、
 * 目录树编辑与章节管理。全部经由边界解析函数把 wire 值转成强类型，
 * 枚举字段统一为 EnumValue（known | unknown），禁止裸 string。
 */

// ========== 列表 ==========

/** 列表项上的活跃管理任务（轻量解析 activeTask 字段） */
export interface ComicActiveTaskEntry {
  readonly id: number
  readonly taskType: EnumValue<TaskType>
  readonly status: EnumValue<ManagementTaskStatus>
  readonly progress: number
  readonly errorMessage: string
}

/** 规范化漫画列表项（ComicListVO） */
export interface ComicListEntry {
  readonly id: number
  readonly title: string
  readonly author: string
  readonly coverUrl: string
  readonly pageCount: number
  readonly categoryId: number | null
  readonly categoryName: string | null
  readonly lifecycle: EnumValue<ComicLifecycleStatus>
  readonly activeTask: ComicActiveTaskEntry | null
  readonly allowedOperations: AllowedOperations
  readonly progressPercent: number
  readonly lastReadChapterId: number
  readonly lastReadPage: number
  readonly createdAt: string
}

export interface PaginatedComics {
  readonly records: readonly ComicListEntry[]
  readonly total: number
  readonly size: number
  readonly current: number
  readonly pages: number
}

/** 列表查询参数（对齐后端 ComicListQuery，全部可空） */
export interface ComicListParams extends ComicListQuery {
  readonly keyword?: string
  readonly tag?: string
  readonly tags?: string[]
  readonly tagMode?: 'AND' | 'OR'
  readonly status?: string
  readonly category?: string
  readonly sourceType?: string
  readonly page?: number
  readonly size?: number
}

// ========== 详情 / 工作区 ==========

/** 详情章节引用（ComicDetailVO.chapters） */
export interface WorkspaceChapterSummary {
  readonly id: number
  readonly chapterNo: number
  readonly title: string
  readonly pageCount: number
}

/** 标签引用（ComicDetailVO.tags） */
export interface WorkspaceTagRef {
  readonly name: string
  readonly type: string
}

/** 工作区漫画详情（ComicDetailVO，含 version 乐观锁与 allowedOperations） */
export interface WorkspaceComicDetail {
  readonly id: number
  readonly title: string
  readonly titleJpn: string
  readonly author: string
  readonly description: string
  readonly coverUrl: string
  readonly pageCount: number
  readonly fileSize: number
  readonly sourceType: string
  readonly sourceRef: string
  readonly categoryId: number | null
  readonly categoryName: string | null
  readonly lifecycle: EnumValue<ComicLifecycleStatus>
  readonly activeTask: ComicActiveTaskEntry | null
  readonly allowedOperations: AllowedOperations
  readonly version: number
  readonly chapters: readonly WorkspaceChapterSummary[]
  readonly tags: readonly WorkspaceTagRef[]
  readonly progressPercent: number
  readonly lastReadChapterId: number
  readonly lastReadPage: number
  readonly createdAt: string
  readonly updatedAt: string
}

/** 漫画更新请求（UpdateComicRequest：version 必填，其余可空=不修改） */
export interface UpdateComicRequest {
  readonly version: number
  readonly title?: string
  readonly titleJpn?: string
  readonly author?: string
  readonly description?: string
  readonly categoryId?: number | null
}

// ========== 目录树 ==========

/** 目录树节点（GET /comics/{id}/catalog 的 CatalogNode） */
export interface CatalogTreeNode {
  readonly id: number | null
  readonly title: string | null
  readonly children: readonly CatalogTreeNode[]
  readonly chapters: readonly CatalogTreeChapter[]
}

/** 目录树章节引用（ChapterRef） */
export interface CatalogTreeChapter {
  readonly id: number
  readonly chapterNo: string
  readonly title: string
  readonly globalOrder: number
  readonly pageCount: number
  readonly status: string | null
}

/** 目录/章节管理响应（CatalogVO / ChapterVO 共用字段） */
export interface CatalogMutationVO {
  readonly id: number
  readonly comicId: number
  readonly parentId: number | null
  readonly catalogId: number | null
  readonly title: string
  readonly chapterNo: string
  readonly sortOrder: number
  readonly globalOrder: number
  readonly status: string
}

// ========== 边界解析（parse, don't validate） ==========

function parseActiveTask(raw: unknown): ComicActiveTaskEntry | null {
  const rec = asRecord(raw)
  if (!rec) return null
  return {
    id: asNumber(rec.id),
    taskType: parseEnum(rec.taskType, isTaskType),
    status: parseEnum(rec.status, isManagementTaskStatus),
    progress: asNumber(rec.progress),
    errorMessage: asString(rec.errorMessage),
  }
}

/** 解析列表项：结构损坏或非对象时返回 null（调用方丢弃） */
export function parseComicListEntry(raw: unknown): ComicListEntry | null {
  const rec = asRecord(raw)
  if (!rec) return null
  return {
    id: asNumber(rec.id),
    title: asString(rec.title),
    author: asString(rec.author),
    coverUrl: asString(rec.coverUrl),
    pageCount: asNumber(rec.pageCount),
    categoryId: rec.categoryId == null ? null : asNumber(rec.categoryId),
    categoryName: rec.categoryName == null ? null : asString(rec.categoryName),
    lifecycle: parseEnum(rec.lifecycle, isComicLifecycleStatus),
    activeTask: parseActiveTask(rec.activeTask),
    allowedOperations: parseAllowedOperations(rec.allowedOperations),
    progressPercent: asNumber(rec.progressPercent),
    lastReadChapterId: asNumber(rec.lastReadChapterId),
    lastReadPage: asNumber(rec.lastReadPage),
    createdAt: asString(rec.createdAt),
  }
}

/** 解析分页列表：损坏条目丢弃 */
export function parseComicListPage(raw: unknown): PaginatedComics {
  const rec = asRecord(raw)
  if (!rec) {
    return { records: [], total: 0, size: 0, current: 1, pages: 0 }
  }
  const recordsRaw = Array.isArray(rec.records) ? rec.records : []
  const records = recordsRaw
    .map(parseComicListEntry)
    .filter((entry): entry is ComicListEntry => entry !== null && entry.id > 0)
  return {
    records,
    total: asNumber(rec.total),
    size: asNumber(rec.size),
    current: asNumber(rec.current),
    pages: asNumber(rec.pages),
  }
}

function parseChapterSummary(raw: unknown): WorkspaceChapterSummary | null {
  const rec = asRecord(raw)
  if (!rec) return null
  return {
    id: asNumber(rec.id),
    chapterNo: asNumber(rec.chapterNo),
    title: asString(rec.title),
    pageCount: asNumber(rec.pageCount),
  }
}

function parseTagRef(raw: unknown): WorkspaceTagRef | null {
  const rec = asRecord(raw)
  if (!rec) return null
  return {
    name: asString(rec.name),
    type: asString(rec.type),
  }
}

/** 解析工作区详情 */
export function parseWorkspaceComicDetail(raw: unknown): WorkspaceComicDetail | null {
  const rec = asRecord(raw)
  if (!rec) return null
  const chaptersRaw = Array.isArray(rec.chapters) ? rec.chapters : []
  const tagsRaw = Array.isArray(rec.tags) ? rec.tags : []
  return {
    id: asNumber(rec.id),
    title: asString(rec.title),
    titleJpn: asString(rec.titleJpn),
    author: asString(rec.author),
    description: asString(rec.description),
    coverUrl: asString(rec.coverUrl),
    pageCount: asNumber(rec.pageCount),
    fileSize: asNumber(rec.fileSize),
    sourceType: asString(rec.sourceType),
    sourceRef: asString(rec.sourceRef),
    categoryId: rec.categoryId == null ? null : asNumber(rec.categoryId),
    categoryName: rec.categoryName == null ? null : asString(rec.categoryName),
    lifecycle: parseEnum(rec.lifecycle, isComicLifecycleStatus),
    activeTask: parseActiveTask(rec.activeTask),
    allowedOperations: parseAllowedOperations(rec.allowedOperations),
    version: asNumber(rec.version),
    chapters: chaptersRaw
      .map(parseChapterSummary)
      .filter((c): c is WorkspaceChapterSummary => c !== null && c.id > 0),
    tags: tagsRaw
      .map(parseTagRef)
      .filter((t): t is WorkspaceTagRef => t !== null),
    progressPercent: asNumber(rec.progressPercent),
    lastReadChapterId: asNumber(rec.lastReadChapterId),
    lastReadPage: asNumber(rec.lastReadPage),
    createdAt: asString(rec.createdAt),
    updatedAt: asString(rec.updatedAt),
  }
}

function parseCatalogChapter(raw: unknown): CatalogTreeChapter | null {
  const rec = asRecord(raw)
  if (!rec) return null
  return {
    id: asNumber(rec.id),
    chapterNo: asString(rec.chapterNo),
    title: asString(rec.title),
    globalOrder: asNumber(rec.globalOrder),
    pageCount: asNumber(rec.pageCount),
    status: rec.status == null ? null : asString(rec.status),
  }
}

function parseCatalogNode(raw: unknown): CatalogTreeNode | null {
  const rec = asRecord(raw)
  if (!rec) return null
  const childrenRaw = Array.isArray(rec.children) ? rec.children : []
  const chaptersRaw = Array.isArray(rec.chapters) ? rec.chapters : []
  return {
    id: rec.id == null ? null : asNumber(rec.id),
    title: rec.title == null ? null : asString(rec.title),
    children: childrenRaw
      .map(parseCatalogNode)
      .filter((node): node is CatalogTreeNode => node !== null),
    chapters: chaptersRaw
      .map(parseCatalogChapter)
      .filter((c): c is CatalogTreeChapter => c !== null && c.id > 0),
  }
}

/** 解析目录树（GET /comics/{id}/catalog → CatalogNode[]） */
export function parseCatalogTree(raw: unknown): readonly CatalogTreeNode[] {
  if (!Array.isArray(raw)) return []
  return raw
    .map(parseCatalogNode)
    .filter((node): node is CatalogTreeNode => node !== null)
}

/** 解析 CatalogVO / ChapterVO 单条管理响应 */
export function parseCatalogMutation(raw: unknown): CatalogMutationVO | null {
  const rec = asRecord(raw)
  if (!rec) return null
  return {
    id: asNumber(rec.id),
    comicId: asNumber(rec.comicId),
    parentId: rec.parentId == null ? null : asNumber(rec.parentId),
    catalogId: rec.catalogId == null ? null : asNumber(rec.catalogId),
    title: asString(rec.title),
    chapterNo: asString(rec.chapterNo),
    sortOrder: asNumber(rec.sortOrder),
    globalOrder: asNumber(rec.globalOrder),
    status: asString(rec.status),
  }
}

/** 解析标签 ID 列表（GET /comics/{id}/tags → number[]） */
export function parseTagIds(raw: unknown): readonly number[] {
  if (!Array.isArray(raw)) return []
  return raw.filter((v): v is number => typeof v === 'number' && Number.isFinite(v))
}
