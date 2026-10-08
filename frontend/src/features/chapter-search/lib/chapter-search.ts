import type { CatalogNode, ChapterRef } from '@/entities/comic'
import type { ChapterSearchResult, ChapterSearchTreeResult } from './chapter-search.types'

function normalizeText(value: string | null | undefined): string {
  return (value ?? '').trim().toLocaleLowerCase()
}

function keySegmentOf(node: CatalogNode, index: number): string {
  return node.id != null ? String(node.id) : `${index}:${node.title ?? ''}`
}

function chapterLabel(chapter: ChapterRef): string {
  return chapter.chapterNo ? `第${chapter.chapterNo}话` : ''
}

/** 章节列表筛选与阅读导航共用的唯一匹配规则。 */
export function chapterMatchesSearch(chapter: ChapterRef, catalogPath: readonly string[], keyword: string): boolean {
  const normalizedKeyword = normalizeText(keyword)
  if (!normalizedKeyword) return false
  return normalizeText([chapterLabel(chapter), chapter.chapterNo, chapter.title, ...catalogPath].join(' ')).includes(
    normalizedKeyword,
  )
}

function collectMatchingChapters(
  nodes: CatalogNode[],
  keyword: string,
  catalogPath: readonly string[] = [],
  results: ChapterSearchResult[] = [],
): ChapterSearchResult[] {
  nodes.forEach((node) => {
    const nextCatalogPath = node.title ? [...catalogPath, node.title] : catalogPath
    node.chapters.forEach((chapter) => {
      if (chapterMatchesSearch(chapter, nextCatalogPath, keyword)) {
        results.push({ chapter, catalogPath: nextCatalogPath })
      }
    })
    collectMatchingChapters(node.children, keyword, nextCatalogPath, results)
  })
  return results
}

export function searchCatalogChapters(tree: CatalogNode[], keyword: string): ChapterSearchResult[] {
  const normalizedKeyword = normalizeText(keyword)
  if (!normalizedKeyword) return []

  return collectMatchingChapters(tree, normalizedKeyword).sort(
    (left, right) =>
      (left.chapter.globalOrder ?? Number.MAX_SAFE_INTEGER) - (right.chapter.globalOrder ?? Number.MAX_SAFE_INTEGER),
  )
}

function countChapters(node: CatalogNode): number {
  return node.chapters.length + node.children.reduce((total, child) => total + countChapters(child), 0)
}

function filterNode(
  node: CatalogNode,
  nodePath: string,
  matchingChapterIds: ReadonlySet<number>,
): { node: CatalogNode | null; expandedNodePaths: string[] } {
  const expandedNodePaths: string[] = []
  const chapters = node.chapters.filter((chapter) => matchingChapterIds.has(chapter.id))

  const children: CatalogNode[] = []
  node.children.forEach((child, index) => {
    const childPath = `${nodePath}/${keySegmentOf(child, index)}`
    const childResult = filterNode(child, childPath, matchingChapterIds)
    if (childResult.node) children.push(childResult.node)
    expandedNodePaths.push(...childResult.expandedNodePaths)
  })

  if (chapters.length > 0 || children.length > 0) {
    if (node.title) expandedNodePaths.push(nodePath)
    return {
      node: { ...node, chapters, children },
      expandedNodePaths,
    }
  }
  return { node: null, expandedNodePaths }
}

export function filterChapterTree(tree: CatalogNode[], keyword: string): ChapterSearchTreeResult {
  const normalizedKeyword = normalizeText(keyword)
  if (!normalizedKeyword) return { tree, results: [], expandedNodePaths: [] }

  const results = searchCatalogChapters(tree, normalizedKeyword)
  const matchingChapterIds = new Set(results.map((item) => item.chapter.id))
  const expandedNodePaths: string[] = []
  const filteredTree: CatalogNode[] = []
  tree.forEach((node, index) => {
    const nodeResult = filterNode(node, `/${keySegmentOf(node, index)}`, matchingChapterIds)
    if (nodeResult.node) filteredTree.push(nodeResult.node)
    expandedNodePaths.push(...nodeResult.expandedNodePaths)
  })
  return { tree: filteredTree, results, expandedNodePaths }
}

export function countTreeChapters(tree: CatalogNode[]): number {
  return tree.reduce((total, node) => total + countChapters(node), 0)
}
