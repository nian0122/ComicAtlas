<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useHistoryStore, type HistoryVO } from '@/entities/history'
import { ReadingCover } from '@/entities/comic'
import { PagedList } from '@/shared/ui/pagination'
import { ReadingState } from '@/shared/ui/reading'
import { VirtualGrid } from '@/shared/ui/virtual-grid'
const store = useHistoryStore()
const pageSize = 48
const pages = computed(() => Math.max(1, Math.ceil(store.total / pageSize)))
function statusGroups(items: HistoryVO[]) {
  return [
    { name: '阅读中', items: items.filter((item) => !item.completed) },
    { name: '已读完', items: items.filter((item) => item.completed) },
  ]
}
async function loadFirst() {
  await store.fetchPage(1)
}
async function loadNext() {
  if (store.loading || store.loadingMore || store.error || store.loadMoreError || !store.hasMore) return
  const next = store.page + 1
  if (next > pages.value) return
  await store.fetchPage(next, true)
}
async function goPage(target: number) {
  if (target < 1 || target > pages.value || target === store.basePage) return
  await store.fetchPage(target)
  window.scrollTo({ top: 0, behavior: 'auto' })
}
function retryMore() {
  store.loadMoreError = ''
  void loadNext()
}
onMounted(async () => {
  if (!store.list.length) await loadFirst()
})
</script>
<template>
  <section class="ca-collection">
    <header class="ca-page-heading">
      <h1>阅读历史</h1>
      <span>{{ store.total }} 部作品</span>
    </header>
    <ReadingState v-if="store.loading && !store.list.length" loading />
    <ReadingState
      v-else-if="store.error && !store.list.length"
      error
      :title="store.error"
      action="重试"
      @action="loadFirst"
    />
    <ReadingState
      v-else-if="!store.list.length"
      title="故事，还没有开始"
      message="打开一部作品，你的阅读足迹会留在这里。"
    />
    <template v-else>
      <PagedList
        :items="store.list"
        :page="store.basePage"
        :page-size="pageSize"
        :total="store.total"
        :loading="store.loading"
        :loading-more="store.loadingMore"
        :append-error="store.loadMoreError"
        :disabled="store.loading"
        label="阅读历史分页"
        @page-change="goPage"
        @load-more="loadNext"
        @retry="retryMore"
      >
        <template #default="{ items }">
          <section v-for="group in statusGroups(items)" :key="group.name" class="ca-history-group">
            <header>
              <h2>{{ group.name }}</h2>
              <span>{{ group.items.length }}</span>
            </header>
            <VirtualGrid
              v-if="group.items.length"
              :items="group.items"
              item-key="comicId"
              :columns="5"
              :compact-columns="3"
            >
              <template #default="{ item }">
                <ReadingCover
                  :id="item.comicId"
                  :title="item.title"
                  :cover="item.coverUrl"
                  :caption="item.title"
                  :meta="
                    [item.chapterTitle, item.pageNumber ? `第 ${item.pageNumber} 页` : ''].filter(Boolean).join(' · ')
                  "
                  :to="`/reader/${encodeURIComponent(item.chapterId)}?media=${encodeURIComponent(item.mediaId)}`"
                />
              </template>
            </VirtualGrid>
            <p v-else class="ca-muted">暂无{{ group.name }}的作品</p>
          </section>
        </template>
      </PagedList>
    </template>
  </section>
</template>
<style scoped></style>
