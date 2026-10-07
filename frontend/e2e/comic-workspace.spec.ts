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

async function mockChapterEditor(page: Page): Promise<void> {
  await mockWorkspace(page, comicDetail)
  await page.route('**/api/manage/comics/7/catalog', (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: resultBody([
        {
          id: null,
          title: null,
          children: [{ id: 2, title: '第二卷', children: [], chapters: [] }],
          chapters: [{ id: 9, chapterNo: '01', title: '第一章', globalOrder: 1, sortOrder: 0, pageCount: 0 }],
        },
      ]),
    }),
  )
  await page.route('**/api/manage/chapters/9', (route) =>
    route.fulfill({ contentType: 'application/json', body: resultBody({ pages: [] }) }),
  )
  await page.route('**/api/manage/admin/storage/comics/7/chapters', (route) =>
    route.fulfill({ contentType: 'application/json', body: resultBody([]) }),
  )
}

test('章节新建和移动可取消并保留编辑草稿，保存可清空原始编号', async ({ page }, testInfo) => {
  await mockChapterEditor(page)
  let savedChapter: unknown
  await page.route('**/api/manage/comics/7/chapters/9', async (route) => {
    savedChapter = route.request().postDataJSON()
    await route.fulfill({ contentType: 'application/json', body: resultBody({ id: 9 }) })
  })
  await page.setViewportSize({ width: 1920, height: 1080 })
  await page.goto('/manage/comics/7?tab=content&chapterId=9')
  const editor = page.locator('.chapter-inspector')
  const title = editor.getByPlaceholder('输入章节标题')
  await expect(title).toHaveValue('第一章')
  await expect(editor.getByRole('button', { name: '保存修改', exact: true })).toBeDisabled()
  await editor.screenshot({ path: testInfo.outputPath('chapter-actions.png') })
  await title.fill('修改后的章节')
  await editor.getByPlaceholder('如 01、番外').fill('')
  await editor.getByRole('button', { name: '新建章节', exact: true }).click()
  await expect(title).toHaveValue('')
  await expect(editor.getByRole('button', { name: '创建章节', exact: true })).toBeDisabled()
  await editor.getByRole('button', { name: '取消', exact: true }).click()
  await expect(title).toHaveValue('修改后的章节')
  await expect(editor.getByPlaceholder('如 01、番外')).toHaveValue('')
  await editor.getByRole('button', { name: '移动章节', exact: true }).click()
  await expect(editor.getByRole('button', { name: '确认移动', exact: true })).toBeDisabled()
  await editor.locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '第二卷', exact: true }).click()
  await expect(editor.getByRole('button', { name: '确认移动', exact: true })).toBeEnabled()
  await editor.getByRole('button', { name: '取消', exact: true }).click()
  await expect(title).toHaveValue('修改后的章节')
  await editor.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByText('修改已保存', { exact: true })).toBeVisible()
  expect(savedChapter).toEqual({ title: '修改后的章节', chapterNo: '' })
  await expect(title).toHaveValue('第一章')
})

test('回收章节明确说明范围，取消不发请求，确认只回收当前章节', async ({ page }) => {
  await mockChapterEditor(page)
  const recycledChapterIds: string[] = []
  await page.route('**/api/manage/comics/7/chapters/*', async (route) => {
    if (route.request().method() === 'DELETE') {
      recycledChapterIds.push(route.request().url().split('/').pop()!)
    }
    await route.fulfill({ contentType: 'application/json', body: resultBody(null) })
  })
  await page.goto('/manage/comics/7?tab=content&chapterId=9')
  const recycle = page.locator('.chapter-inspector').getByRole('button', { name: '回收章节…', exact: true })
  await recycle.click()
  const dialog = page.getByRole('dialog', { name: '回收章节', exact: true })
  await expect(dialog).toContainText('“第一章”及其中的全部媒体')
  await dialog.getByRole('button', { name: '保留章节' }).click()
  await expect(dialog).toBeHidden()
  expect(recycledChapterIds).toEqual([])
  await expect(page.locator('.el-message--error')).toHaveCount(0)
  await recycle.click()
  await dialog.getByRole('button', { name: '确认回收' }).click()
  await expect(page.getByText('章节回收任务已提交', { exact: true })).toBeVisible()
  expect(recycledChapterIds).toEqual(['9'])
})

test('创建后选中新章节，移动后保留章节选择并刷新目录归属', async ({ page }) => {
  await mockChapterEditor(page)
  const createdChapter = { id: 10, title: '番外', chapterNo: '', globalOrder: 2, sortOrder: 1, pageCount: 0 }
  let hasCreatedChapter = false
  let hasMovedChapter = false
  await page.route('**/api/manage/comics/7/catalog', (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: resultBody([
        {
          id: null,
          title: null,
          children: [{ id: 2, title: '第二卷', children: [], chapters: hasMovedChapter ? [createdChapter] : [] }],
          chapters: [
            { id: 9, title: '第一章', chapterNo: '01', globalOrder: 1, sortOrder: 0, pageCount: 0 },
            ...(hasCreatedChapter && !hasMovedChapter ? [createdChapter] : []),
          ],
        },
      ]),
    }),
  )
  await page.route('**/api/manage/chapters/10', (route) =>
    route.fulfill({ contentType: 'application/json', body: resultBody({ pages: [] }) }),
  )
  await page.route('**/api/manage/comics/7/chapters', async (route) => {
    expect(route.request().postDataJSON()).toEqual({ title: '番外', chapterNo: '', catalogId: null })
    hasCreatedChapter = true
    await route.fulfill({ contentType: 'application/json', body: resultBody(createdChapter) })
  })
  await page.route('**/api/manage/comics/7/chapters/10/move', async (route) => {
    expect(route.request().postDataJSON()).toEqual({ catalogId: 2 })
    hasMovedChapter = true
    await route.fulfill({ contentType: 'application/json', body: resultBody(createdChapter) })
  })
  await page.goto('/manage/comics/7?tab=content&chapterId=9')
  const editor = page.locator('.chapter-inspector')
  await editor.getByRole('button', { name: '新建章节', exact: true }).click()
  await editor.getByPlaceholder('输入章节标题').fill('番外')
  await editor.getByRole('button', { name: '创建章节', exact: true }).click()
  await expect(editor).toContainText('当前章节 · #10')
  await expect(editor.getByPlaceholder('输入章节标题')).toHaveValue('番外')
  await editor.getByRole('button', { name: '移动章节', exact: true }).click()
  await editor.locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '第二卷', exact: true }).click()
  await editor.getByRole('button', { name: '确认移动', exact: true }).click()
  await expect(editor).toContainText('当前章节 · #10')
  await expect(editor.getByPlaceholder('输入章节标题')).toHaveValue('番外')
  await editor.getByRole('button', { name: '移动章节', exact: true }).click()
  await expect(editor.locator('.el-select__placeholder')).toHaveText('第二卷')
  await expect(editor.getByRole('button', { name: '确认移动', exact: true })).toBeDisabled()
})
