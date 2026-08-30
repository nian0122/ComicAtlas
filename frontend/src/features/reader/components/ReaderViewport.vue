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
      :page-mode="props.pageMode"
      @scroll="onScrollerScroll"
    >
      <template #default="{ item, index, active }">
        <div class="reader-item-wrapper"><ReaderImageItem :item="item" :index="index" :active="active" :scroller-root="scrollerEl" :item-height="item.size" :force-hq="props.forceHqPages.has(index)" @video-started="emit('video-started', $event)" /></div>
      </template>
    </RecycleScroller>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { RecycleScroller } from 'vue-virtual-scroller'
import type { RecycleScrollerExposed } from 'vue-virtual-scroller'
import { useReaderSettingsStore } from '@/features/reader/settings-store'
import ReaderImageItem from './ReaderImageItem.vue'
import type { MediaItemInfo } from '@/entities/media/types'
import { DEFAULT_ASPECT_RATIO } from '@/entities/media/constants'
import { isVideoMedia } from '@/entities/media/guards'
/** 虚拟滚动缓冲区最小高度（px） */
const MIN_BUFFER_PX = 800
/** 视频完整保留在复用边界内的额外安全距离（px）。 */
const VIDEO_BUFFER_SAFETY_PX = 64
/** 程序化滚动锁定时长（ms），防止自身滚动事件触发页码回写 */
const SCROLL_LOCK_DURATION_MS = 100

interface ScrollerItem extends MediaItemInfo {
  size: number
}

interface Props {
  pages: MediaItemInfo[]
  currentPage: number
  /** 被双击强制切到 HQ 的页面索引（0-based）集合 */
  forceHqPages: ReadonlySet<number>
  /** 移动端纵向阅读使用页面级滚动，让 Safari 能收缩地址栏。 */
  pageMode?: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:currentPage', page: number): void
  (e: 'visible-range', range: { start: number; end: number; total: number }): void
  (e: 'scroll-direction', direction: 'up' | 'down'): void
  (e: 'video-started', page: number): void
}>()

const settings = useReaderSettingsStore()
const viewportRef = ref<HTMLElement | null>(null)
const scrollerRef = ref<RecycleScrollerExposed<ScrollerItem> | null>(null)
const containerWidth = ref(0)
const containerHeight = ref(0)
/** Reactive reference to the scroller's root DOM element, passed to VideoPlayer for viewport-relative visibility tracking. */
const scrollerEl = ref<HTMLElement | null>(null)

function updateContainerSize() {
  /*
   * 两阶段获取宽度：
   * 1. viewportRef.clientWidth 用于初始化（触发 RecycleScroller 渲染）
   * 2. scrollerEl.clientWidth 用于修正：scroller 的 overflow-y:auto 在
   *    内容超长时自动扣除垂直滚动条宽度，与 ProgressiveImage 的 width:100%
   *    参照一致。差距 ≈ 滚动条宽度（Win ~17px），不修正会导致 size 偏差
   *    ~25px → 图片在容器中大量留白 → 视觉间隙。
   */
  const baseW = viewportRef.value?.clientWidth ?? 0
  const scrollerElDom = (scrollerRef.value as Record<string, unknown> | null)?.$el as HTMLElement | undefined
  scrollerEl.value = scrollerElDom ?? null
  containerWidth.value = scrollerElDom?.clientWidth ?? baseW
  containerHeight.value = viewportRef.value?.clientHeight ?? 0
}

function computeAspectRatio(page: MediaItemInfo): number {
  if (page.width && page.height && page.height > 0) {
    return page.width / page.height
  }
  return DEFAULT_ASPECT_RATIO
}

function computeItemSize(page: MediaItemInfo): number {
  const aspectRatio = computeAspectRatio(page)
  const zoom = settings.zoom / 100

  let baseHeight: number
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
  // 整数取整消除 translateY 浮点定位的亚像素缝隙
  return Math.round(baseHeight * zoom)
}

const sizes = computed<number[]>(() => {
  // 访问 props.pages 与 computeItemSize 内部访问的所有响应式依赖，
  // 使 sizes 仅在 pages/zoom/fitMode/viewport 变化时重建。
  return props.pages.map((page) => computeItemSize(page))
})

/**
 * RecycleScroller 的 buffer 以像素衡量。连续竖屏视频在桌面端可能远高于
 * 视口；仅按视口高度缓冲会让相邻视频过早进入复用池，首帧预览随之重建。
 *
 * 宽高来自后端媒体分析结果，sizes 与 RecycleScroller 实际使用的 size 字段
 * 完全一致。只纳入视频，避免超长图片无谓扩大媒体预览的保留范围。
 */
const tallestVideoItemSize = computed(() => {
  let largestSize = 0
  for (const [index, page] of props.pages.entries()) {
    if (!isVideoMedia(page)) continue
    largestSize = Math.max(largestSize, sizes.value[index] ?? 0)
  }
  return largestSize
})

/**
 * 至少保留一个完整视频及其安全边距；手机端通常仍由视口高度主导，桌面端会
 * 自动适配竖屏视频的实际高度。buffer 在可视区上下两侧各生效一次。
 */
const buffer = computed(() =>
  Math.ceil(
    Math.max(
      MIN_BUFFER_PX,
      containerHeight.value,
      tallestVideoItemSize.value + VIDEO_BUFFER_SAFETY_PX,
    ),
  ),
)

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
let programmaticScrollTimer: number | null = null
let lastRangeStart = -1
let lastRangeEnd = -1
let lastScrollOffset = 0
let pendingScrollDirection: 'up' | 'down' | null = null
/** 最近一次由自然滚动计算并回传给父组件的页码，避免回流时反向吸附页面。 */

