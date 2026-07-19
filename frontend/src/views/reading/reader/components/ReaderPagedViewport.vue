<template>
  <div ref="viewportRef" class="paged-viewport" @wheel="onWheel">
    <div v-if="page" class="paged-page" :style="pageStyle">
      <ProgressiveImage
        :key="page.id"
        :lq="page.lqUrl"
        :hq="page.hqUrl"
        :mode="settings.qualityMode"
        :aspect-ratio="aspectRatio"
        :lq-status="page.lqStatus"
        :force-hq="forceHq"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useReaderSettingsStore } from '@/stores/reader-settings-store'
import ProgressiveImage from './ProgressiveImage.vue'
import type { PageInfo } from '@/types'

interface Props {
  pages: PageInfo[]
  currentPage: number
  /** 被双击强制切到 HQ 的页面索引（pageNumber - 1）集合 */
  forceHqPages: ReadonlySet<number>
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'page-request', direction: 'next' | 'prev'): void
  (e: 'visible-range', range: { start: number; end: number; total: number }): void
}>()

const settings = useReaderSettingsStore()
const viewportRef = ref<HTMLElement | null>(null)
const containerWidth = ref(0)
const containerHeight = ref(0)

const WHEEL_PAGE_COOLDOWN_MS = 300
let lastWheelPageTime = 0

function updateContainerSize() {
  if (viewportRef.value) {
    containerWidth.value = viewportRef.value.clientWidth
    containerHeight.value = viewportRef.value.clientHeight
  }
}

const page = computed<PageInfo | null>(() => {
  if (props.pages.length === 0) return null
  const idx = Math.min(Math.max(props.currentPage - 1, 0), props.pages.length - 1)
  return props.pages[idx]
})

const forceHq = computed(() => props.forceHqPages.has(props.currentPage - 1))

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
  () => {
    const total = props.pages.length
    if (total === 0) return
    const idx = Math.min(Math.max(props.currentPage - 1, 0), total - 1)
    emit('visible-range', { start: idx, end: idx, total })
    if (viewportRef.value) {
      viewportRef.value.scrollTop = 0
      viewportRef.value.scrollLeft = 0
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.paged-viewport {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  touch-action: manipulation;
}

.paged-page {
  margin: auto;
  flex-shrink: 0;
}
</style>
