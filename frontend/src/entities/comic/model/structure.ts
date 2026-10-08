import type { CatalogNode } from '@/entities/comic/model/types'

export type CatalogAction = 'create' | 'rename' | 'move' | 'delete'
export type ChapterAction = 'create' | 'rename' | 'move' | 'trash'

export interface StructureRow {
  readonly key: string
  readonly kind: 'CATALOG' | 'CHAPTER'
  readonly id: number
  readonly title: string
  readonly chapterNo?: string
  readonly order: number | null
  readonly status: string | null
  readonly parentCatalogId?: number | null
  readonly children?: readonly StructureRow[]
}

export const CATALOG_ACTIONS = [
  { value: 'create', label: '新建目录' },
  { value: 'rename', label: '重命名目录' },
  { value: 'move', label: '移动目录' },
  { value: 'delete', label: '删除目录' },
] as const

export function countRows(rows: readonly StructureRow[], kind: StructureRow['kind']): number {
  return rows.reduce(
    (count, row) => count + (row.kind === kind ? 1 : 0) + (row.children ? countRows(row.children, kind) : 0),
    0,
  )
}

export function toStructureRows(node: CatalogNode, parentCatalogId: number | null = null): readonly StructureRow[] {
  const childCatalogRows = node.children.map((child) => toStructureRows(child, node.id)[0])
  const chapterRows = node.chapters.map((chapter) => ({
    key: `chapter-${chapter.id}`,
    kind: 'CHAPTER' as const,
    id: chapter.id,
    title: chapter.title,
    chapterNo: chapter.chapterNo,
    order: chapter.sortOrder,
    status: chapter.status ?? null,
    parentCatalogId: node.id,
  }))
  const children = [...childCatalogRows, ...chapterRows].sort(
    (left, right) => (left.order ?? Number.MAX_SAFE_INTEGER) - (right.order ?? Number.MAX_SAFE_INTEGER),
  )

  if (node.id === null) return children
  return [
    {
      key: `catalog-${node.id}`,
      kind: 'CATALOG',
      id: node.id,
      title: node.title ?? '未命名目录',
      order: node.sortOrder ?? null,
      status: null,
      parentCatalogId,
      children,
    },
  ]
}

export function flattenCatalogOptions(rows: readonly StructureRow[]): readonly { id: number; title: string }[] {
  return rows.flatMap((row) =>
    row.kind === 'CATALOG' ? [{ id: row.id, title: row.title }, ...flattenCatalogOptions(row.children ?? [])] : [],
  )
}

export function filterStructureRows(rows: readonly StructureRow[], keyword: string): readonly StructureRow[] {
  if (!keyword) return rows
  return rows.flatMap((row) => {
    const children = row.children ? filterStructureRows(row.children, keyword) : []
    return row.title.toLowerCase().includes(keyword) || children.length > 0 ? [{ ...row, children }] : []
  })
}

export function findStructureRow(rows: readonly StructureRow[], chapterId: number): StructureRow | null {
  for (const row of rows) {
    if (row.kind === 'CHAPTER' && row.id === chapterId) return row
    const match = row.children ? findStructureRow(row.children, chapterId) : null
    if (match) return match
  }
  return null
}
