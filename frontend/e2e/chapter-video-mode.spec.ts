import { expect, test, type Page } from '@playwright/test'
import { registerVideoPlayerBrowserMocks } from './support/video-player-browser-mocks'

test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })

const imageBody = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9S3wAAAABJRU5ErkJggg==',
  'base64',
)

async function swipeUp(page: Page): Promise<void> {
  await page.locator('.short-video-page').evaluate((element) => {
    const start = new Touch({ identifier: 1, target: element, clientX: 180, clientY: 680 })
    const moving = new Touch({ identifier: 1, target: element, clientX: 180, clientY: 470 })
    const end = new Touch({ identifier: 1, target: element, clientX: 180, clientY: 280 })
    element.dispatchEvent(new TouchEvent('touchstart', { changedTouches: [start], bubbles: true }))
    element.dispatchEvent(new TouchEvent('touchmove', { changedTouches: [moving], bubbles: true, cancelable: true }))
    element.dispatchEvent(new TouchEvent('touchend', { changedTouches: [end], bubbles: true }))
  })
  await page.waitForTimeout(380)
}

async function swipeDown(page: Page): Promise<void> {
  await page.locator('.short-video-page').evaluate((element) => {
    const start = new Touch({ identifier: 1, target: element, clientX: 180, clientY: 280 })
    const moving = new Touch({ identifier: 1, target: element, clientX: 180, clientY: 490 })
    const end = new Touch({ identifier: 1, target: element, clientX: 180, clientY: 680 })
    element.dispatchEvent(new TouchEvent('touchstart', { changedTouches: [start], bubbles: true }))
    element.dispatchEvent(new TouchEvent('touchmove', { changedTouches: [moving], bubbles: true, cancelable: true }))
    element.dispatchEvent(new TouchEvent('touchend', { changedTouches: [end], bubbles: true }))
  })
  await page.waitForTimeout(380)
}

test.beforeEach(async ({ page }) => {
  await registerVideoPlayerBrowserMocks(page)
  await page.route('**/api/comics?**', (route) =>
    route.fulfill({
      json: {
        code: 200,
        data: {
          records: [{ id: 7, title: '混排漫画', status: 'READY', pageCount: 5, hqSize: 0, tags: [], coverUrl: '' }],
          total: 1,
          current: 1,
          size: 24,
        },
      },
    }),
  )
  await page.route('**/api/categories**', (route) => route.fulfill({ json: { code: 200, data: [] } }))
  await page.route('**/api/tags**', (route) => route.fulfill({ json: { code: 200, data: [] } }))
  await page.route('**/api/history/page**', (route) =>
    route.fulfill({
      json: {
        code: 200,
        data: {
          records: [
            {
              comicId: 7,
              comicTitle: '混排漫画',
              chapterId: 1,
              chapterNo: '1',
              pageNumber: 1,
              totalPages: 5,
              progressPercent: 20,
              coverUrl: '/files/hq/first.jpg',
              updatedAt: '2026-01-01T00:00:00',
            },
          ],
          total: 1,
          current: 1,
          size: 24,
        },
      },
    }),
  )
  await page.route('**/files/hq/*.jpg', (route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: imageBody }),
  )
  await page.route('/api/chapters/1', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          chapterId: 1,
          comicId: 7,
          chapterTitle: '第一章',
          pages: [
            { id: 11, pageNumber: 1, mediaType: 'IMAGE', hqUrl: '/files/hq/first.jpg', hqStatus: 'READY', lqUrl: null },
            { id: 12, pageNumber: 2, mediaType: 'VIDEO', hqUrl: '/files/hq/clip.mp4', hqStatus: 'READY', lqUrl: null },
            { id: 13, pageNumber: 3, mediaType: 'IMAGE', hqUrl: '/files/hq/last.jpg', hqStatus: 'READY', lqUrl: null },
          ],
          total: 3,
          prevChapterId: null,
          nextChapterId: 2,
        },
      }),
    }),
  )
  await page.route('/api/chapters/2', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          chapterId: 2,
          comicId: 7,
          chapterTitle: '第二章',
          pages: [
            { id: 21, pageNumber: 1, mediaType: 'IMAGE', hqUrl: '/files/hq/next.jpg', hqStatus: 'READY', lqUrl: null },
            { id: 22, pageNumber: 2, mediaType: 'VIDEO', hqUrl: '/files/hq/next.mp4', hqStatus: 'READY', lqUrl: null },
          ],
          total: 2,
          prevChapterId: 1,
          nextChapterId: null,
        },
      }),
    }),
  )
  await page.route('/api/history/7', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: null }),
    }),
  )
  await page.route('/api/comics/7', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          id: 7,
          title: '混排漫画',
          author: '测试作者',
          description: '',
          coverUrl: '',
          pageCount: 5,
          hqSize: 0,
          sourceType: 'REGISTER',
          sourceRef: '',
          categoryId: null,
          categoryName: null,
          status: 'READY',
          progressPercent: 0,
          lastReadChapterId: null,
          lastReadPage: 0,
          chapters: [],
          tags: [],
          createdAt: '2026-01-01T00:00:00',
          updatedAt: '2026-01-01T00:00:00',
        },
      }),
    }),
  )
  await page.route('/api/comics/7/catalog', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: [
          {
            id: null,
            title: null,
            children: [],
            chapters: [
              { id: 1, chapterNo: '1', title: '第一章', globalOrder: 1, pageCount: 3 },
              { id: 2, chapterNo: '2', title: '第二章', globalOrder: 2, pageCount: 2 },
            ],
          },
        ],
      }),
    }),
  )
})

