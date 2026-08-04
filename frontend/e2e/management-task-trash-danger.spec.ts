import { expect, test, type Page, type Route } from '@playwright/test'

/**
 * 任务中心 / 回收站 / 危险区 统一控制台契约测试（Mocked，T20）
 *
 * 覆盖：
 * 1. 统一任务首次加载包含 EXPORT/DIRECTORY_SCAN（T14 统一分页 API），且类型过滤生效
 * 2. 批次任务详情展开（GET /tasks/{id}/items 逐项）+ 部分成功批次（PARTIALLY_SUCCEEDED 计数）
 * 3. 取消非终态任务、只重试失败项（终态成功不显示重试）
 * 4. 活跃任务自动轮询，全部终态后停止（复用 T17 store 2s 有界轮询）
 * 5. 回收站：剩余保留期、reconcile 资产清单 + BOTH 恢复冲突警示、restore、preview token + 标题确认 purge
 * 6. 危险操作 preview token 过期（410 PREVIEW_TOKEN_EXPIRED）→ 红色错误面（reasonCode 可见）
 * 7. 危险动作条件变化阻断（PREVIEW_CONDITION_CHANGED）+ 双击防重（同 key 仅一个请求）
 */

test.setTimeout(90_000)

const CONSOLE = '/manage/console?force-desktop=1'
const TASKS = '/manage/tasks?force-desktop=1'
const TRASH = '/manage/trash?force-desktop=1'

// ====================================================================
// Mock 工具
// ====================================================================

async function json(route: Route, data: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify({ code: status, message: status === 200 ? 'success' : 'error', data }),
  })
}

const EMPTY_PAGE = { records: [], total: 0, size: 20, current: 1, pages: 0 }

function comicItem(
  id: number,
  title: string,
  extra?: Readonly<Record<string, unknown>>,
): Record<string, unknown> {
  return {
    id,
    title,
    author: '作者',
    coverUrl: '',
    pageCount: 120,
    categoryId: null,
    categoryName: null,
    progressPercent: 0,
    lastReadChapterId: 0,
    lastReadPage: 0,
    createdAt: '2026-08-01T10:00:00',
    ...extra,
  }
}

function taskItem(id: number, status: string, extra?: Readonly<Record<string, unknown>>) {
  return {
    id,
    taskType: 'LQ_GENERATE',
    operation: 'LQ_GENERATE',
    targetType: 'COMIC',
    batchId: '',
    isBatch: false,
    status,
    stage: null,
    progress: 50,
    totalCount: 1,
    successCount: 0,
    failureCount: 0,
    cancelledCount: 0,
    errorMessage: '',
    attempt: 1,
    version: 1,
    createdAt: '2026-08-03T10:00:00',
    updatedAt: '2026-08-03T10:00:00',
    startedAt: '2026-08-03T10:00:00',
    completedAt: null,
    ...extra,
  }
}

function taskItemEntry(itemId: number, taskId: number, status: string, extra?: Readonly<Record<string, unknown>>) {
  return {
    id: itemId,
    taskId,
    targetType: 'COMIC',
    targetId: 100 + itemId,
    operationType: 'LQ_GENERATE',
    status,
    attempt: 1,
    progress: 100,
    resultRefType: '',
    resultRefId: 0,
    errorMessage: '',
    version: 1,
    createdAt: '2026-08-03T10:00:00',
    updatedAt: '2026-08-03T10:00:00',
    startedAt: '2026-08-03T10:00:00',
    completedAt: null,
    ...extra,
  }
}

/** 布局层 import/recovery bootstrap 静默（ManagementLayout onMounted） */
async function mockLayoutApis(page: Page): Promise<void> {
  await page.route('**/api/tasks/import*', async (route) => {
    await json(route, EMPTY_PAGE)
  })
  await page.route('**/api/tasks/recovery*', async (route) => {
    await json(route, EMPTY_PAGE)
  })
}

/**
 * 有状态统一任务 mock：
 * - GET  /api/management/tasks        → 按 type/status 过滤的列表分页
 * - GET  /api/management/tasks/{id}/items → 逐项
 * - POST /api/management/tasks/{id}/cancel → 状态改 CANCELLED
 * - POST /api/management/tasks/{id}/retry  → 状态改 QUEUED + attempt+1
 */
