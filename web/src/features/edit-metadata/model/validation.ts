import type { MetadataFields, MetadataRequest, MetadataPreview } from '@/entities/metadata-edit'
const bytes = (value: string) => new TextEncoder().encode(value).length
function name(value: string, limit: number) {
  return !!value.trim() && bytes(value) <= limit && !/[\p{Cc}]/u.test(value)
}
function ids(values: string[], maximum: number) {
  return (
    values.length <= maximum && new Set(values).size === values.length && values.every((id) => !!id && bytes(id) <= 128)
  )
}
export function validateMetadataRequest(value: MetadataRequest) {
  const patch = value.patch,
    selection = value.selection
  if (!Object.keys(patch).length) throw new Error('请至少选择一个需要修改的字段')
  if (
    (patch.title !== undefined && !name(patch.title, 500)) ||
    (patch.author !== undefined && bytes(patch.author) > 2000) ||
    (patch.description !== undefined && bytes(patch.description) > 65536)
  )
    throw new Error('标题、作者或简介超出 Go 接口限制')
  if (patch.categoryId !== undefined && (!patch.categoryId || bytes(patch.categoryId) > 128 || patch.clearCategory))
    throw new Error('分类设置与清空分类不能同时使用')
  if (
    !ids(patch.addTagIds ?? [], 100) ||
    !ids(patch.removeTagIds ?? [], 100) ||
    patch.addTagIds?.some((id) => patch.removeTagIds?.includes(id))
  )
    throw new Error('标签不能重复、同时增加和移除，单侧最多 100 项')
  if (selection.mode === 'ids') {
    if (!selection.ids.length || !ids(selection.ids, 10000)) throw new Error('请选择 1—10000 部不重复的漫画')
  } else {
    if (!ids(selection.excludedIds, 10000) || bytes(selection.filter.keyword ?? '') > 500)
      throw new Error('排除范围或搜索内容超出限制')
  }
  if (bytes(JSON.stringify(value)) > 1024 * 1024) throw new Error('预览请求超过 1 MiB，请减少范围或内容')
  return value
}
export function metadataFieldIssues(item: MetadataFields) {
  const issues: Partial<Record<'title' | 'author' | 'description' | 'tagIds' | 'categoryId' | 'version', string>> = {}
  if (typeof item.title !== 'string' || !item.title.trim()) issues.title = '请输入标题'
  else if (bytes(item.title) > 500) issues.title = '标题不能超过 500 字节'
  else if (/[\p{Cc}]/u.test(item.title)) issues.title = '标题不能包含控制字符'
  if (typeof item.author !== 'string' || bytes(item.author) > 2000) issues.author = '作者不能超过 2000 字节'
  if (typeof item.description !== 'string' || bytes(item.description) > 65536)
    issues.description = '简介不能超过 65536 字节'
  if (!Array.isArray(item.tagIds) || !ids(item.tagIds, 100)) issues.tagIds = '标签无效或超过 100 项，请重新选择'
  if (item.categoryId !== null && (typeof item.categoryId !== 'string' || !item.categoryId))
    issues.categoryId = '请选择有效分类或“无分类”'
  if (!Number.isSafeInteger(item.version) || item.version < 1) issues.version = '资料版本无效，请重新读取'
  return issues
}
export function atomicBody(items: MetadataFields[]) {
  if (
    !items.length ||
    items.length > 100 ||
    !ids(
      items.map((item) => item.id),
      100,
    )
  )
    throw new Error('整批事务只接受 1—100 部不重复作品')
  for (const [index, item] of items.entries()) {
    const issue = Object.values(metadataFieldIssues(item))[0]
    if (issue) throw new Error(`第 ${index + 1} 部作品：${issue}`)
  }
  const body = {
    items: items.map((item) => ({
      id: item.id,
      version: item.version,
      originalTitle: item.originalTitle,
      title: item.title,
      author: item.author,
      description: item.description,
      categoryId: item.categoryId,
      tagIds: [...item.tagIds],
    })),
  }
  if (bytes(JSON.stringify(body)) > 1024 * 1024) throw new Error('整批资料超过 1 MiB，请减少作品或简介内容')
  return body
}
export function metadataBinding(value: MetadataPreview) {
  return JSON.stringify([
    value.id,
    value.operation,
    value.patch,
    value.selectedCount,
    value.eligibleCount,
    value.blockedCount,
    value.expiresAt,
  ])
}
