/** 默认图片宽高比（3:4），用于宽高未知的页面 */
export const DEFAULT_ASPECT_RATIO = 3 / 4

export type ComicStatus =
  | 'DRAFT'
  | 'IMPORTING'
  | 'IMPORT_FAILED'
  | 'READY'
  | 'RECOVERY_REQUIRED'
  | 'REFRESHING'
  | 'DELETING'
  | 'TRASHING'
  | 'TRASHED'
  | 'RESTORING'
  | 'PURGING'
  | 'DELETED'

export interface ComicListQuery {
  keyword?: string
  tag?: string
  tags?: string[]
  tagMode?: 'AND' | 'OR'
  status?: string
  category?: string
  sourceType?: string
  sort?: 'createdAt' | 'updatedAt' | 'title' | 'pageCount' | 'lastReadTime'
  page?: number
  size?: number
}

export interface CategoryDTO {
  id: number
  name: string
  sortOrder: number
}

export interface ComicListVO {
  id: number
  title: string
  author: string
  coverUrl: string
  pageCount: number
  categoryId: number | null
  categoryName: string | null
  status: ComicStatus
  progressPercent: number
  lastReadChapterId: number
  lastReadPage: number
  createdAt: string
}

export interface ComicDetailVO {
  id: number
  title: string
  titleJpn?: string
  author: string
  description?: string
  coverUrl: string
  pageCount: number
  fileSize: number
  sourceType: string
  sourceRef: string
  categoryId: number | null
  categoryName: string | null
  status: ComicStatus
  progressPercent: number
  lastReadChapterId: number
  lastReadPage: number
  chapters: ChapterVO[]
  tags: TagRef[]
  createdAt: string
  updatedAt: string
}

export interface ChapterVO {
  id: number
  chapterNo: number
  title: string
  pageCount: number
}

export interface TagRef {
  name: string
  type: string
}

export interface CatalogNode {
  id: number | null
  title: string | null
  children: CatalogNode[]
  chapters: ChapterRef[]
  /** 目录在阅读顺序中的锚点（= 其下最小子项 globalOrder），用于与章节混合排布 */
  globalOrder?: number | null
}

export interface ChapterRef {
  id: number
  chapterNo: string
  title: string
  globalOrder: number
  pageCount: number
  status?: string
}

/** 媒体类型：图片或视频 */
export type MediaType = 'IMAGE' | 'VIDEO'

export interface MediaItemInfo {
  id: number
  pageNumber: number
  /** HQ 存储文件名，用于管理端媒体维护展示。 */
  fileName?: string
  hqUrl: string
  /** HQ 文件状态，不能用 hqUrl 是否存在推断。 */
  hqStatus?: string
  lqUrl: string
  lqStatus: string
  width: number
  height: number
  fileSize?: number
  lqSize?: number
  transcodeStatus?: string
  /** 媒体类型，缺失时默认按 IMAGE 处理 */
  mediaType?: MediaType
  /** 视频时长（秒），仅 VIDEO 有意义 */
  duration?: number
  /** 视频容器格式，如 mp4/webm/mkv */
  container?: string
  /** 视频编码，如 h264/h265/vp9 */
  videoCodec?: string
  /** 音频编码，如 aac/opus */
  audioCodec?: string
}

export interface ReaderDTO {
  chapterId: number
  comicId: number
  chapterTitle: string
  pages: MediaItemInfo[]
  total: number
  prevChapterId: number | null
  nextChapterId: number | null
}

export interface ChapterPageVO {
  comicId: number
  chapterId: number
  chapterNo: string
  chapterTitle: string
  pages: MediaItemInfo[]
  total: number
  prevChapterId: number | null
  nextChapterId: number | null
}

export interface ImportTaskVO {
  id: number
  comicId: number
  sourceRef: string
  sourceType: string
  sourcePath: string
  status: string
  progress: number
  totalPages: number
  downloadedPages: number
  downloadMethod: string
  downloadSpeed: number
  etaSeconds: number
  batchId?: string
  errorMessage: string
  retryCount: number
  durationMs: number
  startTime: string
  endTime: string
  createdAt: string
}

export interface ImportStatusVO {
  taskId: number
  status: string
  progress: number
}

/** 目录预览节点类型（对齐后端 ScanNodeKind） */
export type ScanNodeKind = 'DIRECTORY' | 'ARCHIVE' | 'COMIC'

/** 目录扫描警告级别（对齐后端 ScanWarningSeverity） */
export type ScanWarningSeverity = 'INFO' | 'WARNING' | 'ERROR'

