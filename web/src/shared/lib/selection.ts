import { computed, ref, type Ref } from 'vue'

/**
 * 行选集：全选、半选、点选与 Shift 连选。
 * 组件只在范围内渲染，选中项由调用方在数据刷新后通过 retain 收紧。
 */
export function useSelection<T extends { id: string }>(items: Ref<T[]>) {
  const selected = ref<string[]>([])
  const anchor = ref('')
  const allSelected = computed(
    () => items.value.length > 0 && items.value.every((item) => selected.value.includes(item.id)),
  )
  const partial = computed(() => selected.value.length > 0 && !allSelected.value)
  const selectedItems = computed(() => items.value.filter((item) => selected.value.includes(item.id)))

  function isSelected(id: string) {
    return selected.value.includes(id)
  }
  function toggle(id: string) {
    selected.value = selected.value.includes(id)
      ? selected.value.filter((value) => value !== id)
      : [...selected.value, id]
    anchor.value = id
  }
  function select(id: string, range = false) {
    const start = items.value.findIndex((item) => item.id === anchor.value)
    const end = items.value.findIndex((item) => item.id === id)
    if (range && start >= 0 && end >= 0)
      selected.value = [
        ...new Set([
          ...selected.value,
          ...items.value.slice(Math.min(start, end), Math.max(start, end) + 1).map((item) => item.id),
        ]),
      ]
    else toggle(id)
    anchor.value = id
  }
  function selectAll() {
    selected.value = allSelected.value ? [] : items.value.map((item) => item.id)
  }
  function clear() {
    selected.value = []
    anchor.value = ''
  }
  function retain(validIds: Iterable<string>) {
    const valid = new Set(validIds)
    selected.value = selected.value.filter((id) => valid.has(id))
    if (!valid.has(anchor.value)) anchor.value = ''
  }
  function set(ids: string[]) {
    selected.value = [...ids]
  }

  return {
    selected,
    allSelected,
    partial,
    selectedItems,
    isSelected,
    toggle,
    select,
    selectAll,
    clear,
    retain,
    set,
  }
}
