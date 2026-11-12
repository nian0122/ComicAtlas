import { defineStore, storeToRefs } from 'pinia'
import { ref, computed } from 'vue'
import { useReadingCatalog, type ComicListQuery } from '@/entities/comic'
/** 查询、阅读筛选与分页属于页面；实体层只保存作品缓存和数据请求。 */
export const useLibraryPage = defineStore('library-page', () => {
  const catalog = useReadingCatalog(),
    query = ref<ComicListQuery>({ page: 1, pageSize: 48, sort: 'createdAt', order: 'desc', tagId: [] }),
    status = ref('全部')
  const key = computed(() =>
    JSON.stringify([
      query.value.keyword,
      query.value.categoryId,
      query.value.tagId,
      query.value.sort,
      query.value.order,
      status.value,
    ]),
  )
  let loadedKey = ''
  async function fetchPage(append = false) {
    const requested = key.value
    const readStatus = ({ 全部: '', 未读: 'unread', 阅读中: 'reading', 已读完: 'completed' } as const)[
      status.value as '全部' | '未读' | '阅读中' | '已读完'
    ]
    await catalog.fetchPage({ ...query.value, readStatus }, append)
    if (!catalog.error && requested === key.value) loadedKey = requested
  }
  function ensureLoaded() {
    if (!catalog.list.length || loadedKey !== key.value) {
      query.value.page = 1
      void fetchPage()
    }
  }
  return { ...storeToRefs(catalog), query, status, fetchPage, ensureLoaded }
})
