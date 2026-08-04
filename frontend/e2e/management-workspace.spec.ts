import { expect, test, type Browser, type Page, type Route } from '@playwright/test'

/**
 * 统一漫画工作区 + 漫画列表重构 E2E（Mocked API）
 *
 * 覆盖（对照 T18 交付）：
 * 1. 路由：/manage/comics/:id 工作区 + 6 tabs；旧 edit/storage detail 重定向到对应 tab
 * 2. 列表：lifecycle / activeTask / allowedAction 展示；跨页选择保持 + 选择筛选全部（FILTER 模式）
 * 3. Overview：metadata/tags/category/存储摘要合并；保存；版本冲突 409 → 错误
 * 4. Catalog：树编辑（create/rename/move/reorder/delete）、chapter CRUD/trash、
 *    目录防环 409 → 错误、按 allowedOperations 禁用并显示 blockedReason、键盘完成非拖拽操作
 * 5. loading / empty / error / blocked 状态
 * 6. 768/1280 无主区横向溢出；375 仍走管理拦截
 */

test.setTimeout(120_000)

// ====================================================================
// Mock 工具
// ====================================================================

/** Result<T> 包装：code===status，HTTP 恒为 200（对齐后端 GlobalExceptionHandler） */
async function json(route: Route, data: unknown, code = 200): Promise<void> {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code, message: code === 200 ? 'success' : '业务失败', data }),
  })
}

const EMPTY_PAGE = { records: [], total: 0, size: 24, current: 1, pages: 0 }

function allowed(ops: string[], blockedReasons: Record<string, string> = {}) {
  return { allowed: ops, blockedReasons }
}

const READY_OPS = allowed([
  'READ', 'EDIT', 'DELETE', 'LQ_GENERATE', 'HQ_DELETE', 'METADATA_REFRESH', 'TRANSCODE', 'RECONCILE',
])

function comicListEntry(id: number, overrides: Readonly<Record<string, unknown>> = {}) {
  return {
    id,
    title: `漫画 ${id}`,
    author: '作者甲',
    coverUrl: `https://example.com/cover-${id}.jpg`,
    pageCount: 120 + id,
    categoryId: null,
    categoryName: null,
    lifecycle: 'READY',
    activeTask: null,
    allowedOperations: READY_OPS,
    progressPercent: 0,
    lastReadChapterId: 0,
    lastReadPage: 0,
    createdAt: '2026-08-03T10:00:00',
    ...overrides,
  }
}

const DETAIL_1 = {
  id: 1,
  title: '某科学的超电磁炮',
  titleJpn: 'とある科学の超電磁砲',
  author: '镰池和马',
  description: '学园都市超能力者御坂美琴的故事。',
  coverUrl: 'https://example.com/cover-1.jpg',
  pageCount: 240,
  fileSize: 1024 * 1024 * 512,
  sourceType: 'ZIP',
  sourceRef: 'D:/manga/import/railgun.zip',
  categoryId: 7,
  categoryName: '漫画',
  lifecycle: 'READY',
  activeTask: null,
  allowedOperations: READY_OPS,
  version: 5,
  chapters: [
    { id: 100, chapterNo: 1, title: '第1话', pageCount: 24 },
    { id: 101, chapterNo: 2, title: '第2话', pageCount: 22 },
  ],
  tags: [{ name: '超能力', type: 'TAG' }],
  progressPercent: 0,
  lastReadChapterId: 0,
  lastReadPage: 0,
  createdAt: '2026-08-03T10:00:00',
  updatedAt: '2026-08-03T10:00:00',
}

const CATALOG_TREE = [
  {
    id: 10,
    title: '第1卷',
    children: [],
    chapters: [
      { id: 100, chapterNo: '1', title: '第1话', globalOrder: 1, pageCount: 24, status: null },
      { id: 101, chapterNo: '2', title: '第2话', globalOrder: 2, pageCount: 22, status: null },
    ],
  },
  {
    id: 11,
    title: '第2卷',
    children: [],
    chapters: [{ id: 102, chapterNo: '3', title: '第3话', globalOrder: 3, pageCount: 20, status: null }],
  },
]

/** 工作区相关端点静默（layout bootstrap 等） */
async function mockLayoutApis(page: Page): Promise<void> {
  await page.route('**/api/tasks/import*', async (route) => {
    await json(route, EMPTY_PAGE)
  })
  await page.route('**/api/tasks/recovery*', async (route) => {
    await json(route, EMPTY_PAGE)
  })
}

function assertNoHorizontalOverflow(page: Page): Promise<{ scrollWidth: number; clientWidth: number }> {
  return page.locator('.management-content').evaluate((el) => ({
    scrollWidth: el.scrollWidth,
    clientWidth: el.clientWidth,
  }))
}

