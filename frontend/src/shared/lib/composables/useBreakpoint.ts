import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/**
 * 全局响应式断点定义（单一事实来源）。
 * 禁止在其他文件硬编码 768/1024/1440——统一从这里导入。
 */
export const BREAKPOINTS = {
  mobile: 768,
  tablet: 1024,
  desktop: 1440,
} as const

/** resize 防抖间隔（毫秒） */
const RESIZE_DEBOUNCE_MS = 100

/** SSR 安全地读取当前视口宽度，window 不存在时返回 0 */
function readViewportWidth(): number {
  return typeof window !== 'undefined' ? window.innerWidth : 0
}

/**
 * 响应式追踪当前视口宽度（window.innerWidth）。
 *
 * - resize 事件 100ms 防抖，避免高频触发
 * - 组件卸载时自动清理事件监听与未触发的定时器
 * - SSR 环境下安全降级（宽度为 0，不访问 window）
 */
export function useBreakpoint(): Ref<number> {
  const width = ref(readViewportWidth())

  let debounceTimer: ReturnType<typeof setTimeout> | null = null

  const handleResize = (): void => {
    if (debounceTimer !== null) {
      clearTimeout(debounceTimer)
    }
    debounceTimer = setTimeout(() => {
      debounceTimer = null
      width.value = readViewportWidth()
    }, RESIZE_DEBOUNCE_MS)
  }

  onMounted(() => {
    if (typeof window === 'undefined') {
      return
    }
    // 挂载后同步一次，覆盖初始化与挂载之间可能发生的宽度变化
    width.value = window.innerWidth
    window.addEventListener('resize', handleResize)
  })

  onBeforeUnmount(() => {
    if (typeof window !== 'undefined') {
      window.removeEventListener('resize', handleResize)
    }
    if (debounceTimer !== null) {
      clearTimeout(debounceTimer)
      debounceTimer = null
    }
  })

  return width
}