/** 目录扫描警告码（对齐后端 ScanWarningCode，枚举名必须保持一致） */
export type ScanWarningCode =
  | 'UNREADABLE_DIRECTORY'
  | 'PATH_TOO_LONG'
  | 'UNSAFE_PATH'
  | 'INVALID_NAME'
  | 'MIXED_DIRECTORY'
  | 'EMPTY_DIRECTORY'
  | 'UNSUPPORTED_FILE'
  | 'SYMLINK_SKIPPED'
  | 'LIMIT_EXCEEDED'

/** 阻断码：命中则对应漫画候选不可导入（importable=false） */
export const BLOCKING_SCAN_WARNING_CODES: readonly ScanWarningCode[] = [
  'UNREADABLE_DIRECTORY',
  'LIMIT_EXCEEDED',
]

export function isBlockingScanWarning(code: ScanWarningCode): boolean {
  return BLOCKING_SCAN_WARNING_CODES.includes(code)
}

/** 目录扫描警告（对齐后端 ScanWarningDTO） */
export interface ScanWarningVO {
  code: ScanWarningCode
  severity: ScanWarningSeverity
  message: string
  relativePath: string
}

/** 目录预览节点（对齐后端 ScanPreviewNodeDTO） */
export interface ScanPreviewNodeVO {
  name: string
  kind: ScanNodeKind
  relativePath: string
  fileCount: number
  children?: ScanPreviewNodeVO[]
  warnings?: ScanWarningVO[]
}

export interface ScanItemVO {
  name: string
  path: string
  imageCount: number
  kind?: ScanNodeKind | null
  relativePath?: string | null
  warnings?: ScanWarningVO[]
}

export interface ScanResultVO {
  parentPath: string
  total: number
  items: ScanItemVO[]
  preview?: ScanPreviewNodeVO[]
  warnings?: ScanWarningVO[]
}

export interface BatchImportRequest {
  sourceType: string
  sourcePaths: string[]
}

/** 导入任务列表查询参数 */
export interface ImportTaskQuery {
  page?: number
  size?: number
  status?: string
  batchId?: string
}

export interface FailedItem {
  sourcePath: string
  errorMessage: string
}

export interface BatchImportResultVO {
  batchId: string
  total: number
  succeeded: ImportTaskVO[]
  failed: FailedItem[]
}

export interface HistoryVO {
  comicId: number
  comicTitle: string
  coverUrl: string
  chapterId: number
  chapterNo: string
  pageNumber: number
  totalPages: number
  progressPercent: number
  updatedAt: string
}

export interface ComicMetadataDTO {
  title: string
  author?: string
  description?: string
  categoryId?: number | null
}

export interface ComicMetadataUpdateDTO {
  title: string
  author?: string
  description?: string
  categoryId?: number | null
}

export interface TagDTO {
  id: number
  name: string
}

export interface TagCreateDTO {
  name: string
}

export interface ComicTagUpdateDTO {
  tagIds: number[]
}

/** 批量更新漫画分类和标签 */
export interface BatchComicUpdateDTO {
  comicIds: number[]
  categoryId?: number | null
  addTagIds?: number[]
}

/** 批量更新结果 */
export interface BatchUpdateResultVO {
  total: number
  succeeded: number
  failed: FailedItem[]
}

export interface FailedItem {
  comicId: number
  title: string | null
  reason: string
}

export const STATUS_COLOR_MAP: Record<string, string> = {
  PENDING: 'info',
  PARSING: 'warning',
  IMPORTING: 'warning',
  DOWNLOADING: 'warning',
  EXTRACTING: 'warning',
  SUCCESS: 'success',
  FAILED: 'danger',
  CANCELLED: 'info',
}

// ========== Storage Management Domain ==========

/** HQ 状态 */
export type HqStatus = 'READY' | 'DELETED' | 'MIXED' | 'EMPTY' | 'PENDING' | 'MISSING'

/** LQ 状态 */
export type LqStatus = 'READY' | 'NOT_GENERATED' | 'MIXED' | 'EMPTY' | 'FAILED' | 'QUEUED' | 'GENERATING'

/** 存储漫画列表项 */
export interface ComicStorageItem {
  comicId: number
  title: string
  coverUrl: string
  totalSize: number
  hqSize: number
  lqSize: number
  hqStatus: HqStatus
  lqStatus: LqStatus
  mediaType: 'IMAGE' | 'VIDEO' | 'MIXED'
  transcodeStatus: 'NOT_NEEDED' | 'PENDING' | 'PROCESSING' | 'DONE' | 'FAILED'
  chapterCount: number
  pageCount: number
}