// ====================================================================
// 1. 路由：工作区 + tabs
// ====================================================================

test('工作区路由渲染 6 个 tabs 且默认在 overview', async ({ page }) => {
  await mockLayoutApis(page)
  await page.route('**/api/comics/1**', async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    if (p === '/api/comics/1') await json(route, DETAIL_1)
    else if (p === '/api/comics/1/catalog') await json(route, CATALOG_TREE)
    else if (p === '/api/comics/1/metadata') await json(route, { title: '某科学的超电磁炮', author: '镰池和马', description: '', categoryId: 7 })
    else if (p === '/api/comics/1/tags') await json(route, [1])
    else await json(route, null, 404)
  })
  await page.route('**/api/categories', async (route) => {
    await json(route, [{ id: 7, name: '漫画', sortOrder: 0 }])
  })
  await page.route('**/api/admin/storage/comics/1', async (route) => {
    await json(route, {
      comicId: 1, title: '某科学的超电磁炮', coverUrl: '', totalSize: 1024 * 1024 * 512,
      hqSize: 1024 * 1024 * 500, lqSize: 1024 * 1024 * 12, hqStatus: 'READY', lqStatus: 'NOT_GENERATED',
      transcodeStatus: 'NOT_NEEDED', chapterCount: 2, pageCount: 240,
    })
  })
  await page.route('**/api/tags', async (route) => {
    await json(route, [{ id: 1, name: '超能力' }])
  })

  await page.goto('/manage/comics/1')

  const pageEl = page.getByTestId('workspace-page')
  await expect(pageEl).toBeVisible({ timeout: 15_000 })

  for (const tab of ['overview', 'catalog', 'media', 'optimization', 'tasks', 'danger']) {
    await expect(page.getByTestId(`ws-tab-${tab}`)).toBeVisible()
  }
  await expect(page.getByTestId('ws-tab-overview')).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByTestId('ws-title')).toHaveText('某科学的超电磁炮')
  await expect(page.getByTestId('ws-lifecycle')).toContainText('已就绪')
})

test('旧 edit 路由重定向到 overview tab；旧 storage detail 重定向到 optimization tab', async ({ page }) => {
  await mockLayoutApis(page)
  // 重定向是纯前端行为，不依赖 API
  await page.route('**/api/comics/5**', async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    if (p === '/api/comics/5') await json(route, { ...DETAIL_1, id: 5, title: '漫画 5' })
    else if (p === '/api/comics/5/catalog') await json(route, [])
    else if (p === '/api/comics/5/metadata') await json(route, { title: '漫画 5', author: '', description: '', categoryId: null })
    else if (p === '/api/comics/5/tags') await json(route, [])
    else await json(route, null, 404)
  })
  await page.route('**/api/categories', async (route) => await json(route, []))
  await page.route('**/api/tags', async (route) => await json(route, []))
  await page.route('**/api/admin/storage/comics/5', async (route) => {
    await json(route, {
      comicId: 5, title: '漫画 5', coverUrl: '', totalSize: 0, hqSize: 0, lqSize: 0,
      hqStatus: 'EMPTY', lqStatus: 'EMPTY', transcodeStatus: 'NOT_NEEDED', chapterCount: 0, pageCount: 0,
    })
  })

  await page.goto('/manage/comics/5/edit')
  await page.waitForURL(/\/manage\/comics\/5(\?.*)?$/)
  await expect(page.getByTestId('ws-tab-overview')).toHaveAttribute('aria-selected', 'true')

  await page.goto('/manage/storage/5')
  await page.waitForURL(/\/manage\/comics\/5\?tab=optimization/)
  await expect(page.getByTestId('ws-tab-optimization')).toHaveAttribute('aria-selected', 'true')
})

// ====================================================================
// 2. 列表：lifecycle / activeTask / allowedAction
// ====================================================================