test('详情页进入普通阅读器，再从工具栏切换混排短视频模式', async ({ page }) => {
  await page.goto('/comic/7')
  await expect(page.getByRole('button', { name: /开始阅读|继续阅读/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /短视频阅读/ })).toHaveCount(0)
  await page.getByRole('button', { name: /开始阅读|继续阅读/ }).click()
  await expect(page).toHaveURL(/\/reader\/1/)
  await page.locator('.reader-page').click({ position: { x: 195, y: 400 } })
  await expect(page.getByRole('button', { name: '短视频阅读' })).toBeVisible()
  await page.getByRole('button', { name: '短视频阅读' }).click()
  await expect(page).toHaveURL(/\/videos\/1\?page=1/)
  await expect(page.locator('.media-image')).toHaveAttribute('src', '/files/hq/first.jpg')

  const stage = page.locator('.short-video-page')
  await stage.evaluate((element) => {
    const start = new Touch({ identifier: 8, target: element, clientX: 180, clientY: 680 })
    const moving = new Touch({ identifier: 8, target: element, clientX: 180, clientY: 470 })
    element.dispatchEvent(new TouchEvent('touchstart', { changedTouches: [start], bubbles: true }))
    element.dispatchEvent(new TouchEvent('touchmove', { changedTouches: [moving], bubbles: true, cancelable: true }))
  })
  await expect(page.locator('.video-media-frame').first()).toHaveAttribute('style', /-210px/)
  await expect(page.locator('.video-media-preview video')).toHaveJSProperty('src', '/files/hq/clip.mp4')
  await stage.evaluate((element) => {
    const end = new Touch({ identifier: 8, target: element, clientX: 180, clientY: 280 })
    element.dispatchEvent(new TouchEvent('touchend', { changedTouches: [end], bubbles: true }))
  })
  await page.waitForTimeout(380)
  await expect(page.locator('video.video-media')).toHaveJSProperty('src', '/files/hq/clip.mp4')
  await expect(page.locator('video.video-media')).toHaveJSProperty('paused', false)
  await page.locator('video.video-media').dispatchEvent('waiting')
  await expect(page.getByRole('status', { name: '视频缓冲中' })).toBeVisible()
  await page.locator('video.video-media').dispatchEvent('timeupdate')
  await expect(page.getByRole('status', { name: '视频缓冲中' })).toHaveCount(0)
  await page.locator('video.video-media').dispatchEvent('stalled')
  await page.waitForTimeout(220)
  await expect(page.getByRole('status', { name: '视频缓冲中' })).toHaveCount(0)
  const progressSlider = page.getByRole('slider', { name: '播放进度，可拖动调整' })
  await expect(progressSlider).toBeVisible()
  await progressSlider.focus()
  await progressSlider.press('ArrowRight')
  await expect(page.locator('video.video-media')).toHaveJSProperty('currentTime', 5)
  await page.locator('.video-stage').evaluate((element) => {
    const touch = new Touch({ identifier: 2, target: element, clientX: 180, clientY: 420 })
    element.dispatchEvent(new TouchEvent('touchstart', { changedTouches: [touch], bubbles: true }))
  })
  await page.waitForTimeout(500)
  await expect(page.getByRole('dialog', { name: '播放速度' })).toBeVisible()
  await page.getByRole('option', { name: '1.5×' }).click()
  await expect(page.locator('video.video-media')).toHaveJSProperty('playbackRate', 1.5)
  await expect(page.locator('video.video-media')).toHaveJSProperty('playbackRate', 1.5)
  await page.getByRole('button', { name: '开启声音' }).click()
  await expect(page.locator('video.video-media')).toHaveJSProperty('muted', false)

  await swipeUp(page)
  await expect(page.locator('.media-image')).toHaveAttribute('src', '/files/hq/last.jpg')
  await swipeUp(page)
  await expect(page).toHaveURL(/\/videos\/2\?page=1/)
  await expect(page.locator('.media-image')).toHaveAttribute('src', '/files/hq/next.jpg')
  await expect(page.locator('.video-state')).toHaveCount(0)

  await swipeDown(page)
  await expect(page).toHaveURL(/\/videos\/1\?page=3/)
  await expect(page.locator('.media-image')).toHaveAttribute('src', '/files/hq/last.jpg')
  await page.getByRole('button', { name: '返回漫画阅读' }).click()
  // 页码是一次性导航参数，阅读器消费后会清理 URL；验证恢复后的实际进度。
  await expect(page).toHaveURL(/\/reader\/1(?:\?|$)/)
  await page.locator('.reader-page').click({ position: { x: 195, y: 400 } })
  await expect(page.getByRole('button', { name: '第 3 / 3 页' })).toBeVisible()
})

