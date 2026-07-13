<template>
  <div ref="viewportRef" class="reader-viewport">
    <RecycleScroller
      v-if="containerWidth > 0 && containerHeight > 0"
      ref="scrollerRef"
      class="scroller"
      :class="{ 'scroller-horizontal': isHorizontal }"
      :items="scrollerItems"
      :item-size="null"
      size-field="size"
      key-field="id"
      :buffer="buffer"
      :direction="scrollerDirection"
      @scroll="onScroll"
    >
      <template #default="{ item, index, active }">
        <div :data-index="index" class="reader-item-wrapper">
          <ReaderImageItem
            :item="item"
            :index="index"
            :active="active"
            :direction="scrollerDirection"
          />
        </div>
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

const isHorizontal = computed(() => settings.readingDirection === 'horizontal')
const buffer = computed(() => Math.max(800, containerWidth.value))
const scrollerDirection = computed(() => (isHorizontal.value ? 'horizontal' : 'vertical'))

function computeAspectRatio(page: PageInfo): number {
  if (page.width && page.height && page.height > 0) {
    return page.width / page.height
  }
  return 3 / 4
}

function computeItemSize(page: PageInfo): number {
  const aspectRatio = computeAspectRatio(page)
  const zoom = settings.zoom / 100
  const padding = 16

  if (isHorizontal.value) {
    let baseWidth = 0
    switch (settings.fitMode) {
      case 'WIDTH':
        baseWidth = containerHeight.value * aspectRatio
        break
      case 'HEIGHT':
        baseWidth = containerHeight.value * aspectRatio
        break
      case 'ORIGINAL':
        baseWidth = page.width || containerWidth.value
        break
      case 'AUTO':
      default: {
        const containerRatio = containerWidth.value / containerHeight.value
        if (aspectRatio > containerRatio) {
          baseWidth = containerWidth.value
        } else {
          baseWidth = containerHeight.value * aspectRatio
        }
        break
      }
    }
    return baseWidth * zoom + padding
  }

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
      const containerRatio = containerWidth.value / containerHeight.value
      if (aspectRatio > containerRatio) {
        baseHeight = containerWidth.value / aspectRatio
      } else {
        baseHeight = containerHeight.value
      }
      break
    }
  }
  return baseHeight * zoom + padding
}

