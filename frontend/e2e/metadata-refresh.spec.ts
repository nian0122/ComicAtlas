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
  await page.route(`**/api/manage/admin/storage/comics/${COMIC_ID}`, (route) =>
    json(route, comicStorageItem())
  )
  await page.route(`**/api/manage/admin/storage/comics/${COMIC_ID}/chapters`, (route) =>
    json(route, [])
  )
  await page.route(`**/api/comics/${COMIC_ID}/catalog`, (route) =>
    json(route, [])
  )
  await page.route(`**/api/comics/${COMIC_ID}`, (route) =>
    json(route, comicDetail('READY'))
  )
  await page.route(`**/api/manage/storage/refresh-metadata/comics/${COMIC_ID}`, handleRefresh)
})

test('章节存储按目录树展示并支持逐级展开', async ({ page }) => {
  await page.unroute(`**/api/comics/${COMIC_ID}/catalog`)
  await page.route(`**/api/comics/${COMIC_ID}/catalog`, (route) =>
    json(route, [{
      id: 1,
      title: '第一卷',
      globalOrder: 1,
      chapters: [{ id: 12, chapterNo: '99', title: '全局顺序章节', globalOrder: 5, pageCount: 20 }],
      children: [{
        id: 2,
        title: '第一部',
        globalOrder: 2,
        chapters: [{ id: 11, chapterNo: '1', title: '启程', globalOrder: 1, pageCount: 10 }],
        children: [],
      }],
    }])
  )
  await page.unroute(`**/api/manage/admin/storage/comics/${COMIC_ID}/chapters`)
  await page.route(`**/api/manage/admin/storage/comics/${COMIC_ID}/chapters`, (route) =>
    json(route, [
      chapterStorageItem(),
      chapterStorageItem({ chapterId: 12, chapterNo: '99', title: '全局顺序章节', pageCount: 20 }),
      chapterStorageItem({ chapterId: 13, chapterNo: '孤儿', title: '无目录章节' }),
    ])
  )

  await page.goto(`/manage/storage/${COMIC_ID}?force-desktop=1`)
  await expect(page.getByRole('cell', { name: '第一卷' })).toBeVisible()
  await expect(page.getByRole('cell', { name: '无目录章节' })).toBeVisible()
  await expect(page.getByRole('cell', { name: '第一部' })).toBeHidden()
  await page.locator('.el-table__expand-icon').first().click()
  await expect(page.getByRole('cell', { name: '第一部' })).toBeVisible()
  await expect(page.getByRole('cell', { name: '启程' })).toBeHidden()
  const visibleRows = await page.locator('.el-table__body-wrapper tbody tr:visible').allTextContents()
  expect(visibleRows.findIndex((row) => row.includes('第一部'))).toBeLessThan(visibleRows.findIndex((row) => row.includes('全局顺序章节')))
  await page.locator('.el-table__expand-icon').nth(1).click()
  await expect(page.getByRole('cell', { name: '启程' })).toBeVisible()
  await page.screenshot({ path: 'test-results/storage-directory-summary.png', fullPage: false })
  await page.getByPlaceholder('搜索章节').fill('全局顺序章节')
  await expect(page.getByRole('cell', { name: '第一卷' })).toBeVisible()
  await expect(page.getByRole('cell', { name: '全局顺序章节' })).toBeVisible()
  await expect(page.getByRole('cell', { name: '第一部' })).toBeHidden()
})

test('纯视频章节显示真实 HQ 大小且 LQ 标记为不适用', async ({ page }) => {
  await page.unroute(`**/api/manage/admin/storage/comics/${COMIC_ID}`)
  await page.route(`**/api/manage/admin/storage/comics/${COMIC_ID}`, (route) =>
    json(route, comicStorageItem({ mediaType: 'VIDEO', hqSize: 1024 * 1024 * 3, lqSize: 0, pageCount: 100 }))
  )
  await page.unroute(`**/api/manage/admin/storage/comics/${COMIC_ID}/chapters`)
  await page.route(`**/api/manage/admin/storage/comics/${COMIC_ID}/chapters`, (route) =>
    json(route, [chapterStorageItem({ title: '视频', mediaType: 'VIDEO', hqSize: 1024 * 1024 * 3, lqSize: 0, pageCount: 100 })])
  )

  await page.goto(`/manage/storage/${COMIC_ID}?force-desktop=1`)
  const videoRow = page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: '视频' })
  await expect(videoRow).toContainText('3.0 MB')
  await expect(videoRow).toContainText('不适用')
  await expect(videoRow.getByRole('button', { name: '生LQ' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '生成 LQ' })).toHaveCount(0)
  await videoRow.scrollIntoViewIfNeeded()
  await page.screenshot({ path: 'test-results/pure-video-storage.png', fullPage: false })
})

test('触发视频转码后后台轮询不遮罩详情页', async ({ page }) => {
  await page.route(`**/api/manage/storage/transcode/comics/${COMIC_ID}`, (route) =>
    json(route, { taskId: 2001, itemCount: 1 })
  )

  await page.goto(`/manage/storage/${COMIC_ID}?force-desktop=1`)
  await expect(page.getByText('测试漫画', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '视频转码' }).click()
  await page.locator('.el-message-box__btns .el-button--primary').click()

  await expect(page.getByText('已提交 1 个视频转码任务')).toBeVisible()
  await expect(page.locator('.el-loading-mask')).toHaveCount(0)

  await page.clock.install()
  await page.clock.fastForward(5000)
  await expect(page.locator('.el-loading-mask')).toHaveCount(0)
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

  await page.getByRole('dialog', { name: '刷新已提交' }).getByRole('button', { name: '前往任务中心' }).click()
  await expect(page).toHaveURL(/\/manage\/tasks$/)
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

function comicStorageItem(overrides: Record<string, unknown> = {}) {
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
    mediaType: 'IMAGE',
    ...overrides,
  }
}

function chapterStorageItem(overrides: Partial<{ chapterId: number; chapterNo: string; title: string; pageCount: number; mediaType: string; hqSize: number; lqSize: number }> = {}) {
  return {
    chapterId: 11,
    chapterNo: '1',
    title: '启程',
    pageCount: 10,
    hqSize: 1024,
    lqSize: 0,
    hqStatus: 'READY',
    lqStatus: 'NOT_GENERATED',
    mediaType: 'IMAGE',
    ...overrides,
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
