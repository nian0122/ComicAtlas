import { onBeforeUnmount, onMounted, watch } from 'vue'
import type { Ref } from 'vue'

/** 横向滑动方向（按手指移动方向命名：向左划为 'left'） */
export type SwipeDirection = 'left' | 'right' | 'up' | 'down'

/** tap 触点坐标（viewport 坐标系，即 clientX/clientY） */
export interface TapPoint {
  x: number
  y: number
}

/**
 * 手势注册 API。
 *
 * 仅负责事件标准化：检测到 tap / double tap / swipe 后回调已注册的 handler，
 * 具体行为完全由调用方（ReaderPage）决定，本 composable
 * 不访问 store、不操作任何状态。
 */
export interface ReaderGestureControls {
  /** 注册 tap（轻点）回调，可多次调用注册多个；回调收到触点坐标（可忽略） */
  onTap: (handler: (point: TapPoint) => void) => void
  /** 注册触控双击图片回调；双击命中时不会再派发对应的单击。 */
  onDoubleTap: (handler: (point: TapPoint) => void) => void
  /** 注册横向 swipe（滑动）回调，参数为手指移动方向 */
  onSwipe: (handler: (direction: SwipeDirection) => void) => void
}

const READER_INTERACTIVE_SELECTOR = [
  'video',
  'audio',
  'button',
  'a[href]',
  'input',
  'select',
  'textarea',
  '[role="button"]',
  '[contenteditable]:not([contenteditable="false"])',
  '[data-reader-interactive]',
].join(',')

/** 判断事件是否来自需要保留浏览器原生交互的控件。 */
export function isReaderInteractiveTarget(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest(READER_INTERACTIVE_SELECTOR) !== null
}

/**
 * 视频画面需要同时支持原生播放控制和阅读器手势。
 * 键盘/滚轮仍使用 isReaderInteractiveTarget，避免干扰视频控件；
 * 这里只放宽 Pointer Events 的手势判定。
 */
function isVideoSurfaceTarget(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false
  return target.closest('video, [data-reader-video-surface]') !== null
}

function isGestureInteractiveTarget(target: EventTarget | null): boolean {
  if (isVideoSurfaceTarget(target)) return false
  return isReaderInteractiveTarget(target)
}

/** tap 判定：按下到抬起的最长毫秒数 */
const TAP_MAX_DURATION_MS = 300
/** tap 判定：按下到抬起允许的最大位移（px），滚动拖拽位移超限故天然不会误判为 tap */
const TAP_MAX_MOVEMENT_PX = 10
/** swipe 判定：横向位移最小阈值（px） */
const SWIPE_MIN_DISTANCE_PX = 50
/** 连击保护：距上次 tap 触发不足该毫秒数的 tap 被忽略，防止快速连点触发过多事件 */
const TAP_GUARD_MS = 100
/** 触控双击允许的最大间隔（ms） */
const DOUBLE_TAP_MAX_INTERVAL_MS = 300
/** 触控双击两次落点允许的最大距离（px） */
const DOUBLE_TAP_MAX_DISTANCE_PX = 24

interface TouchTapCandidate {
  point: TapPoint
  timeStamp: number
  imageTarget: Element
}

/**
 * 延迟图片上的首次触控点击，以便在窗口期内区分单击和双击。
 * 非图片触控和鼠标点击不经过此解析器，不会增加交互延迟。
 */
