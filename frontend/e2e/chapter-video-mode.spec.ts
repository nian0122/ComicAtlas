import { expect, test } from '@playwright/test'
import { registerVideoPlayerBrowserMocks } from './support/video-player-browser-mocks'

test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })

test.beforeEach(async ({ page }) => {
  await registerVideoPlayerBrowserMocks(page)
  await page.route('/api/chapters/1', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          chapterId: 1,
          comicId: 7,
          chapterTitle: '测试章节',
          pages: [
            { id: 11, pageNumber: 1, mediaType: 'IMAGE', hqUrl: '/files/hq/cover.jpg', lqUrl: null },
            { id: 12, pageNumber: 2, mediaType: 'VIDEO', hqUrl: '/files/hq/first.mp4', hqStatus: 'READY' },
            { id: 13, pageNumber: 3, mediaType: 'VIDEO', hqUrl: '/files/hq/deleted.mp4', hqStatus: 'DELETED' },
            { id: 14, pageNumber: 4, mediaType: 'VIDEO', hqUrl: '/files/hq/second.mp4', hqStatus: 'READY' },
          ],
          total: 4,
          prevChapterId: null,
          nextChapterId: null,
        },
      }),
    }),
  )
})

test('只播放本章可用视频，切换时释放上一条并可返回原章节', async ({ page }) => {
  await page.goto('/videos/1?page=2')
  await expect(page.locator('.video-count')).toHaveText('1 / 2')
  await expect(page.locator('.video-media')).toHaveJSProperty('src', '/files/hq/first.mp4')
  await expect(page.locator('.video-media')).toHaveJSProperty('paused', false)

  await page.locator('.short-video-page').evaluate((element) => {
    const start = new Touch({ identifier: 1, target: element, clientY: 680 })
    const end = new Touch({ identifier: 1, target: element, clientY: 280 })
    element.dispatchEvent(new TouchEvent('touchstart', { changedTouches: [start], bubbles: true }))
    element.dispatchEvent(new TouchEvent('touchend', { changedTouches: [end], bubbles: true }))
  })
  await expect(page.locator('.video-count')).toHaveText('2 / 2')
  await expect(page.locator('.video-media')).toHaveJSProperty('src', '/files/hq/second.mp4')
  await expect(page.locator('.video-nav')).toBeHidden()

  await page.getByRole('button', { name: '返回章节阅读' }).click()
  await expect(page).toHaveURL(/\/reader\/1\?page=4/)
  await expect(page.getByRole('link', { name: '短视频播放' })).toBeVisible()
})
