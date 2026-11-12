<script setup lang="ts">
import { ref, reactive, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { WindowScroller } from 'vue-virtual-scroller'
import type { MediaItemInfo } from '@/entities/media'
import {
  canUpgradeImage,
  highQualityImageUrl,
  readerPageSource,
  type ReaderImageQuality,
} from '../model/reader-image-source'
import { readerPageHeight } from '../model/reader-page-size'
import ReaderPageSection from './ReaderPageSection.vue'
const props = defineProps<{
  pages: MediaItemInfo[]
  current: number
  mode: 'scroll' | 'paged'
  fit: 'width' | 'screen'
  gap: boolean
  quality: ReaderImageQuality
  enabled: boolean
  preparingNext: boolean
  preparedImage?: { source: string; url: string }
}>()
const emit = defineEmits<{ position: [page: number]; toggle: []; prepareNext: [] }>()
const root = ref<HTMLElement>(),
  width = ref(0),
  ready = ref(false),
  notice = ref('')
const highQualityPages = reactive(new Set<string>())
const screenHeight = ref(0)
const renderBuffer = computed(() => Math.max(1200, screenHeight.value + 32))
const visiblePages = ref(new Set<number>())
const images = reactive(new Map<number, { source: string; state: 'loading' | 'loaded' | 'failed' }>())
const pagedIndices = computed(() => {
  const current = props.current - 1
  return [...new Set([current, ...images.keys()])].filter((index) => props.pages[index])
})
type Scroller = {
  scrollToItem: (index: number, options?: { align?: 'start' | 'center' | 'end' | 'nearest' }) => void
  getScroll: () => { start: number; end: number }
  findItemIndex: (offset: number) => number
  getItemOffset: (index: number) => number
  getItemSize: (index: number) => number
  updateVisibleItems: (itemsChanged: boolean, checkPositionDiff?: boolean) => unknown
}
const scroller = ref<Scroller>()
let noticeTimer: ReturnType<typeof setTimeout> | undefined, resizeObserver: ResizeObserver | undefined
let frame = 0
let initialization = 0
let direction: 1 | -1 = 1
let directionAnchor: number | undefined
let previousPage: number | undefined
let jumpTarget: number | undefined
const preloadsInFlight = new Set<NonNullable<ReturnType<typeof images.get>>>()
function startImage(index: number, background = false) {
  const page = props.pages[index]
  if (!page || page.mediaType !== 'image' || images.has(index)) return false
  const source = readerPageSource(page, props.quality)
  images.set(index, {
    source: props.preparedImage?.source === source ? props.preparedImage.url : source,
    state: 'loading',
  })
  const request = images.get(index)!
  if (background && source) preloadsInFlight.add(request)
  if (!source) request.state = 'failed'
  return Boolean(source)
}
function settleImage(index: number, source: string | undefined, loaded: boolean) {
  const request = images.get(index)
  if (!request || request.source !== source) return
  request.state = loaded ? 'loaded' : 'failed'
  preloadsInFlight.delete(request)
  scheduleUpdate()
}
function updateImages(visible: number[], candidates: number[], retained: (index: number) => boolean, nearEnd: boolean) {
  if (visible.length !== visiblePages.value.size || visible.some((index) => !visiblePages.value.has(index))) {
    visiblePages.value = new Set(visible)
  }
  for (const [index, request] of images) {
    if (!retained(index)) {
      images.delete(index)
      preloadsInFlight.delete(request)
    }
  }
  const target = jumpTarget
  jumpTarget = undefined
  if (target !== undefined && visible.includes(target) && startImage(target)) {
    scheduleUpdate()
    return
  }
  for (const index of visible) startImage(index)
  for (const index of candidates) {
    if (preloadsInFlight.size >= (props.preparingNext ? 1 : 2)) break
    startImage(index, true)
  }
  if (
    nearEnd &&
    visible.length &&
    preloadsInFlight.size < 2 &&
    visible.every((index) => props.pages[index]?.mediaType === 'video' || images.get(index)?.state === 'loaded')
  )
    emit('prepareNext')
}
function updateViewport() {
  if (!props.enabled || !ready.value || !props.pages.length) return
  if (props.mode === 'paged') {
    const index = props.current - 1
    if (previousPage !== undefined && index !== previousPage) direction = index > previousPage ? 1 : -1
    previousPage = index
    const adjacent = index + direction
    updateImages([index], [adjacent], (item) => item === index || item === adjacent, index >= props.pages.length - 2)
    return
  }
  const list = scroller.value
  if (!list) return
  const { start, end } = list.getScroll()
  const last = props.pages.length - 1
  const bottom = (index: number) => list.getItemOffset(index) + list.getItemSize(index)
  if (directionAnchor === undefined || (start - directionAnchor) * direction >= 0) directionAnchor = start
  else if (Math.abs(start - directionAnchor) >= 32) {
    direction = start > directionAnchor ? 1 : -1
    directionAnchor = start
  }
  const firstVisible = Math.max(0, list.findItemIndex(start))
  const visible: number[] = []
  let index = firstVisible
  while (index <= last && list.getItemOffset(index) < end && bottom(index) > start) visible.push(index++)
  const candidates: number[] = []
  function collect(step: 1 | -1, limit: number, distance: number) {
    const total = candidates.length + limit
    let index = step === 1 ? (visible.at(-1) ?? last) + 1 : firstVisible - 1
    while (index >= 0 && index <= last && candidates.length < total) {
      const fromScreen = step === 1 ? list!.getItemOffset(index) - end : start - bottom(index)
      if (fromScreen > distance) break
      if (props.pages[index]?.mediaType === 'image') candidates.push(index)
      index += step
    }
  }
  collect(direction, 3, screenHeight.value)
  collect(direction === 1 ? -1 : 1, 1, screenHeight.value / 2)
  updateImages(
    visible,
    candidates,
    (index) => bottom(index) > start - renderBuffer.value && list.getItemOffset(index) < end + renderBuffer.value,
    bottom(last) <= end + screenHeight.value,
  )
  syncPosition()
}
function scheduleUpdate() {
  if (frame) return
  frame = requestAnimationFrame(() => {
    frame = 0
    updateViewport()
  })
}
function overlaySource(page: MediaItemInfo) {
  if (props.quality !== 'lq' || !highQualityPages.has(page.id)) return null
  return highQualityImageUrl(page)
}
function itemSize(page: MediaItemInfo, index: number) {
  return readerPageHeight(page, width.value, props.gap, index === 0)
}
function showNotice(message: string) {
  notice.value = message
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => (notice.value = ''), 1800)
}
function upgrade(page: MediaItemInfo, event: MouseEvent) {
  if (page.mediaType !== 'image' || props.quality !== 'lq' || highQualityPages.has(page.id)) return
  if ((event.target as HTMLElement).closest('button,a,input,video')) return
  if (!canUpgradeImage(page)) {
    showNotice(highQualityImageUrl(page) ? '该页已是高清原件' : '该页没有高清原件，继续使用轻量图')
    return
  }
  highQualityPages.add(page.id)
  showNotice('正在加载此页高清')
}
function confirmHighQuality(page: MediaItemInfo) {
  if (highQualityPages.has(page.id)) showNotice('已切换此页高清')
}
function dropHighQuality(page: MediaItemInfo) {
  highQualityPages.delete(page.id)
  showNotice('高清加载失败，已回到轻量图')
}
function syncPosition() {
  if (!ready.value || props.mode !== 'scroll') return
  const state = scroller.value?.getScroll()
  if (!state) return
  const index = scroller.value?.findItemIndex(state.start + (state.end - state.start) * 0.4) ?? 0
  emit('position', index + 1)
}
async function jump(page: number) {
  await nextTick()
  if (props.mode !== 'scroll' || !scroller.value || !props.pages.length) return
  jumpTarget = Math.max(0, Math.min(page - 1, props.pages.length - 1))
  scroller.value.scrollToItem(jumpTarget, { align: 'start' })
  directionAnchor = undefined
  scheduleUpdate()
}
async function initialize() {
  const sequence = ++initialization
  ready.value = false
  await nextTick()
  if (props.mode === 'scroll' && width.value > 0) await jump(props.current)
  if (sequence !== initialization) return
  ready.value = true
  scheduleUpdate()
}
function measure() {
  const next = root.value?.clientWidth ?? 0
  if (next > 0) width.value = next
  screenHeight.value = window.innerHeight
  scheduleUpdate()
}
let pressX = 0,
  pressY = 0
