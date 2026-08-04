import { expect, test, type Page, type Route } from '@playwright/test'

/**
 * 管理领域强类型 Store 契约测试（Mocked）
 *
 * 覆盖（对照 T17 契约）：
 * 1. 多个 task item 状态跨轮询实时更新，全部终态后停止轮询（无额外请求）
 * 2. unknown 枚举状态优雅降级：渲染“未知状态”回退标签且不崩溃、继续轮询
 * 3. 卸载/停止时 AbortController 取消在途请求（net::ERR_ABORTED），不展示错误态
 * 4. 后台标签页（document.hidden）降频为 10s，恢复可见后回到 2s
 * 5. 批量预览 410（过期 preview token）→ typed ApiError status=410 reasonCode=PREVIEW_TOKEN_EXPIRED
 * 6. 批量创建 409/422/500 → typed ApiError 的 status/reasonCode 逐次正确展示
 */

test.setTimeout(90_000)

const HARNESS = '/manage/task-store-harness?force-desktop=1'

// ====================================================================
// Mock 工具
// ====================================================================

/** 标准 Result<T> 包装 */
async function json(route: Route, data: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify({ code: status, message: status === 200 ? 'success' : 'error', data }),
  })
}

/** 空分页（让布局层 import/recovery bootstrap 安静） */
const EMPTY_PAGE = { records: [], total: 0, size: 20, current: 1, pages: 0 }

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

/** 按调用次数回放场景：超出最后一个 payload 时重复最后一个 */
function makeTaskListHandler(
  payloads: readonly Readonly<Record<string, unknown>>[],
  counter: { count: number },
  options?: { delayMs?: number },
) {
  return async (route: Route) => {
    const req = route.request()
    const url = new URL(req.url())
    const isList =
      req.method() === 'GET' &&
      url.pathname === '/api/management/tasks'
    if (!isList) {
      await json(route, null, 404)
      return
    }
    counter.count += 1
    const idx = Math.min(counter.count - 1, payloads.length - 1)
    const payload = payloads[idx] as Readonly<Record<string, unknown>>
    if (options?.delayMs) {
      await new Promise((resolve) => setTimeout(resolve, options.delayMs))
    }
    await json(route, { records: payload.items, total: payload.items.length, size: 20, current: 1, pages: 1 })
  }
}

/** 布局层 import/recovery bootstrap 静默 */
async function mockLayoutApis(page: Page): Promise<void> {
  await page.route('**/api/tasks/import*', async (route) => {
    await json(route, EMPTY_PAGE)
  })
  await page.route('**/api/tasks/recovery*', async (route) => {
    await json(route, EMPTY_PAGE)
  })
}

