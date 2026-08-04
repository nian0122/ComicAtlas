/**
 * 管理领域枚举域（T17）
 *
 * 全部使用 `as const` 字面量对象 + 联合类型，禁止 `enum` 关键字（erasableSyntaxOnly）。
 * 每个枚举同时导出：
 *  - 值对象：`ComicLifecycleStatus.DRAFT` 等
 *  - 联合类型：`ComicLifecycleStatus`（与值对象同名，TS 允许声明合并）
 *  - 运行时守卫：`isComicLifecycleStatus(v)`（边界解析用）
 */

// ========== 生命周期状态 ==========

/** 漫画生命周期（后端 ComicLifecycleStatus） */
export const ComicLifecycleStatus = {
  DRAFT: 'DRAFT',
  IMPORTING: 'IMPORTING',
  IMPORT_FAILED: 'IMPORT_FAILED',
  READY: 'READY',
  RECOVERY_REQUIRED: 'RECOVERY_REQUIRED',
  DELETING: 'DELETING',
  TRASHING: 'TRASHING',
  TRASHED: 'TRASHED',
  RESTORING: 'RESTORING',
  PURGING: 'PURGING',
  DELETED: 'DELETED',
} as const
export type ComicLifecycleStatus =
  (typeof ComicLifecycleStatus)[keyof typeof ComicLifecycleStatus]

/** 章节生命周期（后端 ChapterLifecycleStatus） */
export const ChapterLifecycleStatus = {
  DRAFT: 'DRAFT',
  READY: 'READY',
  DELETING: 'DELETING',
  TRASHING: 'TRASHING',
  TRASHED: 'TRASHED',
  RESTORING: 'RESTORING',
  PURGING: 'PURGING',
  DELETED: 'DELETED',
} as const
export type ChapterLifecycleStatus =
  (typeof ChapterLifecycleStatus)[keyof typeof ChapterLifecycleStatus]

/** 媒体生命周期（后端 MediaLifecycleStatus） */
export const MediaLifecycleStatus = {
  STAGING: 'STAGING',
  READY: 'READY',
  DELETING: 'DELETING',
  TRASHING: 'TRASHING',
  TRASHED: 'TRASHED',
  RESTORING: 'RESTORING',
  PURGING: 'PURGING',
  DELETED: 'DELETED',
} as const
export type MediaLifecycleStatus =
  (typeof MediaLifecycleStatus)[keyof typeof MediaLifecycleStatus]

/** 页面级 HQ 状态（page.hq_status） */
export const HqStatus = {
  PENDING: 'PENDING',
  READY: 'READY',
  MISSING: 'MISSING',
  DELETE_QUEUED: 'DELETE_QUEUED',
  DELETING: 'DELETING',
  DELETED: 'DELETED',
  FAILED: 'FAILED',
} as const
export type HqStatus = (typeof HqStatus)[keyof typeof HqStatus]

/** 页面级 LQ 状态（page.lq_status） */
export const LqStatus = {
  NOT_GENERATED: 'NOT_GENERATED',
  QUEUED: 'QUEUED',
  GENERATING: 'GENERATING',
  READY: 'READY',
  MISSING: 'MISSING',
  FAILED: 'FAILED',
} as const
export type LqStatus = (typeof LqStatus)[keyof typeof LqStatus]

/** 转码状态（page.transcode_status） */
export const TranscodeStatus = {
  NOT_NEEDED: 'NOT_NEEDED',
  QUEUED: 'QUEUED',
  TRANSCODING: 'TRANSCODING',
  READY: 'READY',
  FAILED: 'FAILED',
} as const
export type TranscodeStatus =
  (typeof TranscodeStatus)[keyof typeof TranscodeStatus]

// ========== 任务状态 ==========