test('列表显示 lifecycle、activeTask 与 allowedAction，blocked 动作禁用并显示原因', async ({ page }) => {
  await mockLayoutApis(page)
  const records = [
    comicListEntry(1),
    comicListEntry(2, {
      lifecycle: 'IMPORTING',
      activeTask: {
        id: 91, taskType: 'IMPORT', operation: 'IMPORT', targetType: 'COMIC',
        batchId: '', isBatch: false, status: 'RUNNING', stage: 'EXTRACTING', progress: 42,
        totalCount: 1, successCount: 0, failureCount: 0, cancelledCount: 0, errorMessage: '',
        attempt: 1, version: 1, createdAt: '2026-08-03T10:00:00', updatedAt: '2026-08-03T10:00:00',
        startedAt: '2026-08-03T10:00:00', completedAt: null,
      },
      allowedOperations: allowed([], { '*': '漫画正在导入中，无法操作' }),
    }),
    comicListEntry(3, {
      lifecycle: 'TRASHED',
      allowedOperations: allowed(['RECOVER', 'PURGE', 'RECONCILE'], {
        EDIT: '已回收漫画不可编辑',
        LQ_GENERATE: '已回收漫画不可生成 LQ',
      }),
    }),
  ]
  await page.route('**/api/comics**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname !== '/api/comics') {
      await json(route, null, 404)
      return
    }
    await json(route, { records, total: records.length, size: 24, current: 1, pages: 1 })
  })

  await page.goto('/manage/comics')

  await expect(page.getByTestId('comic-row-1')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('comic-lifecycle-1')).toContainText('已就绪')
  await expect(page.getByTestId('comic-lifecycle-2')).toContainText('导入中')

  // activeTask 展示
  await expect(page.getByTestId('comic-active-task-2')).toBeVisible()
  await expect(page.getByTestId('comic-active-task-2')).toContainText('42')

  // allowedAction：READY 漫画可执行 LQ 生成
  await expect(page.getByTestId('comic-action-1-LQ_GENERATE')).toBeVisible()
  // 导入中：动作禁用且显示 blockedReason（全阻 *）
  await expect(page.getByTestId('comic-action-2-LQ_GENERATE')).toBeDisabled()
  await expect(page.getByTestId('comic-blocked-2-LQ_GENERATE')).toContainText('漫画正在导入中，无法操作')
  // 回收站：EDIT 阻塞有具体原因
  await expect(page.getByTestId('comic-action-3-EDIT')).toBeDisabled()
  await expect(page.getByTestId('comic-blocked-3-EDIT')).toContainText('已回收漫画不可编辑')
})

// ====================================================================
// 3. 列表：跨页选择 + 选择筛选全部
// ====================================================================

test('跨页选择状态保持，选择筛选全部切 FILTER 模式且排除更新', async ({ page }) => {
  await mockLayoutApis(page)
  const total = 40
  await page.route('**/api/comics**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname !== '/api/comics') {
      await json(route, null, 404)
      return
    }
    const pageNum = Number(url.searchParams.get('page') || '1')
    const size = Number(url.searchParams.get('size') || '24')
    const start = (pageNum - 1) * size
    const records = Array.from({ length: Math.min(size, total - start) }, (_, i) =>
      comicListEntry(start + i + 1)
    )
    await json(route, { records, total, size, current: pageNum, pages: Math.ceil(total / size) })
  })

  await page.goto('/manage/comics')

  // 第 1 页勾选漫画 1
  await page.getByTestId('comic-select-1').check()
  await expect(page.getByTestId('batch-bar')).toBeVisible()
  await expect(page.getByTestId('batch-count')).toContainText('1')

  // 翻到第 2 页：选择状态保持（跨页）
  await page.locator('.el-pager li').filter({ hasText: '2' }).click()
  await expect(page.getByTestId('comic-row-25')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByTestId('batch-count')).toContainText('1')

  // 选择筛选全部 → FILTER 模式：全量 40 部
  await page.getByTestId('select-all-filtered').click()
  await expect(page.getByTestId('batch-bar')).toHaveAttribute('data-mode', 'FILTER')
  await expect(page.getByTestId('batch-count')).toContainText('40')

  // 排除漫画 26 → 39 部
  await page.getByTestId('comic-exclude-26').click()
  await expect(page.getByTestId('batch-count')).toContainText('39')
  await expect(page.getByTestId('batch-excluded-count')).toContainText('1')
})

// ====================================================================
// 4. 列表：loading / empty / error
// ====================================================================

test('列表 loading、empty、error 三态', async ({ page }) => {
  await mockLayoutApis(page)

  // loading：延迟响应（2s，保证捕获 loading 态）
  await page.route('**/api/comics**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname !== '/api/comics') {
      await json(route, null, 404)
      return
    }
    await new Promise((resolve) => setTimeout(resolve, 2000))
    await json(route, { records: [comicListEntry(1)], total: 1, size: 24, current: 1, pages: 1 })
  })
  await page.goto('/manage/comics')
  // 等待页面 JS 就绪（导航骨架出现），再断言 loading 态
  await expect(page.getByTestId('comic-list-page')).toBeVisible({ timeout: 20_000 })
  await expect(page.getByTestId('list-loading')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByTestId('comic-row-1')).toBeVisible({ timeout: 20_000 })

  // empty
  await page.route('**/api/comics**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/comics') await json(route, { ...EMPTY_PAGE })
    else await json(route, null, 404)
  })
  await page.reload()
  await expect(page.getByTestId('list-empty')).toBeVisible({ timeout: 20_000 })

  // error
  await page.route('**/api/comics**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/comics') await json(route, null, 500)
    else await json(route, null, 404)
  })
  await page.reload()
  await expect(page.getByTestId('list-error')).toBeVisible({ timeout: 15_000 })
  await page.getByTestId('list-retry').click()
})

