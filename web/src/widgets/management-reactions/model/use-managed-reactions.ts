import { ref, computed, watch, onBeforeUnmount } from 'vue'
import {
  managedReactionApi,
  reactionKey,
  type ManagedReaction,
  type ReactionTarget,
  type ReactionQuery,
  type ReactionType,
} from '@/entities/managed-reaction'
import { getApiErrorMessage } from '@/shared/api/http'
import { recyclableDislikes } from './recycle'
export function useManagedReactions(props: { revision: number }) {
  const rows = ref<ManagedReaction[]>([]),
    selected = ref<ReactionTarget[]>([]),
    page = ref(1),
    loadedPage = ref(1),
    total = ref(0),
    loading = ref(false),
    loadingMore = ref(false),
    hasLoaded = ref(false),
    listFailed = ref(false),
    error = ref(''),
    appendError = ref(''),
    notice = ref('')
  const targetType = ref<ReactionType | ''>(''),
    value = ref<'' | 'like' | 'dislike'>(''),
    mediaType = ref<'' | 'image' | 'video'>(''),
    includeTrashed = ref(false),
    oldest = ref(false)
  const confirmOpen = ref(false),
    confirmItems = ref<ReactionTarget[]>([])
  const selectedKeys = computed(() => new Set(selected.value.map(reactionKey)))
  const selectedDislikes = computed(() => recyclableDislikes(selected.value))
  const canEdit = computed(
    () => !listFailed.value && !loading.value && selected.value.length > 0 && selected.value.every((x) => x.canChange),
  )
  const allPage = computed(
    () =>
      rows.value.some((x) => x.canChange) &&
      rows.value.filter((x) => x.canChange).every((x) => selectedKeys.value.has(reactionKey(x))),
  )
  let generation = 0,
    loadedQuery = '',
    controller: AbortController | null = null,
    disposed = false
  async function load(clear = false, append = false) {
    if (append && (loading.value || loadingMore.value || listFailed.value || rows.value.length >= total.value)) return
    const current = ++generation
    controller?.abort()
    controller = new AbortController()
    if (append) loadingMore.value = true
    else {
      loading.value = true
      loadingMore.value = false
      appendError.value = ''
      listFailed.value = false
      error.value = ''
      if (clear) selected.value = []
    }
    const target = append ? loadedPage.value + 1 : page.value
    const query: ReactionQuery = {
      page: target,
      pageSize: 20,
      targetType: targetType.value,
      value: value.value,
      mediaType: targetType.value === 'media' ? mediaType.value : undefined,
      includeTrashed: includeTrashed.value,
      oldest: oldest.value,
    }
    const signature = JSON.stringify({
      targetType: targetType.value,
      value: value.value,
      mediaType: targetType.value === 'media' ? mediaType.value : '',
      includeTrashed: includeTrashed.value,
      oldest: oldest.value,
    })
    if (!append && signature !== loadedQuery) {
      rows.value = []
      total.value = 0
      hasLoaded.value = false
    }
    try {
      const result = await managedReactionApi.list(query, controller.signal)
      if (disposed || current !== generation) return
      if (!append && result.total > 0 && target > Math.ceil(result.total / 20)) {
        page.value = Math.ceil(result.total / 20)
        loadedPage.value = page.value
        await load(clear)
        return
      }
      if (!append && !result.total) {
        page.value = 1
        loadedPage.value = 1
      }
      if (append) {
        rows.value = [
          ...rows.value,
          ...result.items.filter((item) => !rows.value.some((row) => reactionKey(row) === reactionKey(item))),
        ]
        loadedPage.value = target
      } else {
        rows.value = result.items
        page.value = target
        loadedPage.value = target
        selected.value = selected.value.map(
          (item) => result.items.find((row) => reactionKey(row) === reactionKey(item)) ?? item,
        )
      }
      total.value = result.total
      loadedQuery = signature
      hasLoaded.value = true
    } catch (e) {
      if (!disposed && current === generation) {
        if (append) appendError.value = getApiErrorMessage(e, '追加标记失败')
        else {
          listFailed.value = true
          error.value = getApiErrorMessage(e, '读取标记失败')
        }
      }
    } finally {
      if (!disposed && current === generation) {
        if (append) loadingMore.value = false
        else loading.value = false
      }
    }
  }
  function filters() {
    page.value = 1
    loadedPage.value = 1
    notice.value = ''
    void load(true)
  }
  function changeType() {
    mediaType.value = ''
    filters()
  }
  function clearFilters() {
    targetType.value = ''
    value.value = ''
    mediaType.value = ''
    includeTrashed.value = false
    filters()
  }
  function changePage(next: number) {
    if (loading.value || loadingMore.value) return
    page.value = next
    loadedPage.value = next
    void load(false)
  }
  function loadMore() {
    void load(false, true)
  }
  function retryLoadMore() {
    appendError.value = ''
    void load(false, true)
  }
  function toggle(row: ManagedReaction) {
    if (loading.value || !row.canChange) return
    const key = reactionKey(row)
    if (selectedKeys.value.has(key)) selected.value = selected.value.filter((x) => reactionKey(x) !== key)
    else if (selected.value.length >= 100) error.value = '一次最多选择 100 项，请先处理当前选集'
    else {
      selected.value.push({ ...row })
      error.value = ''
    }
  }
  function selectPage() {
    const allowed = rows.value.filter((x) => x.canChange)
    if (allPage.value) {
      const keys = new Set(allowed.map(reactionKey))
      selected.value = selected.value.filter((x) => !keys.has(reactionKey(x)))
      return
    }
    const extra = allowed.filter((x) => !selectedKeys.value.has(reactionKey(x)))
    if (selected.value.length + extra.length > 100) {
      error.value = '选择已加载项将超过 100 项，请先处理当前选集'
      return
    }
    selected.value.push(...extra)
    error.value = ''
  }
  function edit() {
    if (!canEdit.value || loading.value) return
    confirmItems.value = selected.value.map((x) => ({ ...x }))
    confirmOpen.value = true
  }
  async function saved(count: number) {
    confirmOpen.value = false
    notice.value = `已保存 ${count} 项标记；仅修改所选对象，没有修改内容文件。`
    await load()
  }
  watch(
    () => props.revision,
    () => void load(),
    { immediate: true },
  )
  onBeforeUnmount(() => {
    disposed = true
    generation++
    controller?.abort()
  })
  return {
    rows,
    selected,
    selectedKeys,
    selectedDislikes,
    page,
    total,
    loading,
    loadingMore,
    hasLoaded,
    listFailed,
    error,
    appendError,
    notice,
    targetType,
    value,
    mediaType,
    includeTrashed,
    oldest,
    confirmOpen,
    confirmItems,
    canEdit,
    allPage,
    load,
    filters,
    changeType,
    clearFilters,
    changePage,
    loadMore,
    retryLoadMore,
    toggle,
    selectPage,
    edit,
    saved,
  }
}
