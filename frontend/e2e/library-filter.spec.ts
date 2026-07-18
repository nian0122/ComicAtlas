import { test, expect, type Page } from '@playwright/test'

interface CapturedParams {
  status: (string | null)[]
  category: (string | null)[]
}

/**
 * 拦截漫画库页面用到的三个接口:
 * - /api/comics**    记录 status/category 请求参数并返回 24 条 READY 漫画
 * - /api/categories** 返回两个分类(少年/青年)
 * - /api/tags**       返回空标签列表
 * 响应包裹 { code, data } 形状以配合 axios 拦截器解包。
 */
async function mockRoutes(page: Page, captured: CapturedParams) {
  await page.route('/api/comics**', async (route, request) => {
    const url = new URL(request.url())
    captured.status.push(url.searchParams.get('status'))
    captured.category.push(url.searchParams.get('category'))
    const records = Array.from({ length: 24 }, (_, i) => ({
      id: i + 1,
      title: `漫画 ${i + 1}`,
      author: '作者',
      coverUrl: `https://example.com/cover-${i + 1}.jpg`,
      pageCount: 100,
      categoryId: null,
      categoryName: null,
      status: 'READY',
      lqStatus: 'NOT_GENERATED',
      progressPercent: 0,
      lastReadChapterId: 0,
      lastReadPage: 0,
      createdAt: new Date().toISOString(),
    }))
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, data: { records, total: 24 } }),
    })
  })

  await page.route('/api/categories**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        data: [
          { id: 1, name: '少年', sortOrder: 1 },
          { id: 2, name: '青年', sortOrder: 2 },
        ],
      }),
    })
  })

  await page.route('/api/tags**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, data: [] }),
    })
  })
}

test('漫画库请求恒带 status=READY', async ({ page }) => {
  const captured: CapturedParams = { status: [], category: [] }
  await mockRoutes(page, captured)

  await page.goto('/library')
  await expect(page.locator('.comic-poster').first()).toBeVisible({ timeout: 10000 })

  expect(captured.status.length).toBeGreaterThan(0)
  expect(captured.status.every((s) => s === 'READY')).toBe(true)
})

test('工具栏不含状态筛选下拉', async ({ page }) => {
  const captured: CapturedParams = { status: [], category: [] }
  await mockRoutes(page, captured)

  await page.goto('/library')
  await expect(page.locator('.comic-poster').first()).toBeVisible({ timeout: 10000 })

  await expect(page.locator('.status-select')).toHaveCount(0)
})

test('分类筛选:选中传 category,切回全部不传', async ({ page }) => {
  const captured: CapturedParams = { status: [], category: [] }
  await mockRoutes(page, captured)

  await page.goto('/library')
  const select = page.locator('.category-select select')
  await expect(select).toBeVisible({ timeout: 10000 })
  await expect(select.locator('option')).toHaveCount(3) // 全部分类 + 少年 + 青年

  await select.selectOption({ label: '少年' })
  await expect.poll(() => captured.category[captured.category.length - 1]).toBe('少年')

  await select.selectOption({ label: '全部分类' })
  await expect.poll(() => captured.category[captured.category.length - 1]).toBe(null)
})
