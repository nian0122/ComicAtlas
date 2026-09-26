import { ref } from 'vue'

const CONTROLS_HIDE_DELAY = 2600

export function useAutoHideControls(canHide: () => boolean) {
  const controlsVisible = ref(true)
  let controlsTimer: number | null = null

  function clearControlsTimer(): void {
    if (controlsTimer != null) window.clearTimeout(controlsTimer)
    controlsTimer = null
  }

  function scheduleControlsHide(): void {
    clearControlsTimer()
    if (!canHide()) return
    controlsTimer = window.setTimeout(() => {
      controlsVisible.value = false
    }, CONTROLS_HIDE_DELAY)
  }

  function showControls(autoHide = true): void {
    controlsVisible.value = true
    if (autoHide) scheduleControlsHide()
    else clearControlsTimer()
  }

  return {
    controlsVisible,
    scheduleControlsHide,
    showControls,
    disposeControls: clearControlsTimer,
  }
}
