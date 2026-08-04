import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { registerVideoPlayerBrowserMocks } from './support/video-player-browser-mocks'

declare global {
  interface Window {
    readonly __setVideoVisibility: (index: number, visible: boolean) => void
    readonly __videoObserverCount: number
  }
}

test.beforeEach(async ({ page }) => {
  await registerVideoPlayerBrowserMocks(page)

  await page.goto('/test-fixtures/video-player.html', { waitUntil: 'domcontentloaded' })
  await expect(page.locator('.video-placeholder')).toHaveCount(2)
  await page.locator('.video-placeholder').first().click()
  await page.locator('.video-placeholder').first().click()
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
  await page.locator('.video-placeholder').click()
  await expect(page.locator('video')).toBeVisible()
}

test('激活后不预加载并提供原生进度控制', async ({ page }) => {
  const video = page.locator('video').first()

  await expect(video).toHaveJSProperty('controls', true)
  await expect(video).toHaveAttribute('preload', 'none')
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
