import type { Page } from '@playwright/test'

export async function registerVideoPlayerBrowserMocks(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const pausedState = new WeakMap<HTMLMediaElement, boolean>()
    const currentTimeState = new WeakMap<HTMLMediaElement, number>()
    const sourceState = new WeakMap<HTMLMediaElement, string>()
    Object.defineProperty(HTMLMediaElement.prototype, 'src', {
      configurable: true,
      get() {
        return sourceState.get(this) ?? ''
      },
      set(value: string) {
        sourceState.set(this, value)
      },
    })
    Object.defineProperty(HTMLMediaElement.prototype, 'paused', {
      configurable: true,
      get() {
        return pausedState.get(this) ?? true
      },
    })
    Object.defineProperty(HTMLMediaElement.prototype, 'play', {
      configurable: true,
      value: function play(this: HTMLMediaElement): Promise<void> {
        pausedState.set(this, false)
        this.dispatchEvent(new Event('play'))
        return Promise.resolve()
      },
    })
    Object.defineProperty(HTMLMediaElement.prototype, 'pause', {
      configurable: true,
      value: function pause(this: HTMLMediaElement): void {
        if (pausedState.get(this) === false) {
          pausedState.set(this, true)
          this.dispatchEvent(new Event('pause'))
        }
      },
    })
    Object.defineProperty(HTMLMediaElement.prototype, 'currentTime', {
      configurable: true,
      get() {
        return currentTimeState.get(this) ?? 0
      },
      set(value: number) {
        currentTimeState.set(this, value)
      },
    })

    type Observation = {
      readonly target: Element
      readonly callback: IntersectionObserverCallback
      readonly observer: IntersectionObserver
    }
    const observations: Observation[] = []

    class FakeIntersectionObserver implements IntersectionObserver {
      readonly root = null
      readonly rootMargin = '0px'
      readonly scrollMargin = '0px'
      readonly thresholds = [0]
      readonly callback: IntersectionObserverCallback

      constructor(callback: IntersectionObserverCallback) {
        this.callback = callback
      }

      disconnect(): void {
        observations.splice(
          0,
          observations.length,
          ...observations.filter((item) => item.observer !== this),
        )
      }

      observe(target: Element): void {
        observations.push({ target, callback: this.callback, observer: this })
      }

      takeRecords(): IntersectionObserverEntry[] {
        return []
      }

      unobserve(target: Element): void {
        const index = observations.findIndex(
          (item) => item.observer === this && item.target === target,
        )
        if (index >= 0) observations.splice(index, 1)
      }
    }

    Object.defineProperty(window, 'IntersectionObserver', {
      configurable: true,
      value: FakeIntersectionObserver,
    })
    Object.defineProperty(window, '__videoObserverCount', {
      configurable: true,
      get: () => observations.length,
    })
    Object.defineProperty(window, '__setVideoVisibility', {
      configurable: true,
      value: (index: number, visible: boolean): void => {
        const observation = observations[index]
        if (!observation) return
        const rect = observation.target.getBoundingClientRect()
        const entry: IntersectionObserverEntry = {
          boundingClientRect: rect,
          intersectionRatio: visible ? 1 : 0,
          intersectionRect: visible ? rect : new DOMRectReadOnly(),
          isIntersecting: visible,
          rootBounds: null,
          target: observation.target,
          time: performance.now(),
        }
        observation.callback([entry], observation.observer)
      },
    })
  })
}
