<script setup lang="ts" generic="T">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import AppPagination from './AppPagination.vue'

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
  }>(),
  { pageSize: 20, infinite: true, loading: false, loadingMore: false, appendError: '', disabled: false },
)
const emit = defineEmits<{
  'page-change': [page: number]
  'load-more': []
  retry: []
}>()
defineSlots<{
  default(props: { items: T[]; page: number }): unknown
}>()

const pageCount = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
const blocks = computed(() =>
  Array.from({ length: Math.max(1, Math.ceil(props.items.length / props.pageSize)) }, (_, index) => ({
    page: props.page + index,
    items: props.items.slice(index * props.pageSize, (index + 1) * props.pageSize),
  })),
)
const hasMore = computed(() => props.infinite && props.items.length < props.total)
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
onBeforeUnmount(() => observer?.disconnect())
</script>
<template>
  <section v-for="block in blocks" :key="block.page" class="mpf-page">
    <slot :items="block.items" :page="block.page" />
    <footer v-if="pageCount > 1" class="mpf-pager">
      <AppPagination
        :page="block.page"
        :pages="pageCount"
        :disabled="disabled || loading"
        :label="label"
        @change="emit('page-change', $event)"
      />
    </footer>
  </section>
  <div v-if="hasMore" ref="sentinel" class="mpf-sentinel" aria-hidden="true" />
  <p v-if="loadingMore" class="mpf-status" role="status">正在加载…</p>
  <p v-if="appendError" class="mpf-error" role="alert">
    {{ appendError }}<button class="mpf-retry" @click="emit('retry')">重试加载</button>
  </p>
</template>
<style scoped>
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
