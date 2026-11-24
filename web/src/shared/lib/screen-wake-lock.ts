import { onBeforeUnmount, onMounted } from 'vue'

const RETRY_DELAYS = [600, 1200, 2400, 4800] as const

/**
 * 在页面可见期间保持屏幕常亮，供短视频这类长时间停留的页面使用。
 *
 * 浏览器不会保证一次申请永久有效，因此这里把常亮当作持续同步而不是一次性请求：
 * - iOS Safari 在文档首次被触摸前会以 NotAllowedError 拒绝申请；
 * - 切到后台、系统省电或文档被冻结时，sentinel 会被释放；
 * - 回到前台或从 bfcache 恢复的瞬间，申请可能仍被拒绝。
 *
 * 申请失败后不空转重试：NotAllowedError 等待下一次手势、聚焦或可见性恢复；
 * 其他错误按退避间隔有限重试。sentinel 被浏览器释放时按同样规则重新申请，
 * 自己释放的 sentinel 先摘掉监听，避免误触发重试。
 */
export function useScreenWakeLock(): void {
  if (typeof navigator === 'undefined') return
  const wakeLock = navigator.wakeLock
  if (!wakeLock) return

  let sentinel: WakeLockSentinel | null = null
  let wanted = false
  let requesting = false
  let disposed = false
  let retryTimer: ReturnType<typeof setTimeout> | undefined
  let retryAttempt = 0
  let generation = 0
  let reacquire = false

  function clearRetry(): void {
    if (retryTimer === undefined) return
    clearTimeout(retryTimer)
    retryTimer = undefined
  }

  function scheduleRetry(): void {
    if (disposed || !wanted || retryTimer !== undefined) return
    if (retryAttempt >= RETRY_DELAYS.length) return
    const delay = RETRY_DELAYS[retryAttempt]
    retryAttempt++
    retryTimer = setTimeout(() => {
      retryTimer = undefined
      void acquire()
    }, delay)
  }

  function onRelease(): void {
    sentinel = null
    if (!disposed && wanted && document.visibilityState === 'visible') scheduleRetry()
  }

  async function acquire(): Promise<void> {
    if (disposed || !wanted || requesting || sentinel) return
    if (document.visibilityState !== 'visible') return
    clearRetry()
    requesting = true
    const token = ++generation
    try {
      const lock = await wakeLock.request('screen')
      if (disposed) {
        await lock.release()
        return
      }
      // 申请期间切到后台再回来时，这次结果已经过期；释放后立即按当前可见状态补申请。
      if (!wanted || token !== generation) {
        await lock.release()
        reacquire = true
        return
      }
      sentinel = lock
      retryAttempt = 0
      lock.addEventListener('release', onRelease)
    } catch (reason: unknown) {
      if (disposed || token !== generation) return
      // NotAllowedError 通常表示当前没有用户手势、文档不可见或不可完全激活；
      // 在后台反复申请没有意义，等手势、聚焦或可见性变化再试。
      if (!(reason instanceof DOMException && reason.name === 'NotAllowedError')) scheduleRetry()
    } finally {
      requesting = false
      if (reacquire) {
        reacquire = false
        if (!disposed) void acquire()
      }
    }
  }

  function drop(): void {
    const lock = sentinel
    sentinel = null
    generation++
    if (!lock) return
    lock.removeEventListener('release', onRelease)
    void lock.release().catch(() => {})
  }

  function syncVisibility(): void {
    if (document.visibilityState === 'visible') {
      wanted = true
      retryAttempt = 0
      void acquire()
      return
    }
    wanted = false
    clearRetry()
    drop()
  }

  /** 首次触摸、按键或窗口重新聚焦都是可以补申请常亮的时机。 */
  function retryOnGesture(): void {
    if (disposed || !wanted || sentinel) return
    retryAttempt = 0
    clearRetry()
    void acquire()
  }

  function onPageShow(event: PageTransitionEvent): void {
    if (!event.persisted) return
    syncVisibility()
  }

  function onPageHide(event: PageTransitionEvent): void {
    if (!event.persisted) return
    wanted = false
    clearRetry()
    drop()
  }

  onMounted(() => {
    wanted = document.visibilityState === 'visible'
    document.addEventListener('visibilitychange', syncVisibility)
    window.addEventListener('focus', retryOnGesture)
    window.addEventListener('pageshow', onPageShow)
    window.addEventListener('pagehide', onPageHide)
    document.addEventListener('pointerdown', retryOnGesture, true)
    document.addEventListener('touchstart', retryOnGesture, { capture: true, passive: true })
    document.addEventListener('keydown', retryOnGesture, true)
    if (wanted) void acquire()
  })

  onBeforeUnmount(() => {
    disposed = true
    wanted = false
    clearRetry()
    document.removeEventListener('visibilitychange', syncVisibility)
    window.removeEventListener('focus', retryOnGesture)
    window.removeEventListener('pageshow', onPageShow)
    window.removeEventListener('pagehide', onPageHide)
    document.removeEventListener('pointerdown', retryOnGesture, true)
    document.removeEventListener('touchstart', retryOnGesture, true)
    document.removeEventListener('keydown', retryOnGesture, true)
    drop()
  })
}
