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
      v-if="recentlyAddedItems.length"
      title="最近加入"
      :items="recentlyAddedItems"
      more-link="/comics"
    />

    <HomeActionGrid />

    <footer class="home-footer">
      <div class="footer-inner">
        <div class="footer-stat">
          <span class="stat-value">{{ formatNumber(stats?.comicCount ?? 0) }}</span>
          <span class="stat-label">漫画</span>
        </div>
        <div class="footer-stat">
          <span class="stat-value">{{ formatNumber(stats?.pageCount ?? 0) }}</span>
          <span class="stat-label">总页数</span>
        </div>
        <div class="footer-stat">
          <span class="stat-value">{{ formatNumber(stats?.todayImported ?? 0) }}</span>
          <span class="stat-label">今日导入</span>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import HomeHero from '@/components/home/HomeHero.vue'
import HomeRow from '@/components/home/HomeRow.vue'
import HomeActionGrid from '@/components/home/HomeActionGrid.vue'
import { useHistoryStore } from '@/stores/history-store'
import { useComicStore } from '@/stores/comic-store'
import { useDashboardStore } from '@/stores/dashboard-store'
import type { HomeRowItem } from '@/components/home/HomeRow.vue'
import type { HistoryVO, ComicListVO } from '@/types'

const historyStore = useHistoryStore()
const comicStore = useComicStore()
const dashboardStore = useDashboardStore()

const heroHistory = computed<HistoryVO | undefined>(() => historyStore.list[0])

function toHistoryRowItem(h: HistoryVO): HomeRowItem {
  return {
    id: h.comicId,
    cover: h.coverUrl,
    title: h.comicTitle,
    subtitle: `第 ${h.chapterNo} 章 · 第 ${h.pageNumber}/${h.totalPages} 页`,
    progress: h.progressPercent,
    link: `/comics/${h.comicId}/read?chapterId=${h.chapterId}&page=${h.pageNumber}`,
    detailLink: `/comics/${h.comicId}`,
  }
}

const continueReadingItems = computed<HomeRowItem[]>(() =>
  historyStore.list.slice(0, 8).map(toHistoryRowItem)
)

function toComicRowItem(c: ComicListVO): HomeRowItem {
  return {
    id: c.id,
    cover: c.coverUrl,
    title: c.title,
    subtitle: `${c.pageCount} 页`,
    progress: c.progressPercent,
    link: `/comics/${c.id}`,
  }
}

const recentlyAddedItems = computed<HomeRowItem[]>(() => {
  const sorted = [...comicStore.list].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
  )
  return sorted.slice(0, 8).map(toComicRowItem)
})

const stats = computed(() => dashboardStore.stats)

function formatNumber(n: number): string {
  return n.toLocaleString('zh-CN')
}

onMounted(() => {
  historyStore.fetchList()
  comicStore.search({ sort: 'createdAt' })
  dashboardStore.fetch()
})
</script>

<style scoped>
.home-page {
  min-height: calc(100vh - var(--nav-height));
  padding-bottom: var(--space-3xl);
  background: var(--bg-primary);
  color: var(--text-primary);
}

.home-footer {
  margin-top: var(--space-3xl);
  padding: var(--space-2xl) var(--page-padding);
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

.footer-inner {
  display: flex;
  justify-content: center;
  gap: var(--space-2xl);
  max-width: var(--page-width);
  margin: 0 auto;
}

.footer-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-xs);
}

.stat-value {
  font-size: 28px;
  font-weight: 800;
  line-height: 1;
  color: var(--text-primary);
}

.stat-label {
  font-size: 12px;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

@media (max-width: 768px) {
  .footer-inner {
    flex-direction: column;
    gap: var(--space-lg);
  }
}
</style>
