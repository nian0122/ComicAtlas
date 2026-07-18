import { BREAKPOINTS } from '@/composables/useBreakpoint'

/**
 * 判断当前是否为移动阅读设备。
 *
 * 纯函数，无 Vue 依赖（无 ref、无组合式生命周期），
 * 可在 Router Guard 等非组件上下文中直接调用。
 *
 * 判定条件：粗指针（触摸设备）且视口宽度 ≤ 移动端断点。
 * 窄窗口的桌面浏览器（鼠标指针）不会被误判为移动设备。
 *
 * SSR 安全：`window` 未定义时返回 `false`。
 */
export function isMobileReadingDevice(): boolean {
  if (typeof window === 'undefined') {
    return false
  }
  return (
    window.matchMedia('(pointer: coarse)').matches &&
    window.innerWidth <= BREAKPOINTS.mobile
  )
}
