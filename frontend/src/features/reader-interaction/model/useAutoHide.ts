import { onBeforeUnmount, ref } from 'vue'
import type { Ref } from 'vue'

/**
 * 自动隐藏控制接口。
 *
 * 纯计时器逻辑，不感知 gesture 或 toolbar，
 * 全部状态变更仅由显式方法调用触发。
 */
export interface AutoHideControls {
  /** 当前是否可见 */
  visible: Ref<boolean>
  /** 显示并（重新）启动倒计时，超时后自动置为不可见 */
  show: () => void
  /** 立即隐藏并清除计时器 */
  hide: () => void
  /** 暂停倒计时（不改变 visible，用于设置抽屉打开期间） */
  pause: () => void
  /** 从完整 timeout 重新启动倒计时（仅在可见时有意义） */
  resume: () => void
}

/**
 * 工具栏自动隐藏计时器。
 *
 * @param timeout 无操作后自动隐藏的毫秒数，默认 4000
 */
export function useAutoHide(timeout = 4000): AutoHideControls {
  const visible = ref(false)
  let timer: ReturnType<typeof setTimeout> | null = null

  /** 清除待触发的计时器，防止重复计时 */
  const clearTimer = (): void => {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  /** 从完整 timeout 重新开始倒计时（先清后设，保证唯一计时器） */
  const startTimer = (): void => {
    clearTimer()
    timer = setTimeout(() => {
      timer = null
      visible.value = false
    }, timeout)
  }

  const show = (): void => {
    visible.value = true
    startTimer()
  }

  const hide = (): void => {
    clearTimer()
    visible.value = false
  }

  const pause = (): void => {
    clearTimer()
  }

  const resume = (): void => {
    startTimer()
  }

  // 组件卸载前清除残留计时器，避免卸载后触发状态写入
  onBeforeUnmount(clearTimer)

  return { visible, show, hide, pause, resume }
}
