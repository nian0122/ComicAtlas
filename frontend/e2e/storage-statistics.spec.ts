import { test, expect, type Page, type Route } from '@playwright/test'

const statistics = {
  hqBytes: 1000,
  lqBytes: 100,
  thumbBytes: 20,
  totalBytes: 1120,
  comicCount: 2,
  snapshotAvailable: true,
  thumbFileCount: 2,
  thumbUpdatedAt: '2026-10-02T00:00:00',
  refreshStatus: 'READY',
}
function json(route: Route, data: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
}
async function mockLibrary(page: Page) {
  await page.route('**/api/manage/admin/storage/comics**', (route) => json(route, { records: [], total: 0 }))
  await page.route('**/api/manage/categories**', (route) => json(route, []))
  await page.route('**/api/manage/tags**', (route) => json(route, []))
}

test('首次快照未完成时显示待统计而非零容量，完成后自动更新', async ({ page }, testInfo) => {
  await mockLibrary(page)
  let isReady = false
  await page.route('**/api/manage/storage/stats', (route) =>
    json(
      route,
      isReady
        ? statistics
        : {
            ...statistics,
            totalBytes: null,
            thumbBytes: 0,
            snapshotAvailable: false,
            thumbFileCount: 0,
            thumbUpdatedAt: null,
            refreshStatus: 'RUNNING',
          },
    ),
  )
  await page.goto('/manage/storage')
  const overview = page.getByRole('region', { name: '全库容量统计' })
  await expect(overview.getByText('待完成统计', { exact: true })).toBeVisible()
  await expect(overview.getByText('尚未统计', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '容量核对中' })).toBeDisabled()
  await overview.screenshot({ path: testInfo.outputPath('statistics-initializing.png') })
  isReady = true
  await expect(overview.getByText('缩略图容量已核对')).toBeVisible({ timeout: 7000 })
  await expect(overview.getByText('待完成统计', { exact: true })).toHaveCount(0)
  await expect(overview.getByText('2 个文件 · 最近成功快照')).toBeVisible()
  await expect(page.getByRole('button', { name: '刷新统计' })).toBeEnabled()
})

test('刷新按钮异步触发扫描并刷新汇总与列表，扫描失败保留旧值', async ({ page }, testInfo) => {
  await mockLibrary(page)
  let refreshState = 'READY'
  let refreshRequests = 0
  let statisticsRequests = 0
  await page.route('**/api/manage/storage/stats', (route) => {
    statisticsRequests++
    return json(route, { ...statistics, refreshStatus: refreshState })
  })
  await page.route('**/api/manage/storage/stats/refresh', (route) => {
    refreshRequests++
    refreshState = 'RUNNING'
    return json(route, { refreshStatus: 'PENDING' }, 202)
  })
  await page.goto('/manage/storage')
  const overview = page.getByRole('region', { name: '全库容量统计' })
  await expect(overview.getByText('缩略图容量已核对')).toBeVisible()
  const initialReads = statisticsRequests
  await page.getByRole('button', { name: '刷新统计' }).click()
  await expect(page.getByRole('button', { name: '容量核对中' })).toBeDisabled()
  expect(refreshRequests).toBe(1)
  expect(statisticsRequests).toBeGreaterThan(initialReads)
  refreshState = 'FAILED'
  await expect(overview.getByText('容量核对失败，保留上次成功结果')).toBeVisible({ timeout: 7000 })
  await expect(overview.getByText('2 个文件 · 最近成功快照')).toBeVisible()
  await expect(page.getByRole('button', { name: '刷新统计' })).toBeEnabled()
  await overview.screenshot({ path: testInfo.outputPath('statistics-failed-with-snapshot.png') })
  const readsBeforeLeaving = statisticsRequests
  await page.goto('/manage/comics')
  await expect(page.getByRole('heading', { name: '漫画管理', exact: true })).toBeVisible()
  // 漫画管理页自身会读取一次全库容量；等待这次合法读取后，再验证存储页轮询已停止。
  await expect.poll(() => statisticsRequests).toBe(readsBeforeLeaving + 1)
  const previousReads = statisticsRequests
  await page.waitForTimeout(2200)
  expect(statisticsRequests).toBe(previousReads)
})

test('统计读取失败有明确反馈且不会显示虚假零容量', async ({ page }) => {
  await mockLibrary(page)
  await page.route('**/api/manage/storage/stats', (route) =>
    route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 500, message: '统计服务暂不可用' }),
    }),
  )
  await page.goto('/manage/storage')
  const overview = page.getByRole('region', { name: '全库容量统计' })
  await expect(overview.getByRole('status')).toContainText('统计服务暂不可用')
  await expect(overview.getByText('待完成统计', { exact: true })).toBeVisible()
})
