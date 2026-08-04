import axios, { AxiosError, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import type { ApiErrorBody, ApiResult } from '@/types/management/api'

/**
 * 管理领域显式边界 HTTP 客户端（T17）
 *
 * 与单体 services/api.ts 的隐式 interceptor 解耦：这里不依赖响应拦截器，
 * 每个请求显式解析 `{ code, message, data }` 包装并抛出带类型字段的 ApiError。
 */

/** 类型化错误：携带 HTTP status / 业务 code / reasonCode / 是否被中止 */
export class ApiError extends Error {
  readonly status: number | null
  readonly code: number | null
  readonly reasonCode: string | null
  readonly body: ApiErrorBody | null
  readonly aborted: boolean

  constructor(
    message: string,
    opts: {
      readonly status?: number | null
      readonly code?: number | null
      readonly reasonCode?: string | null
      readonly body?: ApiErrorBody | null
      readonly aborted?: boolean
    } = {},
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = opts.status ?? null
    this.code = opts.code ?? null
    this.reasonCode = opts.reasonCode ?? null
    this.body = opts.body ?? null
    this.aborted = opts.aborted ?? false
  }
}

/** 请求是否因 AbortController 取消（axios 以 ERR_CANCELED 表达） */
export function isAbortError(err: unknown): boolean {
  if (err instanceof ApiError) return err.aborted
  if (err && typeof err === 'object' && 'code' in err) {
    return (err as { code?: unknown }).code === 'ERR_CANCELED'
  }
  return false
}

/** 提取用户可读错误信息（ApiError 优先，未知错误回退文案） */
export function toErrorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError && err.message) return err.message
  if (err instanceof Error && err.message) return err.message
  return fallback
}

function isApiResult(raw: unknown): raw is ApiResult<unknown> {
  if (!raw || typeof raw !== 'object') return false
  const obj = raw as { code?: unknown; message?: unknown }
  return typeof obj.code === 'number' && typeof obj.message === 'string'
}

function parseErrorBody(body: unknown): ApiErrorBody | null {
  if (!body || typeof body !== 'object') return null
  const obj = body as { code?: unknown; message?: unknown; data?: unknown; reasonCode?: unknown }
  if (typeof obj.code !== 'number' || typeof obj.message !== 'string') return null
  return {
    code: obj.code,
    message: obj.message,
    reasonCode: typeof obj.reasonCode === 'string' ? obj.reasonCode : undefined,
    data: obj.data,
  }
}

const http = axios.create({ baseURL: '/api', timeout: 30_000 })

/** 显式解析响应包装；非 2xx / code!==200 抛 ApiError；中止抛 aborted=true 的 ApiError */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  let response: AxiosResponse<unknown>
  try {
    response = await http.request(config)
  } catch (err: unknown) {
    if (isAbortError(err)) {
      throw new ApiError('请求已取消', { aborted: true })
    }
    if (err instanceof ApiError) throw err
    const axErr = err as AxiosError<unknown>
    const body = parseErrorBody(axErr.response?.data)
    const status = axErr.response?.status ?? null
    throw new ApiError(body?.message ?? axErr.message ?? '请求失败', {
      status,
      code: body?.code ?? status,
      reasonCode: body?.reasonCode ?? null,
      body,
    })
  }

  const raw = response.data
  if (isApiResult(raw)) {
    if (raw.code !== 200) {
      const body = parseErrorBody(raw)
      throw new ApiError(raw.message, {
        code: raw.code,
        reasonCode: body?.reasonCode ?? null,
        body,
      })
    }
    return raw.data as T
  }
  return raw as T
}
