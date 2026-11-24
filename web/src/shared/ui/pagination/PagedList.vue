<script setup lang="ts" generic="T">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { readScrollPosition, scrollToOffset } from '@/shared/lib/scroll-restore'
import AppPagination from './AppPagination.vue'
import { buildPageBlocks } from './model/page-blocks'

/*
 * 阅读端分页列表的共享外壳：页块切分、页码入口、滚动追加、追加状态和重试都只在这里实现。
 * 页面只通过默认插槽渲染自己的封面/分组，不再各写一套 IntersectionObserver 和页码。
 */
const route = useRoute()
const props = withDefaults(
  defineProps<{
    items: T[]
    page: number
    total: number
    pageSize?: number
    loading?: boolean
    loadingMore?: boolean
    appendError?: string
    disabled?: boolean
    label: string
    infinite?: boolean
  }>(),
  { pageSize: 48, loading: false, loadingMore: false, appendError: '', disabled: false, infinite: true },
)
const emit = defineEmits<{
  'page-change': [page: number]
  'load-more': []
  retry: []
}>()
defineSlots<{ default(props: { items: T[]; page: number }): unknown }>()

const blocks = computed(() => buildPageBlocks(props.items, props.page, props.pageSize))
const pages = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.pageSize))))
const lastLoadedPage = computed(() => props.page + Math.max(0, blocks.value.length - 1))
const hasMore = computed(() => props.infinite && props.items.length > 0 && lastLoadedPage.value < pages.value)

const sentinel = ref<HTMLElement>()
let observer: IntersectionObserver | undefined
function observe() {
  observer?.disconnect()
  if (!sentinel.value || !hasMore.value || props.loadingMore || props.appendError) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) emit('load-more')
    },
    { rootMargin: '600px 0px' },
  )
  observer.observe(sentinel.value)
}
watch(
  [() => props.items.length, () => props.total, () => props.loadingMore, () => props.appendError],
  () => {
    void nextTick(observe)
  },
  { flush: 'post', immediate: true },
)
onMounted(async () => {
  void nextTick(observe)
  // 返回列表时路由已给出位置，但封面网格宽度和页块高度还要实测；等布局就绪后再落点。
  const top = readScrollPosition(route.fullPath)
  if (top <= 0) return
  await nextTick()
  await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
  void scrollToOffset(top)
})
onBeforeUnmount(() => observer?.disconnect())
</script>
<template>
  <div class="paged-list">
    <div v-if="pages > 1" class="paged-pager paged-pager-top">
      <AppPagination
        :page="page"
        :pages="pages"
        :disabled="disabled || loading"
        :label="label"
        @change="emit('page-change', $event)"
      />
    </div>
    <section v-for="block in blocks" :key="block.key" class="paged-block">
      <slot :items="block.items" :page="block.page" />
      <div v-if="pages > 1" class="paged-pager">
        <AppPagination
          :page="block.page"
          :pages="pages"
          :disabled="disabled || loading"
          :label="label"
          @change="emit('page-change', $event)"
        />
      </div>
    </section>
    <div v-if="hasMore" ref="sentinel" class="paged-sentinel" aria-hidden="true" />
    <p v-if="loadingMore" class="paged-status" role="status">正在加载…</p>
    <p v-if="appendError" class="paged-error" role="alert">
      {{ appendError }}<button type="button" class="paged-retry" @click="emit('retry')">重试加载</button>
    </p>
  </div>
</template>
<style scoped>
.paged-pager {
  display: flex;
  justify-content: center;
  margin: 24px 0 32px;
}
.paged-pager-top {
  margin-top: 16px;
}
.paged-sentinel {
  height: 1px;
}
.paged-status {
  margin: 18px 0 6px;
  color: var(--atlas-faint);
  font-size: 10px;
  letter-spacing: 0.12em;
  text-align: center;
}
.paged-error {
  margin: 14px 0;
  color: var(--atlas-error);
  font-size: 11px;
  text-align: center;
}
.paged-retry {
  min-height: 32px;
  margin-left: 8px;
  padding: 0 10px;
  border: 1px solid var(--atlas-control-border);
  background: transparent;
  color: var(--atlas-text);
  font-size: 11px;
  cursor: pointer;
}
@media (min-width: 900px) {
  .paged-pager {
    margin: 28px 0 42px;
  }
}
</style>
