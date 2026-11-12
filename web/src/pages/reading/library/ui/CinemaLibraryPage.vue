<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { comicApi, ReadingCover, type Term } from '@/entities/comic'
import { historyApi, type HistoryVO } from '@/entities/history'
import { storeToRefs } from 'pinia'
import { useLibraryPage } from '../model/library-state'
import { AppButton } from '@/shared/ui/button'
import { AppPagination } from '@/shared/ui/pagination'
import { AppSearch } from '@/shared/ui/search'
import { ReadingState } from '@/shared/ui/reading'
import { VirtualGrid } from '@/shared/ui/virtual-grid'
const route = useRoute(),
  router = useRouter(),
  store = useLibraryPage(),
  categories = ref<Term[]>([]),
  tags = ref<Term[]>([]),
  filterError = ref(''),
  loadMoreError = ref(''),
  recent = ref<HistoryVO[]>([]),
  sentinel = ref<HTMLElement>()
const { status } = storeToRefs(store)
let debounce: ReturnType<typeof setTimeout> | undefined, observer: IntersectionObserver | undefined
const basePage = ref(1)
const loadedPage = ref(1)
const pageSize = computed(() => Math.max(1, Number(store.query.pageSize) || 48))
const pages = computed(() =>
  store.total === null ? 0 : Math.max(1, Math.ceil(store.total / Math.max(1, pageSize.value))),
)
const pageGroups = computed(() => {
  const size = pageSize.value
  return Array.from({ length: Math.ceil(store.list.length / size) }, (_, index) => ({
    page: basePage.value + index,
    items: store.list.slice(index * size, (index + 1) * size),
  }))
})
const selectedTags = computed(() =>
  (store.query.tagId ?? []).map((id) => ({ id, name: tags.value.find((item) => item.id === id)?.name || '所选标签' })),
)
const categoryName = computed(
  () => categories.value.find((item) => item.id === store.query.categoryId)?.name || '所选分类',
)
const filterCount = computed(() => Number(!!store.query.categoryId) + selectedTags.value.length)
const hasQuery = computed(() => !!store.query.keyword || status.value !== '全部' || filterCount.value > 0)
const itemMeta = (item: HistoryVO) =>
  [item.chapterTitle, item.pageNumber ? `第 ${item.pageNumber} 页` : ''].filter(Boolean).join(' · ')
const readerUrl = (item: { chapterId: string; mediaId: string }) =>
  `/reader/${encodeURIComponent(item.chapterId)}?media=${encodeURIComponent(item.mediaId)}`
const featured = computed(() => recent.value.filter((item) => !item.completed).slice(0, 4))
const resumeUrl = computed(() => (featured.value.length ? readerUrl(featured.value[0]!) : ''))