test.describe('桌面阅读工具栏覆盖层', () => {
  test.use({ viewport: { width: 1440, height: 1000 }, hasTouch: false, isMobile: false })

  for (const readingDirection of ['vertical', 'horizontal']) {
    test(`${readingDirection} 阅读隐藏工具栏不留黑条且不改变图片与进度`, async ({ page }, testInfo) => {
      await page.addInitScript((direction) => {
        localStorage.setItem(
          'comicatlas.reader.settings',
          JSON.stringify({ readingDirection: direction, fitMode: 'HEIGHT', showToolbar: true }),
        )
      }, readingDirection)
      await page.goto('/reader/1')
      const viewport = page.locator(readingDirection === 'vertical' ? '.reader-viewport' : '.paged-viewport')
      // 虚拟列表会复用并重排节点，按媒体地址定位同一页，避免比较不同图片。
      const image = viewport.locator('img[src$="/first.jpg"]')
      await expect(image).toBeVisible()
      const viewportBefore = (await viewport.boundingBox())!
      expect(viewportBefore.y).toBe(0)
      expect(viewportBefore.height).toBe(1000)
      const imageBefore = await image.boundingBox()
      const scrollContainer = readingDirection === 'vertical' ? viewport.locator('.scroller') : viewport
      const scrollBefore = await scrollContainer.evaluate((element) => element.scrollTop)
      const pageBefore = await page.locator('.page-indicator').textContent()
      await page.getByRole('button', { name: '阅读设置', exact: true }).click()
      await page.getByRole('button', { name: '隐藏工具栏', exact: true }).click()
      const toolbar = page.locator('.reader-toolbar')
      await expect(toolbar).toHaveCSS('opacity', '0')
      await expect(toolbar).toHaveAttribute('inert', '')
      expect(await viewport.boundingBox()).toEqual(viewportBefore)
      expect(await image.boundingBox()).toEqual(imageBefore)
      expect(await scrollContainer.evaluate((element) => element.scrollTop)).toBe(scrollBefore)
      expect(await page.locator('.page-indicator').textContent()).toBe(pageBefore)
      const toolbarAfter = (await toolbar.boundingBox())!
      expect(toolbarAfter.y + toolbarAfter.height).toBeLessThanOrEqual(0)
      await page.screenshot({ path: testInfo.outputPath(`desktop-toolbar-hidden-${readingDirection}.png`) })
    })
  }
})

test('移动阅读工具栏分组清晰且页码导航不越界', async ({ page }, testInfo) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/reader/1')
  await showReaderToolbar(page)
  for (const width of [320, 502]) {
    await page.setViewportSize({ width, height: 1078 })
    const toolbar = page.locator('.reader-toolbar-mobile')
    const navigation = page.getByRole('navigation', { name: '章节导航' })
    await expect(toolbar).toBeVisible()
    for (const container of [toolbar, navigation]) {
      const bounds = await container.boundingBox()
      expect(bounds!.x).toBeGreaterThan(0)
      expect(bounds!.x + bounds!.width).toBeLessThan(width)
      for (const button of await container.getByRole('button').all()) {
        const buttonBounds = await button.boundingBox()
        expect(buttonBounds!.x).toBeGreaterThanOrEqual(bounds!.x)
        expect(buttonBounds!.x + buttonBounds!.width).toBeLessThanOrEqual(bounds!.x + bounds!.width)
        expect(buttonBounds!.y + buttonBounds!.height).toBeLessThanOrEqual(bounds!.y + bounds!.height)
      }
    }
    await page.screenshot({ path: testInfo.outputPath(`reader-toolbar-${width}.png`) })
  }
  await page.locator('#mobile-reader-page-progress').click()
  await expect(page.getByRole('dialog', { name: '跳转页码' })).toBeVisible()
  await page.getByLabel('目标页码', { exact: true }).fill('2')
  await page.getByRole('button', { name: '跳转到第 2 页' }).click()
  await expect(page.locator('#mobile-reader-page-progress')).toHaveText('第 2 / 3 页')
  await page.getByRole('button', { name: '阅读设置', exact: true }).click()
  await expect(page.locator('.reader-settings-drawer')).toBeVisible()
})

