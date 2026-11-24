export interface PageBlock<T> {
  key: string
  page: number
  items: T[]
}

/**
 * 分页式无限滚动的一页数据。key 同时包含每页大小，避免切换到不同页大小后
 * 复用上一套已验证的页块高度；页块本身仍以真实页码作为语义页码。
 */
export function buildPageBlocks<T>(items: T[], page: number, pageSize: number): PageBlock<T>[] {
  const size = Math.max(1, Math.floor(pageSize))
  const base = Math.max(1, Math.floor(page))
  const count = Math.max(1, Math.ceil(items.length / size))
  return Array.from({ length: count }, (_, index) => ({
    key: `${size}:${base + index}`,
    page: base + index,
    items: items.slice(index * size, (index + 1) * size),
  }))
}
