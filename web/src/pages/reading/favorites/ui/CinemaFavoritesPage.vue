<script setup lang="ts">
import { AppSearch } from '@/shared/ui/search'
import { ref, computed, nextTick, onBeforeUnmount, onMounted, watch } from 'vue'
import { favoritesApi, type FavoriteItem } from '@/entities/favorite'
import { ReadingCover } from '@/entities/comic'
import { AppButton } from '@/shared/ui/button'
import { AppPagination } from '@/shared/ui/pagination'
import { ReadingState } from '@/shared/ui/reading'
import { VirtualGrid } from '@/shared/ui/virtual-grid'
import { getApiErrorMessage } from '@/shared/api/http'
const pageSize = 48
const items = ref<FavoriteItem[]>([]),
  total = ref(0),
  loading = ref(false),
  error = ref(''),
  loadMoreError = ref(''),
  query = ref(''),
  oldest = ref(false),
  basePage = ref(1),
  loadedPage = ref(1),
  sentinel = ref<HTMLElement>()
let observer: IntersectionObserver | undefined
const visible = computed(() =>
  items.value.filter((item) => item.comicTitle.toLowerCase().includes(query.value.toLowerCase())),
)
const pages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const pageGroups = computed(() =>
  Array.from({ length: Math.ceil(items.value.length / pageSize) }, (_, index) => ({
    page: basePage.value + index,
    items: items.value.slice(index * pageSize, (index + 1) * pageSize),
  })),
)
const hasMore = computed(() => items.value.length < total.value)
function visibleIn(pageItems: FavoriteItem[]) {
  return pageItems.filter((item) => item.comicTitle.toLowerCase().includes(query.value.toLowerCase()))
}
async function fetchPage(target: number, append = false) {
  if (append && (loading.value || !hasMore.value)) return
  loading.value = true
  error.value = ''
  try {
    const data = await favoritesApi.list(oldest.value, target, pageSize)
    items.value = append
      ? [...items.value, ...data.items.filter((item) => !items.value.some((old) => old.targetId === item.targetId))]
      : data.items
    total.value = data.total
  } catch (reason) {
    error.value = getApiErrorMessage(reason, '喜欢的作品加载失败')
  } finally {
    loading.value = false
  }
}
async function loadFirst() {
  basePage.value = 1
  loadedPage.value = 1
  await fetchPage(1)
}
async function loadNext() {
  if (loading.value || error.value || loadMoreError.value || !hasMore.value) return
  const next = loadedPage.value + 1
  if (next > pages.value) return
  const before = items.value.length
  loadMoreError.value = ''
  loadedPage.value = next
  await fetchPage(next, true)
  if (error.value) {
    loadMoreError.value = error.value
    error.value = ''
    loadedPage.value = next - 1
    return
  }
  if (items.value.length <= before) return
  await nextTick()
  const node = sentinel.value
  if (node && node.getBoundingClientRect().top <= window.innerHeight + 600 && hasMore.value) void loadNext()
}
function retryMore() {
  loadMoreError.value = ''
  void loadNext()
}
async function goPage(target: number) {
  if (target < 1 || target > pages.value || target === basePage.value) return
  basePage.value = target
  loadedPage.value = target
  await fetchPage(target)
  window.scrollTo({ top: 0, behavior: 'auto' })
}
function changeOrder() {
  void loadFirst()
}
function observeSentinel() {
  observer?.disconnect()
  if (!sentinel.value || !hasMore.value) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void loadNext()
    },
    { rootMargin: '600px 0px' },
  )
  observer.observe(sentinel.value)
}
watch(
  [() => items.value.length, () => total.value],
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
      <h1>喜欢</h1>
      <span>{{ visible.length }} 部</span>
    </header>
    <AppSearch v-model="query" label="搜索喜欢的作品" placeholder="搜索喜欢的作品" />
    <div class="ca-list-meta">
      <span>{{ visible.length }} 部已加载作品</span
      ><select v-model="oldest" aria-label="收藏排序" @change="changeOrder">
        <option :value="false">最近喜欢</option>
        <option :value="true">最早喜欢</option>
      </select>
    </div>
    <ReadingState v-if="loading && !items.length" loading />
    <div v-if="error" class="ca-inline-error" role="alert">
      {{ error }}<AppButton variant="text" @click="loadFirst">重试</AppButton>
    </div>
    <template v-if="visible.length">
      <div v-if="pages > 1" class="favorites-pager">
        <AppPagination :page="basePage" :pages="pages" :disabled="loading" label="喜欢分页" @change="goPage" />
      </div>
      <section v-for="pageGroup in pageGroups" :key="pageGroup.page" class="favorites-page">
        <VirtualGrid
          v-if="visibleIn(pageGroup.items).length"
          :items="visibleIn(pageGroup.items)"
          item-key="targetId"
          :columns="5"
          :compact-columns="3"
        >
          <template #default="{ item }">
            <ReadingCover
              :id="item.comicId"
              :title="item.comicTitle"
              :cover="item.previewUrl"
              :caption="item.comicTitle"
              :meta="item.lastReadPageNumber ? `上次读到第 ${item.lastReadPageNumber} 页` : ''"
              :to="
                item.lastReadChapterId
                  ? `/reader/${encodeURIComponent(item.lastReadChapterId)}`
                  : `/comic/${item.comicId}`
              "
            />
          </template>
        </VirtualGrid>
        <div v-if="pages > 1" class="favorites-pager favorites-pager-block">
          <AppPagination :page="pageGroup.page" :pages="pages" :disabled="loading" label="喜欢分页" @change="goPage" />
        </div>
      </section>
      <div v-if="hasMore" ref="sentinel" class="favorites-sentinel" aria-hidden="true" />
      <p v-if="loading" class="favorites-infinite-status" role="status">正在加载…</p>
      <p v-if="loadMoreError" class="ca-inline-error" role="alert">
        {{ loadMoreError }}<AppButton variant="text" @click="retryMore">重试加载</AppButton>
      </p>
    </template>
    <ReadingState
      v-else-if="!loading && !error"
      :title="query ? '没有找到相关作品' : '把喜欢的故事留在这里'"
      :message="query ? '试试其他关键词。' : '在作品详情中点击喜欢，即可加入收藏。'"
      :action="query ? '清除搜索' : ''"
      @action="query = ''"
    />
  </section>
</template>
<style scoped>
.favorites-pager {
  display: flex;
  justify-content: center;
  margin: 18px 0;
}
.favorites-pager-block {
  margin: 28px 0 42px;
}
.favorites-sentinel {
  height: 1px;
}
.favorites-infinite-status {
  margin: 18px 0 6px;
  color: var(--atlas-faint);
  font-size: 10px;
  letter-spacing: 0.12em;
  text-align: center;
}
</style>
