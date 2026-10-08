import axios, { type AxiosError, type AxiosInstance } from 'axios'

/** 后端 Result 业务成功码。拦截器会把成功响应解包成 AxiosResponse<T>。 */
const RESULT_OK_CODE = 200

export const api: AxiosInstance = axios.create({ baseURL: '/api' })

api.interceptors.response.use(
  (response) => {
    const data = response.data
    if (data && typeof data === 'object' && 'code' in data) {
      if (data.code !== RESULT_OK_CODE) {
        const message = typeof data.message === 'string' && data.message ? data.message : '请求失败'
        return Promise.reject(new Error(message))
      }
      if ('data' in data) response.data = data.data
    }
    return response
  },
  (error) => Promise.reject(error),
)

/** 提取后端统一错误消息，供页面和领域服务复用。 */
export function getApiErrorMessage(error: unknown, fallback = '请求失败'): string {
  if (axios.isAxiosError(error)) {
    const message = (error as AxiosError<{ message?: string }>).response?.data?.message
    return message || fallback
  }
  return error instanceof Error && error.message ? error.message : fallback
}