// ====================================================================
// 5. Overview：合并 + 保存 + 版本冲突
// ====================================================================

async function installOverviewMocks(page: Page): Promise<{ metadataCalls: number; tagCalls: number }> {
  const calls = { metadataCalls: 0, tagCalls: 0 }
  await mockLayoutApis(page)
  await page.route('**/api/comics/1**', async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    if (p === '/api/comics/1') await json(route, DETAIL_1)
    else if (p === '/api/comics/1/catalog') await json(route, CATALOG_TREE)
    else if (p === '/api/comics/1/metadata') {
      if (route.request().method() === 'PUT') {
        calls.metadataCalls += 1
        await json(route, { title: '新标题', author: '镰池和马', description: '新描述', categoryId: 7 })
      } else {
        await json(route, { title: '某科学的超电磁炮', author: '镰池和马', description: '', categoryId: 7 })
      }
    } else if (p === '/api/comics/1/tags') {
      if (route.request().method() === 'PUT') {
        calls.tagCalls += 1
        await json(route, null)
      } else {
        await json(route, [1])
      }
    } else await json(route, null, 404)
  })
  await page.route('**/api/categories', async (route) => {
    await json(route, [{ id: 7, name: '漫画', sortOrder: 0 }])
  })
  await page.route('**/api/tags', async (route) => {
    await json(route, [{ id: 1, name: '超能力' }])
  })
  await page.route('**/api/admin/storage/comics/1', async (route) => {
    await json(route, {
      comicId: 1, title: '某科学的超电磁炮', coverUrl: '', totalSize: 1024 * 1024 * 512,
      hqSize: 1024 * 1024 * 500, lqSize: 1024 * 1024 * 12, hqStatus: 'READY', lqStatus: 'NOT_GENERATED',
      transcodeStatus: 'NOT_NEEDED', chapterCount: 2, pageCount: 240,
    })
  })
  return calls
}

test('Overview 合并 metadata/tags/category/存储摘要，保存调用 PUT', async ({ page }) => {
  const calls = await installOverviewMocks(page)
  await page.goto('/manage/comics/1?tab=overview')

  await expect(page.getByTestId('overview-title-input')).toHaveValue('某科学的超电磁炮', { timeout: 15_000 })
  await expect(page.getByTestId('overview-author-input')).toHaveValue('镰池和马')
  // 分类回显
  await expect(page.getByTestId('overview-category')).toContainText('漫画')
  // 标签
  await expect(page.getByTestId('overview-tags')).toContainText('超能力')
  // 存储摘要
  await expect(page.getByTestId('ov-storage-hq')).toContainText('500 MB')
  await expect(page.getByTestId('ov-storage-lq')).toContainText('12 MB')

  // 保存
  await page.getByTestId('overview-title-input').fill('新标题')
  await page.getByTestId('overview-save').click()
  await expect.poll(() => calls.metadataCalls).toBe(1)
  await expect.poll(() => calls.tagCalls).toBe(1)
  await expect(page.getByTestId('overview-save-ok')).toBeVisible({ timeout: 10_000 })
})

test('Overview 保存遇版本冲突 409 展示错误', async ({ page }) => {
  await mockLayoutApis(page)
  await page.route('**/api/comics/1**', async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    if (p === '/api/comics/1') await json(route, DETAIL_1)
    else if (p === '/api/comics/1/catalog') await json(route, CATALOG_TREE)
    else if (p === '/api/comics/1/metadata') {
      if (route.request().method() === 'PUT') {
        await json(route, null, 409)
      } else {
        await json(route, { title: '某科学的超电磁炮', author: '镰池和马', description: '', categoryId: 7 })
      }
    } else if (p === '/api/comics/1/tags') {
      await json(route, route.request().method() === 'PUT' ? null : [1])
    } else await json(route, null, 404)
  })
  await page.route('**/api/categories', async (route) => await json(route, []))
  await page.route('**/api/tags', async (route) => await json(route, []))
  await page.route('**/api/admin/storage/comics/1', async (route) => {
    await json(route, {
      comicId: 1, title: 'x', coverUrl: '', totalSize: 0, hqSize: 0, lqSize: 0,
      hqStatus: 'EMPTY', lqStatus: 'EMPTY', transcodeStatus: 'NOT_NEEDED', chapterCount: 0, pageCount: 0,
    })
  })

  await page.goto('/manage/comics/1?tab=overview')
  await expect(page.getByTestId('overview-title-input')).toBeVisible({ timeout: 15_000 })

  await page.getByTestId('overview-save').click()
  await expect(page.getByTestId('overview-error')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByTestId('overview-error')).toContainText('业务失败')
})

