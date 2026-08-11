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
  await page.route('**/api/admin/storage/stats', (route) => json(route, {
    hqBytes: 1024,
    lqBytes: 0,
    comicCount: 1,
  }))
  await page.route('**/api/admin/storage/comics**', (route) => {
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

  await page.goto('/manage/storage')
  const keyword = page.getByPlaceholder('搜索标题')
  await keyword.fill('目标')
  await page.locator('.filter-select').first().click()
  await page.getByRole('option', { name: '还有 HQ' }).click()

  const clearButton = page.getByRole('button', { name: '清空筛选' })
  await expect(clearButton).toBeVisible()
  await clearButton.click()

  await expect(clearButton).toBeHidden()
  await expect(keyword).toHaveValue('')
  await expect(page.locator('.filter-select').first()).toContainText('全部')
  expect(comicRequests.at(-1)).toContain('hqStatus=ALL')
  expect(comicRequests.at(-1)).toContain('lqStatus=ALL')
  expect(comicRequests.at(-1)).not.toContain('keyword=')
})
