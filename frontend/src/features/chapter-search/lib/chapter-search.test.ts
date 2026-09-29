import { describe, expect, it } from 'vitest'
import type { CatalogNode, ChapterRef } from '@/entities/comic'
import { filterChapterTree, searchCatalogChapters } from './chapter-search'

function chapter(id: number, chapterNo: string, title: string, globalOrder: number): ChapterRef {
  return { id, chapterNo, title, globalOrder, sortOrder: globalOrder, pageCount: 10 }
}

const tree: CatalogNode[] = [
  {
    id: 1,
    title: '正篇',
    sortOrder: 0,
    children: [],
    chapters: [chapter(12, '12', '重逢', 2), chapter(2, '2', '相遇', 1)],
  },
  {
    id: 3,
    title: '番外',
    sortOrder: 1,
    children: [],
    chapters: [chapter(21, '特典', '幕后', 3)],
  },
]

describe('漫画章节搜索', () => {
  it('目录过滤和阅读导航使用相同匹配结果并按全书顺序排列', () => {
    const treeResults = filterChapterTree(tree, '正篇').results.map((item) => item.chapter.id)
    const navigationResults = searchCatalogChapters(tree, '正篇').map((item) => item.chapter.id)

    expect(treeResults).toEqual([2, 12])
    expect(navigationResults).toEqual(treeResults)
  })

  it('支持编号格式、标题和目录名称匹配', () => {
    expect(searchCatalogChapters(tree, '第12话').map((item) => item.chapter.id)).toEqual([12])
    expect(searchCatalogChapters(tree, '幕后').map((item) => item.chapter.id)).toEqual([21])
    expect(searchCatalogChapters(tree, '番外').map((item) => item.chapter.id)).toEqual([21])
  })
})
