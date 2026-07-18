<template>
  <div class="home-page fade-in" :class="{ 'is-mobile': mode === 'mobile' }">
    <HomeHero :history-item="heroHistory" />

    <HomeRow
      v-if="continueReadingItems.length"
      title="继续阅读"
      :items="continueReadingItems"
      more-link="/history"
    />

    <HomeRow
      v-if="recentReadingItems.length"
      title="最近阅读"
      :items="recentReadingItems"
      more-link="/history"
    />

    <HomeRow
      v-if="recentlyAddedItems.length"
      title="最近加入"
      :items="recentlyAddedItems"
      more-link="/library"
    />

    <HomeActionGrid />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import HomeHero from '@/components/reading/home/HomeHero.vue'
import HomeRow from '@/components/reading/home/HomeRow.vue'
import HomeActionGrid from '@/components/reading/home/HomeActionGrid.vue'
import { useHistoryStore } from '@/stores/history-store'
import { useComicStore } from '@/stores/comic-store'
import { useInteractionMode } from '@/views/reading/reader/composables/useInteractionMode'
import type { HomeRowItem } from '@/components/reading/home/HomeRow.vue'
import type { HistoryVO, ComicListVO } from '@/types'

const historyStore = useHistoryStore()
const comicStore = useComicStore()

// 交互模式检测：mobile 时给根容器加 is-mobile 类，驱动下方移动端布局
const { mode } = useInteractionMode()

const heroHistory = computed<HistoryVO | undefined>(() => historyStore.list[0])

function toHistoryRowItem(h: HistoryVO): HomeRowItem {
  return {
    id: h.comicId,
    cover: h.coverUrl,
    title: h.comicTitle,
    subtitle: `第 ${h.chapterNo} 章 · 第 ${h.pageNumber}/${h.totalPages} 页`,
    progress: h.progressPercent,
    link: `/reader/${h.chapterId}?page=${h.pageNumber}`,
    detailLink: `/comic/${h.comicId}`,
  }
}

const continueReadingItems = computed<HomeRowItem[]>(() =>
  historyStore.list.filter((h) => h.progressPercent > 0 && h.progressPercent < 100).slice(0, 8).map(toHistoryRowItem)
)

const recentReadingItems = computed<HomeRowItem[]>(() =>
  historyStore.list.slice(0, 8).map(toHistoryRowItem)
)

function toComicRowItem(c: ComicListVO): HomeRowItem {
  return {
    id: c.id,
    cover: c.coverUrl,
    title: c.title,
    subtitle: `${c.pageCount} 页`,
    progress: c.progressPercent,
    link: `/comic/${c.id}`,
  }
}

const recentlyAddedItems = computed<HomeRowItem[]>(() => {
  const sorted = [...comicStore.list].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
  )
  return sorted.slice(0, 8).map(toComicRowItem)
})

onMounted(() => {
  historyStore.fetchList()
  comicStore.search({ sort: 'createdAt' })
})
</script>

<style scoped>
.home-page {
  min-height: calc(100vh - var(--nav-height));
  padding-bottom: var(--space-3xl);
  background: var(--bg-primary);
  color: var(--text-primary);
}

/* ==========================================================================
   移动端布局（由 useInteractionMode 驱动；桌面端无 is-mobile 类，完全不受影响）
   遵循设计规范 §5：Layout 负责响应式，业务组件保持设备无关，故统一从父级 :deep() 覆盖
   ========================================================================== */

/* HomeHero：保持全宽，页面留白从 --page-padding(32px) 收紧到 --space-base(16px) */
.home-page.is-mobile :deep(.hero-content) {
  padding: calc(var(--nav-height) + var(--space-lg)) var(--space-base) var(--space-lg);
}

/* HomeRow：横向滚动 + scroll-snap，逐张封面吸附 */
.home-page.is-mobile :deep(.row-track) {
  overflow-x: auto;
  scroll-snap-type: x mandatory;
}

.home-page.is-mobile :deep(.row-header) {
  padding: 0 var(--space-base);
}

.home-page.is-mobile :deep(.row-items) {
  padding-left: var(--space-base);
  padding-right: var(--space-base);
}

/* 每张封面：吸附起点对齐；flex-basis 70vw 覆盖固定宽度，max-width 收口到 160px */
.home-page.is-mobile :deep(.row-items .comic-poster) {
  scroll-snap-align: start;
  flex: 0 0 70vw;
  max-width: 160px;
}

/* HomeActionGrid：3 列改 2 列网格，收紧留白，触控目标 ≥ 44px */
.home-page.is-mobile :deep(.home-actions) {
  padding: 0 var(--space-base);
}

.home-page.is-mobile :deep(.actions-inner) {
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-base);
}

.home-page.is-mobile :deep(.action-card) {
  min-height: 44px;
  padding: var(--space-base);
}
</style>