// vue-virtual-scroller 2.x 组件实例不暴露 scrollTop/scrollLeft,
// 统一走官方 exposed API(getScroll/scrollToPosition,内部按 direction 分支且处理 RTL)。
function scrollOffset(): number {
  if (props.pageMode) return window.scrollY
  return scrollerRef.value?.getScroll?.()?.start ?? 0
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
  const currentOffset = scrollOffset()
  if (currentOffset !== lastScrollOffset) {
    pendingScrollDirection = currentOffset > lastScrollOffset ? 'up' : 'down'
    lastScrollOffset = currentOffset
  }
  if (scrollRafId != null) return
  scrollRafId = requestAnimationFrame(() => {
    scrollRafId = null
    if (isProgrammaticScroll) return
    const page = deriveCurrentPage()
    if (page !== props.currentPage) {
      emit('update:currentPage', page)
    }
    emitVisibleRange()
    if (pendingScrollDirection !== null) {
      emit('scroll-direction', pendingScrollDirection)
      pendingScrollDirection = null
    }
  })
}

/** page-mode 使用窗口滚动；将方向判断收口到视口组件，避免父组件重复派发状态。 */
function onScrollerScroll() {
  if (props.pageMode) return
  onScroll()
}

function scrollToPage(page: number): void {
  if (props.pages.length === 0) return
  const targetPage = Math.max(1, Math.min(page, props.pages.length))
  const offset = targetPage === 1 ? 0 : prefixSums.value[targetPage - 2]
  isProgrammaticScroll = true
  pendingScrollDirection = null

  if (props.pageMode) {
    // 移动端 page-mode 使用窗口滚动；不能依赖虚拟列表内部 scroller 的
    // scrollToItem，否则页码状态会更新，但窗口位置可能不会移动。
    const viewportTop = viewportRef.value?.getBoundingClientRect().top ?? 0
    const targetTop = Math.max(0, window.scrollY + viewportTop + offset)
    lastScrollOffset = targetTop
    window.scrollTo({ top: targetTop, left: 0, behavior: 'auto' })
  } else {
    if (!scrollerRef.value) return
    lastScrollOffset = offset
    // 桌面端继续使用虚拟列表官方定位 API。
    scrollerRef.value.scrollToItem(targetPage - 1, { smooth: false, align: 'start' })
  }

  // 取消旧计时器，防止新旧 scrollToPage 调用互相干扰
  if (programmaticScrollTimer != null) {
    window.clearTimeout(programmaticScrollTimer)
    programmaticScrollTimer = null
  }

  nextTick(() => {
    emitVisibleRange()
    programmaticScrollTimer = window.setTimeout(() => {
      programmaticScrollTimer = null
      isProgrammaticScroll = false
    }, SCROLL_LOCK_DURATION_MS)
  })
}

function forceUpdateScroller() {
  if (scrollerRef.value) {
    scrollerRef.value.updateVisibleItems(true)
  }
}

defineExpose({ scrollToPage })

onMounted(() => {
  updateContainerSize()
  window.addEventListener('resize', updateContainerSize)
  if (props.pageMode) {
    window.addEventListener('scroll', onScroll, { passive: true })
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateContainerSize)
  if (props.pageMode) {
    window.removeEventListener('scroll', onScroll)
  }
  if (scrollRafId != null) cancelAnimationFrame(scrollRafId)
  if (programmaticScrollTimer != null) window.clearTimeout(programmaticScrollTimer)
})

watch(() => props.currentPage, (newPage) => {
  // page-mode 的 currentPage 由自然滚动产生，只同步父级状态；
  // 外部跳页由 ReaderPage 显式调用 scrollToPage，禁止 watcher 反向吸附。
  if (props.pageMode) return
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
    // 移动端视口高度会随浏览器地址栏/阅读工具栏变化而调整，
    // 这里只刷新虚拟列表，不能把当前滚动位置重新吸附到 currentPage 页首。
    if (props.pageMode) return
    scrollToPage(props.currentPage)
  })
})

watch(() => [settings.fitMode, settings.zoom], () => {
  nextTick(() => {
    forceUpdateScroller()
    // 移动端调整阅读设置不属于页码跳转，保留用户当前阅读位置。
    if (props.pageMode) return
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
  -webkit-overflow-scrolling: touch;
  overscroll-behavior-y: contain;
}

.reader-item-wrapper {
  width: 100%;
}

@media (max-width: 1024px) {
  .reader-viewport {
    flex: none;
    height: 100dvh;
    min-height: 100dvh;
    overflow: visible;
  }

  .scroller {
    height: auto;
    min-height: 100dvh;
    overflow: visible;
  }
}

:deep(.vue-recycle-scroller__item-wrapper) {
  width: 100%;
}

/*
 * 消除 item-view 之间的亚像素缝隙。
 *
 * 根因：vue-virtual-scroller 的 .vue-recycle-scroller.ready .item-view
 * 设置了 will-change:transform（特异性 0,3,0），浏览器为每个 item-view
 * 创建独立 GPU 合成层，相邻层间出现 <1px 渲染裂缝。
 *
 * 修复：用更高特异性（0,3,1）选择器设置 will-change:auto
 * + backface-visibility:hidden，双保险消除合成层边界裂缝。
 * 配合 ReaderImageItem 的明确像素高度（= scroller size），
 * 内容高度与 slot 高度数学上严格一致。
 */
:deep(.vue-recycle-scroller.ready .vue-recycle-scroller__item-view) {
  margin: 0;
  padding: 0;
  border: none;
  will-change: auto;
  backface-visibility: hidden;
}
</style>
