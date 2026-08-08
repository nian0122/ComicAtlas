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
      v-if="recentlyAddedItems.length"
      title="最近更新"
      :items="recentlyAddedItems"
      more-link="/library"
    />

    <HomeActionGrid />

    <footer class="home-footer">
      <span>© 2024 ComicAtlas Archive. 私人高保真控制台。</span>
      <span class="home-footer-meta">本地部署 · 内容由你的仓库提供</span>
    </footer>
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
  min-height: calc(100dvh - var(--nav-height));
  padding-bottom: var(--space-16);
  color: var(--text-primary);
}

.home-footer {
  display: flex;
  justify-content: space-between;
  gap: var(--space-6);
  width: min(100%, var(--content-max));
  padding: var(--space-8) var(--content-gutter) 0;
  margin: var(--space-16) auto 0;
  border-top: 1px solid var(--border);
  color: var(--text-muted);
  font-size: var(--text-xs);
}

.home-footer-meta {
  text-align: right;
}

/* ==========================================================================
   移动端布局（由 useInteractionMode 驱动；桌面端无 is-mobile 类，完全不受影响）
   遵循设计规范 §5：Layout 负责响应式，业务组件保持设备无关，故统一从父级 :deep() 覆盖
   ========================================================================== */

/* HomeHero：保持全宽，页面留白从 --page-padding(32px) 收紧到 --space-base(16px) */
.home-page.is-mobile :deep(.hero-content) {
  padding: 0 var(--mobile-page-gutter) var(--space-8);
}

/* HomeRow：横向滚动 + scroll-snap，逐张封面吸附 */
.home-page.is-mobile :deep(.row-track) {
  overflow-x: auto;
  scroll-snap-type: x mandatory;
}

.home-page.is-mobile :deep(.row-header) {
  padding: 0 var(--mobile-page-gutter);
}

.home-page.is-mobile :deep(.row-items) {
  gap: var(--space-2);
  padding-right: var(--mobile-page-gutter);
  padding-left: var(--mobile-page-gutter);
}

/* 每张封面：吸附起点对齐；flex-basis 70vw 覆盖固定宽度，max-width 收口到 160px */
.home-page.is-mobile :deep(.row-items .comic-poster) {
  scroll-snap-align: start;
  flex: 0 0 min(43vw, 160px);
  max-width: 160px;
}

/* 移动端阅读入口保持内容优先，不展示仓库操作捷径。 */
.home-page.is-mobile :deep(.home-actions) {
  display: none;
}

.home-page.is-mobile .home-footer {
  display: none;
}

@media (max-width: 1024px) {
  .home-page {
    padding-bottom: calc(var(--mobile-tabbar-height) + var(--space-10) + env(safe-area-inset-bottom));
  }

  .home-footer {
    display: none;
  }
}
</style>
