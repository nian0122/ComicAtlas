import { onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'

export interface InfiniteScrollOptions {
  readonly loadMore: () => Promise<boolean>
  readonly disabled?: Ref<boolean>
}

export function useInfiniteScroll(options: InfiniteScrollOptions) {
  const sentinel = ref<HTMLElement | null>(null)
  const loading = ref(false)
  const hasMore = ref(true)
  let observer: IntersectionObserver | null = null

  async function load() {
    if (loading.value || !hasMore.value || options.disabled?.value) return
    loading.value = true
    try {
      hasMore.value = await options.loadMore()
    } finally {
      loading.value = false
    }
  }

  function reset() {
    hasMore.value = true
  }

  onMounted(() => {
    observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) void load()
      },
      { rootMargin: '240px 0px' },
    )
    watch(
      sentinel,
      (element) => {
        if (element) observer?.observe(element)
      },
      { flush: 'post', immediate: true },
    )
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
  })

  return { sentinel, loading, hasMore, load, reset }
}
