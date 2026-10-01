/**
 * Reader 导航 composable（设计规范 §9）。
 *
 * 封装阅读器内所有路由跳转：返回详情页、上/下一章、跳转目录。
 * 统一使用命名路由（禁止手拼路径字符串），并对空 id 做静默守卫。
 */
import { useRoute } from 'vue-router'
import { useReaderStore } from '@/features/reader-navigation'
import { useReadingNavigation } from '@/features/reading-navigation'

export function useReaderNavigation() {
  const navigation = useReadingNavigation()
  const route = useRoute()
  const store = useReaderStore()

  /** 返回漫画详情页；无法识别漫画时返回本次阅读来源。 */
  function goBack() {
    navigation.goToDetail(store.comicId)
  }

  /** 跳转指定章节的阅读器路由（内部工具）；page 支持 'last' 哨兵 = 落到该章最后一页 */
  function goChapter(chapterId: number | null, page: number | 'last' = 1) {
    // null/undefined 守卫：无相邻章节时静默不跳转
    if (chapterId == null) return
    // 切章仍属于同一次阅读；复用当前历史项，返回时才不会落回旧章节。
    navigation.goToReader(chapterId, { ...route.query, page: String(page) })
  }

  /** 上一章；prevChapterId 为 null 时静默不跳转；page 缺省落到第 1 页 */
  function goPrevChapter(page: number | 'last' = 1) {
    goChapter(store.prevChapterId, page)
  }

  /** 下一章；nextChapterId 为 null 时静默不跳转 */
  function goNextChapter() {
    goChapter(store.nextChapterId)
  }

  /**
   * 跳转目录：目录树位于详情页（DetailPage 的 catalog-section），
   * 当前详情页无 hash 锚点处理，故与 goBack 同目标平跳详情页。
   */
  function goToCatalog() {
    goBack()
  }

  return { goBack, goPrevChapter, goNextChapter, goToCatalog }
}
