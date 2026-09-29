<template>
  <div ref="viewportRef" class="paged-viewport" @wheel="onWheel" @scroll="onScroll">
    <Transition :name="pageTransition" mode="out-in">
      <div v-if="page" :key="page.id" class="paged-page" :style="pageStyle">
        <VideoPlayer
          v-if="isVideo"
          :key="page.id"
          :media-id="page.id"
          :hq-url="page.hqUrl"
          :media-type="page.mediaType ?? 'VIDEO'"
          :width="page.width"
          :height="page.height"
          :duration="page.duration"
          :container="page.container"
          :video-codec="page.videoCodec"
          :audio-codec="page.audioCodec"
          :active="true"
          :scroller-root="viewportRef"
          @started="emit('video-started', props.currentPage - 1)"
        />
        <ProgressiveImage
          v-else
          :key="page.id"
          :lq="page.lqUrl"
          :hq="page.hqUrl"
          :mode="settings.qualityMode"
          :aspect-ratio="aspectRatio"
          :lq-status="page.lqStatus"
          :force-hq="forceHq"
        />
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useReaderSettingsStore } from '@/features/reader-settings'
import { isReaderInteractiveTarget } from '@/features/reader-interaction'
import { ProgressiveImage, VideoPlayer } from '@/entities/media'
import type { MediaItemInfo } from '@/entities/media'
import { isVideoMedia } from '@/entities/media'

interface Props {
  pages: MediaItemInfo[]
  currentPage: number
  /** 被双击强制切到 HQ 的页面索引（pageNumber - 1）集合 */
  forceHqPages: ReadonlySet<number>
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'page-request', direction: 'next' | 'prev'): void
  (e: 'visible-range', range: { start: number; end: number; total: number }): void
  (e: 'scroll-direction', direction: 'up' | 'down'): void
  (e: 'video-started', page: number): void
}>()

const settings = useReaderSettingsStore()
const viewportRef = ref<HTMLElement | null>(null)
const containerWidth = ref(0)
const containerHeight = ref(0)
const pageTransition = ref('page-next')

const WHEEL_PAGE_COOLDOWN_MS = 300
let lastWheelPageTime = 0
let lastScrollTop = 0

function onScroll() {
  const currentScrollTop = viewportRef.value?.scrollTop ?? 0
  if (currentScrollTop === lastScrollTop) return
  const direction = currentScrollTop > lastScrollTop ? 'up' : 'down'
  lastScrollTop = currentScrollTop
  emit('scroll-direction', direction)
}

function updateContainerSize() {
  if (viewportRef.value) {
    containerWidth.value = viewportRef.value.clientWidth
    containerHeight.value = viewportRef.value.clientHeight
  }
}

const page = computed<MediaItemInfo | null>(() => {
  if (props.pages.length === 0) return null
  const idx = Math.min(Math.max(props.currentPage - 1, 0), props.pages.length - 1)
  return props.pages[idx]
})

const forceHq = computed(() => props.forceHqPages.has(props.currentPage - 1))

const isVideo = computed(() => page.value != null && isVideoMedia(page.value))

const aspectRatio = computed(() => {
  const p = page.value
  if (p?.width && p.height && p.height > 0) {
    return p.width / p.height
  }
  return 3 / 4
})

const pageStyle = computed(() => {
  const cw = containerWidth.value
  const ch = containerHeight.value
  const ratio = aspectRatio.value
  const zoom = settings.zoom / 100
  let baseWidth: number
  switch (settings.fitMode) {
    case 'WIDTH':
      baseWidth = cw
      break
    case 'HEIGHT':
      baseWidth = ch * ratio
      break
    case 'ORIGINAL':
      baseWidth = page.value?.width || cw
      break
    case 'AUTO':
    default:
      baseWidth = Math.min(cw, ch * ratio)
      break
  }
  return { width: `${Math.max(0, baseWidth * zoom)}px` }
})

// 滚轮语义分层:Ctrl/Meta 冒泡给缩放;横向滚动(shift+滚轮/触控板横扫,deltaX 主导
// 或 deltaY=0)交给原生,防止 deltaY=0 被误判为上一页;页内可滚动方向优先原生滚动;
// 滚到边界后再滚才翻页(带 300ms 冷却防连翻)。
function onWheel(e: WheelEvent) {
  if (isReaderInteractiveTarget(e.target)) return
  if (e.ctrlKey || e.metaKey) return
  if (Math.abs(e.deltaX) >= Math.abs(e.deltaY)) return
  const el = viewportRef.value
  if (el) {
    const canScrollDown = el.scrollTop + el.clientHeight < el.scrollHeight - 1
    const canScrollUp = el.scrollTop > 0
    if (e.deltaY > 0 && canScrollDown) return
    if (e.deltaY < 0 && canScrollUp) return
  }
  e.preventDefault()
  const now = Date.now()
  if (now - lastWheelPageTime < WHEEL_PAGE_COOLDOWN_MS) return
  lastWheelPageTime = now
  emit('page-request', e.deltaY > 0 ? 'next' : 'prev')
}

onMounted(() => {
  updateContainerSize()
  window.addEventListener('resize', updateContainerSize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateContainerSize)
})

watch(
  () => [props.currentPage, props.pages.length] as const,
  ([currentPage], previousValue) => {
    const total = props.pages.length
    if (total === 0) return
    const previousPage = previousValue?.[0]
    if (previousPage !== undefined && currentPage !== previousPage) {
      pageTransition.value = currentPage > previousPage ? 'page-next' : 'page-prev'
    }
    const idx = Math.min(Math.max(props.currentPage - 1, 0), total - 1)
    emit('visible-range', { start: idx, end: idx, total })
    if (viewportRef.value) {
      viewportRef.value.scrollTop = 0
      viewportRef.value.scrollLeft = 0
      lastScrollTop = 0
    }
  },
  { immediate: true },
)
</script>

<style scoped>
.paged-viewport {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  perspective: 1200px;
  /* 让横向 pointer swipe 由阅读器处理，同时保留长页纵向平移与双指缩放。 */
  touch-action: pan-y pinch-zoom;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior: contain;
}

.paged-page {
  margin: auto;
  flex-shrink: 0;
}

.page-next-enter-active,
.page-next-leave-active,
.page-prev-enter-active,
.page-prev-leave-active {
  transition: opacity 220ms ease, transform 220ms cubic-bezier(0.22, 0.68, 0, 1);
  backface-visibility: hidden;
}

.page-next-enter-from { opacity: 0; transform: translateX(7%) rotateY(-5deg); }
.page-next-leave-to { opacity: 0; transform: translateX(-4%) rotateY(3deg); }
.page-prev-enter-from { opacity: 0; transform: translateX(-7%) rotateY(5deg); }
.page-prev-leave-to { opacity: 0; transform: translateX(4%) rotateY(-3deg); }

@media (prefers-reduced-motion: reduce) {
  .page-next-enter-active,
  .page-next-leave-active,
  .page-prev-enter-active,
  .page-prev-leave-active { transition-duration: 1ms; }
}
</style>
