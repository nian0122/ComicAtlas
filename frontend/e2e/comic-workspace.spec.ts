import { expect, test, type Page } from '@playwright/test'

const COMIC_ID = 7

function resultBody(data: unknown, code = 200, message = 'success'): string {
  return JSON.stringify({ code, message, data })
}

async function mockWorkspace(page: Page, detail: unknown): Promise<void> {
  await page.route(`**/api/manage/comics/${COMIC_ID}`, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: resultBody(detail) }),
  )
  await page.route(`**/api/manage/operations/comics/${COMIC_ID}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: resultBody({ allowed: ['METADATA_REFRESH'], blockedReasons: {} }),
    }),
  )
  await page.route('**/api/manage/tasks?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: resultBody({ records: [], total: 0 }),
    }),
  )
  await page.route('**/api/manage/outbox/stats', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: resultBody({ pending: 0, failed: 0, total: 0 }),
    }),
  )
  await page.route('**/api/manage/mq/stats', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: resultBody({ available: true, dlqTotal: 0, dlqQueues: 0, queuedTotal: 0, queues: [] }),
    }),
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

test('懒加载信息编辑页保持公共深色主题和统一控件尺寸', async ({ page }, testInfo) => {
  await mockWorkspace(page, {
    ...comicDetail,
    sourceType: 'REGISTER',
    sourceRef: '导入目录',
    comicInfo: { series: '测试漫画', tags: ['合集'] },
  })
  for (const [path, data] of [
    [`comics/${COMIC_ID}/metadata`, { title: '测试漫画', author: '作者', description: '', categoryId: null }],
    [`comics/${COMIC_ID}/tags`, [1]],
    ['tags', [{ id: 1, name: '合集' }]],
    ['categories', []],
  ] as const) {
    await page.route(`**/api/manage/${path}`, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: resultBody(data) }),
    )
  }
  await page.goto(`/manage/comics/${COMIC_ID}?tab=operations`)
  await page.getByRole('tab', { name: '信息编辑' }).click()
  await expect(page.getByPlaceholder('输入漫画标题')).toHaveValue('测试漫画')
  const textarea = page.getByPlaceholder('写下这部漫画的简介、备注或阅读提示（可选）')
  const input = page.getByPlaceholder('输入漫画标题')
  const inputColor = await input.evaluate((element) => getComputedStyle(element).color)
  const background = await page
    .locator('.title-field .el-input__wrapper')
    .evaluate((element) => getComputedStyle(element).backgroundColor)
  await expect(textarea).toHaveCSS('color', inputColor)
  await expect(textarea).toHaveCSS('background-color', background)
  await expect(textarea).toHaveCSS('border-radius', '8px')
  for (const control of [
    page.locator('.title-field .el-input__wrapper'),
    page.locator('.tag-select .el-select__wrapper'),
    page.locator('.new-tag-input .el-input__wrapper'),
    page.getByRole('button', { name: '添加', exact: true }),
    page.getByRole('button', { name: '保存修改' }),
  ]) {
    const bounds = await control.boundingBox()
    expect(bounds?.height).toBe(42)
  }
  await textarea.fill('编辑描述')
  await expect(textarea).toHaveCSS('background-color', background)
  await page.screenshot({ path: testInfo.outputPath('comic-edit-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('comic-edit-mobile.png'), fullPage: true })
})

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
    }),
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

test('媒体上传在漫画工作区按章节操作，选中媒体后可替换', async ({ page }) => {
  await page.route('**/api/manage/comics/7/catalog', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: resultBody([
        {
          id: null,
          title: null,
          children: [],
          chapters: [{ id: 9, chapterNo: '01', title: '第一章', globalOrder: 1, pageCount: 1 }],
        },
      ]),
    }),
  )
  await page.route('**/api/manage/chapters/9', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: resultBody({
        pages: [
          {
            id: 11,
            pageNumber: 1,
            fileName: '001.jpg',
            mediaType: 'IMAGE',
            hqStatus: 'READY',
            lqStatus: 'NOT_GENERATED',
          },
        ],
      }),
    }),
  )

  await page.goto('/manage/comics/7?tab=content&chapterId=9')
  await expect(page.getByRole('navigation', { name: '管理导航' }).getByText('媒体上传')).toHaveCount(0)
  await page.locator('.chapter-workspace-tabs').getByRole('button', { name: /媒体/ }).click()
  await page.getByRole('button', { name: '上传媒体' }).click()
  await expect(page.getByRole('heading', { name: '上传章节媒体' })).toBeVisible()
  await page.getByRole('button', { name: '关闭' }).click()

  await page.getByRole('row', { name: /001.jpg/ }).click()
  await page.getByRole('button', { name: '替换此媒体' }).click()
  await expect(page.getByRole('heading', { name: '替换章节媒体' })).toBeVisible()
})
