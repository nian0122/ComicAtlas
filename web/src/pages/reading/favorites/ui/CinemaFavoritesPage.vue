<script setup lang="ts">
import { AppSearch } from '@/shared/ui/search'
import { ref, computed, onMounted } from 'vue'
import type { FavoriteItem } from '@/entities/favorite'
import { ReadingCover } from '@/entities/comic'
import { AppButton } from '@/shared/ui/button'
import { PagedList } from '@/shared/ui/pagination'
import { ReadingState } from '@/shared/ui/reading'
import { VirtualGrid } from '@/shared/ui/virtual-grid'
import { FAVORITES_PAGE_SIZE, useFavoritesPage } from '../model/favorites-state'
const store = useFavoritesPage()
const query = ref('')
const visible = computed(() =>
  store.items.filter((item) => item.comicTitle.toLowerCase().includes(query.value.toLowerCase())),
)
const pages = computed(() => Math.max(1, Math.ceil(store.total / FAVORITES_PAGE_SIZE)))
function visibleIn(pageItems: FavoriteItem[]) {
  return pageItems.filter((item) => item.comicTitle.toLowerCase().includes(query.value.toLowerCase()))
}
async function loadNext() {
  if (store.loading || store.error || store.loadMoreError || !store.hasMore) return
  const next = store.page + 1
  if (next > pages.value) return
  await store.fetchPage(next, true)
}
function retryMore() {
  store.loadMoreError = ''
  void loadNext()
}
async function goPage(target: number) {
  if (target < 1 || target > pages.value || target === store.basePage) return
  await store.fetchPage(target)
  window.scrollTo({ top: 0, behavior: 'auto' })
}
onMounted(() => store.ensureLoaded())
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
      ><select v-model="store.oldest" aria-label="收藏排序" @change="store.changeOrder">
        <option :value="false">最近喜欢</option>
        <option :value="true">最早喜欢</option>
      </select>
    </div>
    <ReadingState v-if="store.loading && !store.items.length" loading />
    <div v-if="store.error" class="ca-inline-error" role="alert">
      {{ store.error }}<AppButton variant="text" @click="store.fetchPage(1)">重试</AppButton>
    </div>
    <template v-if="visible.length">
      <PagedList
        :items="store.items"
        :page="store.basePage"
        :page-size="FAVORITES_PAGE_SIZE"
        :total="store.total"
        :loading="store.loading"
        :loading-more="store.loading && store.items.length > 0"
        :append-error="store.loadMoreError"
        :disabled="store.loading"
        label="喜欢分页"
        @page-change="goPage"
        @load-more="loadNext"
        @retry="retryMore"
      >
        <template #default="{ items: pageItems }">
          <VirtualGrid
            v-if="visibleIn(pageItems).length"
            :items="visibleIn(pageItems)"
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
        </template>
      </PagedList>
    </template>
    <ReadingState
      v-else-if="!store.loading && !store.error"
      :title="query ? '没有找到相关作品' : '把喜欢的故事留在这里'"
      :message="query ? '试试其他关键词。' : '在作品详情中点击喜欢，即可加入收藏。'"
      :action="query ? '清除搜索' : ''"
      @action="query = ''"
    />
  </section>
</template>
<style scoped></style>
