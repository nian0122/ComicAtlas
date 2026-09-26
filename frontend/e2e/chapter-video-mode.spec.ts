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
    const end = new Touch({ identifier: 1, target: element, clientX: 180, clientY: 280 })
    element.dispatchEvent(new TouchEvent('touchstart', { changedTouches: [start], bubbles: true }))
    element.dispatchEvent(new TouchEvent('touchend', { changedTouches: [end], bubbles: true }))
  })
}

async function swipeDown(page: Page): Promise<void> {
  await page.locator('.short-video-page').evaluate((element) => {
    const start = new Touch({ identifier: 1, target: element, clientX: 180, clientY: 280 })
    const end = new Touch({ identifier: 1, target: element, clientX: 180, clientY: 680 })
    element.dispatchEvent(new TouchEvent('touchstart', { changedTouches: [start], bubbles: true }))
    element.dispatchEvent(new TouchEvent('touchend', { changedTouches: [end], bubbles: true }))
  })
}

test.beforeEach(async ({ page }) => {
  await registerVideoPlayerBrowserMocks(page)
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

  await swipeUp(page)
  await expect(page.locator('video.video-media')).toHaveJSProperty('src', '/files/hq/clip.mp4')
  await expect(page.locator('video.video-media')).toHaveJSProperty('paused', false)
  const progressSlider = page.getByRole('slider', { name: '播放进度，可拖动调整' })
  await expect(progressSlider).toBeVisible()
  await progressSlider.focus()
  await progressSlider.press('ArrowRight')
  await expect(page.locator('video.video-media')).toHaveJSProperty('currentTime', 5)
  await page.getByRole('button', { name: '播放速度' }).click()
  await page.getByRole('option', { name: '1.5×' }).click()
  await expect(page.getByRole('button', { name: '播放速度' })).toHaveText('1.5×')
  const videoBounds = await page.locator('video.video-media').boundingBox()
  if (!videoBounds) throw new Error('视频区域不可用')
  await page.mouse.move(videoBounds.x + videoBounds.width / 2, videoBounds.y + videoBounds.height / 2)
  await page.mouse.down()
  await page.waitForTimeout(500)
  await expect(page.getByText('2× 加速播放')).toBeVisible()
  await expect(page.locator('video.video-media')).toHaveJSProperty('playbackRate', 2)
  await page.mouse.up()
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
  await page.getByRole('button', { name: '返回漫画详情' }).click()
  await expect(page).toHaveURL(/\/comic\/7/)
})

test('漫画阅读入口进入连续阅读器', async ({ page }) => {
  await page.goto('/comic/7')
  await page.getByRole('button', { name: /开始阅读|继续阅读/ }).click()
  await expect(page).toHaveURL(/\/reader\/1/)
})