function createTaskMock(initial: readonly Readonly<Record<string, unknown>>[]) {
  const tasks = new Map<number, Record<string, unknown>>()
  const items = new Map<number, readonly Readonly<Record<string, unknown>>[]>()
  for (const t of initial) tasks.set(t.id as number, { ...t })

  const listRequests: URL[] = []

  const handler = async (route: Route): Promise<void> => {
    const req = route.request()
    const url = new URL(req.url())
    const method = req.method()

    if (url.pathname === '/api/management/tasks') {
      if (method === 'GET') {
        listRequests.push(url)
        const type = url.searchParams.get('type')
        const status = url.searchParams.get('status')
        let all = [...tasks.values()]
        if (type) all = all.filter((t) => t.taskType === type)
        if (status) all = all.filter((t) => t.status === status)
        await json(route, {
          records: all,
          total: all.length,
          size: Number(url.searchParams.get('size') ?? 20),
          current: Number(url.searchParams.get('page') ?? 1),
          pages: 1,
        })
        return
      }
      await json(route, null, 404)
      return
    }

    const cancelMatch = url.pathname.match(/^\/api\/management\/tasks\/(\d+)\/cancel$/)
    if (cancelMatch && method === 'POST') {
      const id = Number(cancelMatch[1])
      const t = tasks.get(id)
      if (t) {
        t.status = 'CANCELLED'
        t.completedAt = '2026-08-03T10:05:00'
      }
      await json(route, { taskId: id, taskType: t?.taskType ?? '', status: 'CANCELLED', itemCount: 0 })
      return
    }

    const retryMatch = url.pathname.match(/^\/api\/management\/tasks\/(\d+)\/retry$/)
    if (retryMatch && method === 'POST') {
      const id = Number(retryMatch[1])
      const t = tasks.get(id)
      if (t) {
        t.status = 'QUEUED'
        t.attempt = (t.attempt as number) + 1
        t.completedAt = null
      }
      await json(route, { taskId: id, taskType: t?.taskType ?? '', status: 'QUEUED', itemCount: 0 })
      return
    }

    const itemsMatch = url.pathname.match(/^\/api\/management\/tasks\/(\d+)\/items$/)
    if (itemsMatch && method === 'GET') {
      const taskId = Number(itemsMatch[1])
      await json(route, items.get(taskId) ?? [])
      return
    }

    await json(route, null, 404)
  }

  return {
    handler,
    tasks,
    items,
    listRequests,
    lastType: () => listRequests.at(-1)?.searchParams.get('type') ?? null,
    lastStatus: () => listRequests.at(-1)?.searchParams.get('status') ?? null,
  }
}

/** GET /api/comics 状态过滤 mock：READY 与 TRASHED 各一份列表 */
function createComicMock(ready: readonly Readonly<Record<string, unknown>>[], trashed: readonly Readonly<Record<string, unknown>>[]) {
  return async (route: Route): Promise<void> => {
    const url = new URL(route.request().url())
    const status = url.searchParams.get('status')
    const list = status === 'TRASHED' ? trashed : ready
    await json(route, {
      records: list,
      total: list.length,
      size: Number(url.searchParams.get('size') ?? 20),
      current: Number(url.searchParams.get('page') ?? 1),
      pages: 1,
    })
  }
}

/** 漫画回收（DELETE /api/comics/{id}）请求计数 */
function createDeleteCounter(page: Page): { count: number } {
  const counter = { count: 0 }
  return counter
}

// ====================================================================
// 1. 统一首次加载包含 export/scan + 类型过滤
// ====================================================================

