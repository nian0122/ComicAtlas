/**
 * 后端统一响应包装 Result<T>（T17）
 *
 * 所有管理端点都返回 `{ code, message, data }`；code !== 200 视为业务失败。
 * 边界解析由 services/management/http.ts 显式处理，这里只定义类型契约。
 */

/** 成功/失败统一包装 */
export interface ApiResult<T> {
  readonly code: number
  readonly message: string
  readonly data: T
}

/** 失败响应体：可携带 reasonCode（幂等冲突/预览令牌过期等业务原因） */
export interface ApiErrorBody extends ApiResult<unknown> {
  readonly reasonCode?: string
}
