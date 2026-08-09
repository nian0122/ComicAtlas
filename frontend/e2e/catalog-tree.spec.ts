import { test, expect, type Page } from '@playwright/test'

/**
 * 目录树混排 fixture 用例（对应 CatalogServiceImpl 三形态输出）：
 * - 匿名根（id/title 均为 null）同时携带根散页与命名目录，globalOrder 锚点混排
 * - Vol 1 三层嵌套目录用于递归话数断言
 * - Vol 2 下两个同名（无 id）目录用于折叠 key 独立性断言
 * - Vol 3 空目录（globalOrder=null）用于 null 锚点排最后断言
 */

interface ChapterFixture {
  id: number
  chapterNo: string
  title: string
  globalOrder: number
  pageCount: number
}

interface CatalogNodeFixture {
  id: number | null
  title: string | null
  globalOrder?: number | null
  chapters: ChapterFixture[]
  children: CatalogNodeFixture[]
}

const treeFixture: CatalogNodeFixture[] = [
  {
    id: null,
    title: null,
    globalOrder: null,
    chapters: [
      { id: 101, chapterNo: '散1', title: '散页A', globalOrder: 10, pageCount: 5 },
      { id: 102, chapterNo: '散2', title: '散页B', globalOrder: 30, pageCount: 6 },
    ],
    children: [
      {
        id: 1,
        title: 'Vol 1',
        globalOrder: 1,
        chapters: [
          { id: 11, chapterNo: '1', title: '第一话', globalOrder: 2, pageCount: 10 },
          { id: 12, chapterNo: '2', title: '第二话', globalOrder: 5, pageCount: 12 },
        ],
        children: [
          {
            id: 11,
            title: 'Vol 1-1',
            globalOrder: 1,
            chapters: [
              { id: 111, chapterNo: '1', title: '第一章', globalOrder: 1, pageCount: 8 },
              { id: 112, chapterNo: '2', title: '第二章', globalOrder: 3, pageCount: 9 },
            ],
            children: [
              {
                id: 111,
                title: 'Vol 1-1-1',
                globalOrder: 1,
                chapters: [{ id: 1111, chapterNo: '1', title: '深层话', globalOrder: 4, pageCount: 4 }],
                children: [],
              },
            ],
          },
        ],
      },
      {
        id: 2,
        title: 'Vol 2',
        globalOrder: 20,
        chapters: [{ id: 21, chapterNo: '3', title: '第三话', globalOrder: 20, pageCount: 11 }],
        children: [
          {
            id: null,
            title: '同名',
            globalOrder: 21,
            chapters: [{ id: 211, chapterNo: '1', title: '同名A话', globalOrder: 21, pageCount: 7 }],
            children: [],
          },
          {
            id: null,
            title: '同名',
            globalOrder: 22,
            chapters: [{ id: 212, chapterNo: '1', title: '同名B话', globalOrder: 22, pageCount: 7 }],
            children: [],
          },
        ],
      },
      { id: 3, title: 'Vol 3', globalOrder: null, chapters: [], children: [] },
    ],
  },
]

const comicFixture = {
  id: 7,
  title: '混排目录测试漫画',
  titleJpn: '',
  author: '测试作者',
  description: '用于验证目录树混排、递归话数与折叠稳定性的 fixture 漫画。',
  coverUrl: 'https://example.com/cover.jpg',
  pageCount: 100,
  fileSize: 1024,
  sourceType: 'REGISTER',
  sourceRef: '',
  categoryId: null,
  categoryName: null,
  status: 'READY',
  progressPercent: 0,
  lastReadChapterId: null,
  lastReadPage: 0,
  chapters: [],
  tags: [],
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

/** 拦截详情与目录接口（响应包裹 { code, data } 以配合 axios 拦截器解包） */
async function mockDetailPage(page: Page) {
  await page.route('/api/comics/7', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, data: comicFixture }),
    })
  })
  await page.route('/api/comics/7/catalog', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, data: treeFixture }),
    })
  })
}

