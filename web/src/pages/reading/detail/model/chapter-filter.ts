import type { ComicDetailVO, Chapter } from '@/entities/comic'
type Catalog = ComicDetailVO['catalogs'][number]
export function catalogLabels(catalogs: Catalog[]) {
  const entries = new Map(catalogs.map((item) => [item.id, item]))
  return catalogs.map((item) => {
    const labels = [item.title],
      seen = new Set([item.id])
    let parent = item.parentId
    while (parent && !seen.has(parent)) {
      seen.add(parent)
      const node = entries.get(parent)
      if (!node) break
      labels.unshift(node.title)
      parent = node.parentId
    }
    return { id: item.id, label: labels.join(' / ') }
  })
}
export function filterChapters(
  chapters: Chapter[],
  catalogs: Catalog[],
  query: string,
  catalog: string,
  anchor: string | null,
  unread: boolean,
  reverse: boolean,
) {
  const ordered = [...chapters].sort((a, b) => a.globalOrder - b.globalOrder),
    index = ordered.findIndex((item) => item.id === anchor)
  const parents = new Map(catalogs.map((item) => [item.id, item.parentId])),
    keyword = query.trim().toLocaleLowerCase()
  return ordered
    .filter((item, position) => {
      if (unread && index >= 0 && position < index) return false
      if (keyword && !`${item.globalOrder} ${item.title}`.toLocaleLowerCase().includes(keyword)) return false
      if (!catalog) return true
      let parent = item.catalogId
      const seen = new Set<string>()
      while (parent && !seen.has(parent)) {
        if (parent === catalog) return true
        seen.add(parent)
        parent = parents.get(parent) ?? null
      }
      return false
    })
    .sort((a, b) => (reverse ? b.globalOrder - a.globalOrder : a.globalOrder - b.globalOrder))
}