// ====================================================================
// 6. Catalog：树编辑 + chapter CRUD + 防环 + blocked + 键盘
// ====================================================================

/** Catalog mock：可变树状态，记录写操作 */
interface CatalogMockState {
  tree: Readonly<Record<string, unknown>>[]
  createCalls: number
  renameCalls: number
  moveCalls: number
  reorderCalls: number
  deleteCalls: number
  chapterCreateCalls: number
  chapterRenameCalls: number
  chapterMoveCalls: number
  chapterReorderCalls: number
  chapterTrashCalls: number
  moveBodies: Readonly<Record<string, unknown>>[]
}

function installCatalogMocks(
  page: Page,
  state: CatalogMockState,
  options: { moveFailsWithCycle?: boolean; chapterOps?: string[] } = {},
): Promise<void> {
  void options
  return mockLayoutApis(page).then(() => {
    void page.route('**/api/comics/1**', async (route) => {
      const url = new URL(route.request().url())
      const p = url.pathname
      const method = route.request().method()
      if (p === '/api/comics/1') await json(route, DETAIL_1)
      else if (p === '/api/comics/1/catalog') await json(route, state.tree)
      else if (p === '/api/comics/1/metadata') await json(route, { title: '某科学的超电磁炮', author: '镰池和马', description: '', categoryId: 7 })
      else if (p === '/api/comics/1/tags') await json(route, [])
      else if (p === '/api/comics/1/catalogs' && method === 'POST') {
        state.createCalls += 1
        const body = route.request().postDataJSON() as Readonly<Record<string, unknown>>
        state.tree = [...state.tree, { id: 90, title: body.title, children: [], chapters: [] }]
        await json(route, { id: 90, comicId: 1, parentId: body.parentId ?? null, title: body.title, sortOrder: body.sortOrder ?? 1 })
      } else if (p === '/api/comics/1/catalogs/10' && method === 'PATCH') {
        state.renameCalls += 1
        const body = route.request().postDataJSON() as Readonly<Record<string, unknown>>
        state.tree = state.tree.map((n) =>
          n.id === 10 ? { ...n, title: body.title } : n
        )
        await json(route, { id: 10, comicId: 1, parentId: null, title: body.title, sortOrder: 1 })
      } else if (p === '/api/comics/1/catalogs/10/move' && method === 'PUT') {
        state.moveCalls += 1
        const body = route.request().postDataJSON() as Readonly<Record<string, unknown>>
        state.moveBodies.push(body)
        if (options.moveFailsWithCycle) {
          await json(route, null, 409)
        } else {
          await json(route, { id: 10, comicId: 1, parentId: body.parentId ?? null, title: '第1卷', sortOrder: 1 })
        }
      } else if (p === '/api/comics/1/catalogs/10/reorder' && method === 'PUT') {
        state.reorderCalls += 1
        await json(route, null)
      } else if (p.startsWith('/api/comics/1/catalogs/10') && method === 'DELETE') {
        state.deleteCalls += 1
        const q = url.searchParams.get('reparentTo')
        state.tree = state.tree.filter((n) => n.id !== 10)
        void q
        await json(route, null)
      } else if (p === '/api/comics/1/chapters' && method === 'POST') {
        state.chapterCreateCalls += 1
        const body = route.request().postDataJSON() as Readonly<Record<string, unknown>>
        const newNode = {
          id: 200, chapterNo: String(body.chapterNo ?? '1'), title: body.title,
          globalOrder: 99, pageCount: 0, status: null,
        }
        state.tree = state.tree.map((n) =>
          n.id === 10
            ? { ...n, chapters: [...(n.chapters as readonly Readonly<Record<string, unknown>>[]), newNode] }
            : n
        )
        await json(route, { id: 200, comicId: 1, catalogId: body.catalogId ?? null, title: body.title, chapterNo: body.chapterNo ?? '1', pageCount: 0, sortOrder: 1, globalOrder: 99, status: 'DRAFT' })
      } else if (p === '/api/comics/1/chapters/100' && method === 'PATCH') {
        state.chapterRenameCalls += 1
        const body = route.request().postDataJSON() as Readonly<Record<string, unknown>>
        state.tree = state.tree.map((n) => ({
          ...n,
          chapters: (n.chapters as readonly Readonly<Record<string, unknown>>[]).map((c) =>
            c.id === 100 ? { ...c, title: body.title } : c
          ),
        }))
        await json(route, { id: 100, comicId: 1, catalogId: 10, title: body.title, chapterNo: '1', pageCount: 24, sortOrder: 1, globalOrder: 1, status: 'READY' })
      } else if (p === '/api/comics/1/chapters/100/move' && method === 'PUT') {
        state.chapterMoveCalls += 1
        const body = route.request().postDataJSON() as Readonly<Record<string, unknown>>
        state.moveBodies.push(body)
        await json(route, { id: 100, comicId: 1, catalogId: body.catalogId ?? null, title: '第1话', chapterNo: '1', pageCount: 24, sortOrder: 1, globalOrder: 1, status: 'READY' })
      } else if (p === '/api/comics/1/chapters/100/reorder' && method === 'PUT') {
        state.chapterReorderCalls += 1
        await json(route, { id: 100, comicId: 1, catalogId: 10, title: '第1话', chapterNo: '1', pageCount: 24, sortOrder: 1, globalOrder: 3, status: 'READY' })
      } else if (p === '/api/comics/1/chapters/100' && method === 'DELETE') {
        state.chapterTrashCalls += 1
        state.tree = state.tree.map((n) => ({
          ...n,
          chapters: (n.chapters as readonly Readonly<Record<string, unknown>>[]).filter((c) => c.id !== 100),
        }))
        await json(route, null)
      } else {
        await json(route, null, 404)
      }
    })
    void page.route('**/api/categories', async (route) => await json(route, []))
    void page.route('**/api/tags', async (route) => await json(route, []))
    void page.route('**/api/admin/storage/comics/1', async (route) => {
      await json(route, {
        comicId: 1, title: 'x', coverUrl: '', totalSize: 0, hqSize: 0, lqSize: 0,
        hqStatus: 'EMPTY', lqStatus: 'EMPTY', transcodeStatus: 'NOT_NEEDED', chapterCount: 0, pageCount: 0,
      })
    })
  })
}

