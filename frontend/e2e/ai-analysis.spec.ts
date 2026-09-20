import { expect, test } from '@playwright/test'

test('AI 漫画分析入口可以提交任务并展示结果', async ({ page }) => {
  let statusRequests = 0
  await page.route('**/analysis/tasks', (route) => {
    if (route.request().method() === 'POST') {
      return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ taskId: 77, status: 'QUEUED' }) })
    }
    return route.continue()
  })
  await page.route('**/analysis/tasks/77', (route) => {
    statusRequests += 1
    const task = statusRequests === 1
      ? { id: 77, sourcePath: '作者/作品名', status: 'RUNNING', progress: 35, resultJson: null, errorCode: null, errorMessage: null, attempts: 1, createdAt: '2026-09-20T10:00:00Z', startedAt: '2026-09-20T10:00:01Z', finishedAt: null }
      : { id: 77, sourcePath: '作者/作品名', status: 'SUCCEEDED', progress: 100, resultJson: JSON.stringify({ titleCandidate: '候选作品', authorCandidate: '候选作者', tags: ['奇幻', '冒险'], description: '基于抽样页面生成的简介。', warnings: ['仅分析抽样页面'] }), errorCode: null, errorMessage: null, attempts: 1, createdAt: '2026-09-20T10:00:00Z', startedAt: '2026-09-20T10:00:01Z', finishedAt: '2026-09-20T10:00:10Z' }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(task) })
  })

  await page.goto('/manage/ai-analysis?force-desktop=1')
  await expect(page.locator('.sidenav-link').filter({ hasText: 'AI 漫画分析' })).toHaveAttribute('href', '/manage/ai-analysis')
  await expect(page.getByRole('heading', { name: 'AI 漫画分析' })).toBeVisible()
  await page.getByPlaceholder('例如：作者 / 作品名 / 第一卷').fill('作者/作品名')
  await page.getByRole('button', { name: /开始分析/ }).click()

  await expect(page.getByText('分析任务 #77 已提交')).toBeVisible()
  await expect(page.getByText('候选作品')).toBeVisible({ timeout: 5_000 })
  await expect(page.getByText('候选作者')).toBeVisible()
  await expect(page.getByText('奇幻')).toBeVisible()
  await expect(page.getByText('仅分析抽样页面')).toBeVisible()
  await expect.poll(() => statusRequests).toBeGreaterThanOrEqual(2)
})

test('AI 漫画分析表单拒绝空目录', async ({ page }) => {
  await page.goto('/manage/ai-analysis?force-desktop=1')
  await expect(page.getByRole('button', { name: /开始分析/ })).toBeDisabled()
})
