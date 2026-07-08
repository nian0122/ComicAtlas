<template>
  <div ref="viewportRef" class="reader-viewport">
    <RecycleScroller
      v-if="containerWidth > 0"
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
        <ReaderImageItem
          :item="item"
          :index="index"
          :active="active"
        />
      </template>
    </RecycleScroller>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { RecycleScroller } from 'vue-virtual-scroller'
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

const viewportRef = ref<HTMLElement | null>(null)
const scrollerRef = ref<any>(null)
const containerWidth = ref(0)

function updateContainerWidth() {
  if (viewportRef.value) {
    containerWidth.value = viewportRef.value.clientWidth
  }
}

const buffer = computed(() => Math.max(800, containerWidth.value))

function computeItemSize(page: PageInfo): number {
  const width = containerWidth.value || 800
  if (page.width && page.height && page.width > 0) {
    return (width / page.width) * page.height + 16 // 16px for vertical padding
  }
  return (width / 3) * 4 + 16
}

const scrollerItems = computed<ScrollerItem[]>(() =>
  props.pages.map((page) => ({
    ...page,
    size: computeItemSize(page),
  }))
)

let scrollTimeout: number | null = null
let isProgrammaticScroll = false

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
  const scroller = scrollerRef.value
  const scrollTop = scroller.scrollTop || 0
  let accumulated = 0
  for (let i = 0; i < props.pages.length; i++) {
    const size = computeItemSize(props.pages[i])
    if (scrollTop < accumulated + size / 2) {
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
  const scroller = scrollerRef.value
  scroller.$el.scrollTop = offset
  nextTick(() => {
    window.setTimeout(() => {
      isProgrammaticScroll = false
    }, 100)
  })
}

onMounted(() => {
  updateContainerWidth()
  window.addEventListener('resize', updateContainerWidth)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateContainerWidth)
  if (scrollTimeout) clearTimeout(scrollTimeout)
})

watch(() => props.currentPage, (newPage, oldPage) => {
  if (newPage !== oldPage) {
    scrollToPage(newPage)
  }
}, { flush: 'post' })

watch(() => props.pages.length, () => {
  nextTick(() => {
    updateContainerWidth()
    scrollToPage(props.currentPage)
  })
})

watch(containerWidth, () => {
  nextTick(() => {
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

:deep(.vue-recycle-scroller__item-wrapper) {
  width: 100%;
}
</style>
