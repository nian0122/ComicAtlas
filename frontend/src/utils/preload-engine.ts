type PreloadTask = {
  url: string
  index: number
  image: HTMLImageElement
  priority: 'immediate' | 'cascade'
}

/**
 * 图片预加载引擎。
 *
 * 根据虚拟滚动可视区调度三层加载：
 * - Immediate：可视区 ±1，高优先级，立刻加载（HQ）。
 * - Cascade：从可视区边界向外各 10 页，渐进延迟 80ms（LQ）。
 * - Cancel：远离可视区 ±12 页外的 cascade 任务会被取消，避免堆积。
 */
export class PreloadEngine {
  private urlResolver: ((index: number, priority: 'immediate' | 'cascade') => string | null) | null = null
  private total = 0
  private active = new Map<string, PreloadTask>()
  private cascadeTimers: ReturnType<typeof setTimeout>[] = []
  private destroyed = false

  setUrlResolver(resolver: (index: number, priority: 'immediate' | 'cascade') => string | null): void {
    this.urlResolver = resolver
  }

  reset(total: number): void {
    this.cancelAll()
    this.total = total
    this.active.clear()
    this.destroyed = false
  }

  onVisibleChange(visibleStart: number, visibleEnd: number, total: number): void {
    if (this.destroyed) return
    if (!this.urlResolver) return
    if (total <= 0) return
    if (this.total !== total) {
      this.total = total
    }

    const start = Math.max(0, visibleStart)
    const end = Math.min(visibleEnd, this.total - 1)

    if (start > end) return

    this.clearCascadeTimers()
    this.cancelFarIndices(start, end)
    this.loadImmediate(start, end)
    // loadImmediate 已覆盖 [start-1, end+1]，cascade 从边界外侧开始避免重叠
    this.loadCascadeForward(end + 2)
    this.loadCascadeBackward(start - 2)
  }

  destroy(): void {
    this.destroyed = true
    this.cancelAll()
  }

  private loadImmediate(visibleStart: number, visibleEnd: number): void {
    const rangeStart = Math.max(0, visibleStart - 1)
    const rangeEnd = Math.min(visibleEnd + 1, this.total - 1)

    for (let i = rangeStart; i <= rangeEnd; i++) {
      this.enqueue(i, 'immediate')
    }
  }

  private loadCascadeForward(fromIndex: number): void {
    const cascadeCount = 10
    const end = Math.min(fromIndex + cascadeCount - 1, this.total - 1)

    if (fromIndex > end) return

    for (let i = fromIndex; i <= end; i++) {
      const delay = (i - fromIndex) * 80
      this.cascadeTimers.push(
        setTimeout(() => {
          this.enqueue(i, 'cascade')
        }, delay)
      )
    }
  }

  private loadCascadeBackward(fromIndex: number): void {
    const cascadeCount = 10
    const end = Math.max(fromIndex - cascadeCount + 1, 0)

    if (fromIndex < end) return

    for (let i = fromIndex; i >= end; i--) {
      const delay = (fromIndex - i) * 80
      this.cascadeTimers.push(
        setTimeout(() => {
          this.enqueue(i, 'cascade')
        }, delay)
      )
    }
  }

  private enqueue(index: number, priority: 'immediate' | 'cascade'): void {
    if (this.destroyed) return
    if (!this.urlResolver) return

    const url = this.urlResolver(index, priority)
    if (!url) return
    if (this.active.has(url)) return

    const img = new Image()
    img.decoding = 'async'

    img.onerror = () => {
      this.active.delete(url)
    }
    img.onload = () => {
      // 加载完成后保留在 active 中，直到被取消或重置。
      // 这样浏览器图片解码缓存可持续命中。
    }
    img.src = url

    const task: PreloadTask = { url, index, image: img, priority }
    this.active.set(url, task)
  }

  private clearCascadeTimers(): void {
    for (const timer of this.cascadeTimers) {
      clearTimeout(timer)
    }
    this.cascadeTimers = []
  }

  private cancelFarIndices(_visibleStart: number, _visibleEnd: number): void {
    const retainMargin = 12
    const visibleStart = Math.max(0, _visibleStart)
    const visibleEnd = Math.max(0, _visibleEnd)
    const keepAbove = Math.max(0, visibleStart - retainMargin)
    const keepBelow = Math.min(this.total - 1, visibleEnd + retainMargin)

    for (const [url, task] of this.active) {
      if (task.priority !== 'cascade') continue
      if (task.index < keepAbove || task.index > keepBelow) {
        task.image.src = ''
        task.image.onload = null
        task.image.onerror = null
        this.active.delete(url)
      }
    }
  }

  private cancelAll(): void {
    for (const timer of this.cascadeTimers) {
      clearTimeout(timer)
    }
    this.cascadeTimers = []

    for (const [, task] of this.active) {
      task.image.src = ''
      task.image.onload = null
      task.image.onerror = null
    }
    this.active.clear()
  }
}

export const preloadEngine = new PreloadEngine()