test('漫画阅读入口进入连续阅读器', async ({ page }) => {
  await page.goto('/comic/7')
  await page.getByRole('button', { name: /开始阅读|继续阅读/ }).click()
  await expect(page).toHaveURL(/\/reader\/1/)
})

async function showReaderToolbar(page: Page): Promise<void> {
  await expect(page.locator('.reader-page')).toBeVisible()
  if (!(await page.getByRole('button', { name: '短视频阅读', exact: true }).isVisible())) {
    await page.locator('.reader-page').click({ position: { x: 195, y: 400 } })
  }
  await expect(page.getByRole('button', { name: '短视频阅读', exact: true })).toBeVisible()
}

test('漫画库筛选进入阅读，反复切换沉浸模式和刷新后依次返回详情与原列表', async ({ page }) => {
  await page.goto('/library?keyword=混排&sort=title')
  await page.locator('.comic-poster').first().click()
  await expect(page).toHaveURL(/\/comic\/7$/)
  const detailPosition = await page.evaluate(() => window.history.state.position)
  await page.getByRole('button', { name: /开始阅读|继续阅读/ }).click()
  await expect(page).toHaveURL(/\/reader\/1/)
  const readingPosition = await page.evaluate(() => window.history.state.position)
  for (let round = 0; round < 2; round++) {
    await showReaderToolbar(page)
    await page.getByRole('button', { name: '短视频阅读', exact: true }).click()
    await expect(page.locator('.media-image')).toBeVisible()
    await expect(page).toHaveURL(/\/videos\/1/)
    expect(await page.evaluate(() => window.history.state.position)).toBe(readingPosition)
    await page.reload()
    await expect(page.locator('.media-image')).toBeVisible()
    await page.getByRole('button', { name: '返回漫画阅读' }).click()
    await expect(page).toHaveURL(/\/reader\/1(?:\?|$)/)
    expect(await page.evaluate(() => window.history.state.position)).toBe(readingPosition)
  }
  await showReaderToolbar(page)
  await page.getByRole('button', { name: '返回', exact: true }).click()
  await expect(page).toHaveURL(/\/comic\/7$/)
  expect(await page.evaluate(() => window.history.state.position)).toBe(detailPosition)
  await page.reload()
  await page.getByRole('button', { name: '返回', exact: true }).click()
  await expect(page).toHaveURL(/\/library\?/)
  expect(new URL(page.url()).searchParams.get('keyword')).toBe('混排')
  expect(new URL(page.url()).searchParams.get('sort')).toBe('title')
  await expect(page.locator('.comic-poster')).toHaveCount(1)
})

test('历史页直达阅读时先返回详情，再返回历史页', async ({ page }) => {
  await page.goto('/history')
  await page.locator('.history-thumb').click()
  await expect(page).toHaveURL(/\/reader\/1/)
  await showReaderToolbar(page)
  await page.getByRole('button', { name: '返回', exact: true }).click()
  await expect(page).toHaveURL(/\/comic\/7$/)
  await page.getByRole('button', { name: '返回', exact: true }).click()
  await expect(page).toHaveURL(/\/history$/)
})

test('分享链接直达详情或沉浸阅读时返回漫画库', async ({ page }) => {
  await page.goto('/comic/7')
  await page.getByRole('button', { name: '返回', exact: true }).click()
  await expect(page).toHaveURL(/\/library$/)
  await page.goto('/videos/1?page=1')
  await page.getByRole('button', { name: '返回漫画阅读' }).click()
  await showReaderToolbar(page)
  await page.getByRole('button', { name: '返回', exact: true }).click()
  await expect(page).toHaveURL(/\/comic\/7$/)
  await page.getByRole('button', { name: '返回', exact: true }).click()
  await expect(page).toHaveURL(/\/library$/)
})

test('浏览器后退离开沉浸会话，前进后仍可返回来源', async ({ page }) => {
  await page.goto('/library')
  await page.locator('.comic-poster').first().click()
  await page.getByRole('button', { name: /开始阅读|继续阅读/ }).click()
  await showReaderToolbar(page)
  await page.getByRole('button', { name: '短视频阅读', exact: true }).click()
  await expect(page).toHaveURL(/\/videos\/1/)
  await page.goBack()
  await expect(page).toHaveURL(/\/comic\/7$/)
  await page.goForward()
  await expect(page).toHaveURL(/\/videos\/1/)
  await page.getByRole('button', { name: '返回漫画阅读' }).click()
  await showReaderToolbar(page)
  await page.getByRole('button', { name: '目录', exact: true }).click()
  await expect(page).toHaveURL(/\/comic\/7$/)
  await page.getByRole('button', { name: '返回', exact: true }).click()
  await expect(page).toHaveURL(/\/library/)
})