function freshCatalogState(): CatalogMockState {
  return {
    tree: structuredClone(CATALOG_TREE),
    createCalls: 0, renameCalls: 0, moveCalls: 0, reorderCalls: 0, deleteCalls: 0,
    chapterCreateCalls: 0, chapterRenameCalls: 0, chapterMoveCalls: 0, chapterReorderCalls: 0, chapterTrashCalls: 0,
    moveBodies: [],
  }
}

test('Catalog 创建/重命名目录与创建/重命名章节', async ({ page }) => {
  const state = freshCatalogState()
  await installCatalogMocks(page, state)
  await page.goto('/manage/comics/1?tab=catalog')

  // 树渲染
  await expect(page.getByTestId('catalog-node-10')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('catalog-node-11')).toBeVisible()
  await expect(page.getByTestId('chapter-node-100')).toBeVisible()
  await expect(page.getByTestId('chapter-node-101')).toBeVisible()

  // 创建目录
  await page.getByTestId('cat-add-catalog').click()
  await page.getByTestId('catalog-name-input').fill('第3卷')
  await page.getByTestId('catalog-confirm').click()
  await expect(page.getByTestId('catalog-node-90')).toBeVisible({ timeout: 10_000 })
  expect(state.createCalls).toBe(1)

  // 重命名目录
  await page.getByTestId('catalog-node-10').getByTestId('action-rename').click()
  await page.getByTestId('catalog-name-input').fill('第一卷 修订')
  await page.getByTestId('catalog-confirm').click()
  await expect(page.getByTestId('catalog-node-10')).toContainText('第一卷 修订', { timeout: 10_000 })
  expect(state.renameCalls).toBe(1)

  // 创建章节（挂在第1卷下）
  await page.getByTestId('catalog-node-10').getByTestId('action-add-chapter').click()
  await page.getByTestId('chapter-title-input').fill('第1.5话')
  await page.getByTestId('catalog-confirm').click()
  await expect(page.getByTestId('chapter-node-200')).toBeVisible({ timeout: 10_000 })
  expect(state.chapterCreateCalls).toBe(1)

  // 重命名章节
  await page.getByTestId('chapter-node-100').getByTestId('action-rename').click()
  await page.getByTestId('chapter-title-input').fill('第1话 修正版')
  await page.getByTestId('catalog-confirm').click()
  await expect(page.getByTestId('chapter-node-100')).toContainText('第1话 修正版', { timeout: 10_000 })
  expect(state.chapterRenameCalls).toBe(1)
})

