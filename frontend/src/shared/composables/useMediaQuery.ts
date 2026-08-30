import { ref, onMounted, onBeforeUnmount } from 'vue'
import type { Ref } from 'vue'

/**
 * 响应式媒体查询 composable。
 *
 * 基于 window.matchMedia 跟踪 CSS 媒体查询是否命中当前视口，
 * 通过 MediaQueryList 的 change 事件响应式更新。
 *
 * SSR 安全：无 window 环境下返回 ref(false)。
 *
 * @param query CSS 媒体查询字符串，例如 '(pointer: coarse)'
 * @returns 响应式布尔值，媒体查询命中时为 true
 */
export function useMediaQuery(query: string): Ref<boolean> {
  const matches = ref(false)

  if (typeof window === 'undefined') {
    return matches
  }

  let mediaQueryList: MediaQueryList | null = null

  const onChange = (event: MediaQueryListEvent): void => {
    matches.value = event.matches
  }

  onMounted(() => {
    mediaQueryList = window.matchMedia(query)
    matches.value = mediaQueryList.matches
    mediaQueryList.addEventListener('change', onChange)
  })

  onBeforeUnmount(() => {
    if (mediaQueryList) {
      mediaQueryList.removeEventListener('change', onChange)
      mediaQueryList = null
    }
  })

  return matches
}
