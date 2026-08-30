import type { CatalogNode, ChapterRef } from '@/entities/comic/types'

export function findChapterById(nodes: readonly CatalogNode[], chapterId: number): ChapterRef | null {
  for (const node of nodes) {
    const chapter = node.chapters?.find((item) => item.id === chapterId)
    if (chapter) return chapter
    const nestedChapter = findChapterById(node.children ?? [], chapterId)
    if (nestedChapter) return nestedChapter
  }
  return null
}

/** 递归收集全部章节（含子目录），用于按全局顺序定位首章。 */
export function collectChapters(nodes: readonly CatalogNode[]): ChapterRef[] {
  const chapters: ChapterRef[] = []
  for (const node of nodes) {
    chapters.push(...(node.chapters ?? []))
    chapters.push(...collectChapters(node.children ?? []))
  }
  return chapters
}

/** 阅读顺序锚点；缺少顺序的章节排在最后。 */
export function chapterOrder(chapter: ChapterRef): number {
  return chapter.globalOrder ?? Number.MAX_SAFE_INTEGER
}

export function countChapters(node: CatalogNode): number {
  return (node.chapters?.length ?? 0) + (node.children ?? []).reduce(
    (count, child) => count + countChapters(child),
    0,
  )
}
