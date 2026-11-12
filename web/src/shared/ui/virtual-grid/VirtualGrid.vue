<script setup lang="ts" generic="T">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { WindowScroller } from 'vue-virtual-scroller'
import { computeVirtualGridLayout } from './model/layout'

const props = withDefaults(
  defineProps<{
    items: T[]
    itemKey: string
    columns?: number
    compactColumns?: number
    aspectRatio?: number
    columnGap?: number
    rowGap?: number
    compactColumnGap?: number
    compactRowGap?: number
    breakpoint?: number
    buffer?: number
  }>(),
  {
    columns: 4,
    compactColumns: 3,
    aspectRatio: 1.5,
    columnGap: 20,
    rowGap: 38,
    compactColumnGap: 10,
    compactRowGap: 26,
    breakpoint: 900,
    buffer: 400,
  },
)
defineSlots<{ default(props: { item: T; index: number; active: boolean }): unknown }>()

const root = ref<HTMLElement>()
const scroller = ref<{ updateVisibleItems: (itemsChanged: boolean, checkPositionDiff?: boolean) => void }>()
const width = ref(0)
const compact = ref(false)
let scrollFrame = 0
const focusState = {
  element: null as HTMLElement | null,
  key: '',
  lastPointerDown: 0,
}
let observer: ResizeObserver | undefined
let media: MediaQueryList | undefined

const layout = computed(() =>
  computeVirtualGridLayout({
    width: width.value,
    compact: compact.value,
    columns: props.columns,
    compactColumns: props.compactColumns,
    aspectRatio: props.aspectRatio,
    columnGap: props.columnGap,
    rowGap: props.rowGap,
    compactColumnGap: props.compactColumnGap,
    compactRowGap: props.compactRowGap,
  }),
)

function measure() {
  const next = root.value?.clientWidth ?? 0
  if (next > 0) width.value = next
}
function syncCompact(event?: MediaQueryListEvent) {
  compact.value = event?.matches ?? media?.matches ?? false
}
function keyOf(item: T) {
  return item && typeof item === 'object' ? String((item as Record<string, unknown>)[props.itemKey]) : String(item)
}
function onPointerDown() {
  focusState.lastPointerDown = performance.now()
}
function onScroll() {
  if (scrollFrame) return
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0
    /*
     * WindowScroller 默认要等滚动起点跨过一整行才重算窗口。
     * 封面网格的行高较大，部分滚动会让视口底部落在已渲染窗口之外；
     * 这里在每帧滚动后强制重算，避免出现空白行。
     */
    scroller.value?.updateVisibleItems(false, false)
  })
}
function onFocusIn(event: FocusEvent) {
  const target = event.target as HTMLElement
  const key = target.closest<HTMLElement>('[data-virtual-key]')?.dataset.virtualKey
  if (key === undefined) {
    focusState.element = null
    return
  }
  focusState.element = target
  focusState.key = key
}
function onFocusOut(event: FocusEvent) {
  const element = event.target as HTMLElement
  if (element !== focusState.element) return
  const key = focusState.key
  focusState.element = null
  if (event.relatedTarget || !document.hasFocus()) return
  // 点击离开时保留用户意图；只有虚拟列表重排导致的无目标失焦才恢复。
  if (performance.now() - focusState.lastPointerDown < 300) return
  requestAnimationFrame(() => {
    if (document.activeElement !== document.body || !element.isConnected) return
    if (element.closest<HTMLElement>('[data-virtual-key]')?.dataset.virtualKey !== key) return
    if (!root.value?.contains(element)) return
    element.focus({ preventScroll: true })
  })
}

onMounted(() => {
  measure()
  media = window.matchMedia(`(max-width: ${Math.max(0, props.breakpoint - 1)}px)`)
  syncCompact()
  media.addEventListener('change', syncCompact)
  document.addEventListener('pointerdown', onPointerDown, true)
  window.addEventListener('scroll', onScroll, { passive: true })
  if (typeof ResizeObserver === 'undefined' || !root.value) return
  observer = new ResizeObserver((entries) => {
    const next = entries[0]?.contentRect.width ?? 0
    if (next > 0 && Math.abs(next - width.value) > 0.5) width.value = next
  })
  observer.observe(root.value)
})
onBeforeUnmount(() => {
  observer?.disconnect()
  media?.removeEventListener('change', syncCompact)
  document.removeEventListener('pointerdown', onPointerDown, true)
  window.removeEventListener('scroll', onScroll)
  cancelAnimationFrame(scrollFrame)
})
</script>

<template>
  <div
    ref="root"
    class="virtual-grid"
    :data-columns="layout?.columns ?? 0"
    :data-total="items.length"
    @focusin="onFocusIn"
    @focusout="onFocusOut"
  >
    <WindowScroller
      v-if="layout"
      ref="scroller"
      class="virtual-grid-scroller"
      :items="items"
      :key-field="itemKey"
      :grid-items="layout.columns"
      :item-size="layout.rowHeight"
      :item-secondary-size="layout.stride"
      :buffer="buffer"
      item-class="virtual-grid-item"
    >
      <template #default="{ item, index, active }">
        <div
          class="virtual-grid-cell"
          :data-virtual-key="keyOf(item)"
          :style="{ paddingRight: `${layout.columnGap}px`, paddingBottom: `${layout.rowGap}px` }"
        >
          <slot :item="item as T" :index="index" :active="active" />
        </div>
      </template>
    </WindowScroller>
  </div>
</template>

<style scoped>
.virtual-grid {
  position: relative;
  width: 100%;
}

/*
 * WindowScroller 走窗口滚动，但组件根节点没有 page-mode 类；
 * 库里的 overflow-y:auto 会把它误当成内部滚动容器，导致横向溢出和焦点轮廓被裁剪。
 */
.virtual-grid :deep(.vue-recycle-scroller),
.virtual-grid :deep(.vue-recycle-scroller__item-wrapper) {
  overflow: visible;
}

.virtual-grid-cell {
  width: 100%;
  height: 100%;
}
</style>
