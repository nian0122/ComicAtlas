import { computed, nextTick, ref, shallowRef, type ComputedRef, type CSSProperties } from 'vue'

const DRAG_START_DISTANCE = 8
const FLICK_MIN_DISTANCE = 36
const FLICK_MIN_VELOCITY = 0.45
const SWITCH_THRESHOLD_RATIO = 0.18
const EDGE_RESISTANCE = 0.18
const SETTLE_DURATION = 220

interface ImmersiveSwipeOptions<MediaItem> {
  hasPrevious: ComputedRef<boolean>
  hasNext: ComputedRef<boolean>
  previousItem: ComputedRef<MediaItem | undefined>
  nextItem: ComputedRef<MediaItem | undefined>
  canStart: () => boolean
  move: (direction: number) => Promise<void>
  onTap: () => void
  cancelLongPress: () => void
}

export function useImmersiveSwipe<MediaItem>(options: ImmersiveSwipeOptions<MediaItem>) {
  const touchOffsetY = ref(0)
  const touchViewportHeight = ref(0)
  const isTouchDragging = ref(false)
  const isTouchSettling = ref(false)
  const skipMediaTransition = ref(false)
  const gesturePreviewOverride = shallowRef<MediaItem | null>(null)
  let touchStartY = 0
  let touchStartX = 0
  let touchStartTime = 0
  let touchGestureEligible = false
  let touchSettleTimer: number | null = null

  const gestureDirection = computed(() => (touchOffsetY.value < 0 ? 1 : touchOffsetY.value > 0 ? -1 : 0))
  const gesturePreviewItem = computed(() => {
    if (!isTouchDragging.value && !isTouchSettling.value) return null
    if (gesturePreviewOverride.value) return gesturePreviewOverride.value
    return gestureDirection.value > 0
      ? options.nextItem.value
      : gestureDirection.value < 0
        ? options.previousItem.value
        : null
  })
  const currentFrameStyle = computed<CSSProperties>(() =>
    isTouchDragging.value || isTouchSettling.value ? { transform: `translate3d(0, ${touchOffsetY.value}px, 0)` } : {},
  )
  const previewFrameStyle = computed<CSSProperties>(() => {
    const viewportHeight = touchViewportHeight.value || window.innerHeight
    const origin = gestureDirection.value > 0 ? viewportHeight : -viewportHeight
    return { transform: `translate3d(0, ${origin + touchOffsetY.value}px, 0)` }
  })

  function onTouchStart(event: TouchEvent): void {
    const touch = event.changedTouches[0]
    if (!touch || isTouchSettling.value || !options.canStart()) return
    touchGestureEligible =
      !(event.target instanceof Element) ||
      !event.target.closest('button, [role="slider"], .video-speed-sheet, .video-play-error')
    if (!touchGestureEligible) return
    touchStartY = touch.clientY
    touchStartX = touch.clientX
    touchStartTime = performance.now()
    touchViewportHeight.value = window.innerHeight
    touchOffsetY.value = 0
  }

  function onTouchMove(event: TouchEvent): void {
    if (!touchGestureEligible || isTouchSettling.value) return
    const touch = event.changedTouches[0]
    if (!touch) return
    const offsetY = touch.clientY - touchStartY
    const offsetX = touch.clientX - touchStartX
    if (!isTouchDragging.value && Math.abs(offsetY) < DRAG_START_DISTANCE) return
    if (!isTouchDragging.value && Math.abs(offsetX) >= Math.abs(offsetY)) {
      touchGestureEligible = false
      return
    }
    isTouchDragging.value = true
    options.cancelLongPress()
    const canMove = offsetY < 0 ? options.hasNext.value : options.hasPrevious.value
    touchOffsetY.value = canMove ? offsetY : offsetY * EDGE_RESISTANCE
  }

  function finishTouchSettle(commit: boolean, direction: number): void {
    if (touchSettleTimer != null) window.clearTimeout(touchSettleTimer)
    touchSettleTimer = window.setTimeout(() => {
      void (async () => {
        if (commit) {
          skipMediaTransition.value = true
          await options.move(direction)
        }
        isTouchDragging.value = false
        isTouchSettling.value = false
        touchOffsetY.value = 0
        touchGestureEligible = false
        await nextTick()
        gesturePreviewOverride.value = null
        skipMediaTransition.value = false
        touchSettleTimer = null
      })()
    }, SETTLE_DURATION)
  }

  function onTouchEnd(event: TouchEvent): void {
    if (!touchGestureEligible) return
    const endY = event.changedTouches[0]?.clientY ?? touchStartY
    const endX = event.changedTouches[0]?.clientX ?? touchStartX
    const distanceY = endY - touchStartY
    const distanceX = endX - touchStartX
    if (
      !isTouchDragging.value &&
      Math.abs(distanceY) > DRAG_START_DISTANCE &&
      Math.abs(distanceY) > Math.abs(distanceX)
    ) {
      isTouchDragging.value = true
      touchOffsetY.value = distanceY
    }
    if (!isTouchDragging.value || Math.abs(distanceY) <= Math.abs(distanceX)) {
      touchGestureEligible = false
      if (Math.hypot(distanceX, distanceY) < 10) options.onTap()
      return
    }
    const direction = distanceY < 0 ? 1 : -1
    const elapsed = Math.max(1, performance.now() - touchStartTime)
    const isFastFlick = Math.abs(distanceY) / elapsed > FLICK_MIN_VELOCITY && Math.abs(distanceY) > FLICK_MIN_DISTANCE
    const crossedThreshold = Math.abs(distanceY) > touchViewportHeight.value * SWITCH_THRESHOLD_RATIO
    const canCommit = direction > 0 ? options.hasNext.value : options.hasPrevious.value
    const commit = canCommit && (isFastFlick || crossedThreshold)
    gesturePreviewOverride.value = gesturePreviewItem.value
    isTouchDragging.value = false
    isTouchSettling.value = true
    touchOffsetY.value = commit ? (direction > 0 ? -touchViewportHeight.value : touchViewportHeight.value) : 0
    finishTouchSettle(commit, direction)
  }

  function cancelTouchGesture(): void {
    if (!isTouchDragging.value) {
      touchGestureEligible = false
      return
    }
    isTouchDragging.value = false
    isTouchSettling.value = true
    gesturePreviewOverride.value = gesturePreviewItem.value
    touchOffsetY.value = 0
    finishTouchSettle(false, 0)
  }

  function disposeSwipe(): void {
    if (touchSettleTimer != null) window.clearTimeout(touchSettleTimer)
  }

  return {
    isTouchDragging,
    isTouchSettling,
    skipMediaTransition,
    gesturePreviewItem,
    currentFrameStyle,
    previewFrameStyle,
    onTouchStart,
    onTouchMove,
    onTouchEnd,
    cancelTouchGesture,
    disposeSwipe,
  }
}