/** 拦截阅读器接口，避免点击章节后进入真实加载流程 */
async function mockReaderApi(page: Page) {
  await page.route('/api/chapters/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        data: {
          chapterId: 1,
          comicId: 7,
          chapterTitle: '测试章节',
          pages: [],
          total: 0,
          prevChapterId: null,
          nextChapterId: null,
        },
      }),
    })
  })
  await page.route('/api/history/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, data: { pageNumber: 1 } }),
    })
  })
}

/** 按精确标题定位可见目录 header（避免 Vol 1 与 Vol 1-1 子串误匹配、池化隐藏节点干扰） */
function headerByTitle(page: Page, title: string) {
  return page
    .locator('.catalog-tree .node-header')
    .filter({ has: page.getByText(title, { exact: true }), visible: true })
}

/** 按标题定位可见章节行 */
function chapterByTitle(page: Page, title: string) {
  return page
    .locator('.catalog-tree .chapter-row')
    .filter({ hasText: title, visible: true })
}

/**
 * 按视觉位置点击同名 header。
 * RecycleScroller 复用 DOM 节点后 DOM 顺序可能与视觉顺序不一致，
 * 因此改用 getBoundingClientRect 的 top 排序，按视觉坐标点击。
 */
async function clickHeaderAtVisualIndex(page: Page, title: string, visualIndex: number) {
  const boxes = await headerByTitle(page, title).evaluateAll((els) =>
    els.map((el) => {
      const r = el.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
    })
  )
  boxes.sort((a, b) => a.y - b.y)
  await page.mouse.click(boxes[visualIndex].x, boxes[visualIndex].y)
}

/** 读取当前可见行的顺序标签（H=目录 header，C=章节行，跳过池化隐藏节点） */
async function readRowLabels(page: Page): Promise<string[]> {
  return page
    .locator('.catalog-tree .node-header, .catalog-tree .chapter-row')
    .evaluateAll((els) =>
      els
        .filter((el) => window.getComputedStyle(el).visibility !== 'hidden')
        .map((el) => {
          const title = el.querySelector<HTMLElement>('.node-title')?.textContent?.trim()
          if (title) return `H:${title}`
          const no = el.querySelector<HTMLElement>('.chapter-no')?.textContent?.trim()
          const t = el.querySelector<HTMLElement>('.chapter-title')?.textContent?.trim()
          return `C:${no} ${t}`
        })
    )
}

test.beforeEach(async ({ page }) => {
  await mockDetailPage(page)
  await mockReaderApi(page)
})

