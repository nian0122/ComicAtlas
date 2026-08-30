import { expect, test } from '@playwright/test'

function json(route: import('@playwright/test').Route, data: unknown) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, message: 'success', data }),
  })
}

test('存储管理筛选可清空并回到默认条件', async ({ page }) => {
  const comicRequests: string[] = []
  await page.route('**/api/manage/storage/stats', (route) => json(route, {
    hqBytes: 1024,
    lqBytes: 0,
    comicCount: 1,
  }))
  await page.route('**/api/manage/admin/storage/comics**', (route) => {
    comicRequests.push(route.request().url())
    return json(route, {
      records: [{
        comicId: 1,
        title: '目标漫画',
        coverUrl: '',
        totalSize: 1024,
        hqSize: 1024,
        lqSize: 0,
        hqStatus: 'READY',
        lqStatus: 'NOT_GENERATED',
        transcodeStatus: 'NOT_NEEDED',
        chapterCount: 1,
        pageCount: 1,
      }],
      total: 1,
    })
  })
  await page.route('**/api/manage/categories**', (route) => json(route, [{ id: 1, name: '动作', sortOrder: 1 }]))
  await page.route('**/api/manage/tags**', (route) => json(route, [{ id: 2, name: '热血', sortOrder: 1 }]))

  await page.goto('/manage/storage')
  const keyword = page.getByPlaceholder('搜索标题')
  await keyword.fill('目标')
  await page.locator('.filter-select').first().click()
  await page.getByRole('option', { name: '还有 HQ' }).click()
  await page.locator('.filter-select').nth(2).click()
  await page.getByRole('option', { name: '动作' }).click()
  await page.locator('.filter-select').nth(3).click()
  await page.getByRole('option', { name: '热血' }).click()
  await page.keyboard.press('Escape')
  await page.waitForTimeout(200)

  await page.setViewportSize({ width: 1280, height: 720 })
  await page.locator('.action-section').nth(1).evaluate((element) => element.scrollIntoView({ block: 'center' }))
  await page.screenshot({ path: 'C:\\Users\\Acer\\.codex\\visualizations\\2026\\08\\11\\storage-category-tag-filter.png' })

  await expect.poll(() => new URL(comicRequests.at(-1) ?? '').searchParams.get('category')).toBe('动作')
  await expect.poll(() => new URL(comicRequests.at(-1) ?? '').searchParams.get('tag')).toBe('热血')

  const clearButton = page.getByRole('button', { name: '清空筛选' })
  await expect(clearButton).toBeVisible()
  await clearButton.click()

  await expect(clearButton).toBeHidden()
  await expect(keyword).toHaveValue('')
  await expect(page.locator('.filter-select').first()).toContainText('全部')
  const cleared = new URL(comicRequests.at(-1) ?? '')
  expect(cleared.searchParams.get('hqStatus')).toBe('ALL')
  expect(cleared.searchParams.get('lqStatus')).toBe('ALL')
  expect(cleared.searchParams.get('category')).toBeNull()
  expect(cleared.searchParams.get('tag')).toBeNull()
  expect(cleared.searchParams.get('keyword')).toBeNull()
})
