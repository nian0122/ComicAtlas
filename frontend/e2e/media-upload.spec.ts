import { expect, test } from '@playwright/test'

function json(route: import('@playwright/test').Route, data: unknown) {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data }) })
}

test('媒体上传页面支持选择章节与替换目标', async ({ page }) => {
  await page.route('**/api/manage/comics**', (route) => json(route, { records: [{ id: 7, title: '测试漫画' }], total: 1, current: 1, pages: 1 }))
  await page.route('**/api/manage/comics/7/catalog', (route) => json(route, [{ id: null, title: null, children: [], chapters: [{ id: 9, chapterNo: '01', title: '第一章', globalOrder: 1, pageCount: 2 }] }]))
  await page.route('**/api/manage/chapters/9', (route) => json(route, { pages: [{ id: 11, pageNumber: 1, fileName: '001.jpg', mediaType: 'IMAGE', hqUrl: '', lqUrl: null, lqStatus: 'NOT_GENERATED', width: 100, height: 100 }] }))

  await page.goto('/manage/upload')
  await expect(page.getByRole('heading', { name: '媒体上传' })).toBeVisible()
  await page.locator('.el-select').first().click()
  await page.getByRole('option', { name: /测试漫画/ }).click()
  await page.locator('.el-select').nth(1).click()
  await page.getByRole('option', { name: /第一章/ }).click()
  await page.getByText('替换媒体', { exact: true }).click()
  await page.locator('.el-select').nth(2).click()
  await expect(page.getByRole('option', { name: /001\.jpg/ })).toBeVisible()
})