test('Catalog 移动/排序/删除/章节移动/回收，键盘完成非拖拽操作', async ({ page }) => {
  const state = freshCatalogState()
  await installCatalogMocks(page, state)
  await page.goto('/manage/comics/1?tab=catalog')

  await expect(page.getByTestId('catalog-node-10')).toBeVisible({ timeout: 15_000 })

  // 键盘：聚焦目录节点 → 移动到第2卷下（目标选择 + Enter）
  await page.getByTestId('catalog-node-10').getByTestId('action-move').focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('move-dialog')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByTestId('move-target-11')).toBeVisible({ timeout: 10_000 })
  await page.getByTestId('move-target-11').focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('move-dialog')).toBeHidden({ timeout: 10_000 })
  expect(state.moveCalls).toBe(1)
  expect(state.moveBodies[0]).toEqual({ parentId: 11 })

  // 目录重排
  await page.getByTestId('catalog-node-10').getByTestId('action-reorder').focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('reorder-dialog')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByTestId('reorder-confirm')).toBeVisible({ timeout: 10_000 })
  await page.getByTestId('reorder-confirm').focus()
  await page.keyboard.press('Enter')
  // 等待重排对话框关闭（变更完成后才关闭），避免下一步在异步流未完成时启动
  await expect(page.getByTestId('reorder-dialog')).toBeHidden({ timeout: 10_000 })
  expect(state.reorderCalls).toBe(1)

  // 章节移动到第2卷（键盘）
  await page.getByTestId('chapter-node-100').getByTestId('action-move').focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('move-dialog')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByTestId('move-target-11')).toBeVisible({ timeout: 10_000 })
  await page.getByTestId('move-target-11').focus()
  await page.keyboard.press('Enter')
  // 等待移动对话框关闭（变更完成后才关闭），再断言调用次数
  await expect(page.getByTestId('move-dialog')).toBeHidden({ timeout: 10_000 })
  expect(state.chapterMoveCalls).toBe(1)
  expect(state.moveBodies.at(-1)).toEqual({ catalogId: 11 })

  // 章节重排
  await page.getByTestId('chapter-node-100').getByTestId('action-reorder').focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('reorder-dialog')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByTestId('reorder-confirm')).toBeVisible({ timeout: 10_000 })
  await page.getByTestId('reorder-confirm').focus()
  await page.keyboard.press('Enter')
  // 等待重排对话框关闭（变更完成后才关闭），避免旧异步流关闭新对话框
  await expect(page.getByTestId('reorder-dialog')).toBeHidden({ timeout: 10_000 })
  expect(state.chapterReorderCalls).toBe(1)

  // 章节回收（trash）
  await page.getByTestId('chapter-node-100').getByTestId('action-trash').click()
  await page.getByTestId('catalog-confirm').click()
  await expect(page.getByTestId('chapter-node-100')).toHaveCount(0, { timeout: 10_000 })
  expect(state.chapterTrashCalls).toBe(1)

  // 删除目录（reparentTo=11），最后执行避免删除连带章节
  await page.getByTestId('catalog-node-10').getByTestId('action-delete').click()
  await page.getByTestId('delete-reparent-11').check()
  await page.getByTestId('catalog-confirm').click()
  await expect(page.getByTestId('catalog-node-10')).toHaveCount(0, { timeout: 10_000 })
  expect(state.deleteCalls).toBe(1)
})

test('Catalog 目录移动防环 409 展示错误', async ({ page }) => {
  const state = freshCatalogState()
  await installCatalogMocks(page, state, { moveFailsWithCycle: true })
  await page.goto('/manage/comics/1?tab=catalog')

  await expect(page.getByTestId('catalog-node-10')).toBeVisible({ timeout: 15_000 })

  await page.getByTestId('catalog-node-10').getByTestId('action-move').click()
  await page.getByTestId('move-target-11').click()
  await expect(page.getByTestId('catalog-error')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByTestId('catalog-error')).toContainText('业务失败')
  // 树未被破坏：节点仍在
  await expect(page.getByTestId('catalog-node-10')).toBeVisible()
})

