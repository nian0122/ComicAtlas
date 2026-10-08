import { onBeforeUnmount, readonly, ref } from 'vue'

const isFullscreen = ref(false)
const isPending = ref(false)
let isImmersiveFallback = false
let readerCount = 0
let disposalTimer: ReturnType<typeof setTimeout> | null = null
let requestSequence = 0

function syncFullscreen(): void {
  isFullscreen.value = document.fullscreenElement === document.documentElement || isImmersiveFallback
}

function onEscape(event: KeyboardEvent): void {
  if (event.key === 'Escape' && isImmersiveFallback) void exitFullscreen()
}

async function exitFullscreen(): Promise<void> {
  requestSequence++
  isImmersiveFallback = false
  syncFullscreen()
  if (document.fullscreenElement === document.documentElement) {
    try {
      await document.exitFullscreen()
    } catch {
      // 系统退出可能已抢先完成，以浏览器实际状态为准。
    }
  }
  syncFullscreen()
}

/** 漫画与短视频共用文档全屏，切换阅读模式时继续保持全屏。 */
export function useReaderFullscreen() {
  readerCount++
  if (disposalTimer != null) clearTimeout(disposalTimer)
  disposalTimer = null
  if (readerCount === 1) {
    document.addEventListener('fullscreenchange', syncFullscreen)
    document.addEventListener('keydown', onEscape)
    syncFullscreen()
  }

  async function toggleFullscreen(): Promise<void> {
    if (isPending.value) return
    if (isFullscreen.value) {
      isPending.value = true
      try {
        await exitFullscreen()
      } finally {
        isPending.value = false
      }
      return
    }

    const sequence = ++requestSequence
    isPending.value = true
    try {
      if (document.documentElement.requestFullscreen) {
        await document.documentElement.requestFullscreen()
      } else {
        isImmersiveFallback = true
      }
    } catch {
      // 移动浏览器不支持文档全屏或拒绝请求时，使用现有铺满视口的阅读布局。
      if (sequence === requestSequence) isImmersiveFallback = true
    } finally {
      isPending.value = false
      if (sequence === requestSequence) syncFullscreen()
      else if (readerCount === 0) void exitFullscreen()
    }
  }

  onBeforeUnmount(() => {
    readerCount--
    // 路由切到另一个阅读模式时，新页面会在同轮更新中接管；离开阅读器才退出。
    if (readerCount === 0) {
      disposalTimer = setTimeout(() => {
        disposalTimer = null
        if (readerCount !== 0) return
        document.removeEventListener('fullscreenchange', syncFullscreen)
        document.removeEventListener('keydown', onEscape)
        void exitFullscreen()
      }, 0)
    }
  })

  return { isFullscreen: readonly(isFullscreen), isPending: readonly(isPending), toggleFullscreen }
}
