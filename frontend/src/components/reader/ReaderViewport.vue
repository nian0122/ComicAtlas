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
        <ReaderImageItem
          :item="item"
          :index="index"
          :active="active"
          :direction="scrollerDirection"
        />
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
    // In horizontal mode, sizeField represents item width
    let baseWidth = 0
    switch (settings.fitMode) {
      case 'WIDTH':
        baseWidth = containerWidth.value
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

  // Vertical mode: sizeField represents item height
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

const scrollerItems = computed<ScrollerItem[]>(() =>
  props.pages.map((page) => ({
    ...page,
    size: computeItemSize(page),
  }))
)

let scrollTimeout: number | null = null
let isProgrammaticScroll = false

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

function deriveCurrentPage(): number {
  if (!scrollerRef.value || props.pages.length === 0) return 1
  const scroll = scrollOffset()
  let accumulated = 0
  for (let i = 0; i < props.pages.length; i++) {
    const size = computeItemSize(props.pages[i])
    if (scroll < accumulated + size / 2) {
      return i + 1
    }
    accumulated += size
  }
  return props.pages.length
}

function scrollToPage(page: number) {
  if (!scrollerRef.value || props.pages.length === 0) return
  let offset = 0
  for (let i = 0; i < page - 1 && i < props.pages.length; i++) {
    offset += computeItemSize(props.pages[i])
  }
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

onMounted(() => {
  updateContainerSize()
  window.addEventListener('resize', updateContainerSize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateContainerSize)
  if (scrollTimeout) clearTimeout(scrollTimeout)
})

watch(() => props.currentPage, (newPage, oldPage) => {
  if (newPage !== oldPage) {
    scrollToPage(newPage)
  }
}, { flush: 'post' })

watch(() => props.pages.length, () => {
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

:deep(.vue-recycle-scroller__item-wrapper) {
  width: 100%;
}
</style>