export function createTouchTapResolver(onSingleTap: (point: TapPoint) => void, onDoubleTap: (point: TapPoint) => void) {
  let pendingCandidate: TouchTapCandidate | null = null
  let pendingTimer: ReturnType<typeof setTimeout> | null = null

  const clearPendingTimer = (): void => {
    if (pendingTimer === null) return
    clearTimeout(pendingTimer)
    pendingTimer = null
  }

  const dispatchPendingSingleTap = (): void => {
    if (pendingCandidate === null) return
    const point = pendingCandidate.point
    pendingCandidate = null
    clearPendingTimer()
    onSingleTap(point)
  }

  const handleTouchTap = (candidate: TouchTapCandidate): void => {
    if (pendingCandidate !== null) {
      const interval = candidate.timeStamp - pendingCandidate.timeStamp
      const distance = Math.hypot(
        candidate.point.x - pendingCandidate.point.x,
        candidate.point.y - pendingCandidate.point.y,
      )
      const isSameImage = candidate.imageTarget === pendingCandidate.imageTarget

      if (
        interval >= 0 &&
        interval <= DOUBLE_TAP_MAX_INTERVAL_MS &&
        distance <= DOUBLE_TAP_MAX_DISTANCE_PX &&
        isSameImage
      ) {
        pendingCandidate = null
        clearPendingTimer()
        onDoubleTap(candidate.point)
        return
      }

      dispatchPendingSingleTap()
    }

    pendingCandidate = candidate
    pendingTimer = setTimeout(dispatchPendingSingleTap, DOUBLE_TAP_MAX_INTERVAL_MS)
  }

  const dispose = (): void => {
    pendingCandidate = null
    clearPendingTimer()
  }

  return { handleTouchTap, dispose }
}

/** 主指针按下时的快照，用于抬起时计算位移与时长 */
interface PointerSnapshot {
  pointerId: number
  pointerType: string
  x: number
  y: number
  time: number
  target: EventTarget | null
  videoSurface: boolean
}

/**
 * 阅读器视口手势检测。
 *
 * 通过 Pointer Events（pointerdown / pointerup / pointercancel）检测
 * tap 与横向 swipe，并以标准化回调形式发出：
 * - tap：300ms 内抬起且位移（欧氏距离）< 10px；
 *   图片触控单击延迟 300ms，以便与双击互斥；其他点击保留 100ms 连击保护
 * - double tap：300ms 内连续触控同一图片，且两次落点距离不超过 24px
 * - swipe：位移 > 50px 且主方向占优，返回 left/right/up/down
 *
 * 绑定时机：onMounted 覆盖常规挂载；同时 watch viewportRef 覆盖
 * 元素晚挂载（v-if 延迟渲染）与元素替换场景，两者通过 bind 内部
 * 的幂等判断（同元素跳过）避免重复绑定。
 *
 * 零耦合约束：不调用 preventDefault（视口滚动不受影响），
 * 不引用 store / toolbar / 其他 composable。
 *
 * @param viewportRef 视口元素的 template ref，允许初始为 null
 */
