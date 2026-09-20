import { expect, test } from '@playwright/test'

test('AI 漫画分析入口可以提交任务并展示结果', async ({ page }) => {
  let statusRequests = 0
  let createPayload: { comicId?: number } = {}
  await page.route('**/api/comics**', (route) => {
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: { records: [{ id: 77, title: '候选作品', author: '候选作者', coverUrl: '', pageCount: 320, categoryId: null, categoryName: null, status: 'READY', progressPercent: 0, lastReadChapterId: 0, lastReadPage: 0, createdAt: '2026-09-20T10:00:00Z' }], total: 1, current: 1, pages: 1 } }),
    })
  })
  await page.route('**/api/ai/analysis/tasks', (route) => {
    if (route.request().method() === 'POST') {
      createPayload = JSON.parse(route.request().postData() || '{}')
      return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ taskId: 77, status: 'QUEUED' }) })
    }
    return route.continue()
  })
  await page.route('**/api/ai/analysis/tasks/77', (route) => {
    statusRequests += 1
    const task = statusRequests === 1
      ? { id: 77, sourcePath: '作者/作品名', status: 'RUNNING', progress: 35, resultJson: null, errorCode: null, errorMessage: null, attempts: 1, createdAt: '2026-09-20T10:00:00Z', startedAt: '2026-09-20T10:00:01Z', finishedAt: null }
      : { id: 77, sourcePath: '作者/作品名', status: 'SUCCEEDED', progress: 100, resultJson: JSON.stringify({ titleCandidate: '候选作品', authorCandidate: '候选作者', tags: ['奇幻', '冒险'], description: '基于抽样页面生成的简介。', warnings: ['仅分析抽样页面'] }), errorCode: null, errorMessage: null, attempts: 1, createdAt: '2026-09-20T10:00:00Z', startedAt: '2026-09-20T10:00:01Z', finishedAt: '2026-09-20T10:00:10Z' }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(task) })
  })

  await page.goto('/manage/ai-analysis?force-desktop=1')
  await expect(page.locator('.sidenav-link').filter({ hasText: 'AI 漫画分析' })).toHaveAttribute('href', '/manage/ai-analysis')
  await expect(page.getByRole('heading', { name: 'AI 漫画分析' })).toBeVisible()
  await page.locator('.comic-select').click()
  await expect(page.getByText('候选作品').first()).toBeVisible()
  await page.getByText('候选作品').first().click()
  await page.getByRole('button', { name: /开始分析/ }).click()

  await expect(page.getByText('分析任务 #77 已提交')).toBeVisible()
  await expect.poll(() => createPayload.comicId).toBe(77)
  await expect(page.locator('.identity-card').getByText('候选作品', { exact: true })).toBeVisible({ timeout: 5_000 })
  await expect(page.locator('.identity-card').getByText('候选作者', { exact: true })).toBeVisible()
  await expect(page.getByText('奇幻')).toBeVisible()
  await expect(page.getByText('仅分析抽样页面')).toBeVisible()
  await expect.poll(() => statusRequests).toBeGreaterThanOrEqual(2)
})

test('AI 漫画分析表单要求选择漫画', async ({ page }) => {
  await page.goto('/manage/ai-analysis?force-desktop=1')
  await expect(page.getByRole('button', { name: /开始分析/ })).toBeDisabled()
})
