<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useHistoryStore, type HistoryVO } from '@/entities/history'
import { ReadingCover } from '@/entities/comic'
import { AppButton } from '@/shared/ui/button'
import { AppPagination } from '@/shared/ui/pagination'
import { ReadingState } from '@/shared/ui/reading'
import { VirtualGrid } from '@/shared/ui/virtual-grid'
const store = useHistoryStore()
const pageSize = 48
const basePage = ref(1)
const loadedPage = ref(1)
const sentinel = ref<HTMLElement>()
let observer: IntersectionObserver | undefined
const pages = computed(() => Math.max(1, Math.ceil(store.total / pageSize)))
const pageGroups = computed(() =>
  Array.from({ length: Math.ceil(store.list.length / pageSize) }, (_, index) => ({
    page: basePage.value + index,
    items: store.list.slice(index * pageSize, (index + 1) * pageSize),
  })),
)
function statusGroups(items: HistoryVO[]) {
  return [
    { name: '阅读中', items: items.filter((item) => !item.completed) },
    { name: '已读完', items: items.filter((item) => item.completed) },
  ]
}
async function loadFirst() {
  basePage.value = 1
  loadedPage.value = 1
  await store.fetchPage(1)
}
async function loadNext() {
  if (store.loading || store.loadingMore || store.error || store.loadMoreError || !store.hasMore) return
  const next = loadedPage.value + 1
  if (next > pages.value) return
  const before = store.list.length
  loadedPage.value = next
  await store.fetchPage(next, true)
  if (store.loadMoreError) {
    loadedPage.value = next - 1
    return
  }
  if (store.list.length <= before) return
  await nextTick()
  const node = sentinel.value
  if (node && node.getBoundingClientRect().top <= window.innerHeight + 600 && store.hasMore) void loadNext()
}
async function goPage(target: number) {
  if (target < 1 || target > pages.value || target === basePage.value) return
  basePage.value = target
  loadedPage.value = target
  await store.fetchPage(target)
  window.scrollTo({ top: 0, behavior: 'auto' })
}
function retryMore() {
  store.loadMoreError = ''
  void loadNext()
}
function observeSentinel() {
  observer?.disconnect()
  if (!sentinel.value || !store.hasMore) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void loadNext()
    },
    { rootMargin: '600px 0px' },
  )
  observer.observe(sentinel.value)
}
watch(
  [() => store.list.length, () => store.total],
  () => {
    void nextTick(observeSentinel)
  },
  { flush: 'post' },
)
onMounted(async () => {
  await loadFirst()
  void nextTick(observeSentinel)
})
onBeforeUnmount(() => observer?.disconnect())
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
      <div v-if="pages > 1" class="history-pager">
        <AppPagination
          :page="basePage"
          :pages="pages"
          :disabled="store.loading"
          label="阅读历史分页"
          @change="goPage"
        />
      </div>
      <section v-for="pageGroup in pageGroups" :key="pageGroup.page" class="history-page">
        <section
          v-for="group in statusGroups(pageGroup.items)"
          :key="`${pageGroup.page}-${group.name}`"
          class="ca-history-group"
        >
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
        <div v-if="pages > 1" class="history-pager history-pager-block">
          <AppPagination
            :page="pageGroup.page"
            :pages="pages"
            :disabled="store.loading"
            label="阅读历史分页"
            @change="goPage"
          />
        </div>
      </section>
      <div v-if="store.list.length && store.hasMore" ref="sentinel" class="history-sentinel" aria-hidden="true" />
      <p v-if="store.loadingMore" class="history-infinite-status" role="status">正在加载…</p>
      <p v-if="store.loadMoreError" class="ca-inline-error" role="alert">
        {{ store.loadMoreError }}<AppButton variant="text" @click="retryMore">重试加载</AppButton>
      </p>
    </template>
  </section>
</template>
<style scoped>
.history-pager {
  display: flex;
  justify-content: center;
  margin: 18px 0;
}
.history-pager-block {
  margin: 28px 0 42px;
}
.history-sentinel {
  height: 1px;
}
.history-infinite-status {
  margin: 18px 0 6px;
  color: var(--atlas-faint);
  font-size: 10px;
  letter-spacing: 0.12em;
  text-align: center;
}
</style>
