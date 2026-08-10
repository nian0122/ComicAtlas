import { expect, test, type Page, type Route } from '@playwright/test'

test.setTimeout(60_000)

const COMIC_ID = 42

let lqStatusCode = 200
let lqBody = successBody()

test.beforeEach(async ({ page }) => {
  lqStatusCode = 200
  lqBody = successBody()
  await page.route(`**/api/admin/storage/comics/${COMIC_ID}`, (route) =>
    json(route, comicStorageItem())
  )
  await page.route(`**/api/admin/storage/comics/${COMIC_ID}/chapters`, (route) =>
    json(route, [])
  )
  await page.route(`**/api/comics/${COMIC_ID}`, (route) => json(route, comicDetail('READY')))
  await page.route(`**/api/storage/lq/comics/${COMIC_ID}`, handleLqPost)
})

test('code=200 解包成功：提交 LQ 后展示成功提示', async ({ page }) => {
  await page.goto(`/manage/storage/${COMIC_ID}?force-desktop=1`)
  await expect(page.getByText('LQ 测试漫画', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '生成 LQ' }).click()
  await confirmDialog(page)

  // 解包成功：仅出现一次成功提示，且不出现任何错误提示
  await expect(page.getByText('LQ 生成任务已提交')).toBeVisible()
  await expect(page.getByText(/操作失败|失败/)).not.toBeVisible()
})

test('HTTP 200 + 业务 code 409：reject 且展示后端 message，不出现成功提示', async ({ page }) => {
  lqStatusCode = 200
  lqBody = { code: 409, message: '已有 LQ 生成任务，请稍后重试', data: null }

  await page.goto(`/manage/storage/${COMIC_ID}?force-desktop=1`)
  await expect(page.getByText('LQ 测试漫画', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '生成 LQ' }).click()
  await confirmDialog(page)

  // 后端 message 可读（走 executeOperation → extractMessage → ElMessage.error）
  await expect(page.getByText('已有 LQ 生成任务，请稍后重试')).toBeVisible()
  // 不得进入成功分支：成功提示绝不出现
  await expect(page.getByText('LQ 生成任务已提交')).not.toBeVisible()
})

test('HTTP 200 + 业务 code 404：reject 且展示后端 message', async ({ page }) => {
  lqStatusCode = 200
  lqBody = { code: 404, message: '漫画不存在或已删除', data: null }

  await page.goto(`/manage/storage/${COMIC_ID}?force-desktop=1`)
  await expect(page.getByText('LQ 测试漫画', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '生成 LQ' }).click()
  await confirmDialog(page)

  await expect(page.getByText('漫画不存在或已删除')).toBeVisible()
  await expect(page.getByText('LQ 生成任务已提交')).not.toBeVisible()
})

test('HTTP 非 2xx（500）：保留 Axios 错误语义，message 仍可读', async ({ page }) => {
  lqStatusCode = 500
  lqBody = { code: 500, message: 'LQ 服务不可用', data: null }

  await page.goto(`/manage/storage/${COMIC_ID}?force-desktop=1`)
  await expect(page.getByText('LQ 测试漫画', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '生成 LQ' }).click()
  await confirmDialog(page)

  await expect(page.getByText('LQ 服务不可用')).toBeVisible()
  await expect(page.getByText('LQ 生成任务已提交')).not.toBeVisible()
})

async function handleLqPost(route: Route) {
  await route.fulfill({
    status: lqStatusCode,
    contentType: 'application/json',
    body: JSON.stringify(lqBody),
  })
}

/** 点击 ElMessageBox.confirm 的默认主按钮（Element Plus 默认英文 OK，按 class 选择避免受 locale 影响）。 */
async function confirmDialog(page: Page) {
  const messageBox = page.locator('.el-message-box')
  await messageBox.waitFor({ timeout: 10_000 })
  await messageBox.locator('.el-button--primary').click()
}

function comicStorageItem() {
  return {
    comicId: COMIC_ID,
    title: 'LQ 测试漫画',
    coverUrl: '',
    totalSize: 2048,
    hqSize: 2048,
    lqSize: 0,
    hqStatus: 'READY',
    lqStatus: 'NOT_GENERATED',
    transcodeStatus: 'NOT_NEEDED',
    chapterCount: 0,
    pageCount: 0,
  }
}

function comicDetail(status: string) {
  return {
    id: COMIC_ID,
    title: 'LQ 测试漫画',
    author: '作者',
    coverUrl: '',
    pageCount: 0,
    fileSize: 2048,
    sourceType: 'DIRECTORY',
    sourceRef: '',
    categoryId: null,
    categoryName: null,
    status,
    progressPercent: 100,
    lastReadChapterId: null,
    lastReadPage: 0,
    chapters: [],
    tags: [],
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  }
}

function successBody() {
  return {
    code: 200,
    message: 'success',
    data: {
      taskId: 1001,
      taskType: 'LQ_GENERATE',
      status: 'PENDING',
      itemCount: 1,
    },
  }
}

async function json(route: Route, data: unknown) {
  await route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, message: 'success', data }),
  })
}
