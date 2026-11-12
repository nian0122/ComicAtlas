import type { Task } from '@/entities/task'
import { managementCommands as commands, type BatchItem, type BatchPreview, type BatchRequest } from '../api/commands'

export interface BatchPreviewContent {
  preview: BatchPreview
  items: BatchItem[]
  total: number
}
export interface BatchPreviewGuards {
  preview?: (value: BatchPreview) => void
  items?: (value: BatchItem[]) => void
}

/**
 * 创建服务端冻结预览并读取第一页清单。
 * 操作自己的契约校验放在 guards 中，调用方不再各写一遍请求顺序。
 */
export async function createBatchPreview(
  request: BatchRequest,
  guards: BatchPreviewGuards = {},
): Promise<BatchPreviewContent> {
  const preview = await commands.preview(request)
  guards.preview?.(preview)
  const page = await commands.previewItems(preview.id)
  guards.items?.(page.items)
  return { preview, items: page.items, total: page.total }
}

export async function readBatchPreviewPage(preview: BatchPreview, page: number, guards: BatchPreviewGuards = {}) {
  const value = await commands.previewItems(preview.id, page)
  guards.items?.(value.items)
  return value
}

export async function confirmBatchPreview(preview: BatchPreview, key: string): Promise<Task> {
  const task = await commands.confirm(preview, key)
  if (
    !task.id ||
    task.type !== 'command_batch' ||
    task.targetType !== preview.operation ||
    task.targetId !== preview.id
  )
    throw new Error('受理任务与原批次不一致，请继续核对原批次')
  return task
}

export function batchPreviewExpired(preview: BatchPreview, at = Date.now()) {
  const expires = Date.parse(preview.expiresAt)
  return !Number.isFinite(expires) || expires <= at
}
