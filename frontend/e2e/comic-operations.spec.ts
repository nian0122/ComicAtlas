import { test, expect } from '@playwright/test'

/**
 * 漫画操作台（ComicOperationsPage）健壮性回归：
 * 后端业务失败以 HTTP 200 + {code:非200, message, data:null} 返回（GlobalExceptionHandler 无 @ResponseStatus），
 * axios 拦截器必须转为 reject，使页面 catch 展示后端 message；
 * 曾因拦截器只解包不 reject，导致 loadState 里 recordStatus(detail.data.status) 读 null.status
 * 报 "Cannot read properties of null (reading 'status')"。
 */

/** 后端 Result 成功/失败响应体（data 由 axios 拦截器解包，业务失败则 reject） */
function resultBody(code: number, message: string, data: unknown): string {
  return JSON.stringify({ code, message, data })
}

/** 拦截操作台并行请求；detailBody 为漫画详情接口的完整 Result 响应体（错误场景传 404 体） */
async function mockOperationsEndpoints(
  page: import('@playwright/test').Page,
  comicId: string,
  detailBody: string,
): Promise<void> {
  await page.route(`/api/comics/${comicId}`, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: detailBody })
  )
  await page.route(`/api/management/operations/comics/${comicId}`, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: resultBody(200, 'success', { allowed: [], blockedReasons: {} }) })
  )
  await page.route(/\/api\/management\/tasks\?/, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: resultBody(200, 'success', { records: [], total: 0 }) })
  )
  await page.route(/\/api\/management\/outbox\/stats/, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: resultBody(200, 'success', { pending: 0, failed: 0, total: 0 }) })
  )
}

const validDetail = {
  id: 7,
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

test('加载不存在的漫画时错误提示显示后端消息而非内部 TypeError', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (err) => pageErrors.push(err.message))

  await mockOperationsEndpoints(page, '999999', resultBody(404, '漫画不存在或不可阅读', null))
  await page.goto('/manage/operations?comicId=999999')

  const alert = page.locator('.el-alert__title')
  await expect(alert).toBeVisible({ timeout: 10000 })
  const text = (await alert.textContent()) ?? ''

  expect(text).toBe('漫画不存在或不可阅读')
  expect(text).not.toContain("reading 'status'")
  expect(text).not.toContain('Cannot read properties')
  expect(pageErrors).toEqual([])
})

test('加载正常漫画时展示当前状态卡片', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (err) => pageErrors.push(err.message))

  await mockOperationsEndpoints(page, '7', resultBody(200, 'success', validDetail))
  await page.goto('/manage/operations?comicId=7')

  await expect(page.locator('.current-state')).toBeVisible({ timeout: 10000 })
  await expect(page.locator('.current-state')).toContainText('测试漫画')
  await expect(page.locator('.current-state')).toContainText('READY')
  expect(pageErrors).toEqual([])
})
