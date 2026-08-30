import { expect, test, type Page } from '@playwright/test'

const COMIC_ID = 7

function resultBody(data: unknown, code = 200, message = 'success'): string {
  return JSON.stringify({ code, message, data })
}

async function mockWorkspace(page: Page, detail: unknown): Promise<void> {
  await page.route(`**/api/manage/comics/${COMIC_ID}`, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: resultBody(detail) })
  )
  await page.route(`**/api/manage/operations/comics/${COMIC_ID}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: resultBody({ allowed: ['METADATA_REFRESH'], blockedReasons: {} }),
    })
  )
  await page.route('**/api/manage/tasks?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: resultBody({ records: [], total: 0 }),
    })
  )
  await page.route('**/api/manage/outbox/stats', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: resultBody({ pending: 0, failed: 0, total: 0 }),
    })
  )
  await page.route('**/api/manage/mq/stats', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: resultBody({ available: true, dlqTotal: 0, dlqQueues: 0, queuedTotal: 0, queues: [] }),
    })
  )
}

const comicDetail = {
  id: COMIC_ID,
  title: '测试漫画',
  author: '作者',
  coverUrl: '',
  pageCount: 10,
  categoryId: null,
  categoryName: null,
  status: 'READY',
  progressPercent: 0,
  lastReadChapterId: 0,
  lastReadPage: 0,
  chapters: [],
  tags: [],
  createdAt: '2026-08-11T00:00:00',
  updatedAt: '2026-08-11T00:00:00',
}

test('工作区默认展示漫画概览与操作页', async ({ page }) => {
  await mockWorkspace(page, comicDetail)
  await page.goto(`/manage/comics/${COMIC_ID}?tab=operations`)

  await expect(page.locator('.comic-workspace-page')).toContainText('单本漫画工作区')
  await expect(page.locator('.current-state')).toContainText('测试漫画')
  await expect(page.locator('.current-state')).toContainText('READY')
  await expect(page.getByRole('tab', { name: '目录与存储' })).toBeVisible()
})

test('工作区展示后端业务错误且不抛出页面异常', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))
  await mockWorkspace(page, null)
  await page.unroute(`**/api/manage/comics/${COMIC_ID}`)
  await page.route(`**/api/manage/comics/${COMIC_ID}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: resultBody(null, 404, '漫画不存在或不可阅读'),
    })
  )
  await page.goto(`/manage/comics/${COMIC_ID}?tab=operations`)

  await expect(page.locator('.el-alert__title')).toHaveText('漫画不存在或不可阅读')
  expect(pageErrors).toEqual([])
})

test('工作区可提交元数据刷新并展示任务反馈', async ({ page }) => {
  await mockWorkspace(page, comicDetail)
  await page.route('**/api/manage/storage/refresh-metadata/comics/7', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: resultBody({ taskId: 1001, taskType: 'METADATA_REFRESH', status: 'PENDING', itemCount: 1 }),
    }),
  )
  await page.goto('/manage/comics/7?tab=operations')
  await expect(page.getByRole('button', { name: '刷新元数据' })).toBeEnabled()
  await page.getByRole('button', { name: '刷新元数据' }).click()
  await expect(page.getByText('刷新元数据已提交')).toBeVisible()
})
