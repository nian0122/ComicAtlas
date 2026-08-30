import type { CatalogNode, ChapterRef } from '@/entities/comic/types'
import type {
  ChapterSearchItem,
  ChapterSearchResult,
  ChapterSearchTreeResult,
} from './chapter-search.types'

function normalizeText(value: string | null | undefined): string {
  return (value ?? '').trim().toLocaleLowerCase()
}

function keySegmentOf(node: CatalogNode, index: number): string {
  return node.id != null ? String(node.id) : `${index}:${node.title ?? ''}`
}

function chapterLabel(chapter: ChapterRef): string {
  return chapter.chapterNo ? `第${chapter.chapterNo}话` : ''
}

function collectChapters(
  nodes: CatalogNode[],
  catalogPath: readonly string[] = [],
  output: ChapterSearchItem[] = [],
): ChapterSearchItem[] {
  nodes.forEach((node) => {
    const nextCatalogPath = node.title ? [...catalogPath, node.title] : catalogPath
    node.chapters.forEach((chapter) => {
      const searchableText = normalizeText(
        [chapterLabel(chapter), chapter.chapterNo, chapter.title, ...nextCatalogPath].join(' '),
      )
      output.push({
        chapter,
        catalogPath: nextCatalogPath,
        searchableText,
        globalOrder: chapter.globalOrder ?? Number.MAX_SAFE_INTEGER,
      })
    })
    collectChapters(node.children, nextCatalogPath, output)
  })
  return output
}

export function buildChapterSearchIndex(tree: CatalogNode[]): ChapterSearchItem[] {
  return collectChapters(tree).sort((left, right) => left.globalOrder - right.globalOrder)
}

export function searchChapters(
  index: readonly ChapterSearchItem[],
  keyword: string,
): ChapterSearchResult[] {
  const normalizedKeyword = normalizeText(keyword)
  if (!normalizedKeyword) return []

  return index
    .filter((item) => item.searchableText.includes(normalizedKeyword))
    .map((item) => ({ chapter: item.chapter, catalogPath: item.catalogPath }))
}

function countChapters(node: CatalogNode): number {
  return node.chapters.length + node.children.reduce((total, child) => total + countChapters(child), 0)
}

function filterNode(
  node: CatalogNode,
  nodePath: string,
  catalogPath: readonly string[],
  normalizedKeyword: string,
): { node: CatalogNode | null; results: ChapterSearchResult[]; expandedNodePaths: string[] } {
  const nextCatalogPath = node.title ? [...catalogPath, node.title] : catalogPath
  const catalogMatches = Boolean(node.title && normalizeText(nextCatalogPath.join(' ')).includes(normalizedKeyword))
  const results: ChapterSearchResult[] = []
  const expandedNodePaths: string[] = []
  const chapters = catalogMatches
    ? node.chapters
    : node.chapters.filter((chapter) =>
        normalizeText(
          [chapterLabel(chapter), chapter.chapterNo, chapter.title, ...nextCatalogPath].join(' '),
        ).includes(normalizedKeyword),
      )

  chapters.forEach((chapter) => results.push({ chapter, catalogPath: nextCatalogPath }))

  const children: CatalogNode[] = []
  node.children.forEach((child, index) => {
    const childPath = `${nodePath}/${keySegmentOf(child, index)}`
    const childResult = catalogMatches
      ? { node: child, results: collectResultItems(child, nextCatalogPath), expandedNodePaths: collectNodePaths(child, childPath) }
      : filterNode(child, childPath, nextCatalogPath, normalizedKeyword)
    if (childResult.node) children.push(childResult.node)
    results.push(...childResult.results)
    expandedNodePaths.push(...childResult.expandedNodePaths)
  })

  if (catalogMatches || chapters.length > 0 || children.length > 0) {
    if (node.title) expandedNodePaths.push(nodePath)
    return {
      node: { ...node, chapters, children },
      results,
      expandedNodePaths,
    }
  }
  return { node: null, results, expandedNodePaths }
}

function collectResultItems(node: CatalogNode, catalogPath: readonly string[]): ChapterSearchResult[] {
  const nextCatalogPath = node.title ? [...catalogPath, node.title] : catalogPath
  return [
    ...node.chapters.map((chapter) => ({ chapter, catalogPath: nextCatalogPath })),
    ...node.children.flatMap((child) => collectResultItems(child, nextCatalogPath)),
  ]
}

function collectNodePaths(node: CatalogNode, nodePath: string): string[] {
  const paths = node.title ? [nodePath] : []
  return node.children.flatMap((child, index) =>
    collectNodePaths(child, `${nodePath}/${keySegmentOf(child, index)}`),
  ).concat(paths)
}

export function filterChapterTree(tree: CatalogNode[], keyword: string): ChapterSearchTreeResult {
  const normalizedKeyword = normalizeText(keyword)
  if (!normalizedKeyword) return { tree, results: [], expandedNodePaths: [] }

  const results: ChapterSearchResult[] = []
  const expandedNodePaths: string[] = []
  const filteredTree: CatalogNode[] = []
  tree.forEach((node, index) => {
    const nodeResult = filterNode(node, `/${keySegmentOf(node, index)}`, [], normalizedKeyword)
    if (nodeResult.node) filteredTree.push(nodeResult.node)
    results.push(...nodeResult.results)
    expandedNodePaths.push(...nodeResult.expandedNodePaths)
  })

  results.sort((left, right) => (left.chapter.globalOrder ?? Number.MAX_SAFE_INTEGER) - (right.chapter.globalOrder ?? Number.MAX_SAFE_INTEGER))
  return { tree: filteredTree, results, expandedNodePaths }
}

export function countTreeChapters(tree: CatalogNode[]): number {
  return tree.reduce((total, node) => total + countChapters(node), 0)
}
