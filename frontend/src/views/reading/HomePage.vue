<template>
  <div class="home-page fade-in" :class="{ 'is-mobile': mode === 'mobile' }">
    <HomeHero class="home-hero" :history-item="heroHistory" />

    <HomeRow
      v-if="continueReadingItems.length"
      title="继续阅读"
      :items="continueReadingItems"
      more-link="/history"
      :is-mobile="mode === 'mobile'"
    />

    <HomeRow
      v-if="recentlyAddedItems.length"
      title="最近更新"
      :items="recentlyAddedItems"
      more-link="/library"
      :is-mobile="mode === 'mobile'"
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
import HomeHero from '@/features/home/components/HomeHero.vue'
import HomeRow from '@/features/home/components/HomeRow.vue'
import HomeActionGrid from '@/features/home/components/HomeActionGrid.vue'
import { useHistoryStore } from '@/features/history/store'
import { useComicStore } from '@/features/comic/store'
import { useInteractionMode } from '@/features/reader/composables/useInteractionMode'
import type { HomeRowItem } from '@/features/home/components/HomeRow.vue'
import type { ComicListVO } from '@/entities/comic/types'
import type { HistoryVO } from '@/features/history/types'

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
  historyStore.list
    .filter((h) => h.progressPercent > 0 && h.progressPercent < 100)
    .slice(0, 8)
    .map(toHistoryRowItem),
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
  const sorted = [...comicStore.list].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
  return sorted.slice(0, 8).map(toComicRowItem)
})

onMounted(() => {
  void historyStore.fetchList()
  void comicStore.search({ sort: 'createdAt' })
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
   HomeHero 的内容结构属于子组件，移动端布局需要跨组件作用域覆盖其内部布局节点。
   ========================================================================== */

/* HomeHero：保持全宽，页面留白从 --page-padding(32px) 收紧到 --space-base(16px) */
.home-page.is-mobile > .home-hero :deep(.hero-content) {
  padding: 0 var(--mobile-page-gutter) var(--space-8);
}

/* 移动端阅读入口保持内容优先，不展示仓库操作捷径。 */
.home-page.is-mobile > .home-actions {
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