/** 存储章节列表项 */
export interface ChapterStorageItem {
  chapterId: number
  chapterNo: string
  title: string
  pageCount: number
  hqSize: number
  lqSize: number
  hqStatus: HqStatus
  lqStatus: LqStatus
  mediaType: 'IMAGE' | 'VIDEO' | 'MIXED'
}

/** 存储统计摘要 */
export interface StorageStats {
  totalBytes: number
  hqBytes: number
  lqBytes: number
  thumbBytes: number
  comicCount: number
}

/** 存储漫画查询参数 */
export interface ComicStorageQuery {
  page?: number
  size?: number
  hqStatus?: 'ALL' | 'HAS_HQ' | 'NO_HQ'
  lqStatus?: 'ALL' | 'NEEDS_LQ' | 'READY'
  sort?: 'totalSize' | 'hqSize' | 'lqSize' | 'title'
  order?: 'asc' | 'desc'
  keyword?: string
  category?: string
  tag?: string
}

/** 存储操作类型 */
export const StorageOperationType = {
  DeleteHQ: 'DELETE_HQ',
  GenerateLQ: 'GENERATE_LQ',
  TranscodeVideos: 'TRANSCODE_VIDEOS',
  RefreshMetadata: 'REFRESH_METADATA',
} as const

export type StorageOperationType =
  (typeof StorageOperationType)[keyof typeof StorageOperationType]

/** 存储操作参数 */
export interface StorageOperation {
  type: StorageOperationType
  comicId: number
  chapterId?: number
}

// ========== Export Domain ==========

/** 导出产物分卷（GET /api/storage/export/tasks/{taskId}/artifacts 返回） */
export interface ExportArtifactVO {
  /** 卷序号（0 起） */
  index: number
  /** 本地 ZIP 文件名 */
  fileName: string
  /** 文件字节数 */
  size: number
  /** 是否为最后一卷 */
  lastSegment: boolean
  /** 宿主机物理路径 */
  physicalPath: string
}

/** 导出任务（后端 ExportTaskVO，主键字段为 id、错误字段为 errorMsg） */
export interface ExportTaskVO {
  id: number
  comicId: number
  status: string // PENDING | RUNNING | SUCCESS | FAILED
  progress: number // 0-100
  outputRoot?: string
  outputPath?: string
  outputSize: number
  errorMsg?: string
  createdAt: string
  completedAt?: string
}

/** 存储操作统一提交结果（/api/storage/* 返回） */
export interface OperationSubmitResult {
  readonly taskId: number | null
  readonly taskType: string
  readonly status: string | null
  readonly itemCount: number
}

// ========== Recovery Domain ==========

