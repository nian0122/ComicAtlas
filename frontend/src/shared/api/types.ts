export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  pages: number
}

/** 管理端异步操作提交结果。 */
export interface OperationSubmitResult {
  readonly taskId: number | null
  readonly taskType: string
  readonly status: string | null
  readonly itemCount: number
}
