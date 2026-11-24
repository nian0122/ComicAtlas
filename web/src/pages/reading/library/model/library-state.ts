import { defineStore, storeToRefs } from 'pinia'
import { ref, computed } from 'vue'
import { useReadingCatalog, type ComicListQuery } from '@/entities/comic'
import { historyApi, type HistoryVO } from '@/entities/history'
/** 查询、阅读筛选、分页与最近阅读属于页面；实体层只保存作品缓存和数据请求。 */
export const useLibraryPage = defineStore('library-page', () => {
  const catalog = useReadingCatalog(),
    query = ref<ComicListQuery>({ page: 1, pageSize: 48, sort: 'createdAt', order: 'desc', tagId: [] }),
    status = ref('全部'),
    /*
     * 最近阅读是列表上方的异步区块。返回漫画库时若等请求回来才渲染，
     * 恢复的滚动位置会按没有该区块的高度落点，整页随后下移。
     * 缓存放在页面状态里，返回时首帧即可按原高度渲染。
     */
    recent = ref<HistoryVO[]>([])
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
  async function loadRecent() {
    try {
      recent.value = (await historyApi.page(1, 8)).items
    } catch {
      // 读取失败时保留上次的最近阅读，避免列表上方高度突然消失。
    }
  }
  return { ...storeToRefs(catalog), query, status, recent, fetchPage, ensureLoaded, loadRecent }
})
