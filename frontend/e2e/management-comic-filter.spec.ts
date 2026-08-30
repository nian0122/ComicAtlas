import { expect, test, type Page, type Route } from '@playwright/test'

function success(route: Route, data: unknown) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, message: 'success', data }),
  })
}

async function mockManagementPage(page: Page, comicUrls: URL[]) {
  await page.route('/api/manage/comics**', (route) => {
    comicUrls.push(new URL(route.request().url()))
    return success(route, {
      records: [{
        id: 1,
        title: '测试漫画',
        author: '作者',
        coverUrl: '',
        pageCount: 12,
        categoryId: null,
        categoryName: null,
        status: 'READY',
        lqStatus: 'NOT_GENERATED',
        progressPercent: 0,
        lastReadChapterId: 0,
        lastReadPage: 0,
        createdAt: '2026-01-01T00:00:00Z',
      }],
      total: 1,
    })
  })
  await page.route('/api/manage/categories**', (route) => success(route, []))
  await page.route('/api/manage/tags**', (route) => success(route, [
    { id: 1, name: '热血', sortOrder: 1 },
    { id: 2, name: '冒险', sortOrder: 2 },
  ]))
  await page.route('/api/manage/storage/stats', (route) => success(route, {
    hqBytes: 0,
    lqBytes: 0,
    thumbBytes: 0,
  }))
}

test('管理页状态筛选使用后端枚举并清空旧批量选择', async ({ page }) => {
  const comicUrls: URL[] = []
  await mockManagementPage(page, comicUrls)
  await page.goto('/manage/comics')
  await expect(page.locator('.comic-row').first()).toBeVisible()

  await page.locator('.comic-row .el-checkbox').first().click()
  await expect(page.locator('.batch-toolbar')).toBeVisible()

  await page.locator('.filter-select').nth(1).click()
  await page.getByRole('option', { name: '导入失败' }).click()

  await expect.poll(() => comicUrls.at(-1)?.searchParams.get('status')).toBe('IMPORT_FAILED')
  await expect(page.locator('.batch-toolbar')).toHaveCount(0)
})

test('管理页选择无标签时请求不会混入其他标签', async ({ page }) => {
  const comicUrls: URL[] = []
  await mockManagementPage(page, comicUrls)
  await page.goto('/manage/comics')
  await expect(page.locator('.comic-row').first()).toBeVisible()

  const tagSelect = page.locator('.filter-select--wide')
  await tagSelect.click()
  await page.getByRole('option', { name: '热血' }).click()
  await page.keyboard.press('Escape')
  await tagSelect.click()
  await expect(page.getByRole('option', { name: '无标签' })).toBeVisible()
  await page.getByRole('option', { name: '无标签' }).click()

  await expect.poll(() => comicUrls.at(-1)?.searchParams.getAll('tags[]')).toEqual(['_NONE'])
})
