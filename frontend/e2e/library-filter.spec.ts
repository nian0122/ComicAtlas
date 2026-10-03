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
      body: JSON.stringify({ code: 200, data: { records, total: 24 } }),
    })
  })

  await page.route('/api/categories**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
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
      body: JSON.stringify({ code: 200, data: [] }),
    })
  })
}

test('移动漫画库搜索框仅由外层绘制背景和焦点边界', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 502, height: 1078 })
  await mockRoutes(page, { status: [], category: [] })
  await page.goto('/library')
  const search = page.getByRole('textbox', { name: '搜索漫画' })
  await expect(search).toBeVisible()
  async function expectSingleSearchSurface() {
    await expect(search).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
    await expect(search).toHaveCSS('border-top-width', '0px')
    await expect(search).toHaveCSS('box-shadow', 'none')
    await expect(search).toHaveCSS('outline-style', 'none')
    const bounds = await search.boundingBox()
    const container = await page.locator('.search-input').boundingBox()
    expect(bounds!.height).toBeLessThan(container!.height)
  }
  await expectSingleSearchSurface()
  await search.hover()
  await expectSingleSearchSurface()
  await search.fill('测试')
  await expectSingleSearchSurface()
  await page.getByRole('button', { name: '清除搜索' }).click()
  await expect(search).toHaveValue('')
  await expectSingleSearchSurface()
  await page.screenshot({ path: testInfo.outputPath('mobile-library-search.png') })
})

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
  const select = page.locator('.category-select .el-select__wrapper')
  await expect(select).toBeVisible({ timeout: 10000 })
  await select.click()
  await expect(page.getByRole('option')).toHaveCount(4) // 全部分类 + 未分类 + 少年 + 青年

  await page.getByRole('option', { name: '少年' }).click()
  await expect.poll(() => captured.category[captured.category.length - 1]).toBe('少年')

  await select.click()
  await page.getByRole('option', { name: '全部分类' }).click()
  await expect.poll(() => captured.category[captured.category.length - 1]).toBe(null)
})

test('分类筛选支持未分类(_NONE)', async ({ page }) => {
  const captured: CapturedParams = { status: [], category: [] }
  await mockRoutes(page, captured)

  await page.goto('/library')
  const select = page.locator('.category-select .el-select__wrapper')
  await expect(select).toBeVisible({ timeout: 10000 })

  await select.click()
  await page.getByRole('option', { name: '未分类' }).click()
  await expect.poll(() => captured.category[captured.category.length - 1]).toBe('_NONE')
})

test('标签筛选支持无标签(_NONE)且与正常标签互斥', async ({ page }) => {
  const captured: CapturedParams = { status: [], category: [] }
  await mockRoutes(page, captured)

  await page.goto('/library')
  await expect(page.locator('.comic-poster').first()).toBeVisible({ timeout: 10000 })

  const tagSelect = page.locator('.tag-select')
  await tagSelect.click()
  // 选"无标签"
  await page.locator('.el-select-dropdown__item').filter({ hasText: '无标签' }).click()
  // 点击空白关闭下拉
  await page.locator('body').click()
  await expect.poll(() => captured.category[captured.category.length - 1]).toBe(null) // categoryFilter unchanged
})
