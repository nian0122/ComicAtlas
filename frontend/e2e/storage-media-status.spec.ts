import { expect, test, type Page, type Route } from '@playwright/test'

test.setTimeout(60_000)

const COMIC_ID = 42

let initialTranscodeStatus: string = 'REQUIRED'
let submitted = false
let fetchAfterSubmit = 0
let postStatusCode = 200
let postMessage = 'success'

test.beforeEach(async ({ page }) => {
  initialTranscodeStatus = 'REQUIRED'
  submitted = false
  fetchAfterSubmit = 0
  postStatusCode = 200
  postMessage = 'success'
  await page.route(`**/api/admin/storage/comics/${COMIC_ID}`, handleComicStorage)
  await page.route(`**/api/admin/storage/comics/${COMIC_ID}/chapters`, (route) =>
    json(route, [chapterStorageItem()])
  )
  await page.route(`**/api/comics/${COMIC_ID}`, (route) => json(route, comicDetail('READY')))
  await page.route(`**/api/storage/transcode/comics/${COMIC_ID}`, handleTranscodePost)
})

test('REQUIRED 视频可发起转码，提交后按 QUEUED→TRANSCODING→READY 结束轮询', async ({ page }) => {
  await page.goto(`/manage/storage/${COMIC_ID}?force-desktop=1`)

  // 初始 REQUIRED：显示“需要转码”标签，转码按钮可点击
  await expect(page.getByText('需要转码')).toBeVisible()
  const transcodeButton = page.getByRole('button', { name: '视频转码' })
  await expect(transcodeButton).toBeEnabled()

  await transcodeButton.click()
  await page.getByRole('button', { name: '开始转码', exact: true }).click()

  // 提交后返回 QUEUED：成功提示 + 按钮禁用为“转码中” + 标签“排队中”
  await expect(page.getByText('已提交 2 个视频转码任务')).toBeVisible()
  await expect(page.getByRole('button', { name: '转码中' })).toBeDisabled()
  await expect(page.getByText('排队中')).toBeVisible()

  // 轮询推进到 READY（经 TRANSCODING）后停止，标签变“已转码”、按钮禁用
  await expect(page.locator('.el-tag').filter({ hasText: '已转码' })).toBeVisible({ timeout: 20_000 })
  await expect(page.getByRole('button', { name: '已转码' })).toBeDisabled()
})

test('FAILED 显示转码失败并允许重试；服务端拒绝时透出后端错误', async ({ page }) => {
  initialTranscodeStatus = 'FAILED'
  postStatusCode = 500
  postMessage = '转码服务不可用'

  await page.goto(`/manage/storage/${COMIC_ID}?force-desktop=1`)

  // FAILED：标签“转码失败”，按钮变为“重试转码”且可点击
  await expect(page.getByText('转码失败')).toBeVisible()
  const retryButton = page.getByRole('button', { name: '重试转码' })
  await expect(retryButton).toBeEnabled()

  await retryButton.click()
  await page.getByRole('button', { name: '开始转码', exact: true }).click()

  // 服务端拒绝：展示后端 message，且失败后可再次重试
  await expect(page.getByText('转码服务不可用')).toBeVisible()
  await expect(page.getByRole('button', { name: '重试转码' })).toBeEnabled()
})

test('未知/旧词汇状态渲染兜底，不崩溃且按钮安全禁用', async ({ page }) => {
  initialTranscodeStatus = 'PENDING'

  await page.goto(`/manage/storage/${COMIC_ID}?force-desktop=1`)

  // 未知状态：标签回退显示原始值，不崩溃；按钮安全禁用
  await expect(page.getByText('PENDING')).toBeVisible()
  await expect(page.getByRole('button', { name: '视频转码' })).toBeDisabled()
})

async function handleComicStorage(route: Route) {
  await json(route, comicStorageItem())
}

async function handleTranscodePost(route: Route) {
  if (postStatusCode === 200) {
    submitted = true
  }
  await route.fulfill({
    status: postStatusCode,
    contentType: 'application/json',
    body: JSON.stringify(
      postStatusCode === 200
        ? { code: 200, message: 'success', data: transcodeResult() }
        : { code: postStatusCode, message: postMessage, data: null }
    ),
  })
}

/** 提交后的存储详情拉取按 QUEUED → TRANSCODING → READY 逐步推进，模拟 Worker 转码进度。 */
function currentTranscodeStatus(): string {
  if (!submitted) return initialTranscodeStatus
  fetchAfterSubmit++
  if (fetchAfterSubmit === 1) return 'QUEUED'
  if (fetchAfterSubmit === 2) return 'TRANSCODING'
  return 'READY'
}

function comicStorageItem() {
  return {
    comicId: COMIC_ID,
    title: '转码测试漫画',
    coverUrl: '',
    totalSize: 2048,
    hqSize: 2048,
    lqSize: 0,
    hqStatus: 'READY',
    lqStatus: 'NOT_GENERATED',
    transcodeStatus: currentTranscodeStatus(),
    chapterCount: 1,
    pageCount: 2,
  }
}

function chapterStorageItem() {
  return {
    chapterId: 1,
    chapterNo: '001',
    title: '第 1 话',
    pageCount: 2,
    hqSize: 100,
    lqSize: 0,
    hqStatus: 'READY',
    lqStatus: 'NOT_GENERATED',
  }
}

function comicDetail(status: string) {
  return {
    id: COMIC_ID,
    title: '转码测试漫画',
    author: '作者',
    coverUrl: '',
    pageCount: 2,
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

function transcodeResult() {
  return {
    taskId: 5001,
    taskType: 'TRANSCODE_VIDEOS',
    status: 'PENDING',
    itemCount: 2,
  }
}

async function json(route: Route, data: unknown) {
  await route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, message: 'success', data }),
  })
}