/** 管理任务状态（后端 ManagementTaskStatus） */
export const ManagementTaskStatus = {
  QUEUED: 'QUEUED',
  RUNNING: 'RUNNING',
  CANCELLING: 'CANCELLING',
  CANCELLED: 'CANCELLED',
  SUCCEEDED: 'SUCCEEDED',
  PARTIALLY_SUCCEEDED: 'PARTIALLY_SUCCEEDED',
  FAILED: 'FAILED',
} as const
export type ManagementTaskStatus =
  (typeof ManagementTaskStatus)[keyof typeof ManagementTaskStatus]

/** 任务阶段（后端 TaskStage，用于 stage 字段） */
export const TaskStage = {
  DOWNLOADING: 'DOWNLOADING',
  EXTRACTING: 'EXTRACTING',
  PARSING: 'PARSING',
} as const
export type TaskStage = (typeof TaskStage)[keyof typeof TaskStage]

/** 任务类型（后端 TaskType） */
export const TaskType = {
  IMPORT: 'IMPORT',
  RECOVERY: 'RECOVERY',
  EXPORT: 'EXPORT',
  DIRECTORY_SCAN: 'DIRECTORY_SCAN',
  LQ_GENERATE: 'LQ_GENERATE',
  LQ_REGENERATE: 'LQ_REGENERATE',
  HQ_DELETE: 'HQ_DELETE',
  TRANSCODE: 'TRANSCODE',
  METADATA_REFRESH: 'METADATA_REFRESH',
  METADATA_UPDATE: 'METADATA_UPDATE',
  COMIC_DELETE: 'COMIC_DELETE',
  MEDIA_UPLOAD: 'MEDIA_UPLOAD',
  MEDIA_REPLACE: 'MEDIA_REPLACE',
  MEDIA_TRASH: 'MEDIA_TRASH',
  CHAPTER_TRASH: 'CHAPTER_TRASH',
  COMIC_RESTORE: 'COMIC_RESTORE',
  CHAPTER_RESTORE: 'CHAPTER_RESTORE',
  MEDIA_RESTORE: 'MEDIA_RESTORE',
  COMIC_PURGE: 'COMIC_PURGE',
  CHAPTER_PURGE: 'CHAPTER_PURGE',
  MEDIA_PURGE: 'MEDIA_PURGE',
} as const
export type TaskType = (typeof TaskType)[keyof typeof TaskType]

/** 操作名（后端 OperationPolicyService 常量，AllowedOperations.allowed 元素） */
export const OperationName = {
  READ: 'READ',
  EDIT: 'EDIT',
  DELETE: 'DELETE',
  RECOVER: 'RECOVER',
  PURGE: 'PURGE',
  RECONCILE: 'RECONCILE',
  IMPORT: 'IMPORT',
  RETRY_IMPORT: 'RETRY_IMPORT',
  LQ_GENERATE: 'LQ_GENERATE',
  LQ_REGENERATE: 'LQ_REGENERATE',
  HQ_DELETE: 'HQ_DELETE',
  TRANSCODE: 'TRANSCODE',
  METADATA_REFRESH: 'METADATA_REFRESH',
} as const
export type OperationName = (typeof OperationName)[keyof typeof OperationName]

/** 目标类型（targetType：COMIC/CHAPTER/MEDIA/DIRECTORY/SYSTEM） */
export const TargetType = {
  COMIC: 'COMIC',
  CHAPTER: 'CHAPTER',
  MEDIA: 'MEDIA',
  DIRECTORY: 'DIRECTORY',
  SYSTEM: 'SYSTEM',
} as const
export type TargetType = (typeof TargetType)[keyof typeof TargetType]

// ========== 上传 / 回收站状态 ==========

/** 上传会话状态（后端 UploadSessionStatus） */
export const UploadSessionStatus = {
  ACTIVE: 'ACTIVE',
  COMPLETED: 'COMPLETED',
  CANCELLED: 'CANCELLED',
  EXPIRED: 'EXPIRED',
  FAILED: 'FAILED',
} as const
export type UploadSessionStatus =
  (typeof UploadSessionStatus)[keyof typeof UploadSessionStatus]

