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
})

/** 逐个点击占位图，激活全部视频播放器。 */
async function activateAllPlayers(page: Page): Promise<void> {
  await page.locator('.video-placeholder').first().click()
  await page.locator('.video-placeholder').first().click()
  await expect(page.locator('video')).toHaveCount(2)
}

async function openPagedReader(page: Page): Promise<void> {
  await page.route('/api/chapters/1', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
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
      body: JSON.stringify({ code: 200, data: { pageNumber: 1 } }),
    })
  })
  await page.route('/api/comics/7', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: { id: 7, title: '视频测试漫画' } }),
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

test('激活后使用 metadata 预加载并提供原生进度控制', async ({ page }) => {
  await activateAllPlayers(page)
  const video = page.locator('video').first()

  await expect(video).toHaveJSProperty('controls', true)
  await expect(video).toHaveAttribute('preload', 'metadata')

  await video.evaluate((element: HTMLVideoElement) => element.pause())
  await expect(video).toHaveJSProperty('paused', true)

  await video.evaluate((element: HTMLVideoElement) => {
    element.currentTime = 0.25
  })
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime))
    .toBeCloseTo(0.25, 2)
})

test('占位预览经静音微播放解码首帧后立即暂停', async ({ page }) => {
  const preview = page.locator('.video-preview').first()

  await expect(preview).toHaveAttribute('preload', 'metadata')

  // 夹具 data URL 不会真实解码：手动把元素置于 HAVE_CURRENT_DATA 并派发
  // metadata 事件，桌面 Chromium 与安卓走同一条静音微播放路径。
  await preview.evaluate((element: HTMLVideoElement) => {
    Object.defineProperty(element, 'readyState', {
      configurable: true,
      value: HTMLMediaElement.HAVE_CURRENT_DATA,
    })
    element.dispatchEvent(new Event('loadedmetadata'))
  })

  await expect(preview).toHaveJSProperty('paused', true)
  await expect(preview).toHaveJSProperty('src', 'data:video/mp4;base64,AAAA')
  await expect(page.locator('.video-placeholder-overlay.preview-ready')).toHaveCount(1)
})

test('苹果端占位预览用静音微播放解码首帧后立即暂停', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'userAgent', {
      configurable: true,
      value:
        'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) ' +
        'AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
    })
    Object.defineProperty(navigator, 'platform', {
      configurable: true,
      value: 'iPhone',
    })
  })
  await page.goto('/test-fixtures/video-player.html', { waitUntil: 'domcontentloaded' })
  await expect(page.locator('.video-placeholder')).toHaveCount(2)

  const preview = page.locator('.video-preview').first()

  // 夹具 data URL 不会真实解码：手动模拟 HAVE_CURRENT_DATA 事件，
  // 验证 iOS 也走静音微播放路径，并在拿到首帧后立即暂停。
  await preview.evaluate((element: HTMLVideoElement) => {
    Object.defineProperty(element, 'readyState', {
      configurable: true,
      value: HTMLMediaElement.HAVE_CURRENT_DATA,
    })
    element.dispatchEvent(new Event('loadedmetadata'))
  })

  await expect(preview).toHaveJSProperty('paused', true)
  await expect(preview).toHaveJSProperty('src', 'data:video/mp4;base64,AAAA')
  await expect(page.locator('.video-placeholder-overlay.preview-ready')).toHaveCount(1)
})

test('开始播放另一个视频时暂停当前视频', async ({ page }) => {
  await activateAllPlayers(page)
  const videos = page.locator('video')
  const first = videos.nth(0)
  const second = videos.nth(1)

  await first.evaluate((element: HTMLVideoElement) => element.play())
  await expect(first).toHaveJSProperty('paused', false)

  await second.evaluate((element: HTMLVideoElement) => element.play())
  await expect(second).toHaveJSProperty('paused', false)
  await expect(first).toHaveJSProperty('paused', true)
})

test('视频移出视窗时自动暂停且保留已加载资源', async ({ page }) => {
  await activateAllPlayers(page)
  await expect.poll(() => page.evaluate(() => window.__videoObserverCount)).toBe(2)
  const video = page.locator('video').first()

  await page.evaluate(() => window.__setVideoVisibility(0, true))
  await video.evaluate((element: HTMLVideoElement) => element.play())
  await expect(video).toHaveJSProperty('paused', false)

  await page.evaluate(() => window.__setVideoVisibility(0, false))
  await expect(video).toHaveJSProperty('paused', true)
  await expect(video).toHaveJSProperty('src', 'data:video/mp4;base64,AAAA')
})

test('操作视频控件不会触发阅读器轻点手势', async ({ page }) => {
  await activateAllPlayers(page)
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