test.describe('desktop', () => {
  test.use({ viewport: { width: 1440, height: 900 } })

  test('三层目录递归话数、匿名根无 header、根散页与目录按锚点混排', async ({ page }) => {
    await page.goto('/comic/7')

    const headers = page.locator('.catalog-tree .node-header')
    await expect(headers.first()).toBeVisible({ timeout: 10000 })

    // 匿名根（id/title 均 null）不产生 header：初始仅 3 个顶层命名目录有 header
    await expect(headers).toHaveCount(3)
    await expect(headers.nth(0)).toContainText('Vol 1')

    // 顶层递归话数：Vol 1 = 自身2 + 子2 + 孙1 = 5（不把子目录当"话"）；Vol 2 = 自身1 + 同名×2 = 3
    await expect(headerByTitle(page, 'Vol 1')).toContainText('5 话')
    await expect(headerByTitle(page, 'Vol 2')).toContainText('3 话')

    // 初始仅顶层可见：Vol1(1) < 散A(10) < Vol2(20) < 散B(30) < Vol3(null 排最后)
    const initialLabels = await readRowLabels(page)
    expect(initialLabels).toEqual([
      'H:Vol 1',
      'C:第散1话 散页A',
      'H:Vol 2',
      'C:第散2话 散页B',
      'H:Vol 3',
    ])

    // 三层展开：子目录话数保持递归值
    await headerByTitle(page, 'Vol 1').click()
    await expect(chapterByTitle(page, '第一话')).toBeVisible()
    await expect(headerByTitle(page, 'Vol 1-1')).toContainText('3 话')
    await headerByTitle(page, 'Vol 1-1').click()
    await expect(chapterByTitle(page, '第一章')).toBeVisible()
    await expect(headerByTitle(page, 'Vol 1-1-1')).toContainText('1 话')
    await headerByTitle(page, 'Vol 1-1-1').click()
    await expect(chapterByTitle(page, '深层话')).toBeVisible()

    await page.screenshot({
      path: '../.omo/evidence/task-11-catalog-ui.png',
      fullPage: true,
    })

    // 点击散页章节 → 进入对应阅读器
    await chapterByTitle(page, '散页A').click()
    await page.waitForURL(/\/reader\/101/)
  })

  test('同名目录各自独立折叠', async ({ page }) => {
    await page.goto('/comic/7')
    await expect(page.locator('.catalog-tree .node-header').first()).toBeVisible({ timeout: 10000 })

    // 展开 Vol 2，露出两个同名目录
    await headerByTitle(page, 'Vol 2').click()
    const sameName = headerByTitle(page, '同名')
    await expect(sameName).toHaveCount(2)

    // 初始均折叠：两个子章节都不可见
    await expect(chapterByTitle(page, '同名A话')).toHaveCount(0)
    await expect(chapterByTitle(page, '同名B话')).toHaveCount(0)

    // 展开第一个（视觉最靠上）同名 → 仅同名A话出现
    await clickHeaderAtVisualIndex(page, '同名', 0)
    await expect(chapterByTitle(page, '同名A话')).toBeVisible()
    await expect(chapterByTitle(page, '同名B话')).toHaveCount(0)

    // 展开第二个（视觉最靠下）同名 → 同名B话出现，同名A话仍可见（互不干扰）
    await clickHeaderAtVisualIndex(page, '同名', 1)
    await expect(chapterByTitle(page, '同名A话')).toBeVisible()
    await expect(chapterByTitle(page, '同名B话')).toBeVisible()

    // 折叠第一个同名 → 同名A话消失，同名B话保留
    await clickHeaderAtVisualIndex(page, '同名', 0)
    await expect(chapterByTitle(page, '同名A话')).toHaveCount(0)
    await expect(chapterByTitle(page, '同名B话')).toBeVisible()
  })

  test('首章按钮按全局锚点取最小章节，空目录不崩溃', async ({ page }) => {
    await page.goto('/comic/7')
    await expect(page.getByRole('button', { name: '开始阅读' })).toBeVisible({ timeout: 10000 })

    // 全局 globalOrder 最小为第一章(111, go=1)，而非散页A(101, go=10)
    await page.getByRole('button', { name: '开始阅读' }).click()
    await page.waitForURL(/\/reader\/111/)
  })

  test('空目录树不崩溃且显示空态', async ({ page }) => {
    // 覆盖 beforeEach 的目录路由：返回空数组
    await page.route('/api/comics/7/catalog', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 0, data: [] }),
      })
    })
    await page.goto('/comic/7')
    await expect(page.getByText('暂无章节')).toBeVisible({ timeout: 10000 })
  })
})

test.describe('mobile', () => {
  test.use({
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  })

  test('移动端目录渲染、折叠与截图', async ({ page }) => {
    await page.goto('/comic/7')

    // 确认进入移动端布局
    await expect(page.locator('.mobile-detail')).toBeVisible({ timeout: 10000 })

    // 匿名根无 header、递归话数正确（初始仅 3 个顶层命名目录有 header）
    const headers = page.locator('.catalog-tree .node-header')
    await expect(headers.first()).toBeVisible()
    await expect(headers).toHaveCount(3)
    await expect(headerByTitle(page, 'Vol 1')).toContainText('5 话')

    // 展开三层目录
    await headerByTitle(page, 'Vol 1').click()
    await expect(chapterByTitle(page, '第一话')).toBeVisible()
    await headerByTitle(page, 'Vol 1-1').click()
    await expect(chapterByTitle(page, '第一章')).toBeVisible()
    await headerByTitle(page, 'Vol 1-1-1').click()
    await expect(chapterByTitle(page, '深层话')).toBeVisible()

    await page.screenshot({
      path: '../.omo/evidence/task-11-catalog-ui-mobile.png',
      fullPage: true,
    })
  })
})