/** 回收站清单状态（TrashManifestActual.status） */
export const TrashManifestStatus = {
  TRASHED: 'TRASHED',
  COMPENSATED: 'COMPENSATED',
  PARTIAL: 'PARTIAL',
  RESTORED: 'RESTORED',
  PURGED: 'PURGED',
} as const
export type TrashManifestStatus =
  (typeof TrashManifestStatus)[keyof typeof TrashManifestStatus]

// ========== 边界解析工具 ==========

/**
 * 解析后的枚举值：已知值（受信任的联合类型）或未知原始值。
 * 未知值必须优雅降级而不是崩溃——未知枚举视为非终态、渲染“未知状态”回退标签。
 */
export type EnumValue<T extends string> =
  | { readonly kind: 'known'; readonly value: T }
  | { readonly kind: 'unknown'; readonly value: string }

/** 生成运行时守卫：校验字符串是否属于该枚举 */
export function makeEnumGuard<T extends string>(
  values: Readonly<Record<string, T>>,
): (v: string) => v is T {
  const set = new Set<string>(Object.values(values))
  return (v: string): v is T => set.has(v)
}

export const isComicLifecycleStatus = makeEnumGuard(ComicLifecycleStatus)
export const isChapterLifecycleStatus = makeEnumGuard(ChapterLifecycleStatus)
export const isMediaLifecycleStatus = makeEnumGuard(MediaLifecycleStatus)
export const isHqStatus = makeEnumGuard(HqStatus)
export const isLqStatus = makeEnumGuard(LqStatus)
export const isTranscodeStatus = makeEnumGuard(TranscodeStatus)
export const isManagementTaskStatus = makeEnumGuard(ManagementTaskStatus)
export const isTaskStage = makeEnumGuard(TaskStage)
export const isTaskType = makeEnumGuard(TaskType)
export const isOperationName = makeEnumGuard(OperationName)
export const isTargetType = makeEnumGuard(TargetType)
export const isUploadSessionStatus = makeEnumGuard(UploadSessionStatus)

/** 边界解析：把未知 wire 值解析成 EnumValue */
export function parseEnum<T extends string>(
  raw: unknown,
  isKnown: (v: string) => v is T,
): EnumValue<T> {
  if (typeof raw === 'string' && isKnown(raw)) {
    return { kind: 'known', value: raw }
  }
  return {
    kind: 'unknown',
    value: typeof raw === 'string' ? raw : raw == null ? '' : String(raw),
  }
}

/** 穷举 switch 的兜底：编译期保证联合类型全覆盖，运行期不应触达 */
export function assertNever(value: never): never {
  throw new Error(`Unexpected enum value: ${String(value)}`)
}

// ========== 基础字段解析（parse, don't validate） ==========

export function asRecord(raw: unknown): Readonly<Record<string, unknown>> | null {
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    return raw as Readonly<Record<string, unknown>>
  }
  return null
}

export function asString(raw: unknown, fallback = ''): string {
  return typeof raw === 'string' ? raw : fallback
}

export function asNumber(raw: unknown, fallback = 0): number {
  return typeof raw === 'number' && Number.isFinite(raw) ? raw : fallback
}

export function asBoolean(raw: unknown, fallback = false): boolean {
  return typeof raw === 'boolean' ? raw : fallback
}

export function asStringArray(raw: unknown): readonly string[] {
  return Array.isArray(raw)
    ? raw.filter((v): v is string => typeof v === 'string')
    : []
}

/** 把未知对象转成 string → string 映射（如 blockedReasons） */
export function asStringMap(raw: unknown): Readonly<Record<string, string>> {
  const rec = asRecord(raw)
  if (!rec) return {}
  const result: Record<string, string> = {}
  for (const [key, value] of Object.entries(rec)) {
    if (typeof value === 'string') result[key] = value
  }
  return result
}