const sizes = computed<number[]>(() => {
  // 访问 props.pages 与 computeItemSize 内部访问的所有响应式依赖，
  // 使 sizes 仅在 pages/zoom/fitMode/readingDirection/viewport 变化时重建。
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

const midpoints = computed<number[]>(() =>
  sizes.value.map((size, index) => (index === 0 ? 0 : prefixSums.value[index - 1]) + size / 2)
)

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

let scrollTimeout: number | null = null
let isProgrammaticScroll = false
let pageIntersectionObserver: IntersectionObserver | null = null
let itemMutationObserver: MutationObserver | null = null
const visibilityByIndex = new Map<number, number>()
const observedElements = new Set<Element>()

function scrollOffset(): number {
  if (!scrollerRef.value) return 0
  return isHorizontal.value ? scrollerRef.value.scrollLeft || 0 : scrollerRef.value.scrollTop || 0
}

function setScrollOffset(offset: number) {
  if (!scrollerRef.value) return
  if (isHorizontal.value) {
    scrollerRef.value.$el.scrollLeft = offset
  } else {
    scrollerRef.value.$el.scrollTop = offset
  }
}

function deriveCurrentPage(): number {
  if (midpoints.value.length === 0) return 1
  const offset = scrollOffset()
  const idx = upperBound(midpoints.value, offset)
  return Math.min(idx + 1, props.pages.length)
}

function onScroll() {
  if (isProgrammaticScroll) return
  if (scrollTimeout) clearTimeout(scrollTimeout)
  scrollTimeout = window.setTimeout(() => {
    const page = deriveCurrentPage()
    if (page !== props.currentPage) {
      emit('update:currentPage', page)
    }
  }, 150)
}

function scrollToPage(page: number) {
  if (!scrollerRef.value || props.pages.length === 0) return
  const targetPage = Math.max(1, Math.min(page, props.pages.length))
  const offset = targetPage === 1 ? 0 : prefixSums.value[targetPage - 2]
  isProgrammaticScroll = true
  setScrollOffset(offset)
  nextTick(() => {
    window.setTimeout(() => {
      isProgrammaticScroll = false
    }, 100)
  })
}

function forceUpdateScroller() {
  if (scrollerRef.value) {
    scrollerRef.value.updateItems(true)
  }
}

function updateCurrentPageFromVisibility() {
  let minVisible = Infinity
  let maxVisible = -Infinity
  let bestIndex = -1
  let bestRatio = 0

  for (const [index, ratio] of visibilityByIndex) {
    if (ratio > 0) {
      minVisible = Math.min(minVisible, index)
      maxVisible = Math.max(maxVisible, index)
    }
    if (ratio > bestRatio) {
      bestIndex = index
      bestRatio = ratio
    }
  }

  if (bestIndex >= 0) {
    const page = bestIndex + 1
    if (page !== props.currentPage) {
      emit('update:currentPage', page)
    }
    const start = minVisible !== Infinity ? minVisible : bestIndex
    const end = maxVisible !== -Infinity ? maxVisible : bestIndex
    emit('visible-range', { start, end, total: props.pages.length })
  }
}

function observeActiveItems() {
  if (!scrollerRef.value) return
  const items = scrollerRef.value.$el.querySelectorAll('[data-index]')
  for (const item of items) {
    if (!observedElements.has(item)) {
      pageIntersectionObserver?.observe(item)
      observedElements.add(item)
    }
  }
}

function setupIntersectionObserver() {
  if (!globalThis.IntersectionObserver) return
  if (pageIntersectionObserver) return

  pageIntersectionObserver = new IntersectionObserver(
    (entries) => {
      if (isProgrammaticScroll) return
      let hasRemoved = false
      for (const entry of entries) {
        const el = entry.target as HTMLElement
        if (!document.contains(el)) {
          observedElements.delete(el)
          hasRemoved = true
          continue
        }
        const index = Number(el.dataset.index ?? '0')
        visibilityByIndex.set(index, entry.isIntersecting ? entry.intersectionRatio : 0)
      }
      updateCurrentPageFromVisibility()
      if (hasRemoved) {
        nextTick(observeActiveItems)
      }
    },
    { threshold: [0, 0.25, 0.5, 0.75, 1] }
  )

  itemMutationObserver = new MutationObserver(() => {
    observeActiveItems()
  })

  const wrapper = scrollerRef.value?.$el.querySelector('.vue-recycle-scroller__item-wrapper')
  if (wrapper) {
    itemMutationObserver.observe(wrapper, { childList: true, subtree: true })
  }

  observeActiveItems()
}

function disconnectObservers() {
  pageIntersectionObserver?.disconnect()
  pageIntersectionObserver = null
  itemMutationObserver?.disconnect()
  itemMutationObserver = null
  observedElements.clear()
  visibilityByIndex.clear()
}

onMounted(() => {
  updateContainerSize()
  window.addEventListener('resize', updateContainerSize)
  nextTick(() => {
    setupIntersectionObserver()
  })
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateContainerSize)
  if (scrollTimeout) clearTimeout(scrollTimeout)
  disconnectObservers()
})

watch(() => props.currentPage, (newPage, oldPage) => {
  if (newPage !== oldPage) {
    scrollToPage(newPage)
  }
}, { flush: 'post' })

watch(() => props.pages.length, () => {
  visibilityByIndex.clear()
  nextTick(() => {
    updateContainerSize()
    scrollToPage(props.currentPage)
    observeActiveItems()
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

watch(() => settings.readingDirection, () => {
  nextTick(() => {
    forceUpdateScroller()
    scrollToPage(props.currentPage)
  })
})
</script>

<style scoped>
.reader-viewport {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.scroller {
  width: 100%;
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
}

.scroller.scroller-horizontal {
  overflow-y: hidden;
  overflow-x: auto;
}

.reader-item-wrapper {
  width: 100%;
  height: 100%;
}

:deep(.vue-recycle-scroller__item-wrapper) {
  width: 100%;
}
</style>