function applyQuery() {
  basePage.value = 1
  loadedPage.value = 1
  store.query.page = 1
  if (route.query.page !== undefined) {
    const nextQuery = { ...route.query }
    delete nextQuery.page
    void router.replace({ name: 'library', query: nextQuery })
  }
  void store.fetchPage()
}
watch(
  () =>
    [
      store.query.keyword,
      store.query.sort,
      store.query.categoryId,
      JSON.stringify(store.query.tagId),
      store.query.order,
      status.value,
    ] as const,
  (next, previous) => {
    clearTimeout(debounce)
    const keywordOnly = next[0] !== previous[0] && next.slice(1).every((value, index) => value === previous[index + 1])
    if (keywordOnly) debounce = setTimeout(applyQuery, 250)
    else applyQuery()
  },
)
function reset() {
  const changed =
    !!store.query.keyword || !!store.query.categoryId || selectedTags.value.length > 0 || status.value !== '全部'
  store.query.keyword = ''
  store.query.categoryId = ''
  store.query.tagId = []
  status.value = '全部'
  if (!changed) applyQuery()
}
function toggleTag(id: string) {
  const ids = store.query.tagId ?? []
  store.query.tagId = ids.includes(id) ? ids.filter((value) => value !== id) : [...ids, id]
}
function clearFilters() {
  store.query.categoryId = ''
  store.query.tagId = []
}
function goPage(target: number) {
  if (target < 1 || target > pages.value || target === basePage.value) return
  const query = { ...route.query }
  if (target > 1) query.page = String(target)
  else delete query.page
  void router.push({ name: 'library', query })
  window.scrollTo({ top: 0, behavior: 'auto' })
}
watch(
  () => route.query.page,
  (value) => {
    const target = Math.max(1, Number(Array.isArray(value) ? value[0] : value) || 1)
    if (target === basePage.value) return
    basePage.value = target
    loadedPage.value = target
    store.query.page = target
    void store.fetchPage()
    window.scrollTo({ top: 0, behavior: 'auto' })
  },
)
async function loadNext() {
  if (store.loading || store.error || loadMoreError.value || store.total === null) return
  if (!store.list.length || store.list.length >= store.total) return
  const next = loadedPage.value + 1
  if (next > pages.value) return
  const before = store.list.length
  loadMoreError.value = ''
  loadedPage.value = next
  store.query.page = next
  await store.fetchPage(true)
  if (store.error) {
    loadMoreError.value = store.error
    store.error = ''
    loadedPage.value = next - 1
    store.query.page = next - 1
    return
  }
  if (store.list.length <= before) return
  await nextTick()
  const node = sentinel.value
  if (node && node.getBoundingClientRect().top <= window.innerHeight + 600 && store.list.length < store.total) {
    void loadNext()
  }
}
function retryMore() {
  loadMoreError.value = ''
  void loadNext()
}
function observeSentinel() {
  observer?.disconnect()
  if (!sentinel.value || !store.list.length || store.total === null) return
  if (store.list.length >= store.total) return
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
async function loadRecent() {
  try {
    recent.value = (await historyApi.page(1, 8)).items
  } catch {
    recent.value = []
  }
}
async function loadFilters() {
  filterError.value = ''
  const results = await Promise.allSettled([comicApi.categories(), comicApi.tags()])
  if (results[0].status === 'fulfilled') categories.value = results[0].value.items
  if (results[1].status === 'fulfilled') tags.value = results[1].value.items
  if (results.some((result) => result.status === 'rejected')) filterError.value = '筛选词条加载失败'
}

onMounted(() => {
  const initialPage = Number(Array.isArray(route.query.page) ? route.query.page[0] : route.query.page)
  if (Number.isInteger(initialPage) && initialPage > 1) {
    basePage.value = initialPage
    loadedPage.value = initialPage
    store.query.page = initialPage
  }
  store.ensureLoaded()
  void loadFilters()
  void loadRecent()
  void nextTick(observeSentinel)
})
onBeforeUnmount(() => {
  clearTimeout(debounce)
  observer?.disconnect()
})
</script>
<template>
  <section class="lib">
    <aside class="lib-rail">
      <AppSearch v-model="store.query.keyword" class="lib-search" label="搜索漫画" placeholder="搜索作品、作者、标签" />
      <div class="lib-rail-sec" role="group" aria-label="按分类筛选">
        <p class="lib-rail-h">分类</p>
        <button :aria-pressed="!store.query.categoryId" @click="store.query.categoryId = ''">全部</button>
        <button
          v-for="category in categories"
          :key="category.id"
          :aria-pressed="store.query.categoryId === category.id"
          @click="store.query.categoryId = category.id"
        >
          {{ category.name }}
        </button>
      </div>
      <div v-if="tags.length" class="lib-rail-sec" role="group" aria-label="按标签筛选">
        <p class="lib-rail-h">标签</p>
        <button :aria-pressed="!selectedTags.length" @click="store.query.tagId = []">全部</button>
        <button
          v-for="tag in tags"
          :key="tag.id"
          :aria-pressed="(store.query.tagId ?? []).includes(tag.id)"
          :disabled="selectedTags.length >= 100 && !(store.query.tagId ?? []).includes(tag.id)"
          @click="toggleTag(tag.id)"
        >
          {{ tag.name }}
        </button>
      </div>
      <a class="lib-rail-foot" href="/manage-entry">漫画管理 →</a>
    </aside>

    <div class="lib-main">
      <div class="lib-topline">
        <p class="lib-count" aria-live="polite">
          <template v-if="store.loading">更新中…</template>
          <template v-else-if="store.total === null">数量暂不可用</template>
          <template v-else
            ><strong>{{ store.total.toLocaleString('zh-CN') }}</strong> 部作品<span v-if="store.error"
              >（上次结果）</span
            ></template
          >
        </p>
        <RouterLink v-if="resumeUrl" class="lib-cta" :to="resumeUrl">继续阅读</RouterLink>
      </div>

      <section v-if="featured.length" class="feat" aria-label="最近阅读">
        <ReadingCover
          v-for="item in featured"
          :id="item.comicId"
          :key="item.comicId"
          :title="item.title"
          :cover="item.coverUrl"
          :caption="item.title"
          :meta="itemMeta(item)"
          :details="false"
          :to="`/comic/${item.comicId}`"
          hover-title
        />
      </section>

      <div class="lib-bar">
        <nav class="lib-tabs" aria-label="阅读状态">
          <button
            v-for="item in ['全部', '未读', '阅读中', '已读完']"
            :key="item"
            :aria-pressed="status === item"
            @click="status = item"
          >
            {{ item }}
          </button>
        </nav>
        <div class="lib-order">
          <select v-model="store.query.sort" aria-label="漫画排序">
            <option value="createdAt">最近入库</option>
            <option value="lastReadTime">最近阅读</option>
            <option value="title">标题</option>
          </select>
          <button
            class="lib-order-toggle"
            :aria-label="store.query.order === 'asc' ? '当前正序，点击切换为倒序' : '当前倒序，点击切换为正序'"
            @click="store.query.order = store.query.order === 'asc' ? 'desc' : 'asc'"
          >
            {{ store.query.order === 'asc' ? '↑' : '↓' }}
          </button>
        </div>
      </div>

      <div v-if="pages > 1 && store.list.length" class="lib-pager">
        <AppPagination :page="basePage" :pages="pages" :disabled="store.loading" label="漫画库分页" @change="goPage" />
      </div>

      <p v-if="filterError" class="ca-inline-error" role="alert">
        {{ filterError }}<AppButton variant="text" @click="loadFilters">重试加载筛选</AppButton>
      </p>
      <div v-if="store.error && store.list.length" class="ca-inline-error" role="alert">
        {{ store.error }}<AppButton variant="text" @click="store.fetchPage()">重试</AppButton>
      </div>
      <div v-if="filterCount" class="lib-active" role="group" aria-label="当前筛选">
        <AppButton
          v-if="store.query.categoryId"
          variant="text"
          size="sm"
          :aria-label="`移除分类筛选：${categoryName}`"
          @click="store.query.categoryId = ''"
          >分类：{{ categoryName }}</AppButton
        >
        <AppButton
          v-for="tag in selectedTags"
          :key="tag.id"
          variant="text"
          size="sm"
          :aria-label="`移除标签筛选：${tag.name}`"
          @click="toggleTag(tag.id)"
          >标签：{{ tag.name }}</AppButton
        >
        <AppButton variant="text" size="sm" @click="clearFilters">清除筛选</AppButton>
      </div>

      <ReadingState v-if="store.loading && !store.list.length" loading />
      <ReadingState
        v-else-if="store.error && !store.list.length"
        error
        :title="store.error"
        action="重新加载"
        @action="store.fetchPage()"
      />
      <div v-else-if="store.list.length" class="lib-pages">
        <section v-for="group in pageGroups" :key="group.page" class="lib-page-block">
          <VirtualGrid class="library-grid" :items="group.items" item-key="id">
            <template #default="{ item }">
              <ReadingCover
                :id="item.id"
                :title="item.title"
                :cover="item.coverUrl"
                :caption="item.title"
                :meta="[item.author, item.categoryName].filter(Boolean).join(' · ')"
                :to="`/comic/${item.id}`"
                :details="false"
                hover-title
              />
            </template>
          </VirtualGrid>
          <div v-if="pages > 1" class="lib-pager">
            <AppPagination
              :page="group.page"
              :pages="pages"
              :disabled="store.loading"
              label="漫画库分页"
              @change="goPage"
            />
          </div>
        </section>
      </div>
      <ReadingState
        v-else
        :title="hasQuery ? '没有找到相关作品' : '漫画库还很安静'"
        :message="hasQuery ? '试试其他关键词，或清除当前筛选条件。' : '作品入库后，会在这里等待你。'"
        :action="hasQuery ? '清除搜索与筛选' : ''"
        @action="reset"
      >
        <div v-if="!hasQuery" class="ca-empty-actions">
          <a class="app-button app-button--primary is-manage-link" href="/manage-entry">前往漫画管理</a>
          <AppButton class="is-refresh" @click="store.fetchPage()">刷新漫画库</AppButton>
        </div>
      </ReadingState>
      <div
        v-if="store.list.length && store.total !== null && store.list.length < store.total"
        ref="sentinel"
        class="lib-sentinel"
        aria-hidden="true"
      />
      <p v-if="store.loading && store.list.length" class="lib-infinite-status" role="status">正在加载…</p>
      <p v-if="loadMoreError" class="ca-inline-error" role="alert">
        {{ loadMoreError }}<AppButton variant="text" @click="retryMore">重试加载</AppButton>
      </p>
    </div>
  </section>
</template>
<style scoped>
.lib-active {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
}
.lib-active .app-button {
  font-size: 11px;
  letter-spacing: 0.1em;
  color: var(--atlas-muted);
}
.lib-pager {
  display: flex;
  justify-content: center;
  margin: 16px 0;
}
.lib-page-block .lib-pager {
  margin: 24px 0 32px;
}
.lib-sentinel {
  height: 1px;
}
.lib-infinite-status {
  margin: 18px 0 6px;
  color: var(--atlas-faint);
  font-size: 10px;
  letter-spacing: 0.12em;
  text-align: center;
}
@media (min-width: 900px) {
  .lib-pager {
    display: flex;
    justify-content: center;
    margin: 20px 0 4px;
  }

  .lib-page-block .lib-pager {
    margin: 28px 0 42px;
  }
}
.lib-order-toggle {
  width: 26px;
  height: 34px;
  padding: 0;
  font-size: 12px;
  color: var(--atlas-muted);
  background: none;
  border: 0;
  cursor: pointer;
}
@media (max-width: 899px) {
  .ca-empty-actions .is-manage-link {
    display: none;
  }
}
</style>
