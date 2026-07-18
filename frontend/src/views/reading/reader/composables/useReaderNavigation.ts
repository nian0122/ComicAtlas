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

  /** 返回漫画详情页；comicId 未就绪（0/falsy）时静默不跳转 */
  function goBack() {
    if (!store.comicId) return
    router.push({ name: 'comic-detail', params: { id: store.comicId } })
  }

  /** 跳转指定章节的阅读器路由（内部工具，query.page=1 与旧逻辑保持一致） */
  function goChapter(chapterId: number | null) {
    // null/undefined 守卫：无相邻章节时静默不跳转
    if (chapterId == null) return
    router.push({ name: 'reader', params: { chapterId }, query: { page: '1' } })
  }

  /** 上一章；prevChapterId 为 null 时静默不跳转 */
  function goPrevChapter() {
    goChapter(store.prevChapterId)
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
