<template>
  <div ref="viewportRef" class="reader-viewport">
    <RecycleScroller
      v-if="containerWidth > 0 && containerHeight > 0"
      ref="scrollerRef"
      class="scroller"
      :items="scrollerItems"
      :item-size="null"
      size-field="size"
      key-field="id"
      :buffer="buffer"
      @scroll="onScroll"
    >
      <template #default="{ item, index, active }">
        <div class="reader-item-wrapper"><ReaderImageItem :item="item" :index="index" :active="active" /></div>
      </template>
    </RecycleScroller>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { RecycleScroller } from 'vue-virtual-scroller'
import { useReaderSettingsStore } from '@/stores/reader-settings-store'
import ReaderImageItem from './ReaderImageItem.vue'
import type { PageInfo } from '@/types'

interface ScrollerItem extends PageInfo {
  size: number
}

interface Props {
  pages: PageInfo[]
  currentPage: number
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:currentPage', page: number): void
  (e: 'visible-range', range: { start: number; end: number; total: number }): void
}>()

const settings = useReaderSettingsStore()
const viewportRef = ref<HTMLElement | null>(null)
const scrollerRef = ref<any>(null)
const containerWidth = ref(0)
const containerHeight = ref(0)

function updateContainerSize() {
  if (viewportRef.value) {
    containerWidth.value = viewportRef.value.clientWidth
    containerHeight.value = viewportRef.value.clientHeight
  }
}

const buffer = computed(() => Math.max(800, containerHeight.value))

function computeAspectRatio(page: PageInfo): number {
  if (page.width && page.height && page.height > 0) {
    return page.width / page.height
  }
  return 3 / 4
}

function computeItemSize(page: PageInfo): number {
  const aspectRatio = computeAspectRatio(page)
  const zoom = settings.zoom / 100

  let baseHeight = 0
  switch (settings.fitMode) {
    case 'WIDTH':
      baseHeight = containerWidth.value / aspectRatio
      break
    case 'HEIGHT':
      baseHeight = containerHeight.value
      break
    case 'ORIGINAL':
      baseHeight = page.height || containerHeight.value
      break
    case 'AUTO':
    default: {
      // 纵向连续滚动场景下宽度是自然约束，高度按每页宽高比变化。
      // 不再按 containerHeight 封顶——否则所有竖版漫画页高度相同，
      // 无视 page.width/height 的实际差异。
      baseHeight = containerWidth.value / aspectRatio
      break
    }
  }
  return baseHeight * zoom
}

const sizes = computed<number[]>(() => {
  // 访问 props.pages 与 computeItemSize 内部访问的所有响应式依赖，
  // 使 sizes 仅在 pages/zoom/fitMode/viewport 变化时重建。
  return props.pages.map((page) => computeItemSize(page))
})

const prefixSums = computed<number[]>(() => {
  const sums: number[] = []
  let acc = 0
  for (const size of sizes.value) {
    acc += size
    sums.push(acc)
  }
  return sums
})

const scrollerItems = computed<ScrollerItem[]>(() =>
  props.pages.map((page, index) => ({
    ...page,
    size: sizes.value[index],
  }))
)

function upperBound(arr: number[], value: number): number {
  let lo = 0
  let hi = arr.length
  while (lo < hi) {
    const mid = (lo + hi) >>> 1
    if (arr[mid] <= value) {
      lo = mid + 1
    } else {
      hi = mid
    }
  }
  return lo
}

let scrollRafId: number | null = null
let isProgrammaticScroll = false
let lastRangeStart = -1
let lastRangeEnd = -1

// vue-virtual-scroller 2.x 组件实例不暴露 scrollTop/scrollLeft,
// 统一走官方 exposed API(getScroll/scrollToPosition,内部按 direction 分支且处理 RTL)。
function scrollOffset(): number {
  return scrollerRef.value?.getScroll?.()?.start ?? 0
}

function setScrollOffset(offset: number) {
  scrollerRef.value?.scrollToPosition(offset)
}

function viewportSize(): number {
  return containerHeight.value
}