test('Catalog 按 allowedOperations 禁用动作并显示 blockedReason', async ({ page }) => {
  const state = freshCatalogState()
  await mockLayoutApis(page)
  // 该漫画 allowedOperations 仅 READ：编辑目录/回收章节全部禁用
  const blockedDetail = {
    ...DETAIL_1,
    allowedOperations: allowed(['READ'], { EDIT: '漫画已冻结，不可编辑目录', DELETE: '漫画已冻结，不可删除' }),
  }
  void state
  await page.route('**/api/comics/1**', async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    if (p === '/api/comics/1') await json(route, blockedDetail)
    else if (p === '/api/comics/1/catalog') await json(route, CATALOG_TREE)
    else if (p === '/api/comics/1/metadata') await json(route, { title: 'x', author: '', description: '', categoryId: null })
    else if (p === '/api/comics/1/tags') await json(route, [])
    else await json(route, null, 404)
  })
  await page.route('**/api/categories', async (route) => await json(route, []))
  await page.route('**/api/tags', async (route) => await json(route, []))
  await page.route('**/api/admin/storage/comics/1', async (route) => {
    await json(route, {
      comicId: 1, title: 'x', coverUrl: '', totalSize: 0, hqSize: 0, lqSize: 0,
      hqStatus: 'EMPTY', lqStatus: 'EMPTY', transcodeStatus: 'NOT_NEEDED', chapterCount: 0, pageCount: 0,
    })
  })

  await page.goto('/manage/comics/1?tab=catalog')
  await expect(page.getByTestId('catalog-node-10')).toBeVisible({ timeout: 15_000 })

  await expect(page.getByTestId('catalog-node-10').getByTestId('action-rename').first()).toBeDisabled()
  await expect(page.getByTestId('catalog-node-10').getByTestId('action-move').first()).toBeDisabled()
  await expect(page.getByTestId('cat-add-catalog')).toBeDisabled()
  await expect(page.getByTestId('chapter-node-100').getByTestId('action-trash')).toBeDisabled()

  // blockedReason 文案
  await expect(page.getByTestId('catalog-node-10').getByTestId('blocked-reason-EDIT').first()).toContainText('漫画已冻结，不可编辑目录')
  await expect(page.getByTestId('chapter-node-100').getByTestId('blocked-reason-DELETE')).toContainText('漫画已冻结，不可删除')
})

// ====================================================================
// 7. 工作区 loading / error 状态
// ====================================================================

test('工作区 loading 与 error 状态', async ({ page }) => {
  await mockLayoutApis(page)
  await page.route('**/api/comics/999**', async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    if (p === '/api/comics/999') await json(route, null, 404)
    else await json(route, null, 404)
  })
  await page.goto('/manage/comics/999?tab=overview')
  await expect(page.getByTestId('workspace-error')).toBeVisible({ timeout: 15_000 })
  await page.getByTestId('workspace-retry').click()
})

// ====================================================================
// 8. 无横向溢出（768 / 1280）
// ====================================================================

for (const vp of [
  { name: 'tablet-768', width: 768, height: 1024 },
  { name: 'desktop-1280', width: 1280, height: 900 },
] as const) {
  test(`工作区无主区横向溢出 — ${vp.name}`, async ({ page }) => {
    const state = freshCatalogState()
    await installCatalogMocks(page, state)
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await page.goto('/manage/comics/1?tab=catalog')

    await expect(page.getByTestId('catalog-node-10')).toBeVisible({ timeout: 15_000 })
    const { scrollWidth, clientWidth } = await assertNoHorizontalOverflow(page)
    expect(scrollWidth, `横向溢出: scrollWidth=${scrollWidth} > clientWidth=${clientWidth}`)
      .toBeLessThanOrEqual(clientWidth + 1)

    await page.screenshot({
      path: `.omo/evidence/comic-management-console/task-18-workspace-${vp.name}.png`,
      fullPage: true,
    })
  })
}

// ====================================================================
// 9. 375 移动端仍走管理拦截
// ====================================================================

test('375 移动设备访问工作区重定向到拦截页', async ({ browser }) => {
  const context = await browser.newContext({
    viewport: { width: 375, height: 812 },
    hasTouch: true,
    isMobile: true,
    baseURL: 'http://localhost:5173',
  })
  const page = await context.newPage()
  try {
    await page.goto('/manage/comics/1?tab=overview')
    await page.waitForURL('**/manage/intercept', { timeout: 15_000 })
    await expect(page.getByTestId('intercept-page')).toBeVisible()
  } finally {
    await context.close()
  }
})

// ====================================================================
// 10. 截图：列表 + 工作区（375 仍走拦截）
// ====================================================================

test('截图 — 列表与工作区桌面一屏', async ({ page }) => {
  const state = freshCatalogState()
  await installCatalogMocks(page, state)
  await mockLayoutApis(page)
  await page.route('**/api/comics**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname !== '/api/comics') {
      await route.fallback()
      return
    }
    await json(route, { records: [comicListEntry(1)], total: 1, size: 24, current: 1, pages: 1 })
  })

  await page.goto('/manage/comics')
  await expect(page.getByTestId('comic-row-1')).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(400)
  await page.screenshot({ path: '.omo/evidence/comic-management-console/task-18-comic-list.png', fullPage: true })

  await page.goto('/manage/comics/1?tab=overview')
  await expect(page.getByTestId('overview-title-input')).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(400)
  await page.screenshot({ path: '.omo/evidence/comic-management-console/task-18-workspace-overview.png', fullPage: true })
})
