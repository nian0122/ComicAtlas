import { expect, test, type Page, type Route } from '@playwright/test'

test.setTimeout(60_000)

/**
 * 导入目录规范化预览（Wave 4）：
 * - 扫描混合目录展示图片/视频/总媒体统计与可展开规范化树
 * - 含阻断 warning（UNREADABLE_DIRECTORY/LIMIT_EXCEEDED）的项禁止勾选
 * - 旧扫描结果（缺 preview/warnings）仍渲染简版，不报错
 * - 批量导入成功后以真实 batchId 跳转任务中心
 */

const scanTaskId = 1
const batchId = 'batch-test-123'

/** 新契约扫描结果：3 个候选（2 可导入 + 1 阻断），含 preview 树与 warning */
const scanResultFixture = {
  parentPath: 'F:/games/comics',
  total: 3,
  items: [
    {
      name: 'comic-ok',
      path: 'F:/games/comics/comic-ok',
      imageCount: 4,
      kind: 'COMIC',
      relativePath: 'comic-ok',
      warnings: [
        {
          code: 'MIXED_DIRECTORY',
          severity: 'WARNING',
          message: '目录同时包含图片与视频',
          relativePath: 'comic-ok',
        },
      ],
    },
    {
      name: 'comic-blocked',
      path: 'F:/games/comics/comic-blocked',
      imageCount: 0,
      kind: 'COMIC',
      relativePath: 'comic-blocked',
      warnings: [
        {
          code: 'UNREADABLE_DIRECTORY',
          severity: 'ERROR',
          message: '目录不可读',
          relativePath: 'comic-blocked',
        },
      ],
    },
    {
      name: 'comic-simple',
      path: 'F:/games/comics/comic-simple',
      imageCount: 2,
      kind: 'COMIC',
      relativePath: 'comic-simple',
      warnings: [],
    },
  ],
  preview: [
    {
      name: 'comic-ok',
      kind: 'COMIC',
      relativePath: 'comic-ok',
      fileCount: 5,
      children: [
        {
          name: 'vol1',
          kind: 'DIRECTORY',
          relativePath: 'comic-ok/vol1',
          fileCount: 3,
          children: [],
          warnings: [],
        },
        {
          name: 'vol2',
          kind: 'DIRECTORY',
          relativePath: 'comic-ok/vol2',
          fileCount: 1,
          children: [],
          warnings: [],
        },
      ],
      warnings: [
        {
          code: 'MIXED_DIRECTORY',
          severity: 'WARNING',
          message: '目录同时包含图片与视频',
          relativePath: 'comic-ok',
        },
      ],
    },
    {
      name: 'comic-blocked',
      kind: 'COMIC',
      relativePath: 'comic-blocked',
      fileCount: 0,
      children: [],
      warnings: [
        {
          code: 'UNREADABLE_DIRECTORY',
          severity: 'ERROR',
          message: '目录不可读',
          relativePath: 'comic-blocked',
        },
      ],
    },
    {
      name: 'comic-simple',
      kind: 'COMIC',
      relativePath: 'comic-simple',
      fileCount: 2,
      children: [],
      warnings: [],
    },
  ],
  warnings: [
    {
      code: 'SYMLINK_SKIPPED',
      severity: 'WARNING',
      message: '符号链接已跳过',
      relativePath: 'junk',
    },
  ],
}

/** 旧契约扫描结果：仅 name/path/imageCount，无任何附加字段 */
const legacyScanResultFixture = {
  parentPath: 'F:/games/legacy',
  total: 1,
  items: [{ name: 'old-comic', path: 'F:/games/legacy/old-comic', imageCount: 4 }],
}

const batchResultFixture = {
  batchId,
  total: 2,
  succeeded: [
    { id: 11, status: 'PENDING', sourcePath: 'F:/games/comics/comic-ok' },
    { id: 12, status: 'PENDING', sourcePath: 'F:/games/comics/comic-simple' },
  ],
  failed: [],
}

type ScanMode = 'new' | 'legacy' | 'fail-first'

/** 测试共享的扫描返回模式；beforeEach 重置，路由 handler 读取 */
let scanMode: ScanMode = 'new'

test.beforeEach(async () => {
  scanMode = 'new'
})

