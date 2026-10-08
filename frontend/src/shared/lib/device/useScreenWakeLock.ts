import { onBeforeUnmount } from 'vue'

/**
 * 播放期间阻止设备自动息屏。浏览器不支持 Screen Wake Lock 时保持静默，
 * 不影响视频播放；页面回到前台后会按当前播放状态重新申请。
 */
export function useScreenWakeLock() {
  let isPlaybackActive = false
  let wakeLock: WakeLockSentinel | null = null
  let requestSequence = 0

  async function requestWakeLock(): Promise<void> {
    if (!isPlaybackActive || document.hidden || !('wakeLock' in navigator) || wakeLock) return

    const currentSequence = ++requestSequence
    try {
      const requestedWakeLock = await navigator.wakeLock.request('screen')
      if (currentSequence !== requestSequence || !isPlaybackActive || document.hidden) {
        await requestedWakeLock.release()
        return
      }

      wakeLock = requestedWakeLock
      requestedWakeLock.addEventListener('release', () => {
        if (wakeLock === requestedWakeLock) wakeLock = null
      })
    } catch {
      // 权限或浏览器策略拒绝时，保留正常播放行为。
    }
  }

  async function releaseWakeLock(): Promise<void> {
    requestSequence++
    const activeWakeLock = wakeLock
    wakeLock = null
    if (activeWakeLock && !activeWakeLock.released) {
      try {
        await activeWakeLock.release()
      } catch {
        // 浏览器可能已经自动释放唤醒锁。
      }
    }
  }

  function onVisibilityChange(): void {
    if (document.hidden) {
      void releaseWakeLock()
      return
    }
    void requestWakeLock()
  }

  function setPlaybackActive(active: boolean): void {
    isPlaybackActive = active
    if (active) {
      void requestWakeLock()
    } else {
      void releaseWakeLock()
    }
  }

  document.addEventListener('visibilitychange', onVisibilityChange)
  onBeforeUnmount(() => {
    isPlaybackActive = false
    document.removeEventListener('visibilitychange', onVisibilityChange)
    void releaseWakeLock()
  })

  return { setPlaybackActive }
}
