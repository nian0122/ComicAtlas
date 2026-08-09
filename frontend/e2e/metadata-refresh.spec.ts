import { expect, test, type Page, type Route } from '@playwright/test'

test.setTimeout(60_000)

const COMIC_ID = 42

let refreshPostCount = 0
let refreshStatus = 202
let refreshBody = successBody()

test.beforeEach(async ({ page }) => {
  refreshPostCount = 0
  refreshStatus = 202
  refreshBody = successBody()
  await page.route(`**/api/admin/storage/comics/${COMIC_ID}`, (route) =>
    json(route, comicStorageItem())
  )
  await page.route(`**/api/admin/storage/comics/${COMIC_ID}/chapters`, (route) =>
    json(route, [])
  )
  await page.route(`**/api/comics/${COMIC_ID}`, (route) =>
    json(route, comicDetail('READY'))
  )
  await page.route(`**/api/storage/refresh-metadata/comics/${COMIC_ID}`, handleRefresh)
})

test('READY 状态下提交元数据刷新：单次 POST 并展示任务编号', async ({ page }) => {
  await page.goto(`/manage/storage/${COMIC_ID}?force-desktop=1`)
  await expect(page.getByText('测试漫画', { exact: true })).toBeVisible()

  const refreshButton = page.getByRole('button', { name: '刷新元数据' })
  await expect(refreshButton).toBeEnabled()

  await refreshButton.click()
  await expect(page.getByText(/重读该漫画的 HQ 目录/)).toBeVisible()
  await page.getByRole('button', { name: '刷新', exact: true }).click()

  await expect(page.getByText(/元数据刷新任务已提交/)).toBeVisible()
  await expect(page.getByText(/任务 #1001/)).toBeVisible()
  await expect(page.getByRole('button', { name: '前往任务中心' })).toBeVisible()
  expect(refreshPostCount).toBe(1)

  await page.getByRole('dialog', { name: '刷新已提交' }).getByLabel('Close this dialog').click()
  await expect(page.getByRole('button', { name: '已提交' })).toBeDisabled()
})

test('服务端拒绝（409）时展示后端 message 且页面数据不变', async ({ page }) => {
  refreshStatus = 409
  refreshBody = { code: 409, message: '漫画状态 IMPORTING 不支持元数据刷新，仅 READY 可刷新', data: null }

  await page.goto(`/manage/storage/${COMIC_ID}?force-desktop=1`)
  await expect(page.getByText('测试漫画', { exact: true })).toBeVisible()

  const refreshButton = page.getByRole('button', { name: '刷新元数据' })
  await refreshButton.click()
  await page.getByRole('button', { name: '刷新', exact: true }).click()

  await expect(
    page.getByText('漫画状态 IMPORTING 不支持元数据刷新，仅 READY 可刷新')
  ).toBeVisible()
  // 页面主体数据不变
  await expect(page.getByText('测试漫画', { exact: true })).toBeVisible()
  // 失败后可重新提交
  await expect(page.getByRole('button', { name: '刷新元数据' })).toBeEnabled()
})

test('提交成功后按钮禁用，重复点击不会重复提交', async ({ page }) => {
  await page.goto(`/manage/storage/${COMIC_ID}?force-desktop=1`)
  await expect(page.getByText('测试漫画', { exact: true })).toBeVisible()

  const refreshButton = page.getByRole('button', { name: '刷新元数据' })
  await refreshButton.click()
  await page.getByRole('button', { name: '刷新', exact: true }).click()

  await expect(page.getByText(/元数据刷新任务已提交/)).toBeVisible()
  await page.getByRole('dialog', { name: '刷新已提交' }).getByLabel('Close this dialog').click()

  await expect(page.getByRole('button', { name: '已提交' })).toBeDisabled()
  await page.getByRole('button', { name: '已提交' }).click({ force: true })
  expect(refreshPostCount).toBe(1)
})

async function handleRefresh(route: Route) {
  refreshPostCount++
  await route.fulfill({
    status: refreshStatus,
    contentType: 'application/json',
    body: JSON.stringify(refreshBody),
  })
}

function comicStorageItem() {
  return {
    comicId: COMIC_ID,
    title: '测试漫画',
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
    title: '测试漫画',
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
      taskType: 'METADATA_REFRESH',
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
