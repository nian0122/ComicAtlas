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
      targetId: null,
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
    }, {
      id: 102,
      taskType: 'METADATA_REFRESH',
      operation: '刷新元数据',
      targetType: 'COMIC',
      targetId: 502,
      targetName: '元数据示例',
      batchId: null,
      isBatch: false,
      status: 'RUNNING',
      stage: '扫描 HQ',
      progress: 40,
      totalCount: 1,
      successCount: 0,
      failureCount: 0,
      cancelledCount: 0,
      errorMessage: null,
      attempt: 1,
      version: 1,
      createdAt: '2026-08-11T10:02:00Z',
      updatedAt: '2026-08-11T10:02:30Z',
      startedAt: '2026-08-11T10:02:01Z',
      completedAt: null,
    }],
    total: 2,
  }))
  await page.route('/api/management/tasks/101/items', (route) => success(route, [{
    id: 1001,
    taskId: 101,
    targetType: 'COMIC',
    targetId: 501,
    operationType: 'IMPORT',
    status: 'SUCCEEDED',
    progress: 100,
  }]))
  await page.route('/api/comics/501', (route) => success(route, { id: 501, title: '179漫画' }))
  await page.goto('/manage/tasks')

  await expect(page.locator('.sidenav-link').filter({ hasText: '任务中心' })).toHaveAttribute('href', '/manage/tasks')
  await expect(page.locator('.sidenav-link').filter({ hasText: '导入任务' })).toHaveCount(0)
  await expect(page.locator('.task-group').filter({ hasText: '导入漫画' })).toBeVisible()
  await expect(page.locator('.task-card').filter({ hasText: '179漫画' })).toBeVisible()
  await expect(page.locator('.task-card').filter({ hasText: '成功' }).first()).toBeVisible()
  await expect(page.locator('.task-card').filter({ hasText: '任务 #101' })).toHaveCount(0)
  await page.locator('.task-card').filter({ hasText: '179漫画' }).click()
  await expect(page.getByText('任务标识')).toBeVisible()
  await expect(page.getByText('任务 ID').locator('..').getByText('101')).toBeVisible()
  await expect(page.locator('.detail-fields').first().getByText('目标 ID').locator('..').getByText('501')).toBeVisible()
  await expect(page.getByText('目标项明细')).toBeVisible()
  await expect(page.getByText('目标 ID').locator('..').getByText('501')).toBeVisible()
  await page.locator('.detail-section').first().screenshot({ path: 'test-results/management-task-target-id.png' })
  await page.locator('.el-drawer__close-btn').click()
  await expect(page.locator('.task-group').filter({ hasText: '刷新元数据' })).toBeVisible()
  await expect(page.locator('.task-card')).toHaveCount(2)
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
  await expect(page.locator('.empty-state')).toBeVisible()

  await page.clock.install()
  await page.clock.fastForward(2500)
  await expect.poll(() => requestCount).toBe(2)
  await expect(page.locator('.el-loading-mask')).toHaveCount(0)
  releaseSecondRequest?.()
})