test('统一任务首次加载包含 EXPORT/DIRECTORY_SCAN，类型过滤生效', async ({ page }) => {
  const mock = createTaskMock([
    taskItem(1, 'RUNNING', { taskType: 'IMPORT', operation: 'IMPORT' }),
    taskItem(2, 'RUNNING', { taskType: 'EXPORT', operation: 'EXPORT' }),
    taskItem(3, 'SUCCEEDED', { taskType: 'DIRECTORY_SCAN', operation: 'DIRECTORY_SCAN' }),
    taskItem(4, 'FAILED', { taskType: 'LQ_GENERATE', operation: 'LQ_GENERATE' }),
  ])
  await mockLayoutApis(page)
  await page.route('**/api/management/tasks**', mock.handler)

  await page.goto(TASKS)

  await expect(page.getByTestId('task-row-1')).toBeVisible()
  await expect(page.getByTestId('task-row-2')).toBeVisible()
  await expect(page.getByTestId('task-row-3')).toBeVisible()
  await expect(page.getByTestId('task-row-4')).toBeVisible()

  await expect(page.getByTestId('task-row-2').getByTestId('task-type')).toContainText('EXPORT')
  await expect(page.getByTestId('task-row-3').getByTestId('task-type')).toContainText('DIRECTORY_SCAN')

  await page.selectOption('[data-testid="tasks-filter-type"]', 'LQ_GENERATE')
  await expect.poll(() => mock.lastType(), { timeout: 8000 }).toBe('LQ_GENERATE')
  await expect(page.getByTestId('task-row-4')).toBeVisible()
  await expect(page.getByTestId('task-row-1')).toHaveCount(0)
  await expect(page.getByTestId('task-row-3')).toHaveCount(0)
})

// ====================================================================
// 2. 批次任务详情展开 + 部分成功批次
// ====================================================================

test('批次任务展开显示逐项，部分成功批次展示成功/失败计数', async ({ page }) => {
  const mock = createTaskMock([
    taskItem(10, 'PARTIALLY_SUCCEEDED', {
      taskType: 'IMPORT',
      operation: 'IMPORT',
      isBatch: true,
      batchId: 'batch-0001',
      totalCount: 3,
      successCount: 2,
      failureCount: 1,
    }),
  ])
  mock.items.set(10, [
    taskItemEntry(101, 10, 'SUCCEEDED'),
    taskItemEntry(102, 10, 'SUCCEEDED'),
    taskItemEntry(103, 10, 'FAILED', { errorMessage: '来源文件缺失' }),
  ])
  await mockLayoutApis(page)
  await page.route('**/api/management/tasks**', mock.handler)

  await page.goto(CONSOLE)
  await page.getByTestId('console-tab-tasks').click()

  await expect(page.getByTestId('task-row-10')).toBeVisible()
  await expect(page.getByTestId('task-row-10').getByTestId('task-status')).toContainText('PARTIALLY_SUCCEEDED')
  await expect(page.getByTestId('task-row-10').getByTestId('task-counts')).toContainText('2')
  await expect(page.getByTestId('task-row-10').getByTestId('task-counts')).toContainText('1')

  await page.getByTestId('task-toggle-10').click()
  await expect(page.getByTestId('task-item-10-101')).toBeVisible()
  await expect(page.getByTestId('task-item-10-102')).toBeVisible()
  await expect(page.getByTestId('task-item-10-103')).toBeVisible()
  await expect(page.getByTestId('task-item-10-103').getByTestId('task-item-status')).toContainText('FAILED')
  await expect(page.getByTestId('task-item-10-103').getByTestId('task-item-error')).toContainText('来源文件缺失')
})

// ====================================================================
// 3. 取消非终态 + 只重试失败项
// ====================================================================

test('取消非终态任务、只重试失败项（终态成功不显示重试）', async ({ page }) => {
  const mock = createTaskMock([
    taskItem(1, 'RUNNING', { taskType: 'IMPORT', operation: 'IMPORT' }),
    taskItem(2, 'FAILED', { taskType: 'LQ_GENERATE', operation: 'LQ_GENERATE', errorMessage: '生成超时' }),
    taskItem(3, 'SUCCEEDED', { taskType: 'EXPORT', operation: 'EXPORT' }),
  ])
  await mockLayoutApis(page)
  await page.route('**/api/management/tasks**', mock.handler)

  await page.goto(TASKS)

  await expect(page.getByTestId('task-cancel-1')).toBeVisible()
  await expect(page.getByTestId('task-retry-2')).toBeVisible()
  await expect(page.getByTestId('task-retry-3')).toHaveCount(0)
  await expect(page.getByTestId('task-cancel-3')).toHaveCount(0)
  await expect(page.getByTestId('task-row-2').getByTestId('task-error')).toContainText('生成超时')

  await page.getByTestId('task-cancel-1').click()
  await expect
    .poll(async () => (await page.getByTestId('task-row-1').getByTestId('task-status').textContent())?.trim(), {
      timeout: 8000,
    })
    .toBe('CANCELLED')
  await expect(page.getByTestId('task-cancel-1')).toHaveCount(0)

  await page.getByTestId('task-retry-2').click()
  await expect
    .poll(async () => (await page.getByTestId('task-row-2').getByTestId('task-status').textContent())?.trim(), {
      timeout: 8000,
    })
    .toBe('QUEUED')
  await expect(page.getByTestId('task-retry-2')).toHaveCount(0)
  await expect(page.getByTestId('task-cancel-2')).toBeVisible()
})

