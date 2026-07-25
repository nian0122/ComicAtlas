import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

declare global {
  interface Window {
    readonly __setVideoVisibility: (index: number, visible: boolean) => void
    readonly __videoObserverCount: number
  }
}

test.beforeEach(async ({ page }) => {
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

  await page.goto('/e2e/fixtures/video-player.html', { waitUntil: 'commit' })
  await expect(page.locator('video')).toHaveCount(2)
})

async function openPagedReader(page: Page): Promise<void> {
  await page.route('/api/chapters/1', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        data: {
          chapterId: 1,
          comicId: 7,
          chapterTitle: '视频章节',
          pages: [
            {
              id: 101,
              pageNumber: 1,
              hqUrl: 'https://test.local/video-1.mp4',
              lqUrl: '',
              lqStatus: 'NOT_GENERATED',
              width: 640,
              height: 360,
              mediaType: 'VIDEO',
            },
            {
              id: 102,
              pageNumber: 2,
              hqUrl: 'https://test.local/video-2.mp4',
              lqUrl: '',
              lqStatus: 'NOT_GENERATED',
              width: 640,
              height: 360,
              mediaType: 'VIDEO',
            },
          ],
          total: 2,
          prevChapterId: null,
          nextChapterId: null,
        },
      }),
    })
  })
  await page.route('/api/history/7', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, data: { pageNumber: 1 } }),
    })
  })
  await page.route('/api/comics/7', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, data: { id: 7, title: '视频测试漫画' } }),
    })
  })

  await page.evaluate(() => {
    localStorage.setItem(
      'comicatlas.reader.settings',
      JSON.stringify({
        qualityMode: 'AUTO',
        fitMode: 'AUTO',
        zoom: 100,
        readingDirection: 'horizontal',
        showToolbar: true,
        preloadWindow: 2,
        enablePreload: true,
      }),
    )
  })
  await page.goto('/reader/1?page=1')
  await expect(page.locator('video')).toBeVisible()
}

test('进入视窗后保持暂停并提供原生进度控制', async ({ page }) => {
  const video = page.locator('video').first()

  await expect(video).toHaveJSProperty('controls', true)
  await expect(video).toHaveAttribute('preload', 'metadata')
  await expect(video).toHaveJSProperty('paused', true)

  await video.evaluate((element: HTMLVideoElement) => {
    element.currentTime = 0.25
  })
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime))
    .toBeCloseTo(0.25, 2)
})

test('开始播放另一个视频时暂停当前视频', async ({ page }) => {
  const videos = page.locator('video')
  const first = videos.nth(0)
  const second = videos.nth(1)

  await first.evaluate((element: HTMLVideoElement) => element.play())
  await expect(first).toHaveJSProperty('paused', false)

  await second.evaluate((element: HTMLVideoElement) => element.play())
  await expect(second).toHaveJSProperty('paused', false)
  await expect(first).toHaveJSProperty('paused', true)
})

test('视频移出视窗时自动暂停', async ({ page }) => {
  await expect.poll(() => page.evaluate(() => window.__videoObserverCount)).toBe(2)
  const video = page.locator('video').first()

  await page.evaluate(() => window.__setVideoVisibility(0, true))
  await video.evaluate((element: HTMLVideoElement) => element.play())
  await expect(video).toHaveJSProperty('paused', false)

  await page.evaluate(() => window.__setVideoVisibility(0, false))
  await expect(video).toHaveJSProperty('paused', true)
})

test('操作视频控件不会触发阅读器轻点手势', async ({ page }) => {
  const video = page.locator('video').first()

  await video.dispatchEvent('pointerdown', {
    pointerId: 1,
    isPrimary: true,
    button: 0,
    clientX: 100,
    clientY: 340,
  })
  await video.dispatchEvent('pointerup', {
    pointerId: 1,
    isPrimary: true,
    button: 0,
    clientX: 100,
    clientY: 340,
  })

  await expect(page.getByTestId('tap-count')).toHaveText('0')
})

test('真实分页阅读器不会把视频滚轮和快捷键解释为翻页', async ({ page }) => {
  await openPagedReader(page)
  const video = page.locator('video')

  await expect(video).toHaveJSProperty('src', 'https://test.local/video-1.mp4')
  await video.dispatchEvent('wheel', { deltaY: 120 })
  await expect(video).toHaveJSProperty('src', 'https://test.local/video-1.mp4')

  await video.focus()
  await page.keyboard.press('Space')
  await page.keyboard.press('ArrowRight')
  await expect(video).toHaveJSProperty('src', 'https://test.local/video-1.mp4')
})
