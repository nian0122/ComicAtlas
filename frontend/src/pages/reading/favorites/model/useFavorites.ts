import { onBeforeUnmount, ref } from 'vue'
import { favoritesApi, type FavoriteItem, type FavoriteTarget } from '@/features/favorites'
import { getApiErrorMessage } from '@/shared/api/http'

const PAGE_SIZE = 24
export function useFavorites() {
  const items = ref<FavoriteItem[]>([])
  const loading = ref(false)
  const error = ref('')
  const busy = ref(false)
  const removed = ref<FavoriteItem | null>(null)
  const page = ref(1)
  const hasNext = ref(false)
  let sequence = 0
  let undoTimer: ReturnType<typeof setTimeout> | undefined
  let target: FavoriteTarget = 'COMIC'
  let oldest = false
  let disposed = false

  async function load(nextTarget = target, nextOldest = oldest, nextPage = page.value) {
    target = nextTarget
    oldest = nextOldest
    page.value = nextPage
    const request = ++sequence
    loading.value = true
    error.value = ''
    items.value = []
    try {
      // 多取一项判断下一页，避免为每个标签额外执行总数查询。
      const response = await favoritesApi.list(target, oldest, nextPage, PAGE_SIZE)
      if (request !== sequence) return
      items.value = response.data.slice(0, PAGE_SIZE)
      hasNext.value = response.data.length > PAGE_SIZE
    } catch (cause) {
      if (request === sequence) error.value = getApiErrorMessage(cause, '喜欢列表加载失败')
    } finally {
      if (request === sequence) loading.value = false
    }
  }

  async function change(item: FavoriteItem, reaction: 'NONE' | 'LIKE') {
    if (busy.value) return
    busy.value = true
    error.value = ''
    try {
      await favoritesApi.mark(item, reaction)
      if (disposed) return
      clearTimeout(undoTimer)
      removed.value = reaction === 'NONE' ? item : null
      if (removed.value)
        undoTimer = setTimeout(() => {
          removed.value = null
        }, 6000)
      await load()
      if (!items.value.length && page.value > 1 && !error.value) await load(target, oldest, page.value - 1)
    } catch (cause) {
      if (!disposed) error.value = getApiErrorMessage(cause, '标记更新失败，请重试')
    } finally {
      busy.value = false
    }
  }
  onBeforeUnmount(() => {
    disposed = true
    sequence++
    clearTimeout(undoTimer)
  })
  return { items, loading, error, busy, removed, page, hasNext, load, change }
}