test('扫描混合目录：统计、规范化树、阻断项禁用、提交跳转 batchId', async ({ page }) => {
  await setScanMode(page, 'new')
  await openBatchPanel(page)

  await page.getByPlaceholder('F:/games/comics/...').fill('F:/games/comics')
  await page.getByRole('button', { name: '扫描' }).click()

  const results = page.locator('.scan-results')
  await expect(results).toBeVisible({ timeout: 10_000 })

  // 规范化统计：候选 3 / 图片 6 / 视频 1 / 媒体 7
  const stats = page.getByLabel('扫描统计')
  await expect(stats).toContainText('候选 3')
  await expect(stats).toContainText('图片 6')
  await expect(stats).toContainText('视频 1')
  await expect(stats).toContainText('媒体 7')

  // 扫描级警告摘要
  await expect(page.getByLabel('扫描警告')).toContainText('符号链接已跳过')

  // 阻断项禁用且点击不选中
  const blockedItem = page.locator('.scan-item', { hasText: 'comic-blocked' })
  await expect(blockedItem.locator('.el-checkbox input')).toBeDisabled()
  await expect(blockedItem).toContainText('不可导入：目录不可读')
  await blockedItem.click()
  await expect(blockedItem).not.toHaveClass(/selected/)
  await expect(page.getByText('已选 0 / 2 个可导入')).toBeVisible()

  // 非阻断项可选
  await page.locator('.scan-item', { hasText: 'comic-ok' }).locator('.el-checkbox').click()
  await expect(page.getByText('已选 1 / 2 个可导入')).toBeVisible()

  // 全选只选中可导入项
  await page.getByText('全选', { exact: true }).click()
  await expect(page.getByText('已选 2 / 2 个可导入')).toBeVisible()

  // 展开规范化预览树
  const okItem = page.locator('.scan-item', { hasText: 'comic-ok' })
  await okItem.getByRole('button', { name: '展开规范化预览' }).click()
  const tree = page.getByLabel('comic-ok 目录预览')
  await expect(tree).toBeVisible()
  await expect(tree).toContainText('vol1')
  await expect(tree).toContainText('vol2')
  await expect(tree).toContainText('5 个媒体')
  await expect(tree).toContainText('目录同时包含图片与视频')

  // 提交 → 携带真实 batchId 跳转任务中心
  await page.getByRole('button', { name: '确认导入 2 项' }).click()
  await expect(page).toHaveURL(new RegExp(`batchId=${batchId}`), { timeout: 10_000 })
  await expect(page.getByRole('heading', { name: '任务中心' })).toBeVisible()
})

test('旧扫描结果缺新字段时渲染简版，不报错', async ({ page }) => {
  await setScanMode(page, 'legacy')
  await openBatchPanel(page)

  await page.getByPlaceholder('F:/games/comics/...').fill('F:/games/legacy')
  await page.getByRole('button', { name: '扫描' }).click()

  const results = page.locator('.scan-results')
  await expect(results).toBeVisible({ timeout: 10_000 })

  // 无规范化统计行
  await expect(page.getByLabel('扫描统计')).toHaveCount(0)
  // 简版条目正常渲染且可勾选
  const legacyItem = page.locator('.scan-item', { hasText: 'old-comic' })
  await expect(legacyItem).toContainText('4 张图片')
  await expect(legacyItem.locator('.el-checkbox input')).toBeEnabled()
  await legacyItem.locator('.el-checkbox').click()
  await expect(page.getByText('已选 1 / 1 个可导入')).toBeVisible()

  // 页面无错误告警
  await expect(page.locator('.el-alert')).toHaveCount(0)
})

test('扫描失败可重试，阻断项保持禁用', async ({ page }) => {
  await setScanMode(page, 'fail-first')
  await openBatchPanel(page)

  await page.getByPlaceholder('F:/games/comics/...').fill('F:/games/comics')
  await page.getByRole('button', { name: '扫描' }).click()

  // 第一次扫描失败 → 错误提示 + 可重试
  await expect(page.locator('.el-alert')).toContainText('目录扫描失败', { timeout: 10_000 })

  // 重试成功
  await page.getByRole('button', { name: '扫描' }).click()
  const results = page.locator('.scan-results')
  await expect(results).toBeVisible({ timeout: 10_000 })

  const blockedItem = page.locator('.scan-item', { hasText: 'comic-blocked' })
  await expect(blockedItem.locator('.el-checkbox input')).toBeDisabled()
  await expect(page.getByLabel('扫描统计')).toContainText('候选 3')
})

async function openBatchPanel(page: Page) {
  await page.goto('/manage/import?force-desktop=1')
  await page.locator('.import-tab', { hasText: '批量导入' }).click()
}

async function setScanMode(page: Page, mode: ScanMode) {
  scanMode = mode
  await page.route('**/api/**', (route: Route) => handleApi(route))
}

async function handleApi(route: Route) {
  const request = route.request()
  const url = new URL(request.url())
  const path = url.pathname

  if (request.method() === 'POST' && path.endsWith('/tasks/directory-scan')) {
    await json(route, { id: scanTaskId, status: 'PENDING', directoryPath: 'F:/games/comics' })
    return
  }
  if (request.method() === 'GET' && path.includes('/tasks/directory-scan/')) {
    if (scanMode === 'fail-first') {
      scanMode = 'new'
      await json(route, {
        id: scanTaskId,
        status: 'FAILED',
        errorMessage: '目录扫描失败',
        directoryPath: 'F:/games/comics',
      })
      return
    }
    const result =
      scanMode === 'legacy' ? legacyScanResultFixture : scanResultFixture
    await json(route, {
      id: scanTaskId,
      status: 'SUCCESS',
      directoryPath: 'F:/games/comics',
      totalItems: result.total,
      result,
    })
    return
  }
  if (request.method() === 'POST' && path.endsWith('/tasks/import/batch')) {
    await json(route, batchResultFixture)
    return
  }
  if (request.method() === 'GET' && path.endsWith('/tasks/import')) {
    await json(route, { records: [], total: 0 })
    return
  }
  if (request.method() === 'GET' && path.endsWith('/tasks/recovery')) {
    await json(route, { records: [], total: 0 })
    return
  }
  await route.fulfill({ status: 404, body: '' })
}

async function json(route: Route, data: unknown) {
  await route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, message: 'success', data }),
  })
}
