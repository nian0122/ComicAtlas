import { expect, test, type Page, type Route } from '@playwright/test'

function json(route: Route, data: unknown) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, message: 'success', data }),
  })
}

async function mockStorage(page: Page) {
  const comicRequests: string[] = []
  await page.route('**/api/manage/storage/stats', (route) =>
    json(route, {
      hqBytes: 1024,
      lqBytes: 0,
      thumbBytes: 0,
      comicCount: 1,
    }),
  )
  await page.route('**/api/manage/admin/storage/comics**', (route) => {
    comicRequests.push(route.request().url())
    return json(route, {
      records: [
        {
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
        },
      ],
      total: 1,
    })
  })
  await page.route('**/api/manage/categories**', (route) => json(route, [{ id: 1, name: '动作', sortOrder: 1 }]))
  await page.route('**/api/manage/tags**', (route) => json(route, [{ id: 2, name: '热血', sortOrder: 1 }]))
  return comicRequests
}

async function choose(page: Page, name: string, option: string) {
  await page
    .getByRole('combobox', { name, exact: true })
    .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
    .click()
  await page.getByRole('option', { name: option, exact: true }).click()
}

test('存储管理筛选可清空并回到默认条件', async ({ page }) => {
  const comicRequests = await mockStorage(page)
  await page.goto('/manage/storage')
  const keyword = page.getByRole('textbox', { name: '存储标题关键词' })
  await keyword.fill('目标')
  await choose(page, '存储 HQ 状态', '还有 HQ')
  await choose(page, '存储分类', '动作')
  await choose(page, '存储标签', '热血')

  await expect.poll(() => new URL(comicRequests.at(-1) ?? '').searchParams.get('category')).toBe('动作')
  await expect.poll(() => new URL(comicRequests.at(-1) ?? '').searchParams.get('tag')).toBe('热血')
  const clearButton = page.getByRole('button', { name: '清空筛选', exact: true })
  await expect(clearButton).toBeVisible()
  await clearButton.click()
  await expect(clearButton).toBeHidden()
  await expect(keyword).toHaveValue('')
  await expect.poll(() => new URL(comicRequests.at(-1) ?? '').searchParams.get('hqStatus')).toBe('ALL')
  const cleared = new URL(comicRequests.at(-1) ?? '')
  expect(cleared.searchParams.get('lqStatus')).toBe('ALL')
  expect(cleared.searchParams.get('category')).toBeNull()
  expect(cleared.searchParams.get('tag')).toBeNull()
  expect(cleared.searchParams.get('keyword')).toBeNull()
})

test('存储条件可单独清除且保留其他条件与排序', async ({ page }) => {
  const comicRequests = await mockStorage(page)
  await page.goto('/manage/storage?category=动作&tag=热血&hqStatus=HAS_HQ&sort=hqSize&order=asc&page=3')
  await expect(page.getByRole('button', { name: '清除分类：动作', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '清除标签：热血', exact: true }).click()
  await expect.poll(() => new URL(comicRequests.at(-1) ?? '').searchParams.has('tag')).toBe(false)
  const query = new URL(comicRequests.at(-1) ?? '').searchParams
  expect(query.get('category')).toBe('动作')
  expect(query.get('hqStatus')).toBe('HAS_HQ')
  expect(query.get('sort')).toBe('hqSize')
  expect(query.get('order')).toBe('asc')
  expect(query.get('page')).toBe('1')
  await expect(page.getByText('匹配 1 本漫画', { exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('button', { name: '清除分类：动作', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '清除标签：热血', exact: true })).toHaveCount(0)
})

test('存储筛选控件在桌面与窄屏统一尺寸且不溢出', async ({ page }, testInfo) => {
  await mockStorage(page)
  await page.goto('/manage/storage')
  const panel = page.getByRole('region', { name: '存储记录筛选' })
  await expect(panel).toBeVisible()
  await choose(page, '存储标签', '热血')
  const geometry = await panel.locator('.el-input__wrapper, .el-select__wrapper').evaluateAll((elements) =>
    elements.map((element) => ({
      height: element.getBoundingClientRect().height,
      radius: getComputedStyle(element).borderRadius,
    })),
  )
  expect(geometry).toHaveLength(7)
  expect(geometry.every((control) => control.height === 42 && control.radius === '8px')).toBe(true)
  await panel.screenshot({ path: testInfo.outputPath('storage-filter-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(panel).toBeVisible()
  const bounds = await panel.boundingBox()
  expect(bounds).not.toBeNull()
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390)
  const controls = await panel
    .locator('.el-input__wrapper, .el-select__wrapper')
    .evaluateAll((elements) => elements.map((element) => element.getBoundingClientRect().right))
  expect(controls.every((right) => right <= 390)).toBe(true)
  await panel.screenshot({ path: testInfo.outputPath('storage-filter-narrow.png') })
})
