/**
 * Reader 导航 composable（设计规范 §9）。
 *
 * 封装阅读器内所有路由跳转：返回详情页、上/下一章、跳转目录。
 * 统一使用命名路由（禁止手拼路径字符串），并对空 id 做静默守卫。
 */
import { useRouter } from 'vue-router'
import { useReaderStore } from '@/stores/reader-store'

export function useReaderNavigation() {
  const router = useRouter()
  const store = useReaderStore()

  /** 返回漫画详情页；无法识别漫画时退回漫画库 */
  function goBack() {
    if (!store.comicId) {
      router.replace({ name: 'library' })
      return
    }
    const detailRoute = router.resolve({ name: 'comic-detail', params: { id: store.comicId } })
    const previousPath = router.options.history.state.back

    // 从详情页进入阅读器时回退历史栈，避免 push 详情页造成“返回又回阅读器”。
    if (typeof previousPath === 'string' && previousPath === detailRoute.fullPath) {
      router.back()
      return
    }

    // 直接打开阅读器或历史页进入时没有详情页可回退，用 replace 避免制造循环历史。
    router.replace(detailRoute)
  }

  /** 跳转指定章节的阅读器路由（内部工具）；page 支持 'last' 哨兵 = 落到该章最后一页 */
  function goChapter(chapterId: number | null, page: number | 'last' = 1) {
    // null/undefined 守卫：无相邻章节时静默不跳转
    if (chapterId == null) return
    router.push({ name: 'reader', params: { chapterId }, query: { page: String(page) } })
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
