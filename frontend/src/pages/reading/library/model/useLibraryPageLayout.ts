import { computed, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import { BREAKPOINTS, useBreakpoint } from '@/shared/lib/composables/useBreakpoint'

const DESKTOP_FILTER_BREAKPOINT = 1024
const FILTER_HIDE_SCROLL_START = 160
const FILTER_SCROLL_DELTA = 8
const BACK_TO_TOP_SCROLL_START = 600

/** Library 页面专属的响应式布局与筛选栏滚动行为。 */
export function useLibraryPageLayout(pageHeaderRef: Ref<HTMLElement | null>) {
  const viewportWidth = useBreakpoint()
  const posterSize = computed<'sm' | 'md' | 'lg'>(() => (viewportWidth.value <= BREAKPOINTS.tablet ? 'sm' : 'lg'))
  const isMobileViewport = computed(() => viewportWidth.value <= BREAKPOINTS.tablet)
  const isDesktopFilterHidden = ref(false)
  const isBackToTopVisible = ref(false)
  let lastWindowScrollY = 0
  let scrollAnimationFrame: number | null = null

  function updateDesktopFilterVisibility(): void {
    scrollAnimationFrame = null
    const currentScrollY = Math.max(0, window.scrollY)
    isBackToTopVisible.value = currentScrollY >= BACK_TO_TOP_SCROLL_START
    if (window.innerWidth <= DESKTOP_FILTER_BREAKPOINT || currentScrollY <= FILTER_HIDE_SCROLL_START) {
      isDesktopFilterHidden.value = false
      lastWindowScrollY = currentScrollY
      return
    }
    const activeElement = document.activeElement
    if (activeElement instanceof Node && pageHeaderRef.value?.contains(activeElement)) {
      isDesktopFilterHidden.value = false
      lastWindowScrollY = currentScrollY
      return
    }
    const scrollDelta = currentScrollY - lastWindowScrollY
    if (scrollDelta >= FILTER_SCROLL_DELTA) {
      isDesktopFilterHidden.value = true
      lastWindowScrollY = currentScrollY
    } else if (scrollDelta <= -FILTER_SCROLL_DELTA) {
      isDesktopFilterHidden.value = false
      lastWindowScrollY = currentScrollY
    }
  }

  function onWindowScroll(): void {
    if (scrollAnimationFrame !== null) return
    scrollAnimationFrame = window.requestAnimationFrame(updateDesktopFilterVisibility)
  }

  function backToTop(): void {
    isDesktopFilterHidden.value = false
    // 返回筛选入口后恢复键盘操作位置，滚动动画遵循系统减少动态效果设置。
    pageHeaderRef.value?.focus({ preventScroll: true })
    window.scrollTo({
      top: 0,
      left: 0,
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth',
    })
  }

  onMounted(() => {
    lastWindowScrollY = Math.max(0, window.scrollY)
    updateDesktopFilterVisibility()
    window.addEventListener('scroll', onWindowScroll, { passive: true })
  })
  onBeforeUnmount(() => {
    window.removeEventListener('scroll', onWindowScroll)
    if (scrollAnimationFrame !== null) window.cancelAnimationFrame(scrollAnimationFrame)
  })

  return { posterSize, isDesktopFilterHidden, isMobileViewport, isBackToTopVisible, backToTop }
}
