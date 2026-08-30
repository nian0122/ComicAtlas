import { ref, computed } from 'vue'
import type { ComicStorageItem } from '@/features/storage/types'

export function useStorageSelection(getFilteredList: () => ComicStorageItem[]) {
  const selectedIds = ref<number[]>([])

  const hasSelection = computed(() => selectedIds.value.length > 0)
  const count = computed(() => selectedIds.value.length)

  function toggle(id: number) {
    const idx = selectedIds.value.indexOf(id)
    if (idx !== -1) {
      selectedIds.value.splice(idx, 1)
    } else {
      selectedIds.value.push(id)
    }
  }

  function selectAll() {
    const list = getFilteredList()
    selectedIds.value = Array.isArray(list) ? list.map((item) => item.comicId) : []
  }

  function clear() {
    selectedIds.value = []
  }

  return { selectedIds, hasSelection, count, toggle, selectAll, clear }
}
