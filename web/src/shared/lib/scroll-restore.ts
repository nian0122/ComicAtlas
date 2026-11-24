const positions = new Map<string, number>()

/** 路由在离开页面时记录滚动位置，页面组件在数据与虚拟布局就绪后读取恢复。 */
export function recordScrollPosition(fullPath: string, top: number) {
  positions.set(fullPath, top)
}

export function readScrollPosition(fullPath: string): number {
  return positions.get(fullPath) ?? 0
}

/**
 * 页块高度或封面网格宽度是实测的，一次 scrollTo 可能落在尚未撑开的布局上。
 * 反复落到目标位置，直到高度足够且连续两帧一致；用户滚动或按键立即停止。
 */
export async function scrollToOffset(top: number): Promise<boolean> {
  if (top <= 0) return false
  let cancelled = false
  const cancel = () => {
    cancelled = true
  }
  const events = ['wheel', 'touchstart', 'pointerdown', 'keydown'] as const
  for (const name of events) window.addEventListener(name, cancel, { passive: true })
  try {
    let settled = 0
    for (let attempt = 0; attempt < 12 && !cancelled; attempt++) {
      window.scrollTo({ top, behavior: 'instant' })
      await new Promise<void>((resolve) => setTimeout(resolve, 100))
      if (Math.abs(window.scrollY - top) <= 1) {
        if (++settled >= 2) return true
      } else settled = 0
    }
    return false
  } finally {
    for (const name of events) window.removeEventListener(name, cancel)
  }
}
