import { computed, nextTick, ref, shallowRef, type ComputedRef, type CSSProperties } from 'vue'
import type { MediaItemInfo } from '@/entities/media'

const DRAG_START_DISTANCE = 8
const FLICK_MIN_DISTANCE = 36
const FLICK_MIN_VELOCITY = 0.45
const SWITCH_THRESHOLD_RATIO = 0.18
const EDGE_RESISTANCE = 0.18
const SETTLE_DURATION = 220

interface FeedSwipeOptions {
  hasPrevious: ComputedRef<boolean>
  hasNext: ComputedRef<boolean>
  previousItem: ComputedRef<MediaItemInfo | undefined>
  nextItem: ComputedRef<MediaItemInfo | undefined>
  canStart: () => boolean
  move: (direction: 1 | -1) => Promise<void>
  onTap: (point: { x: number; y: number }) => void
}

/** 竖向短视频手势：内容跟手位移，越过阈值或快速滑动时切换媒体。 */
export function useFeedSwipe(options: FeedSwipeOptions) {
  const isTouchDragging = ref(false)
  const isTouchSettling = ref(false)
  const skipMediaTransition = ref(false)
  const touchOffsetY = ref(0)
  const touchViewportHeight = ref(0)
  const previewOverride = shallowRef<MediaItemInfo | null>(null)
  let startY = 0
  let startX = 0
  let startTime = 0
  let eligible = false
  let settleTimer: number | null = null

  const direction = computed(() => (touchOffsetY.value < 0 ? 1 : touchOffsetY.value > 0 ? -1 : 0))
  const previewItem = computed(() => {
    if (!isTouchDragging.value && !isTouchSettling.value) return undefined
    if (previewOverride.value) return previewOverride.value
    return direction.value > 0 ? options.nextItem.value : direction.value < 0 ? options.previousItem.value : undefined
  })
  const currentFrameStyle = computed<CSSProperties>(() =>
    isTouchDragging.value || isTouchSettling.value ? { transform: `translate3d(0, ${touchOffsetY.value}px, 0)` } : {},
  )
  const previewFrameStyle = computed<CSSProperties>(() => {
    const height = touchViewportHeight.value || window.innerHeight
    const origin = direction.value > 0 ? height : -height
    return { transform: `translate3d(0, ${origin + touchOffsetY.value}px, 0)` }
  })

  function onTouchStart(event: TouchEvent): void {
    const touch = event.changedTouches[0]
    if (!touch || isTouchSettling.value || !options.canStart()) return
    eligible = !(event.target instanceof Element) || !event.target.closest('button, input, a, [role="slider"], dialog')
    if (!eligible) return
    startY = touch.clientY
    startX = touch.clientX
    startTime = performance.now()
    touchViewportHeight.value = window.innerHeight
    touchOffsetY.value = 0
  }

  function onTouchMove(event: TouchEvent): void {
    if (!eligible || isTouchSettling.value) return
    const touch = event.changedTouches[0]
    if (!touch) return
    const offsetY = touch.clientY - startY
    const offsetX = touch.clientX - startX
    if (!isTouchDragging.value && Math.abs(offsetY) < DRAG_START_DISTANCE) return
    if (!isTouchDragging.value && Math.abs(offsetX) >= Math.abs(offsetY)) {
      eligible = false
      return
    }
    isTouchDragging.value = true
    const canMove = offsetY < 0 ? options.hasNext.value : options.hasPrevious.value
    touchOffsetY.value = canMove ? offsetY : offsetY * EDGE_RESISTANCE
  }

  function finishSettle(commit: boolean, moveDirection: 1 | -1): void {
    if (settleTimer != null) window.clearTimeout(settleTimer)
    settleTimer = window.setTimeout(() => {
      void (async () => {
        if (commit) {
          skipMediaTransition.value = true
          await options.move(moveDirection)
        }
        isTouchDragging.value = false
        isTouchSettling.value = false
        touchOffsetY.value = 0
        eligible = false
        await nextTick()
        previewOverride.value = null
        skipMediaTransition.value = false
        settleTimer = null
      })()
    }, SETTLE_DURATION)
  }

  function onTouchEnd(event: TouchEvent): void {
    if (!eligible) return
    const endY = event.changedTouches[0]?.clientY ?? startY
    const endX = event.changedTouches[0]?.clientX ?? startX
    const distanceY = endY - startY
    const distanceX = endX - startX
    if (
      !isTouchDragging.value &&
      Math.abs(distanceY) > DRAG_START_DISTANCE &&
      Math.abs(distanceY) > Math.abs(distanceX)
    ) {
      isTouchDragging.value = true
      touchOffsetY.value = distanceY
    }
    if (!isTouchDragging.value || Math.abs(distanceY) <= Math.abs(distanceX)) {
      eligible = false
      if (Math.hypot(distanceX, distanceY) < 10) options.onTap({ x: endX, y: endY })
      return
    }
    const moveDirection: 1 | -1 = distanceY < 0 ? 1 : -1
    const elapsed = Math.max(1, performance.now() - startTime)
    const isFastFlick = Math.abs(distanceY) / elapsed > FLICK_MIN_VELOCITY && Math.abs(distanceY) > FLICK_MIN_DISTANCE
    const crossedThreshold = Math.abs(distanceY) > touchViewportHeight.value * SWITCH_THRESHOLD_RATIO
    const canCommit = moveDirection > 0 ? options.hasNext.value : options.hasPrevious.value
    const commit = canCommit && (isFastFlick || crossedThreshold)
    previewOverride.value = previewItem.value ?? null
    isTouchDragging.value = false
    isTouchSettling.value = true
    touchOffsetY.value = commit ? (moveDirection > 0 ? -touchViewportHeight.value : touchViewportHeight.value) : 0
    finishSettle(commit, moveDirection)
  }

  function cancelTouchGesture(): void {
    if (!isTouchDragging.value) {
      eligible = false
      return
    }
    isTouchDragging.value = false
    isTouchSettling.value = true
    previewOverride.value = previewItem.value ?? null
    touchOffsetY.value = 0
    finishSettle(false, 1)
  }

  function disposeSwipe(): void {
    if (settleTimer != null) window.clearTimeout(settleTimer)
    settleTimer = null
  }

  return {
    isTouchDragging,
    isTouchSettling,
    skipMediaTransition,
    previewItem,
    currentFrameStyle,
    previewFrameStyle,
    onTouchStart,
    onTouchMove,
    onTouchEnd,
    cancelTouchGesture,
    disposeSwipe,
  }
}
