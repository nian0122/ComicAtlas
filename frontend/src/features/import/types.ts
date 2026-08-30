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

export type ScanNodeKind = 'DIRECTORY' | 'ARCHIVE' | 'COMIC'
export type ScanWarningSeverity = 'INFO' | 'WARNING' | 'ERROR'
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

export const BLOCKING_SCAN_WARNING_CODES: readonly ScanWarningCode[] = [
  'UNREADABLE_DIRECTORY',
  'LIMIT_EXCEEDED',
]

export function isBlockingScanWarning(code: ScanWarningCode): boolean {
  return BLOCKING_SCAN_WARNING_CODES.includes(code)
}

export interface ScanWarningVO {
  code: ScanWarningCode
  severity: ScanWarningSeverity
  message: string
  relativePath: string
}

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

export interface ImportTaskQuery {
  page?: number
  size?: number
  status?: string
  batchId?: string
}

export interface ImportFailedItem {
  sourcePath: string
  errorMessage: string
}

export interface BatchImportResultVO {
  batchId: string
  total: number
  succeeded: ImportTaskVO[]
  failed: ImportFailedItem[]
}

export interface DirectoryScanTaskVO {
  id: number
  status: string
  directoryPath: string
  totalItems: number
  result?: ScanResultVO | null
  errorMessage?: string
  createdAt: string
  startedAt?: string
  endedAt?: string
}
