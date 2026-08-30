import type { CatalogNode, ChapterRef } from '@/types'

export interface ChapterSearchItem {
  readonly chapter: ChapterRef
  readonly catalogPath: readonly string[]
  readonly searchableText: string
  readonly globalOrder: number
}

export interface ChapterSearchResult {
  readonly chapter: ChapterRef
  readonly catalogPath: readonly string[]
}

export interface ChapterSearchTreeResult {
  readonly tree: CatalogNode[]
  readonly results: readonly ChapterSearchResult[]
  readonly expandedNodePaths: readonly string[]
}
