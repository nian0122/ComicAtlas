import { describe, expect, it } from 'vitest'
import { filterStructureRows, flattenCatalogOptions, toStructureRows } from './structure'
import type { CatalogNode } from '@/entities/comic/types'

const catalogNode: CatalogNode = {
  id: 1,
  title: '卷一',
  globalOrder: 1,
  chapters: [
    { id: 2, chapterNo: '1', title: '第一话', globalOrder: 1, pageCount: 20 },
  ],
  children: [
    {
      id: 3,
      title: '番外',
      globalOrder: 2,
      chapters: [],
      children: [],
    },
  ],
}

describe('漫画结构工具', () => {
  it('把目录和章节转换为可展示的树行', () => {
    const rows = toStructureRows(catalogNode)
    expect(rows).toHaveLength(1)
    expect(rows[0].kind).toBe('CATALOG')
    expect(rows[0].children?.map((row) => row.kind)).toEqual(['CHAPTER', 'CATALOG'])
    expect(flattenCatalogOptions(rows)).toEqual([
      { id: 1, title: '卷一' },
      { id: 3, title: '番外' },
    ])
  })

  it('过滤目录时保留命中的父节点和子节点', () => {
    const rows = toStructureRows(catalogNode)
    expect(filterStructureRows(rows, '第一话')).toHaveLength(1)
    expect(filterStructureRows(rows, '第一话')[0].children).toHaveLength(1)
    expect(filterStructureRows(rows, '不存在')).toEqual([])
  })
})