export function useReaderGesture(viewportRef: Ref<HTMLElement | null>): ReaderGestureControls {
  const tapHandlers: Array<(point: TapPoint) => void> = []
  const doubleTapHandlers: Array<(point: TapPoint) => void> = []
  const swipeHandlers: Array<(direction: SwipeDirection) => void> = []

  const dispatchTap = (point: TapPoint): void => {
    for (const handler of tapHandlers) handler(point)
  }

  const touchTapResolver = createTouchTapResolver(dispatchTap, (point) => {
    for (const handler of doubleTapHandlers) handler(point)
  })

  /** 当前按下中的主指针快照，null 表示无按下 */
  let pressed: PointerSnapshot | null = null
  /** 上次 tap 触发时刻（event.timeStamp），用于连击保护 */
  let lastTapTime = 0
  /** 当前已绑定监听器的元素，用于元素替换 / 卸载时解绑 */
  let boundEl: HTMLElement | null = null

  const handlePointerDown = (event: PointerEvent): void => {
    // 仅跟踪主指针 + 主键：排除多指触摸的第二根手指与鼠标右键/中键
    if (!event.isPrimary || event.button !== 0) return
    if (isGestureInteractiveTarget(event.target)) {
      pressed = null
      return
    }
    pressed = {
      pointerId: event.pointerId,
      pointerType: event.pointerType,
      x: event.clientX,
      y: event.clientY,
      time: event.timeStamp,
      target: event.target,
      videoSurface: isVideoSurfaceTarget(event.target),
    }
  }

  const handlePointerUp = (event: PointerEvent): void => {
    if (isGestureInteractiveTarget(event.target)) {
      pressed = null
      return
    }
    if (pressed === null || event.pointerId !== pressed.pointerId) return
    const deltaX = event.clientX - pressed.x
    const deltaY = event.clientY - pressed.y
    const duration = event.timeStamp - pressed.time
    const pointerType = pressed.pointerType
    const pressedTarget = pressed.target
    const videoSurface = pressed.videoSurface
    pressed = null

    // tap：短按 + 几乎无位移（位移取欧氏距离）
    if (duration <= TAP_MAX_DURATION_MS && Math.hypot(deltaX, deltaY) < TAP_MAX_MOVEMENT_PX) {
      // 视频轻点交给播放器自身处理，避免播放/暂停时同时唤出阅读工具栏。
      if (videoSurface) return
      const imageTarget = pressedTarget instanceof Element ? pressedTarget.closest('.progressive-image') : null
      if (pointerType === 'touch' && imageTarget !== null && doubleTapHandlers.length > 0) {
        touchTapResolver.handleTouchTap({
          point: { x: event.clientX, y: event.clientY },
          timeStamp: event.timeStamp,
          imageTarget,
        })
        return
      }
      if (event.timeStamp - lastTapTime < TAP_GUARD_MS) return
      lastTapTime = event.timeStamp
      const point: TapPoint = { x: event.clientX, y: event.clientY }
      dispatchTap(point)
      return
    }

    const absDeltaX = Math.abs(deltaX)
    const absDeltaY = Math.abs(deltaY)
    if (Math.max(absDeltaX, absDeltaY) <= SWIPE_MIN_DISTANCE_PX) return

    // 主方向占优；纵向用于控制移动端工具栏，横向用于翻页。
    if (absDeltaX > absDeltaY) {
      const direction: SwipeDirection = deltaX < 0 ? 'left' : 'right'
      for (const handler of swipeHandlers) handler(direction)
      return
    }

    if (absDeltaY > absDeltaX) {
      const direction: SwipeDirection = deltaY < 0 ? 'up' : 'down'
      for (const handler of swipeHandlers) handler(direction)
    }
  }

  const handlePointerCancel = (event: PointerEvent): void => {
    // 浏览器接管手势（如触摸滚动）时会 cancel 当前指针，重置跟踪防止状态卡死
    if (pressed !== null && event.pointerId === pressed.pointerId) {
      pressed = null
    }
  }

  /** 解绑当前元素上的全部监听器并重置跟踪状态 */
  const unbind = (): void => {
    if (boundEl === null) return
    boundEl.removeEventListener('pointerdown', handlePointerDown)
    boundEl.removeEventListener('pointerup', handlePointerUp)
    boundEl.removeEventListener('pointercancel', handlePointerCancel)
    boundEl = null
    pressed = null
  }

  /** 绑定监听器到指定元素（同元素幂等跳过；不 preventDefault） */
  const bind = (el: HTMLElement | null): void => {
    if (el === null || el === boundEl) return
    unbind()
    el.addEventListener('pointerdown', handlePointerDown)
    el.addEventListener('pointerup', handlePointerUp)
    el.addEventListener('pointercancel', handlePointerCancel)
    boundEl = el
  }

  onMounted(() => bind(viewportRef.value))

  // 元素晚挂载：ref 变为非 null 时补绑；变回 null（v-if 移除）时解绑，避免持有已脱离元素
  watch(viewportRef, (el) => {
    if (el === null) {
      unbind()
    } else {
      bind(el)
    }
  })

  // 卸载前移除全部监听器
  onBeforeUnmount(unbind)
  onBeforeUnmount(touchTapResolver.dispose)

  const onTap = (handler: (point: TapPoint) => void): void => {
    tapHandlers.push(handler)
  }

  const onDoubleTap = (handler: (point: TapPoint) => void): void => {
    doubleTapHandlers.push(handler)
  }

  const onSwipe = (handler: (direction: SwipeDirection) => void): void => {
    swipeHandlers.push(handler)
  }

  return { onTap, onDoubleTap, onSwipe }
}
