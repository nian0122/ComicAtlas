import type { CatalogNode, ChapterRef } from '@/entities/comic'

export interface ChapterSearchResult {
  readonly chapter: ChapterRef
  readonly catalogPath: readonly string[]
}

export interface ChapterSearchTreeResult {
  readonly tree: CatalogNode[]
  readonly results: readonly ChapterSearchResult[]
  readonly expandedNodePaths: readonly string[]
}