function press(event: PointerEvent) {
  pressX = event.clientX
  pressY = event.clientY
}
function tap(event: MouseEvent) {
  if (event.detail > 1) return
  if ((event.target as HTMLElement).closest('button,a,input,video')) return
  if (Math.hypot(event.clientX - pressX, event.clientY - pressY) > 10) return
  if (event.clientX > window.innerWidth * 0.2 && event.clientX < window.innerWidth * 0.8) emit('toggle')
}
watch(
  [() => props.pages, () => props.mode],
  () => {
    images.clear()
    preloadsInFlight.clear()
    visiblePages.value = new Set()
    direction = 1
    directionAnchor = undefined
    previousPage = undefined
    jumpTarget = undefined
    void initialize()
  },
  { immediate: true },
)
watch([() => props.enabled, () => props.current, () => props.preparingNext], scheduleUpdate)
watch(width, async (next, previous) => {
  await nextTick()
  scroller.value?.updateVisibleItems(true)
  if (previous === 0 && next > 0) void initialize()
  else scheduleUpdate()
})
watch(
  () => props.gap,
  async () => {
    await nextTick()
    scroller.value?.updateVisibleItems(true)
    scheduleUpdate()
  },
)
watch(
  () => props.pages,
  () => highQualityPages.clear(),
)
watch(
  () => props.quality,
  () => {
    highQualityPages.clear()
    notice.value = ''
    images.clear()
    preloadsInFlight.clear()
    scheduleUpdate()
  },
)
onMounted(() => {
  measure()
  window.addEventListener('scroll', scheduleUpdate, { passive: true })
  window.addEventListener('resize', measure)
  if (typeof ResizeObserver !== 'undefined' && root.value) {
    resizeObserver = new ResizeObserver(measure)
    resizeObserver.observe(root.value)
  }
})
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  initialization++
  cancelAnimationFrame(frame)
  window.removeEventListener('scroll', scheduleUpdate)
  window.removeEventListener('resize', measure)
  clearTimeout(noticeTimer)
})
defineExpose({ jump })
</script>
<template>
  <div
    ref="root"
    class="cinema-viewport"
    :class="{ 'fit-screen': fit === 'screen', 'is-paged': mode === 'paged' }"
    @pointerdown="press"
    @click="tap"
  >
    <template v-if="mode === 'paged'">
      <template v-for="index in pagedIndices" :key="pages[index]!.id">
        <ReaderPageSection
          v-show="index === current - 1"
          :page="pages[index]!"
          :page-number="index + 1"
          :src="images.get(index)?.source"
          :overlay-src="index === current - 1 ? overlaySource(pages[index]!) : null"
          :visible="index === current - 1 && enabled"
          :gap="false"
          @image-settled="settleImage(index, images.get(index)?.source, $event)"
          @upgrade="upgrade(pages[index]!, $event)"
          @high-quality-loaded="confirmHighQuality(pages[index]!)"
          @high-quality-failed="dropHighQuality(pages[index]!)"
        />
      </template>
    </template>
    <WindowScroller
      v-else-if="mode === 'scroll' && width > 0"
      ref="scroller"
      class="cinema-viewport-scroller"
      :items="pages"
      key-field="id"
      :item-size="itemSize"
      :min-item-size="120"
      :buffer="renderBuffer"
      emit-update
      @update="scheduleUpdate"
    >
      <template #default="{ item, index, active }">
        <ReaderPageSection
          v-if="active"
          :key="item.id"
          :page="item"
          :page-number="index + 1"
          :src="images.get(index)?.source"
          :overlay-src="overlaySource(item)"
          :visible="visiblePages.has(index)"
          :gap="gap && index > 0"
          @upgrade="upgrade(item, $event)"
          @image-settled="settleImage(index, images.get(index)?.source, $event)"
          @high-quality-loaded="confirmHighQuality(item)"
          @high-quality-failed="dropHighQuality(item)"
        />
      </template>
    </WindowScroller>
    <p v-if="notice" class="cinema-quality-notice" role="status">{{ notice }}</p>
  </div>
</template>
<style scoped>
.cinema-viewport {
  max-width: 760px;
  margin: 0 auto;
  min-height: 70dvh;
}
.cinema-viewport-scroller {
  width: 100%;
}
.cinema-viewport.fit-screen {
  max-width: min(760px, calc(100dvh * 0.7));
}
.cinema-viewport.is-paged {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100dvh;
}
.cinema-quality-notice {
  position: fixed;
  left: 50%;
  bottom: calc(88px + env(safe-area-inset-bottom));
  z-index: 30;
  max-width: calc(100% - 32px);
  margin: 0;
  padding: var(--atlas-space-2) var(--atlas-space-4);
  color: var(--atlas-text);
  background: var(--atlas-surface);
  border: 1px solid var(--atlas-control-border);
  border-radius: var(--atlas-radius-control);
  font-size: 12px;
  letter-spacing: 0.06em;
  text-align: center;
  transform: translateX(-50%);
  pointer-events: none;
}
</style>
