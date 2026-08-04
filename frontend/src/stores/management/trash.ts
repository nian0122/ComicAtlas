import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { request, toErrorMessage } from '@/services/management/http'
import { trashApi } from '@/services/management/trash'
import { asNumber, asRecord, asString } from '@/types/management/enums'
import { ComicLifecycleStatus } from '@/types/management/enums'
import type { TrashComicItem, TrashTarget } from '@/types/management/trash'
import type { OperationSubmitResult } from '@/types/management/task'
import type { ComicListQuery } from '@/types'

function parseTrashComic(raw: unknown): TrashComicItem | null {
  const rec = asRecord(raw)
  if (!rec) return null
  const id = asNumber(rec.id)
  if (id <= 0) return null
  return {
    id,
    title: asString(rec.title),
    coverUrl: asString(rec.coverUrl),
    pageCount: asNumber(rec.pageCount),
    categoryId: typeof rec.categoryId === 'number' ? rec.categoryId : null,
    categoryName: typeof rec.categoryName === 'string' ? rec.categoryName : null,
    trashedAt: asString(rec.createdAt),
  }
}

/**
 * 回收站 Store（T17）：拉取 TRASHED 漫画列表 + 恢复/永久删除/一致性对账。
 * 列表复用 GET /comics 的 lifecycle 筛选，恢复/清除走 /api/trash 端点。
 */
export const useTrashStore = defineStore('trash', () => {
  const items = ref<readonly TrashComicItem[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const serverTotal = ref(0)
  const busyIds = ref<Readonly<Record<number, boolean>>>({})

  const count = computed(() => items.value.length)
  const hasItems = computed(() => items.value.length > 0)

  async function fetchTrash(params?: ComicListQuery): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const raw = await request<unknown>({
        method: 'GET',
        url: '/comics',
        params: {
          ...params,
          status: ComicLifecycleStatus.TRASHED,
          page: params?.page ?? 1,
          size: params?.size ?? 20,
        },
      })
      const page = asRecord(raw)
      const records = Array.isArray(page?.records) ? page.records : []
      items.value = records
        .map(parseTrashComic)
        .filter((item): item is TrashComicItem => item !== null)
      serverTotal.value = asNumber(page?.total)
    } catch (err: unknown) {
      error.value = toErrorMessage(err, '加载回收站失败')
      items.value = []
      serverTotal.value = 0
    } finally {
      loading.value = false
    }
  }

  async function restore(target: TrashTarget): Promise<OperationSubmitResult> {
    setBusy(target, true)
    error.value = null
    try {
      const result = await trashApi.restore(target)
      if (target.targetType === 'COMIC') {
        items.value = items.value.filter((item) => item.id !== target.targetId)
        serverTotal.value = Math.max(0, serverTotal.value - 1)
      }
      return result
    } catch (err: unknown) {
      error.value = toErrorMessage(err, '恢复失败')
      throw err
    } finally {
      setBusy(target, false)
    }
  }

  async function purge(target: TrashTarget, token: string): Promise<OperationSubmitResult> {
    setBusy(target, true)
    error.value = null
    try {
      const result = await trashApi.purge(target, token)
      if (target.targetType === 'COMIC') {
        items.value = items.value.filter((item) => item.id !== target.targetId)
        serverTotal.value = Math.max(0, serverTotal.value - 1)
      }
      return result
    } catch (err: unknown) {
      error.value = toErrorMessage(err, '永久删除失败')
      throw err
    } finally {
      setBusy(target, false)
    }
  }

  function setBusy(target: TrashTarget, busy: boolean): void {
    busyIds.value = { ...busyIds.value, [target.targetId]: busy }
  }

  function clear(): void {
    items.value = []
    serverTotal.value = 0
    error.value = null
    busyIds.value = {}
  }

  return {
    items,
    loading,
    error,
    serverTotal,
    busyIds,
    count,
    hasItems,
    fetchTrash,
    restore,
    purge,
    clear,
  }
})
