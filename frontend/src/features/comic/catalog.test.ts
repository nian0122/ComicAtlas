import { describe, expect, it } from 'vitest'
import { chapterOrder, collectChapters, countChapters, findChapterById } from './catalog'
import type { CatalogNode } from '@/entities/comic/types'

const chapter = (id: number, globalOrder: number) => ({
  id,
  chapterNo: String(id),
  title: `章节 ${id}`,
  globalOrder,
  pageCount: 10,
})

const catalog: CatalogNode[] = [
  {
    id: null,
    title: null,
    globalOrder: null,
    chapters: [chapter(1, 2)],
    children: [
      {
        id: 10,
        title: '卷一',
        globalOrder: 1,
        chapters: [chapter(2, 1)],
        children: [],
      },
    ],
  },
]

describe('漫画目录工具', () => {
  it('递归查找、收集并统计章节', () => {
    expect(findChapterById(catalog, 2)?.title).toBe('章节 2')
    expect(collectChapters(catalog).map((item) => item.id)).toEqual([1, 2])
    expect(countChapters(catalog[0])).toBe(2)
  })

  it('缺少全局顺序的章节排在最后', () => {
    expect(chapterOrder({ ...chapter(3, 3), globalOrder: null } as never)).toBe(Number.MAX_SAFE_INTEGER)
    expect(chapterOrder(chapter(4, 4))).toBe(4)
  })
})
