<template>
  <section v-if="items.length" class="home-row">
    <div class="row-header">
      <h2 class="row-title">{{ title }}</h2>
      <router-link v-if="moreLink" :to="moreLink" class="row-more">
        查看更多
        <el-icon :size="14"><ArrowRight /></el-icon>
      </router-link>
    </div>

    <div
      ref="trackRef"
      class="row-track"
      @mouseenter="showArrows = true"
      @mouseleave="showArrows = false"
    >
      <button
        v-show="showArrows && canScrollLeft"
        type="button"
        class="row-arrow row-arrow--left"
        aria-label="向左滚动"
        @click="scrollBy(-1)"
      >
        <el-icon :size="24"><ArrowLeft /></el-icon>
      </button>

      <div class="row-items">
        <ComicPoster
          v-for="item in items"
          :key="item.id"
          :id="item.id"
          :cover-url="item.cover"
          :title="item.title"
          :subtitle="item.subtitle"
          :progress="item.progress"
          status="ready"
          size="md"
          @click="onItemClick(item)"
          @continue="onItemClick(item)"
          @detail="onItemDetail(item)"
        />
      </div>

      <button
        v-show="showArrows && canScrollRight"
        type="button"
        class="row-arrow row-arrow--right"
        aria-label="向右滚动"
        @click="scrollBy(1)"
      >
        <el-icon :size="24"><ArrowRight /></el-icon>
      </button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import ComicPoster from '@/components/reading/comic/ComicPoster.vue'

export interface HomeRowItem {
  id: string | number
  cover: string
  title: string
  subtitle?: string
  progress?: number
  link?: string
  detailLink?: string
}

interface HomeRowProps {
  title: string
  items: HomeRowItem[]
  moreLink?: string
}

const props = defineProps<HomeRowProps>()
const router = useRouter()

const trackRef = ref<HTMLElement | null>(null)
const showArrows = ref(false)
const scrollLeft = ref(0)
const scrollWidth = ref(0)
const clientWidth = ref(0)

const canScrollLeft = computed(() => scrollLeft.value > 0)
const canScrollRight = computed(() => scrollLeft.value + clientWidth.value < scrollWidth.value - 1)

function syncScrollState() {
  const el = trackRef.value
  if (!el) return
  scrollLeft.value = el.scrollLeft
  scrollWidth.value = el.scrollWidth
  clientWidth.value = el.clientWidth
}

function scrollBy(direction: -1 | 1) {
  const el = trackRef.value
  if (!el) return
  const distance = el.clientWidth * 0.8
  el.scrollBy({ left: direction * distance, behavior: 'smooth' })
}

function onWheel(e: WheelEvent) {
  if (!e.shiftKey) return
  e.preventDefault()
  const el = trackRef.value
  if (!el) return
  el.scrollBy({ left: e.deltaY, behavior: 'instant' })
}

function onItemClick(item: HomeRowItem) {
  if (item.link) {
    router.push(item.link)
  }
}

function onItemDetail(item: HomeRowItem) {
  if (item.detailLink) {
    router.push(item.detailLink)
  } else if (item.link) {
    router.push(item.link)
  }
}

onMounted(() => {
  syncScrollState()
  const el = trackRef.value
  if (el) {
    el.addEventListener('scroll', syncScrollState, { passive: true })
    el.addEventListener('wheel', onWheel, { passive: false })
  }
  window.addEventListener('resize', syncScrollState)
})

onBeforeUnmount(() => {
  const el = trackRef.value
  if (el) {
    el.removeEventListener('scroll', syncScrollState)
    el.removeEventListener('wheel', onWheel)
  }
  window.removeEventListener('resize', syncScrollState)
})
</script>

<style scoped>
.home-row {
  margin-top: var(--space-xl);
  color: var(--text-primary);
}

.row-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-base);
  padding: 0 var(--page-padding);
  max-width: var(--page-width);
  margin: 0 auto var(--space-base);
}

.row-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.2;
}

.row-more {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  text-decoration: none;
  transition: color var(--transition-fast);
}

.row-more:hover {
  color: var(--text-primary);
}

.row-track {
  position: relative;
  display: flex;
  overflow-x: auto;
  overflow-y: hidden;
  scroll-behavior: smooth;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.row-track::-webkit-scrollbar {
  display: none;
}

.row-items {
  display: flex;
  flex-shrink: 0;
  gap: var(--poster-gap);
  padding: var(--space-md) var(--page-padding);
}

.row-arrow {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(20, 20, 20, 0.7);
  color: var(--text-primary);
  border: none;
  cursor: pointer;
  z-index: 10;
  opacity: 0;
  transition: opacity var(--transition-fast), background-color var(--transition-fast);
}

.row-track:hover .row-arrow {
  opacity: 1;
}

.row-arrow:hover {
  background: rgba(20, 20, 20, 0.9);
}

.row-arrow--left {
  left: 0;
}

.row-arrow--right {
  right: 0;
}

@media (max-width: 768px) {
  .row-arrow {
    display: none;
  }
}
</style>
