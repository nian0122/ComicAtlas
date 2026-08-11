import { expect, test, type Route } from '@playwright/test'

function success(route: Route, data: unknown) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, message: 'success', data }),
  })
}

test('统一管理任务页展示完整任务类型并可筛选导入任务', async ({ page }) => {
  await page.route('/api/management/tasks**', (route) => success(route, {
    records: [{
      id: 101,
      taskType: 'IMPORT',
      operation: '导入漫画',
      targetType: 'COMIC',
      batchId: null,
      isBatch: false,
      status: 'SUCCEEDED',
      stage: null,
      progress: 100,
      totalCount: 1,
      successCount: 1,
      failureCount: 0,
      cancelledCount: 0,
      errorMessage: null,
      attempt: 1,
      version: 1,
      createdAt: '2026-08-11T10:00:00Z',
      updatedAt: '2026-08-11T10:01:00Z',
      startedAt: '2026-08-11T10:00:01Z',
      completedAt: '2026-08-11T10:01:00Z',
    }],
    total: 1,
  }))
  await page.route('/api/management/tasks/101/items', (route) => success(route, []))
  await page.goto('/manage/tasks')

  await expect(page.locator('.el-table').getByText('导入漫画', { exact: true }).first()).toBeVisible()
  await page.locator('.filters .el-select').first().click()
  await expect(page.getByRole('option', { name: '导入漫画' })).toBeVisible()
  await page.getByRole('option', { name: '导入漫画' }).click()
  await expect(page.getByRole('option', { name: '导入漫画' })).toBeHidden()
  await page.screenshot({ path: 'C:\\Users\\Acer\\.codex\\visualizations\\2026\\08\\11\\management-tasks-final.png', fullPage: true })
})

test('统一管理任务自动刷新不覆盖当前列表', async ({ page }) => {
  let requestCount = 0
  let releaseSecondRequest: (() => void) | undefined
  await page.route('/api/management/tasks**', async (route) => {
    requestCount += 1
    if (requestCount === 2) await new Promise<void>((resolve) => { releaseSecondRequest = resolve })
    await success(route, { records: [], total: 0 })
  })
  await page.goto('/manage/tasks')
  await expect(page.locator('.el-table__empty-text')).toBeVisible()

  await page.clock.install()
  await page.clock.fastForward(2500)
  await expect.poll(() => requestCount).toBe(2)
  await expect(page.locator('.el-loading-mask')).toHaveCount(0)
  releaseSecondRequest?.()
})