/** 存储恢复任务 */
export interface RecoveryTaskVO {
  id: number
  status: string // PENDING | RUNNING | SUCCESS | FAILED
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

/** 目录扫描异步任务 */
export interface DirectoryScanTaskVO {
  id: number
  status: string // PENDING | SUCCESS | FAILED
  directoryPath: string
  totalItems: number
  result?: ScanResultVO | null
  errorMessage?: string
  createdAt: string
  startedAt?: string
  endedAt?: string
}

// ========== Export Domain ==========

export const EXPORT_STATUS_COLOR_MAP: Record<string, string> = {
  PENDING: 'info',
  RUNNING: 'warning',
  SUCCESS: 'success',
  FAILED: 'danger',
}

// ========== Management Domain ==========

/** 管理任务类型（后端 TaskType 枚举） */
export type ManagementTaskType =
  | 'IMPORT'
  | 'RECOVERY'
  | 'EXPORT'
  | 'DIRECTORY_SCAN'
  | 'LQ_GENERATE'
  | 'LQ_REGENERATE'
  | 'HQ_DELETE'
  | 'TRANSCODE'
  | 'METADATA_REFRESH'
  | 'METADATA_UPDATE'
  | 'COMIC_DELETE'
  | 'MEDIA_UPLOAD'
  | 'MEDIA_REPLACE'
  | 'MEDIA_TRASH'
  | 'CHAPTER_TRASH'
  | 'COMIC_RESTORE'
  | 'CHAPTER_RESTORE'
  | 'MEDIA_RESTORE'
  | 'COMIC_PURGE'
  | 'CHAPTER_PURGE'
  | 'MEDIA_PURGE'

/** 管理任务状态（后端 ManagementTaskStatus 枚举） */
export type ManagementTaskStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'CANCELLING'
  | 'CANCELLED'
  | 'SUCCEEDED'
  | 'PARTIALLY_SUCCEEDED'
  | 'FAILED'

/** 管理任务列表查询参数（后端 ManagementTaskController.listTasks） */
export interface ManagementTaskQuery {
  readonly page?: number
  readonly size?: number
  readonly type?: ManagementTaskType
  readonly status?: ManagementTaskStatus
  readonly batchId?: string
  readonly targetType?: string
  readonly targetId?: number
}

/** 统一管理任务（后端 ManagementTaskResponse，JSON 字段 isBatch） */
export interface ManagementTaskVO {
  readonly id: number
  readonly taskType: ManagementTaskType
  readonly operation: string
  readonly targetType: string
  readonly targetId: number | null
  readonly targetName: string | null
  readonly batchId: string | null
  readonly isBatch: boolean
  readonly status: ManagementTaskStatus
  readonly stage: string | null
  readonly progress: number | null
  readonly totalCount: number | null
  readonly successCount: number | null
  readonly failureCount: number | null
  readonly cancelledCount: number | null
  readonly errorMessage: string | null
  readonly attempt: number | null
  readonly version: number | null
  readonly createdAt: string
  readonly updatedAt: string
  readonly startedAt: string | null
  readonly completedAt: string | null
}

/** 管理任务目标项（后端 ManagementTaskItemResponse） */
export interface ManagementTaskItemVO {
  readonly id: number
  readonly taskId: number
  readonly targetType: string
  readonly targetId: number
  readonly operationType: ManagementTaskType
  readonly status: ManagementTaskStatus
  readonly attempt: number | null
  readonly progress: number | null
  readonly resultRefType: string | null
  readonly resultRefId: number | null
  readonly errorMessage: string | null
  readonly version: number | null
  readonly createdAt: string
  readonly updatedAt: string
  readonly startedAt: string | null
  readonly completedAt: string | null
}

/** 创建管理任务的目标项（后端 CreateManagementTaskRequest.TaskTarget） */
export interface ManagementTaskTarget {
  readonly targetType: string
  readonly targetId: number
  readonly operationType?: ManagementTaskType
}

/** 创建管理任务请求（后端 CreateManagementTaskRequest） */
export interface CreateManagementTaskRequest {
  readonly taskType: ManagementTaskType
  readonly operation: string
  readonly targetType?: string
  readonly batchId?: string
  readonly targets?: readonly ManagementTaskTarget[]
}

/** 上传会话状态（后端 UploadSessionStatus 枚举） */
export type UploadSessionState =
  | 'ACTIVE'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'FAILED'

/** 会话内单个文件状态（后端 UploadFileResponse） */
export interface UploadFileStatus {
  readonly fileId: string
  readonly storageName: string
  readonly receivedBytes: number
  readonly sizeBytes: number
  readonly complete: boolean
  readonly receivedRanges: string
}

/** 上传会话状态响应（后端 UploadSessionStatusResponse） */
export interface UploadSessionStatus {
  readonly sessionId: string
  readonly status: UploadSessionState
  readonly totalBytes: number
  readonly totalFiles: number
  readonly expiresAt: string
  readonly completedAt: string | null
  readonly files: readonly UploadFileStatus[]
}

/** 上传文件清单项（后端 CreateUploadSessionRequest.FileManifest） */
export interface UploadFileManifest {
  readonly fileId: string
  readonly name: string
  readonly contentType: string
  readonly size: number
  readonly sha256: string
}

/** 创建上传会话请求（后端 CreateUploadSessionRequest） */
export interface CreateUploadSessionRequest {
  readonly comicId: number
  readonly chapterId: number
  readonly replaceMediaId?: number | null
  readonly files: readonly UploadFileManifest[]
}

/** 创建上传会话响应（后端 CreateUploadSessionResponse） */
export interface CreateUploadSessionResult {
  readonly sessionId: string
  readonly chunkSize: number
  readonly expiresAt: string
  readonly totalBytes: number
  readonly files: readonly UploadFileStatus[]
}

/** 分片上传响应（后端 UploadChunkResponse） */
export interface UploadChunkResult {
  readonly fileId: string
  readonly receivedBytes: number
  readonly complete: boolean
  readonly receivedRanges: string
}

/** 上传会话完成响应（后端 UploadCompleteResponse） */
export interface UploadCompleteResult {
  readonly taskId: number | null
  readonly taskType: string
  readonly status: string | null
  readonly itemCount: number | null
  readonly mediaIds: readonly number[]
}

/** 永久清理二次确认请求（后端 PurgeRequest） */
export interface TrashPurgeRequest {
  readonly token: string
}

/** 回收对账报告条目（后端 TrashReconcileReport.EntryReport） */
export interface ReconcileEntry {
  readonly rootKey: string
  readonly sourceRelativePath: string
  readonly sourceExists: boolean
  readonly trashExists: boolean
  readonly state: string
}

/** 回收对账报告（后端 TrashReconcileReport） */
export interface ReconcileResult {
  readonly targetType: string
  readonly targetId: number
  readonly dbStatus: string | null
  readonly manifestTaskId: number | null
  readonly manifestStatus: string | null
  readonly consistent: boolean
  readonly entries: readonly ReconcileEntry[]
}

/** 批量选择：显式 ID 列表 */
export interface BatchSelectionIds {
  readonly type: 'IDS'
  readonly ids: readonly number[]
}

/** 批量选择：筛选条件 + 排除项 */
export interface BatchSelectionFilter {
  readonly type: 'FILTER'
  readonly query?: ComicListQuery
  readonly excludedIds?: readonly number[]
}

/** 批量目标选择（后端 BatchSelectionVO 判别联合） */
export type BatchSelection = BatchSelectionIds | BatchSelectionFilter

/** 批量操作负载（后端 BatchOperationPayloadDTO） */
export interface BatchOperationPayload {
  readonly categoryId?: number | null
  readonly addTagIds?: readonly number[]
  readonly title?: string
  readonly author?: string
  readonly description?: string
}

/** 被阻止的批量目标（后端 BlockedBatchItem） */
export interface BlockedBatchItem {
  readonly comicId: number
  readonly reasonCode: string
  readonly reason: string
}

/** 批量选择预览结果（后端 BatchPreviewResponse） */
export interface BatchPreviewResult {
  readonly operation: ManagementTaskType
  readonly selectedCount: number
  readonly eligibleCount: number
  readonly blocked: readonly BlockedBatchItem[]
  readonly dangerous: boolean
  readonly previewToken: string | null
  readonly expiresAt: string | null
}

/** 批量操作提交请求（后端 BatchOperationRequest） */
export interface BatchSubmitRequest {
  readonly operation: ManagementTaskType
  readonly selection: BatchSelection
  readonly payload?: BatchOperationPayload | null
  readonly previewToken?: string | null
}

/** 批量任务创建结果（后端 BatchCreateResponse） */
export interface BatchCreateResult {
  readonly task: ManagementTaskVO
  readonly selectedCount: number
  readonly eligibleCount: number
  readonly blocked: readonly BlockedBatchItem[]
}

/** 允许操作查询结果（后端 AllowedOperations） */
export interface MediaOperationResult {
  readonly allowed: readonly string[]
  readonly blockedReasons: Readonly<Record<string, string>>
}

/** Outbox 积压统计（后端 OutboxStatsDTO） */
export interface OutboxStats {
  readonly pending: number
  readonly failed: number
  readonly total: number
}

/** MQ 积压与死信统计（后端 MqStatsDTO），覆盖消费层失败与堆积 */
export interface MqStats {
  readonly available: boolean
  readonly dlqTotal: number
  readonly dlqQueues: number
  readonly queuedTotal: number
  readonly queues: readonly MqQueueStat[]
}

/** 单队列积压快照（后端 MqQueueStat） */
export interface MqQueueStat {
  readonly name: string
  readonly messages: number
  readonly consumers: number
  readonly dlq: boolean
}

/** 目录管理请求（创建/重命名/移动/重排，字段按操作取用） */
export interface CatalogManagementRequest {
  readonly title?: string
  readonly parentId?: number | null
  readonly sortOrder?: number
}

/** 章节管理请求（创建/重命名/移动/重排，字段按操作取用） */
export interface ChapterManagementRequest {
  readonly title?: string
  readonly chapterNo?: string
  readonly catalogId?: number | null
  readonly targetGlobalOrder?: number
}

/** 目录视图（后端 CatalogVO） */
export interface CatalogVO {
  readonly id: number
  readonly comicId: number
  readonly parentId: number | null
  readonly title: string
  readonly sortOrder: number | null
}

/** 章节管理视图（后端 ChapterVO，区别于阅读域的 ChapterVO） */
export interface ChapterManagementVO {
  readonly id: number
  readonly comicId: number
  readonly catalogId: number | null
  readonly title: string
  readonly chapterNo: string | null
  readonly pageCount: number | null
  readonly sortOrder: number | null
  readonly globalOrder: number | null
  readonly status: string | null
}

/** 媒体重排请求（后端 MediaReorderRequest） */
export interface MediaReorderRequest {
  readonly mediaIds: readonly number[]
}

/** 媒体重排结果项（后端 MediaReorderItem） */
export interface MediaReorderItem {
  readonly mediaId: number
  readonly pageNumber: number | null
}

/** 媒体重排结果（后端 MediaReorderResponse） */
export interface MediaReorderResult {
  readonly items: readonly MediaReorderItem[]
}
