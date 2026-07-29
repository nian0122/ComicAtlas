/**
 * Video Reader E2E 测试 — 基线套件。
 *
 * 依赖: 库中至少有一本含视频的漫画（2+ VIDEO 页 + 1+ IMAGE 页）。
 * 目标: http://localhost:80 (Nginx)，不是 Vite dev server。
 */
import { test, expect, type Page } from '@playwright/test'
import { openVideoComic, createHqRequestCollector } from '../utils/video-fixtures'

/**
 * 辅助: 滚动阅读器并统计可见的视频/图片元素数。
 * 由于 virtual-scroller 会卸载视口外元素，这里取各滚动位置的最大值。
 */
async function countVisibleMedia(page: Page): Promise<{ videoCount: number; imageCount: number }> {
  let videoCount = 0
  let imageCount = 0

  const scroller = page.locator('.scroller')
  const scrollerExists = await scroller.count()

  if (scrollerExists === 0) {
    // 横向翻页模式——用键盘翻页
    for (let p = 0; p < 15; p++) {
      const v = await page.locator('.video-player').count()
      const im = await page.locator('.progressive-image').count()
      videoCount = Math.max(videoCount, v)
      imageCount = Math.max(imageCount, im)
      if (videoCount >= 2 && imageCount >= 1) break
      await page.keyboard.press('ArrowRight')
      await page.waitForTimeout(250)
    }
  } else {
    // 纵向滚动模式
    for (let i = 0; i < 15; i++) {
      const v = await page.locator('.video-player').count()
      const im = await page.locator('.progressive-image').count()
      videoCount = Math.max(videoCount, v)
      imageCount = Math.max(imageCount, im)
      if (videoCount >= 2 && imageCount >= 1) break

      await scroller.evaluate((el) => {
        el.scrollTop += el.clientHeight * 0.8
        el.dispatchEvent(new Event('scroll'))
      })
      await page.waitForTimeout(200)
    }
  }

  return { videoCount, imageCount }
}

test.describe('video reader', () => {
  test('fixtures: video comic exists with 2+ VIDEO items', async ({ page }) => {
    // Step 1: 定位并打开视频漫画的阅读器
    await openVideoComic(page)

    // Step 2: 滚动浏览，统计可见的 VIDEO/IMAGE 元素
    const { videoCount, imageCount } = await countVisibleMedia(page)

    // Step 3: 断言
    expect(
      videoCount,
      `Expected at least 2 VIDEO pages, found ${videoCount}. ` +
      'Ensure the test comic contains 2+ VIDEO tracks.',
    ).toBeGreaterThanOrEqual(2)

    expect(
      imageCount,
      `Expected at least 1 IMAGE page, found ${imageCount}. ` +
      'The test comic should contain a mix of VIDEO and IMAGE pages.',
    ).toBeGreaterThanOrEqual(1)
  })

  test('HQ requests are issued through Nginx /files/hq/ route', async ({ page }) => {
    const collector = createHqRequestCollector(page)

    // 打开视频漫画并开始采集
    await openVideoComic(page)
    collector.start()

    // 翻几页触发 HQ 图片/视频请求
    for (let i = 0; i < 3; i++) {
      await page.keyboard.press('ArrowRight')
      await page.waitForTimeout(300)
    }

    collector.stop()

    const allRequests = collector.getRequestsForUrl('/files/hq/')

    expect(
      allRequests.length,
      `Expected at least 1 HQ request through /files/hq/, got ${allRequests.length}. ` +
      'If the reader is showing LQ images, ensure force-hq mode or wait for HQ fallback. ' +
      'Check that Nginx is running and serving /files/hq/ correctly.',
    ).toBeGreaterThanOrEqual(1)

    // 验证请求 URL 格式正确（经过 Nginx 路由）
    for (const req of allRequests) {
      expect(req.url()).toMatch(/\/files\/hq\//)
    }

    collector.dispose()
  })
})