/** 模拟浏览器标签页可见性切换（覆盖 document.visibilityState/hidden 并派发事件） */
async function setPageVisibility(page: Page, state: 'hidden' | 'visible'): Promise<void> {
  await page.evaluate((next) => {
    const hidden = next === 'hidden'
    Object.defineProperty(document, 'visibilityState', { get: () => next, configurable: true })
    Object.defineProperty(document, 'hidden', { get: () => hidden, configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
  }, state)
}

async function taskStatusText(page: Page, id: number): Promise<string> {
  const loc = page.getByTestId(`task-item-${id}`).getByTestId('status')
  return (await loc.textContent())?.trim() ?? ''
}

// ====================================================================
// 1. 多任务实时更新 + 终态停止轮询
// ====================================================================

test('任务列表多状态实时更新且全部终态后停止轮询', async ({ page }) => {
  const counter = { count: 0 }
  const payloads = [
    {
      items: [
        taskItem(1, 'RUNNING'),
        taskItem(2, 'QUEUED'),
        taskItem(3, 'FAILED'),
      ],
    },
    {
      items: [
        taskItem(1, 'RUNNING'),
        taskItem(2, 'RUNNING'),
        taskItem(3, 'FAILED'),
      ],
    },
    {
      items: [
        taskItem(1, 'SUCCEEDED'),
        taskItem(2, 'FAILED'),
        taskItem(3, 'FAILED'),
      ],
    },
  ]
  await mockLayoutApis(page)
  await page.route('**/api/management/tasks**', makeTaskListHandler(payloads, counter))

  await page.goto(HARNESS)

  // 初始渲染：三个任务项
  await expect(page.getByTestId('task-item-1')).toBeVisible()
  await expect(page.getByTestId('task-item-2')).toBeVisible()
  await expect(page.getByTestId('task-item-3')).toBeVisible()

  // 先断言瞬态中间态（在终态出现前）：item2 QUEUED → RUNNING
  await expect.poll(async () => taskStatusText(page, 2), { timeout: 8000 }).toBe('RUNNING')
  // item2 RUNNING → FAILED（终态）
  await expect.poll(async () => taskStatusText(page, 2), { timeout: 8000 }).toBe('FAILED')
  // item1 RUNNING → SUCCEEDED（终态）
  await expect.poll(async () => taskStatusText(page, 1), { timeout: 8000 }).toBe('SUCCEEDED')
  // item3: 保持 FAILED
  expect(await taskStatusText(page, 3)).toBe('FAILED')

  // 轮询指示器最终回到 idle
  await expect
    .poll(() => page.getByTestId('polling-indicator').getAttribute('data-polling'), { timeout: 8000 })
    .toBe('false')

  // 终态后不再发出任何列表请求（等待超过一个可见 2s 周期）
  const countAfterTerminal = counter.count
  await page.waitForTimeout(2600)
  expect(counter.count, '终态后不应继续轮询').toBe(countAfterTerminal)
})

// ====================================================================
// 2. unknown 枚举状态优雅降级
// ====================================================================

test('unknown 枚举状态渲染未知回退标签且继续轮询', async ({ page }) => {
  const counter = { count: 0 }
  const payloads = [
    { items: [taskItem(1, 'BOGUS_STATUS', { taskType: 'BOGUS_TYPE' })] },
  ]
  await mockLayoutApis(page)
  await page.route('**/api/management/tasks**', makeTaskListHandler(payloads, counter))

  await page.goto(HARNESS)

  await expect(page.getByTestId('task-item-1')).toBeVisible()
  const statusText = await taskStatusText(page, 1)
  expect(statusText, 'unknown 状态应显示未知回退标签').toContain('未知状态')
  expect(statusText, 'unknown 状态应保留原始值').toContain('BOGUS_STATUS')
  expect(await page.getByTestId('polling-indicator').getAttribute('data-polling')).toBe('true')

  // unknown 视为非终态 → 继续轮询
  const countBefore = counter.count
  await page.waitForTimeout(2600)
  expect(counter.count, 'unknown 状态应继续轮询').toBeGreaterThan(countBefore)
})

// ====================================================================
// 3. AbortController 取消在途请求（net::ERR_ABORTED）
// ====================================================================

test('停止轮询取消在途请求且不展示错误态', async ({ page }) => {
  const counter = { count: 0 }
  const aborted: string[] = []
  page.on('requestfailed', (req) => {
    const failure = req.failure()
    if (failure) aborted.push(`${req.url()}::${failure.errorText}`)
  })

  // 首次列表请求挂起 4s：确保点击“停止”时请求仍在途
  await mockLayoutApis(page)
  await page.route(
    '**/api/management/tasks**',
    makeTaskListHandler([{ items: [taskItem(1, 'RUNNING')] }], counter, { delayMs: 4000 }),
  )

  await page.goto(HARNESS)
  await page.waitForTimeout(400) // 确保初次请求已发出并在途

  await page.getByTestId('stop-polling').click()

  // 在途请求应被中止为 net::ERR_ABORTED
  await expect
    .poll(() => aborted.some((a) => a.includes('/api/management/tasks') && a.includes('ERR_ABORTED')), {
      timeout: 3000,
    })
    .toBe(true)

  // 中止不产生错误态，轮询指示器回到 idle
  await expect(page.getByTestId('poll-error')).toHaveCount(0)
  await expect(page.getByTestId('polling-indicator')).toHaveAttribute('data-polling', 'false')

  // 不再发起新请求
  const countAfterStop = counter.count
  await page.waitForTimeout(2600)
  expect(counter.count).toBe(countAfterStop)
})

// ====================================================================
// 4. 后台标签页降频 10s
// ====================================================================

test('后台标签页降频为 10s，恢复可见后回到 2s', async ({ page }) => {
  const counter = { count: 0 }
  await mockLayoutApis(page)
  await page.route(
    '**/api/management/tasks**',
    makeTaskListHandler([{ items: [taskItem(1, 'RUNNING')] }], counter),
  )

  await page.goto(HARNESS)
  await expect(page.getByTestId('task-item-1')).toBeVisible()
  // 确认可见期轮询进行中
  await expect(page.getByTestId('polling-indicator')).toHaveAttribute('data-polling', 'true')

  // 切到后台
  await setPageVisibility(page, 'hidden')
  await page.waitForTimeout(300) // 让 visibilitychange 生效并重排期

  const countAtHide = counter.count
  // 后台 10s 间隔：3.5s 内不应有任何新轮询
  await page.waitForTimeout(3500)
  expect(counter.count, '后台应以 10s 间隔轮询（3.5s 内无请求）').toBe(countAtHide)

  // 恢复可见 → 2s 间隔恢复轮询
  await setPageVisibility(page, 'visible')
  await expect
    .poll(() => counter.count, { timeout: 6000 })
    .toBeGreaterThan(countAtHide)
})

// ====================================================================
// 5. 批量预览 410（过期 preview token）
// ====================================================================

test('批量预览 410 过期令牌返回 typed ApiError（reasonCode=PREVIEW_TOKEN_EXPIRED）', async ({ page }) => {
  await mockLayoutApis(page)
  // 终态任务让列表稳定渲染，轮询立即停止，不干扰批量操作
  const counter = { count: 0 }
  await page.route(
    '**/api/management/tasks**',
    makeTaskListHandler([{ items: [taskItem(1, 'SUCCEEDED')] }], counter),
  )
  await page.route('**/api/management/batch/preview', async (route) => {
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

  await page.goto(HARNESS)
  await expect(page.getByTestId('task-item-1')).toBeVisible()

  await page.getByTestId('batch-preview').click()

  const err = page.getByTestId('batch-error')
  await expect(err).toBeVisible()
  await expect(err).toHaveAttribute('data-status', '410')
  await expect(err).toHaveAttribute('data-reason-code', 'PREVIEW_TOKEN_EXPIRED')
  await expect(err).toContainText('预览令牌已过期')
})

// ====================================================================
// 6. 批量创建 409/422/500 typed error 逐次正确展示
// ====================================================================

test('批量创建 409/422/500 的 typed ApiError status/reasonCode 逐次正确展示', async ({ page }) => {
  await mockLayoutApis(page)
  const counter = { count: 0 }
  await page.route(
    '**/api/management/tasks**',
    makeTaskListHandler([{ items: [taskItem(1, 'SUCCEEDED')] }], counter),
  )

  const sequence = [
    { status: 409, code: 409, message: '幂等冲突：相同操作正在执行', reasonCode: 'IDEMPOTENCY_CONFLICT' },
    { status: 422, code: 422, message: '预览条件已变化，请重新预览', reasonCode: 'PREVIEW_CONDITION_CHANGED' },
    { status: 500, code: 500, message: '服务器内部错误' },
  ] as const
  let call = 0
  await page.route('**/api/management/batch', async (route) => {
    if (route.request().method() !== 'POST') {
      await json(route, null, 404)
      return
    }
    const entry = sequence[Math.min(call, sequence.length - 1)] as (typeof sequence)[number]
    call += 1
    await route.fulfill({
      status: entry.status,
      contentType: 'application/json',
      body: JSON.stringify({
        code: entry.code,
        message: entry.message,
        reasonCode: entry.reasonCode ?? null,
        data: null,
      }),
    })
  })

  await page.goto(HARNESS)
  await expect(page.getByTestId('task-item-1')).toBeVisible()

  const createBtn = page.getByTestId('batch-create')
  const err = page.getByTestId('batch-error')

  // 409
  await createBtn.click()
  await expect(err).toHaveAttribute('data-status', '409')
  await expect(err).toHaveAttribute('data-reason-code', 'IDEMPOTENCY_CONFLICT')
  await expect(err).toContainText('幂等冲突')

  // 422
  await createBtn.click()
  await expect(err).toHaveAttribute('data-status', '422')
  await expect(err).toHaveAttribute('data-reason-code', 'PREVIEW_CONDITION_CHANGED')
  await expect(err).toContainText('预览条件已变化')

  // 500（无 reasonCode → 空）
  await createBtn.click()
  await expect(err).toHaveAttribute('data-status', '500')
  await expect(err).toHaveAttribute('data-reason-code', '')
  await expect(err).toContainText('服务器内部错误')
})
