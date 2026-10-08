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
      totalBytes: 1024,
      snapshotAvailable: true,
      thumbFileCount: 0,
      thumbUpdatedAt: '2026-10-02T00:00:00',
      refreshStatus: 'READY',
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
          mediaType: 'IMAGE',
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

test('存储统计宽屏充分利用内容区，概览与筛选紧凑且列表可见', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 2560, height: 1392 })
  await mockStorage(page)
  await page.route('**/api/manage/storage/stats', (route) =>
    json(route, {
      hqBytes: 1229779136384,
      lqBytes: 18455646546,
      thumbBytes: 168880338,
      totalBytes: 1248403663268,
      comicCount: 920,
      snapshotAvailable: true,
      thumbFileCount: 940,
      thumbUpdatedAt: '2026-10-03T08:30:00',
      refreshStatus: 'READY',
    }),
  )
  await page.goto('/manage/storage')
  await expect(page.getByRole('region', { name: '全库容量统计' })).toContainText('1.1 TB')
  const layout = await page.locator('.storage-page').evaluate((element) => {
    const bounds = element.getBoundingClientRect()
    const parent = element.parentElement!
    const parentStyle = getComputedStyle(parent)
    const availableWidth =
      parent.clientWidth - parseFloat(parentStyle.paddingLeft) - parseFloat(parentStyle.paddingRight)
    const cards = [...element.querySelectorAll('.stat-card')].map((card) => card.getBoundingClientRect())
    const filter = element.querySelector('.storage-filter-panel')!.getBoundingClientRect()
    const table = element.querySelector('.el-table')!.getBoundingClientRect()
    return {
      width: bounds.width,
      availableWidth,
      cardTops: cards.map((card) => card.top),
      cardHeights: cards.map((card) => card.height),
      filterHeight: filter.height,
      tableTop: table.top,
    }
  })
  expect(layout.width).toBeGreaterThan(layout.availableWidth * 0.98)
  expect(new Set(layout.cardTops).size).toBe(1)
  expect(new Set(layout.cardHeights).size).toBe(1)
  expect(layout.cardHeights[0]).toBeLessThan(150)
  expect(layout.filterHeight).toBeLessThan(250)
  expect(layout.tableTop).toBeLessThan(650)
  await expect(page.locator('.storage-bar')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('storage-page-wide.png'), fullPage: true })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.screenshot({ path: testInfo.outputPath('storage-page-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  const overflow = await page.locator('.storage-page').evaluate((element) => element.scrollWidth > element.clientWidth)
  expect(overflow).toBe(false)
  await page.screenshot({ path: testInfo.outputPath('storage-page-mobile.png'), fullPage: true })
})
