import { expect, test } from '@playwright/test'

test('历史卡片的长标题与进度条不会挤占继续阅读按钮', async ({ page }) => {
  await page.route('/api/history/page**', (route) =>
    route.fulfill({
      json: {
        code: 200,
        data: {
          records: [
            {
              comicId: 7,
              comicTitle: '用于验证长标题截断与按钮布局的漫画名称'.repeat(8),
              chapterId: 1,
              chapterNo: '1',
              pageNumber: 1,
              totalPages: 5,
              progressPercent: 20,
              coverUrl: '',
              updatedAt: '2026-01-01T00:00:00',
            },
          ],
          total: 1,
          current: 1,
          size: 20,
        },
      },
    }),
  )
  for (const width of [320, 502, 1440]) {
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/history')
    const title = page.locator('.history-title').first()
    await expect(title).toBeVisible()
    const titleBox = (await title.boundingBox())!
    const progressBox = (await page.locator('.history-progress-row').first().boundingBox())!
    const playBox = (await page.locator('.history-play').first().boundingBox())!
    expect(titleBox.x + titleBox.width).toBeLessThanOrEqual(playBox.x)
    expect(progressBox.x + progressBox.width).toBeLessThanOrEqual(playBox.x)
    expect(await title.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true)
  }
})

test('管理页面共用标题宽度与面板外观，壳层不改变公共组件', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.route('/api/**', (route) => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/settings')
      ? { defaultQuality: 'auto', defaultFit: 'width', defaultDirection: 'vertical' }
      : path.endsWith('/comics')
        ? { records: [], total: 0 }
        : []
    return route.fulfill({ json: { code: 200, data } })
  })
  let pageTitleFont: string | undefined
  let panelAppearance: string | undefined
  for (const path of ['/manage/settings', '/manage/import', '/manage/ai-analysis', '/manage/metadata']) {
    await page.goto(path)
    const title = page.locator('.page-header h1')
    await expect(title).toBeVisible()
    const titleFont = await title.evaluate((element) => getComputedStyle(element).font)
    pageTitleFont ??= titleFont
    expect(titleFont).toBe(pageTitleFont)
    const content = await page.locator('.management-content > *').boundingBox()
    const header = await page.locator('.page-header').boundingBox()
    expect(header!.width).toBeCloseTo(content!.width, 0)
    if (path === '/manage/metadata') await page.getByRole('tab', { name: '标签', exact: true }).click()
    const panel = page.locator('.management-panel').first()
    await expect(panel).toBeVisible()
    const appearance = await panel.evaluate((element) => {
      const style = getComputedStyle(element)
      return [style.backgroundColor, style.borderRadius, style.borderTopColor].join('|')
    })
    panelAppearance ??= appearance
    expect(appearance).toBe(panelAppearance)
    await page.screenshot({ path: testInfo.outputPath(`${path.split('/').at(-1)}.png`) })
  }
})