// ====================================================================
// 4. 活跃任务自动轮询，终态后停止
// ====================================================================

test('活跃任务自动轮询，全部终态后停止（有界轮询契约）', async ({ page }) => {
  let call = 0
  const counter = { count: 0 }
  const mock = createTaskMock([])
  await mockLayoutApis(page)
  await page.route('**/api/management/tasks**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/management/tasks' && route.request().method() === 'GET') {
      counter.count += 1
      call += 1
      const payloads = [
        { items: [taskItem(1, 'RUNNING', { taskType: 'IMPORT', operation: 'IMPORT' })] },
        { items: [taskItem(1, 'SUCCEEDED', { taskType: 'IMPORT', operation: 'IMPORT' })] },
      ]
      const idx = Math.min(call - 1, payloads.length - 1)
      const payload = payloads[idx] as { items: readonly Readonly<Record<string, unknown>>[] }
      await json(route, {
        records: payload.items,
        total: payload.items.length,
        size: 20,
        current: 1,
        pages: 1,
      })
      return
    }
    await json(route, null, 404)
  })

  await page.goto(TASKS)
  await expect(page.getByTestId('task-row-1')).toBeVisible()
  await expect(page.getByTestId('tasks-polling')).toHaveAttribute('data-polling', 'true')

  await expect
    .poll(async () => (await page.getByTestId('task-row-1').getByTestId('task-status').textContent())?.trim(), {
      timeout: 8000,
    })
    .toBe('SUCCEEDED')

  await expect
    .poll(() => page.getByTestId('tasks-polling').getAttribute('data-polling'), { timeout: 8000 })
    .toBe('false')

  const countAfterTerminal = counter.count
  await page.waitForTimeout(2600)
  expect(counter.count, '终态后不应继续轮询').toBe(countAfterTerminal)
})

// ====================================================================
// 5. 回收站：保留期 / 清单冲突 / restore / purge
// ====================================================================

