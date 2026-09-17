import { computed, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { MediaItemInfo } from '@/entities/media'

/** 章节媒体排序的纯交互状态；保存动作仍由页面调用管理 API。 */
export function useMediaOrder(mediaItems: Ref<readonly MediaItemInfo[]>) {
  const mediaOrderItems = ref<MediaItemInfo[]>([])
  const mediaOrder = ref('')
  const draggingMediaIndex = ref<number | null>(null)
  const mediaOrderDirty = computed(() =>
    mediaOrderItems.value.some((item, index) => item.id !== mediaItems.value[index]?.id),
  )
  const mediaOrderChangeCount = computed(() =>
    mediaOrderItems.value.reduce((count, item, index) => count + (item.id !== mediaItems.value[index]?.id ? 1 : 0), 0),
  )
  function syncMediaOrder(items: readonly MediaItemInfo[]): void {
    mediaOrderItems.value = [...items]
    mediaOrder.value = items.map((item) => item.id).join(',')
  }
  function startMediaDrag(index: number): void {
    draggingMediaIndex.value = index
  }
  function dropMedia(targetIndex: number): void {
    const sourceIndex = draggingMediaIndex.value
    draggingMediaIndex.value = null
    if (sourceIndex === null || sourceIndex === targetIndex) return
    const nextItems = [...mediaOrderItems.value]
    const [movedItem] = nextItems.splice(sourceIndex, 1)
    if (movedItem) nextItems.splice(targetIndex, 0, movedItem)
    syncMediaOrder(nextItems)
  }
  function resetMediaOrder(): void {
    syncMediaOrder(mediaItems.value)
  }
  function sortMediaByName(): void {
    syncMediaOrder(
      [...mediaOrderItems.value].sort((a, b) =>
        (a.fileName || '').localeCompare(b.fileName || '', 'zh-CN', { numeric: true, sensitivity: 'base' }),
      ),
    )
  }
  function applyAdvancedMediaOrder(): void {
    const ids = mediaOrder.value
      .split(',')
      .map((value) => Number(value.trim()))
      .filter((id) => Number.isSafeInteger(id) && id > 0)
    const itemById = new Map(mediaItems.value.map((item) => [item.id, item]))
    if (
      ids.length !== mediaItems.value.length ||
      new Set(ids).size !== ids.length ||
      ids.some((id) => !itemById.has(id))
    ) {
      ElMessage.warning('媒体 ID 必须完整、有效且不能重复')
      return
    }
    mediaOrderItems.value = ids.map((id) => itemById.get(id)!).filter(Boolean)
    ElMessage.success('已应用到排序列表')
  }
  return {
    mediaOrderItems,
    mediaOrder,
    mediaOrderDirty,
    mediaOrderChangeCount,
    syncMediaOrder,
    startMediaDrag,
    dropMedia,
    resetMediaOrder,
    sortMediaByName,
    applyAdvancedMediaOrder,
  }
}
