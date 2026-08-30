export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  pages: number
}