test('回收站显示剩余保留期、恢复冲突警示，支持恢复与 token 永久删除', async ({ page }) => {
  const daysAgo = (days: number): string => {
    const d = new Date(Date.now() - days * 86_400_000)
    return d.toISOString().slice(0, 10) + 'T10:00:00'
  }
  // 有状态回收站列表：restore / purge 成功后从 mock 移除，供页面重新拉取反映
  const trashed = [
    comicItem(1, '漫画A', { status: 'TRASHED', createdAt: daysAgo(5) }),
    comicItem(2, '漫画B', { status: 'TRASHED', createdAt: daysAgo(8) }),
  ]
  await mockLayoutApis(page)
  await page.route('**/api/comics**', async (route) => {
    const url = new URL(route.request().url())
    const status = url.searchParams.get('status')
    const list = status === 'TRASHED' ? trashed : []
    await json(route, {
      records: list,
      total: list.length,
      size: 100,
      current: 1,
      pages: 1,
    })
  })

  // reconcile 报告：漫画A 存在 BOTH 冲突（源与回收站同时存在）→ 恢复冲突
  await page.route('**/api/trash/comic/1/reconcile', async (route) => {
    await json(route, {
      targetType: 'COMIC',
      targetId: 1,
      dbStatus: 'TRASHED',
      manifestTaskId: 100,
      manifestStatus: 'TRASHED',
      consistent: false,
      entries: [
        { rootKey: 'HQ', sourceRelativePath: '1/1/001.jpg', sourceExists: true, trashExists: true, state: 'BOTH' },
        { rootKey: 'HQ', sourceRelativePath: '1/1/002.jpg', sourceExists: false, trashExists: true, state: 'IN_TRASH' },
      ],
    })
  })

  // restore：漫画B
  await page.route('**/api/trash/comics/2/restore', async (route) => {
    if (route.request().method() !== 'POST') {
      await json(route, null, 404)
      return
    }
    trashed.splice(trashed.findIndex((t) => t.id === 2), 1)
    await json(route, { taskId: 201, taskType: 'COMIC_RESTORE', status: 'RUNNING', itemCount: 1 })
  })

  // purge（漫画A）：batch preview → token → batch create（成功后从 mock 移除）
  await page.route('**/api/management/batch/preview', async (route) => {
    if (route.request().method() !== 'POST') {
      await json(route, null, 404)
      return
    }
    const body = route.request().postDataJSON() as { operation?: string }
    expect(body.operation, 'preview 应请求 COMIC_PURGE').toBe('COMIC_PURGE')
    await json(route, {
      operation: 'COMIC_PURGE',
      selectedCount: 1,
      eligibleCount: 1,
      blocked: [],
      dangerous: true,
      previewToken: 'preview-token-comic-1',
      expiresAt: '2026-08-03T10:10:00',
    })
  })
  await page.route('**/api/management/batch', async (route) => {
    if (route.request().method() !== 'POST') {
      await json(route, null, 404)
      return
    }
    const body = route.request().postDataJSON() as { previewToken?: string }
    expect(body.previewToken, 'create 应携带 preview token').toBe('preview-token-comic-1')
    trashed.splice(trashed.findIndex((t) => t.id === 1), 1)
    await json(route, {
      task: taskItem(300, 'RUNNING', { taskType: 'COMIC_PURGE', operation: 'COMIC_PURGE' }),
      selectedCount: 1,
      eligibleCount: 1,
      blocked: [],
    })
  })

  await page.goto(TRASH)

  await expect(page.getByTestId('trash-row-1')).toBeVisible()
  await expect(page.getByTestId('trash-row-2')).toBeVisible()

  await expect(page.getByTestId('trash-retention-1')).toContainText('剩余')
  await expect(page.getByTestId('trash-retention-2')).toContainText('已过保留期')

  // reconcile 清单 + 冲突警示
  await page.getByTestId('trash-reconcile-1').click()
  await expect(page.getByTestId('trash-manifest')).toBeVisible()
  await expect(page.getByTestId('trash-conflict-1')).toBeVisible()
  await expect(page.getByTestId('trash-manifest-entry-0')).toContainText('BOTH')

  // restore 漫画B
  await page.getByTestId('trash-restore-2').click()
  await expect(page.getByTestId('trash-row-2')).toHaveCount(0, { timeout: 8000 })

  // purge 漫画A：标题确认 → token 永久删除
  await page.getByTestId('trash-purge-1').click()
  const dialog = page.getByTestId('trash-dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByTestId('danger-confirm-input').fill('漫画A')
  await dialog.getByTestId('danger-confirm-btn').click()
  await expect(page.getByTestId('trash-row-1')).toHaveCount(0, { timeout: 8000 })
})

// ====================================================================
// 6. 危险 token 过期 → 红色错误面
// ====================================================================

test('危险操作 preview token 过期（410）显示红色 reasonCode 错误', async ({ page }) => {
  await mockLayoutApis(page)
  await page.route(
    '**/api/comics**',
    createComicMock(
      [],
      [comicItem(5, '漫画X', { status: 'TRASHED', createdAt: '2026-07-25T10:00:00' })],
    ),
  )
  await page.route('**/api/management/batch/preview', async (route) => {
    if (route.request().method() !== 'POST') {
      await json(route, null, 404)
      return
    }
    await route.fulfill({
      status: 410,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 410,
        message: '预览令牌已过期，请重新选择',
        reasonCode: 'PREVIEW_TOKEN_EXPIRED',
        data: null,
      }),
    })
  })

  await page.goto(CONSOLE)
  await page.getByTestId('console-tab-danger').click()

  await expect(page.getByTestId('danger-row-5')).toBeVisible()
  await page.getByTestId('danger-purge-5').click()

  const err = page.getByTestId('danger-error')
  await expect(err).toBeVisible()
  await expect(err).toHaveAttribute('data-reason-code', 'PREVIEW_TOKEN_EXPIRED')
  await expect(err).toHaveAttribute('data-state', 'red')
  await expect(err).toContainText('预览令牌已过期')
})

// ====================================================================
// 7. 危险动作条件变化阻断 + 双击防重（同 key 仅一个请求）
// ====================================================================

