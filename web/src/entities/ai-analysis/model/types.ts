export interface AnalysisTerm {
  id: string
  name: string
}
export interface AnalysisResult {
  taskId: string
  completedAt: string
  request: {
    requestId: string
    comicId: string
    metadataVersion: number
    structureVersion: number
    categories: AnalysisTerm[]
    tags: AnalysisTerm[]
    samples: {
      mediaId: string
      ordinal: number
      sha256: string
      size: number
      width: number
      height: number
      contentType: string
    }[]
  }
  candidate: {
    title: string | null
    author: string | null
    description: string | null
    categoryId: string | null
    tags: { name: string; existingId: string | null; evidence: number[] }[]
    warnings: string[]
  }
}
