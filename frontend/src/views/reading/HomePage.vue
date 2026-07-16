<template>
  <div class="home-page fade-in">
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
import type { HomeRowItem } from '@/components/reading/home/HomeRow.vue'
import type { HistoryVO, ComicListVO } from '@/types'

const historyStore = useHistoryStore()
const comicStore = useComicStore()

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

</style>