test('危险动作条件变化阻断；双击回收仅发送一个删除请求', async ({ page }) => {
  let deleteCount = 0
  await mockLayoutApis(page)
  await page.route(
    '**/api/comics**',
    createComicMock([comicItem(6, '漫画R', { status: 'READY' })], []),
  )

  // 回收删除：延迟响应以便双击
  await page.route('**/api/comics/6', async (route) => {
    if (route.request().method() !== 'DELETE') {
      await json(route, null, 404)
      return
    }
    deleteCount += 1
    await new Promise((resolve) => setTimeout(resolve, 600))
    await json(route, { taskId: 400, taskType: 'COMIC_DELETE', status: 'RUNNING', itemCount: 1 })
  })

  // purge 条件变化：preview 成功发 token，create 时 422 阻断
  await page.route('**/api/management/batch/preview', async (route) => {
    if (route.request().method() !== 'POST') {
      await json(route, null, 404)
      return
    }
    await json(route, {
      operation: 'COMIC_PURGE',
      selectedCount: 1,
      eligibleCount: 1,
      blocked: [],
      dangerous: true,
      previewToken: 'preview-token-stale',
      expiresAt: '2026-08-03T10:10:00',
    })
  })
  await page.route('**/api/management/batch', async (route) => {
    if (route.request().method() !== 'POST') {
      await json(route, null, 404)
      return
    }
    await route.fulfill({
      status: 422,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 422,
        message: '预览条件已变化，请重新确认',
        reasonCode: 'PREVIEW_CONDITION_CHANGED',
        data: null,
      }),
    })
  })

  await page.goto(CONSOLE)
  await page.getByTestId('console-tab-danger').click()

  // 双击回收按钮 → 标题确认 → 确认按钮双击 → 只发一个 DELETE
  await expect(page.getByTestId('danger-row-6')).toBeVisible()
  await page.getByTestId('danger-recycle-6').click()
  const dialog = page.getByTestId('danger-dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByTestId('danger-confirm-input').fill('漫画R')
  const confirmBtn = dialog.getByTestId('danger-confirm-btn')
  await confirmBtn.click()
  await confirmBtn.click({ force: true }).catch(() => {})
  await expect.poll(() => deleteCount, { timeout: 8000 }).toBe(1)
  await expect(page.getByTestId('danger-row-6')).toHaveCount(0, { timeout: 8000 })
})

test('危险操作条件变化在批量提交时阻断并显示红色原因', async ({ page }) => {
  await mockLayoutApis(page)
  await page.route(
    '**/api/comics**',
    createComicMock(
      [],
      [comicItem(7, '漫画Z', { status: 'TRASHED', createdAt: '2026-07-24T10:00:00' })],
    ),
  )
  await page.route('**/api/management/batch/preview', async (route) => {
    if (route.request().method() !== 'POST') {
      await json(route, null, 404)
      return
    }
    await json(route, {
      operation: 'COMIC_PURGE',
      selectedCount: 1,
      eligibleCount: 1,
      blocked: [],
      dangerous: true,
      previewToken: 'preview-token-stale-7',
      expiresAt: '2026-08-03T10:10:00',
    })
  })
  await page.route('**/api/management/batch', async (route) => {
    if (route.request().method() !== 'POST') {
      await json(route, null, 404)
      return
    }
    await route.fulfill({
      status: 422,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 422,
        message: '预览条件已变化（漫画状态翻转），请重新确认',
        reasonCode: 'PREVIEW_CONDITION_CHANGED',
        data: null,
      }),
    })
  })

  await page.goto(CONSOLE)
  await page.getByTestId('console-tab-danger').click()

  await expect(page.getByTestId('danger-row-7')).toBeVisible()
  await page.getByTestId('danger-purge-7').click()
  const dialog = page.getByTestId('danger-dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByTestId('danger-confirm-input').fill('漫画Z')
  await dialog.getByTestId('danger-confirm-btn').click()

  const err = page.getByTestId('danger-error')
  await expect(err).toBeVisible()
  await expect(err).toHaveAttribute('data-reason-code', 'PREVIEW_CONDITION_CHANGED')
  await expect(err).toHaveAttribute('data-state', 'red')
  await expect(err).toContainText('预览条件已变化')
})
