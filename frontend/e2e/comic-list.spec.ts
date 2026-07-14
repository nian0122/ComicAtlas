import { test, expect } from '@playwright/test'

function mockComicsRoute(page: any) {
  return page.route('/api/comics**', async (route, request) => {
    const url = new URL(request.url())
    const pageNum = Number(url.searchParams.get('page') || '1')
    const size = Number(url.searchParams.get('size') || '24')

    const total = 30
    const records = Array.from({ length: Math.min(size, total - (pageNum - 1) * size) }, (_, i) => {
      const id = (pageNum - 1) * size + i + 1
      return {
        id,
        title: `漫画 ${id}`,
        author: '作者',
        coverUrl: `https://example.com/cover-${id}.jpg`,
        pageCount: 100 + id,
        category: '',
        status: 'SUCCESS',
        lqStatus: 'NOT_GENERATED',
        progressPercent: id === 1 ? 35 : 0,
        lastReadChapterId: 0,
        lastReadPage: 0,
        createdAt: new Date().toISOString(),
      }
    })

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, data: { records, total } }),
    })
  })
}

test('desktop: renders library with posters, sticky toolbar, hover scale and pagination', async ({ page }) => {
  await mockComicsRoute(page)
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto('/comics')
  await page.waitForLoadState('networkidle')

  // 等待海报渲染
  const posters = page.locator('.comic-poster')
  await expect(posters.first()).toBeVisible({ timeout: 10000 })
  await expect(posters).toHaveCount(24)

  // 工具栏固定/粘性定位
  const toolbar = page.locator('.page-header')
  const position = await toolbar.evaluate((el) => window.getComputedStyle(el).position)
  expect(['sticky', 'fixed']).toContain(position)

  // 滚动后工具栏仍停留在顶部（nav-height = 56px 下方）
  await page.evaluate(() => window.scrollTo(0, 500))
  await page.waitForTimeout(200)
  const toolbarTop = await toolbar.evaluate((el) => el.getBoundingClientRect().top)
  expect(toolbarTop).toBe(56)

  // 悬停放大 scale(1.04)
  const firstPoster = posters.first()
  await firstPoster.hover()
  await page.waitForTimeout(300)
  const transform = await firstPoster.evaluate((el) => window.getComputedStyle(el).transform)
  expect(transform).toMatch(/1\.04/)

  // 分页存在且可切换
  const pagination = page.locator('.el-pagination')
  await expect(pagination).toBeVisible()
  const pageTwo = pagination.locator('.el-pager li').filter({ hasText: '2' })
  await pageTwo.click()
  await expect(pageTwo).toHaveClass(/is-active|active/)

  await page.screenshot({
    path: '../.omo/evidence/task-9-netflix-frontend-refactor.png',
    fullPage: true,
  })
})

test('mobile: 3-column grid with sm posters', async ({ page }) => {
  await mockComicsRoute(page)
  await page.setViewportSize({ width: 375, height: 667 })
  await page.goto('/comics')

  const posters = page.locator('.comic-poster')
  await expect(posters.first()).toBeVisible()

  // 验证海报使用 sm 尺寸
  const firstPoster = posters.first()
  await expect(firstPoster).toHaveClass(/size--sm/)

  // 验证网格为 3 列
  const grid = page.locator('.comic-grid')
  const gridTemplateColumns = await grid.evaluate(
    (el) => window.getComputedStyle(el).gridTemplateColumns
  )
  const columnCount = gridTemplateColumns.split(' ').length
  expect(columnCount).toBe(3)
})
