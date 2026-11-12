import type { AnalysisResult, AnalysisTerm } from '@/entities/ai-analysis'
import type { ComicDetailVO } from '@/entities/comic'
export type ReviewField = 'title' | 'author' | 'description' | 'categoryId'
export interface ReviewSelection {
  fields: ReviewField[]
  tagIds: string[]
}
function sameTerms(a: AnalysisTerm[], b: AnalysisTerm[]) {
  return a.length === b.length && a.every((term) => b.some((value) => value.id === term.id && value.name === term.name))
}
export function reviewBlocker(
  result: AnalysisResult,
  comic: ComicDetailVO,
  categories: AnalysisTerm[],
  tags: AnalysisTerm[],
) {
  const source = result.request
  if (comic.id !== source.comicId) return '候选不属于当前漫画'
  if (comic.version !== source.metadataVersion || comic.structureVersion !== source.structureVersion)
    return '漫画资料或结构已变化，请按当前内容重新分析'
  if (!sameTerms(source.categories, categories) || !sameTerms(source.tags, tags))
    return '分类或标签词表已变化，请重新分析'
  return ''
}
export function reviewBody(result: AnalysisResult, comic: ComicDetailVO, selection: ReviewSelection) {
  const candidate = result.candidate
  if (!selection.fields.length && !selection.tagIds.length) throw new Error('请先勾选需要采纳的候选')
  for (const field of selection.fields) {
    if (candidate[field] === null) throw new Error('未知候选不能用于清空已有资料')
    if (field === 'title' && !candidate.title?.trim()) throw new Error('标题不能为空')
    if (field === 'categoryId' && !result.request.categories.some((term) => term.id === candidate.categoryId))
      throw new Error('候选分类不在冻结词表内')
  }
  for (const id of selection.tagIds) {
    const tag = candidate.tags.find((tag) => tag.existingId === id)
    if (!tag || !result.request.tags.some((term) => term.id === id && term.name === tag.name))
      throw new Error('只能采纳已存在且名称一致的候选标签')
  }
  return {
    version: comic.version,
    originalTitle: comic.originalTitle,
    title: selection.fields.includes('title') ? candidate.title! : comic.title,
    author: selection.fields.includes('author') ? candidate.author! : comic.author,
    description: selection.fields.includes('description') ? candidate.description! : comic.description,
    categoryId: selection.fields.includes('categoryId') ? candidate.categoryId : (comic.category?.id ?? null),
    tagIds: [...new Set([...comic.tags.map((tag) => tag.id), ...selection.tagIds])],
  }
}
