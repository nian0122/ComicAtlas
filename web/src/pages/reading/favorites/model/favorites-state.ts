import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { favoritesApi, type FavoriteItem } from '@/entities/favorite'
import { getApiErrorMessage } from '@/shared/api/http'

export const FAVORITES_PAGE_SIZE = 48

/** 喜欢的作品分页与排序属于页面；实体层只提供接口。 */
export const useFavoritesPage = defineStore('favorites-page', () => {
  const items = ref<FavoriteItem[]>([]),
    total = ref(0),
    oldest = ref(false),
    basePage = ref(1),
    page = ref(0),
    loading = ref(false),
    error = ref(''),
    loadMoreError = ref('')
  const hasMore = computed(() => items.value.length < total.value)
  async function fetchPage(target: number, append = false) {
    if (append && (loading.value || !hasMore.value)) return
    loading.value = true
    if (append) loadMoreError.value = ''
    else error.value = ''
    try {
      const data = await favoritesApi.list(oldest.value, target, FAVORITES_PAGE_SIZE)
      items.value = append
        ? [...items.value, ...data.items.filter((item) => !items.value.some((old) => old.targetId === item.targetId))]
        : data.items
      total.value = data.total
      page.value = target
      if (!append) basePage.value = target
    } catch (reason) {
      const message = getApiErrorMessage(reason, '喜欢的作品加载失败')
      if (append) loadMoreError.value = message
      else error.value = message
    } finally {
      loading.value = false
    }
  }
  function ensureLoaded() {
    if (!items.value.length) void fetchPage(1)
  }
  async function changeOrder() {
    await fetchPage(1)
  }
  return {
    items,
    total,
    oldest,
    basePage,
    page,
    loading,
    error,
    loadMoreError,
    hasMore,
    fetchPage,
    ensureLoaded,
    changeOrder,
  }
})
