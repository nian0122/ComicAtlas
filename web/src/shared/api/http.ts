export class ApiError extends Error {
  readonly status: number
  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}
type Query = Record<string, string | number | boolean | readonly string[] | null | undefined>
/** 只接受统一响应协议，直接返回业务数据。 */
export async function request<T>(
  path: string,
  options: {
    method?: string
    body?: unknown
    binary?: { body: Blob; headers: Record<string, string> }
    query?: Query
    signal?: AbortSignal
    keepalive?: boolean
    response?: 'empty'
    idempotencyKey?: string
  } = {},
): Promise<T> {
  if (options.binary && options.body !== undefined) throw new Error('请求不能同时包含 JSON 与二进制正文')
  const url = new URL(`/api/v1${path}`, window.location.origin)
  for (const [name, value] of Object.entries(options.query ?? {})) {
    if (value === undefined || value === null || value === '') continue
    if (Array.isArray(value)) value.forEach((item) => url.searchParams.append(name, item))
    else url.searchParams.set(name, String(value))
  }
  const response = await fetch(url, {
    method: options.method ?? 'GET',
    credentials: 'same-origin',
    headers: {
      ...(options.binary
        ? { 'Content-Type': 'application/octet-stream', ...options.binary.headers }
        : options.body === undefined
          ? {}
          : { 'Content-Type': 'application/json' }),
      ...(options.idempotencyKey ? { 'Idempotency-Key': options.idempotencyKey } : {}),
    },
    body: options.binary ? options.binary.body : options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal ?? AbortSignal.timeout(30000),
    keepalive: options.keepalive,
  })
  if (response.ok && options.response === 'empty') {
    if (response.status !== 204) throw new ApiError('接口应返回 204 空响应', response.status)
    return undefined as T
  }
  let body: unknown
  try {
    body = await response.json()
  } catch {
    throw new ApiError(response.ok ? '接口响应不是有效 JSON' : `请求失败（${response.status}）`, response.status)
  }
  const envelope =
    body !== null && typeof body === 'object' && !Array.isArray(body) ? (body as Record<string, unknown>) : null
  if (!response.ok)
    throw new ApiError(
      envelope?.error &&
        typeof envelope.error === 'object' &&
        'message' in envelope.error &&
        typeof envelope.error.message === 'string'
        ? envelope.error.message
        : `请求失败（${response.status}）`,
      response.status,
    )
  if (!envelope || Object.keys(envelope).length !== 1 || !Object.hasOwn(envelope, 'data'))
    throw new ApiError('接口响应不符合 Go API 的 data 协议', response.status)
  return envelope.data as T
}
export function getApiErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}