// 当前页 = 视口中线所在页(与旧 IntersectionObserver「最大可见比例」行为基本等价)
function deriveCurrentPage(): number {
  if (prefixSums.value.length === 0) return 1
  const center = scrollOffset() + viewportSize() / 2
  const idx = upperBound(prefixSums.value, center)
  return Math.min(idx + 1, props.pages.length)
}

// 可视索引区间由前缀和推导(scroller 用同一 size 字段定位,数学与渲染严格一致)。
// range 不变则不 emit:preloadEngine.onVisibleChange 每次调用都会清空重排 cascade
// 定时器(80ms 延迟),rAF 频率的重复调用会把预加载饿死。
function emitVisibleRange() {
  const total = props.pages.length
  if (total === 0) return
  const offset = scrollOffset()
  const start = Math.min(upperBound(prefixSums.value, offset), total - 1)
  const end = Math.min(upperBound(prefixSums.value, offset + viewportSize()), total - 1)
  if (start === lastRangeStart && end === lastRangeEnd) return
  lastRangeStart = start
  lastRangeEnd = end
  emit('visible-range', { start, end, total })
}

function onScroll() {
  if (isProgrammaticScroll) return
  if (scrollRafId != null) return
  scrollRafId = requestAnimationFrame(() => {
    scrollRafId = null
    if (isProgrammaticScroll) return
    const page = deriveCurrentPage()
    if (page !== props.currentPage) {
      emit('update:currentPage', page)
    }
    emitVisibleRange()
  })
}

function scrollToPage(page: number) {
  if (!scrollerRef.value || props.pages.length === 0) return
  const targetPage = Math.max(1, Math.min(page, props.pages.length))
  const offset = targetPage === 1 ? 0 : prefixSums.value[targetPage - 2]
  isProgrammaticScroll = true
  setScrollOffset(offset)
  nextTick(() => {
    emitVisibleRange()
    window.setTimeout(() => {
      isProgrammaticScroll = false
    }, 100)
  })
}

function forceUpdateScroller() {
  if (scrollerRef.value) {
    scrollerRef.value.updateVisibleItems(true)
  }
}

onMounted(() => {
  updateContainerSize()
  window.addEventListener('resize', updateContainerSize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateContainerSize)
  if (scrollRafId != null) cancelAnimationFrame(scrollRafId)
})

watch(() => props.currentPage, (newPage) => {
  // 斩断回声循环:自身滚动 emit 的页码经父组件回流时,视口已在该页,跳过吸附;
  // 外部跳页(工具栏/键盘/URL)因当前位置不符,正常执行 scrollToPage。
  if (newPage === deriveCurrentPage()) return
  scrollToPage(newPage)
}, { flush: 'post' })

watch(() => props.pages.length, () => {
  // 重置 visible-range 去重状态:新章节即使 range 数值相同也必须重发,
  // 否则 preloadEngine reset 后收不到首次可视区,预加载不启动。
  lastRangeStart = -1
  lastRangeEnd = -1
  nextTick(() => {
    updateContainerSize()
    scrollToPage(props.currentPage)
  })
})

watch([containerWidth, containerHeight], () => {
  nextTick(() => {
    forceUpdateScroller()
    scrollToPage(props.currentPage)
  })
})

watch(() => [settings.fitMode, settings.zoom], () => {
  nextTick(() => {
    forceUpdateScroller()
    scrollToPage(props.currentPage)
  })
}, { flush: 'post' })
</script>

<style scoped>
.reader-viewport {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  /* 移动端：消除 300ms 点击延迟并禁用双击缩放，滚动/平移不受影响 */
  touch-action: manipulation;
}

.scroller {
  width: 100%;
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
}

.reader-item-wrapper {
  width: 100%;
  /*
   * 内容区比 item-view 高 1px，向下溢出覆盖虚拟滚动
   * translateY 浮点定位产生的亚像素缝隙。
   * item-view（position:absolute, will-change:transform）
   * 各自形成独立层叠上下文，后续 item 天然覆盖前序溢出。
   */
  height: calc(100% + 1px);
}

:deep(.vue-recycle-scroller__item-wrapper) {
  width: 100%;
}

/* 消除 item-view 之间的任何间隙：margin/padding/border 归零 */
:deep(.vue-recycle-scroller__item-view) {
  margin: 0;
  padding: 0;
  border: none;
}
</style>
