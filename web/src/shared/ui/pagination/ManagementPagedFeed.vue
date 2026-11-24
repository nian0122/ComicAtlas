<script setup lang="ts" generic="T">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import { useVirtualFocus } from '@/shared/lib/virtual-focus'
import { scrollToOffset } from '@/shared/lib/scroll-restore'
import AppPagination from './AppPagination.vue'
import { buildPageBlocks } from './model/page-blocks'

/*
 * 页块高度随封面密度、网格/列表和真实标题变化，固定行高会错位；
 * 因此由 DynamicScroller 实测每个页块，并跟随窗口滚动只保留视口附近的页块。
 */
const MIN_BLOCK_SIZE = 600

const props = withDefaults(
  defineProps<{
    items: T[]
    page: number
    total: number
    pageSize?: number
    infinite?: boolean
    loading?: boolean
    loadingMore?: boolean
    appendError?: string
    disabled?: boolean
    label: string
    restore?: { top: number; token: number } | null
  }>(),
  { pageSize: 20, infinite: true, loading: false, loadingMore: false, appendError: '', disabled: false, restore: null },
)
const emit = defineEmits<{
  'page-change': [page: number]
  'load-more': []
  retry: []
  restored: [token: number, settled: boolean]
}>()
defineSlots<{
  default(props: { items: T[]; page: number }): unknown
}>()

const blocks = computed(() => buildPageBlocks(props.items, props.page, props.pageSize))
const pageCount = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
/*
 * 分页窗口可能从中间页开始（页码入口跳转后只加载该页），
 * 此时 items.length 只是当前窗口的行数，不能与总数直接比较。
 * 只要最后一个页块已经到末页，就不能再触发追加，
 * 否则空页会让“已加载数 < 总数”永远成立并持续请求后面的空页。
 */
const lastLoadedPage = computed(() => props.page + Math.max(0, blocks.value.length - 1))
const hasMore = computed(() => props.infinite && props.items.length > 0 && lastLoadedPage.value < pageCount.value)

const root = ref<HTMLElement>()
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

watch(
  () => props.restore,
  async (request) => {
    if (!request) return
    await nextTick()
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    if (props.restore?.token !== request.token) return
    emit('restored', request.token, await scrollToOffset(request.top))
  },
  { immediate: true },
)

/* 页块被回收时把焦点交还给同一页块，规则与虚拟封面网格、目录树共用。 */
const { onFocusIn, onFocusOut } = useVirtualFocus({
  keyOf: (element) => element.closest<HTMLElement>('[data-mpf-block]')?.dataset.mpfBlock ?? '',
  within: () => root.value,
})

onMounted(() => {
  void nextTick(observe)
})
onBeforeUnmount(() => {
  observer?.disconnect()
})
</script>
<template>
  <div ref="root" class="mpf-feed" @focusin="onFocusIn" @focusout="onFocusOut">
    <DynamicScroller
      :key="page"
      class="mpf-scroller"
      :items="blocks"
      key-field="key"
      :min-item-size="MIN_BLOCK_SIZE"
      page-mode
    >
      <template #default="{ item, index, active }">
        <DynamicScrollerItem :item="item" :active="active" :index="index">
          <section class="mpf-page" :data-mpf-block="item.key">
            <slot :items="item.items" :page="item.page" />
            <footer v-if="pageCount > 1" class="mpf-pager">
              <AppPagination
                :page="item.page"
                :pages="pageCount"
                :disabled="disabled || loading"
                :label="label"
                @change="emit('page-change', $event)"
              />
            </footer>
          </section>
        </DynamicScrollerItem>
      </template>
    </DynamicScroller>
    <div v-if="hasMore" ref="sentinel" class="mpf-sentinel" aria-hidden="true" />
    <p v-if="loadingMore" class="mpf-status" role="status">正在加载…</p>
    <p v-if="appendError" class="mpf-error" role="alert">
      {{ appendError }}<button class="mpf-retry" @click="emit('retry')">重试加载</button>
    </p>
  </div>
</template>
<style scoped>
.mpf-feed {
  position: relative;
}
.mpf-page {
  min-width: 0;
}
.mpf-pager {
  display: flex;
  justify-content: center;
  margin: 18px 0 28px;
}
.mpf-sentinel {
  height: 1px;
}
.mpf-status {
  margin: 14px 0 4px;
  color: var(--atlas-muted);
  font-size: 10px;
  letter-spacing: 0.1em;
  text-align: center;
}
.mpf-error {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin: 14px 0 4px;
  color: var(--atlas-error);
  font-size: 11px;
  text-align: center;
}
.mpf-retry {
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid var(--atlas-control-border);
  border-radius: 0;
  background: transparent;
  color: var(--atlas-text);
  font-size: 10px;
}
</style>
