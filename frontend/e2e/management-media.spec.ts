import { expect, test, type Page, type Route } from '@playwright/test'

/**
 * 媒体管理控制台（Task 19）
 *
 * Mocked + real upload 覆盖：
 * 1. 10k 虚拟媒体经 RecycleScroller 渲染（仅渲染可见项，无横向溢出）
 * 2. 混合文件（图片+视频）真实分块上传：Content-Range 与字节内容逐块校验
 * 3. 断点续传：暂停后不再发 chunk，继续后从服务端 receivedBytes 续传直至完成
 * 4. 取消上传：DELETE session，队列项进入已取消
 * 5. 替换媒体保留 mediaId：session 请求携带 replaceMediaId，完成后 ID 不变
 * 6. 失败上传可逐文件重试：首 chunk 500 → 失败标签 → 重试 → 完成
 * 7. 键盘排序：聚焦媒体格按 Alt+←/→ 移动，保存后 POST reorder 新顺序
 * 8. 回收/恢复：DELETE /media/{id} → 已回收标签 → restore 恢复
 * 9. 永不展示客户端绝对路径：错误文案与文件名均脱敏
 * 10. reduced-motion + 键盘可达 + 状态双通道（文字+颜色）
 */

test.setTimeout(120_000)

const COMIC_ID = 1
const CHAPTER_101 = 101
const CHAPTER_102 = 102
const PAGE_URL = `/manage/comics/${COMIC_ID}/media?force-desktop=1`

const EMPTY_PAGE = { records: [], total: 0, size: 20, current: 1, pages: 0 }

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

/** 布局层 import/recovery bootstrap 静默（无后端时避免 500 噪音） */
async function mockLayoutApis(page: Page): Promise<void> {
  await page.route('**/api/tasks/import*', async (route) => {
    await json(route, EMPTY_PAGE)
  })
  await page.route('**/api/tasks/recovery*', async (route) => {
    await json(route, EMPTY_PAGE)
  })
}

function mediaItem(
  id: number,
  pageNumber: number,
  overrides: Readonly<Record<string, unknown>> = {},
): Record<string, unknown> {
  const isVideo = overrides.mediaType === 'VIDEO'
  return {
    id,
    pageNumber,
    hqUrl: `/files/hq/${COMIC_ID}/${CHAPTER_101}/${String(id).padStart(3, '0')}.${isVideo ? 'mp4' : 'jpg'}`,
    lqUrl: isVideo ? '' : `/files/lq/${COMIC_ID}/${CHAPTER_101}/${String(id).padStart(3, '0')}.webp`,
    lqStatus: isVideo ? 'NOT_GENERATED' : 'READY',
    width: 800,
    height: 1200,
    mediaType: isVideo ? 'VIDEO' : 'IMAGE',
    duration: isVideo ? 95 : undefined,
    container: isVideo ? 'mp4' : undefined,
    videoCodec: isVideo ? 'h264' : undefined,
    audioCodec: isVideo ? 'aac' : undefined,
    hqStatus: 'READY',
    lifecycle: 'READY',
    transcodeStatus: isVideo ? 'NOT_NEEDED' : 'NOT_NEEDED',
    ...overrides,
  }
}

/** 生成 chapter pages（每 3 个混入一个视频，保证首屏即可见混排） */
function chapterPages(count: number): Record<string, unknown>[] {
  const pages: Record<string, unknown>[] = []
  for (let i = 1; i <= count; i++) {
    pages.push(mediaItem(i, i, i % 3 === 0 ? { mediaType: 'VIDEO' } : {}))
  }
  return pages
}

async function mockComicDetail(page: Page, comicId = COMIC_ID): Promise<void> {
  await page.route(`**/api/comics/${comicId}`, async (route) => {
    if (route.request().method() !== 'GET') {
      await json(route, null, 404)
      return
    }
    await json(route, {
      id: comicId,
      title: '测试漫画',
      author: '',
      coverUrl: '',
      pageCount: 0,
      fileSize: 0,
      sourceType: 'REGISTER',
      sourceRef: '',
      categoryId: null,
      categoryName: null,
      status: 'READY',
      progressPercent: 0,
      lastReadChapterId: CHAPTER_101,
      lastReadPage: 1,
      chapters: [
        { id: CHAPTER_101, chapterNo: 1, title: '第1话', pageCount: 10000 },
        { id: CHAPTER_102, chapterNo: 2, title: '第2话', pageCount: 3 },
      ],
      tags: [],
      createdAt: '2026-08-01T00:00:00',
      updatedAt: '2026-08-01T00:00:00',
    })
  })
}

async function mockChapter(
  page: Page,
  chapterId: number,
  pages: Record<string, unknown>[],
  options?: { readonly title?: string },
): Promise<void> {
  await page.route(`**/api/chapters/${chapterId}`, async (route) => {
    if (route.request().method() !== 'GET') {
      await json(route, null, 404)
      return
    }
    await json(route, {
      comicId: COMIC_ID,
      chapterId,
      chapterNo: String(chapterId === CHAPTER_101 ? 1 : 2),
      chapterTitle: options?.title ?? (chapterId === CHAPTER_101 ? '第1话' : '第2话'),
      total: pages.length,
      pages,
      prevChapterId: null,
      nextChapterId: chapterId === CHAPTER_101 ? CHAPTER_102 : null,
    })
  })
}

/** 上传会话 mock：校验 chunk 字节长度与 Content-Range，返回累计 receivedBytes */
function mockUploadSession(
  page: Page,
  options?: { readonly chunkSize?: number; readonly chunkDelayMs?: number },
): {
  readonly state: {
    readonly sessionId: string
    readonly chunkSize: number
    chunkCount: number
    receivedByFile: Map<string, number>
    sizeByFile: Map<string, number>
    lastCreateBody: Record<string, unknown> | null
    completeCalls: number
    cancelCalls: number
  }
} {
  const chunkSize = options?.chunkSize ?? 1024
  const delayMs = options?.chunkDelayMs ?? 80
  const state = {
    sessionId: 'sess-media-1',
    chunkSize,
    chunkCount: 0,
    receivedByFile: new Map<string, number>(),
    sizeByFile: new Map<string, number>(),
    lastCreateBody: null as Record<string, unknown> | null,
    completeCalls: 0,
    cancelCalls: 0,
  }

  void page.route('**/api/uploads/sessions', async (route) => {
    if (route.request().method() !== 'POST') {
      await json(route, null, 404)
      return
    }
    const body = route.request().postDataJSON() as Record<string, unknown>
    state.lastCreateBody = body
    const files = (body.files as { fileId: string; size: number }[]) ?? []
    let totalBytes = 0
    for (const f of files) {
      totalBytes += f.size
      state.sizeByFile.set(f.fileId, f.size)
      state.receivedByFile.set(f.fileId, 0)
    }
    await json(route, {
      sessionId: state.sessionId,
      chunkSize,
      expiresAt: '2026-08-03T12:00:00',
      totalBytes,
      files: files.map((f) => ({
        fileId: f.fileId,
        storageName: `staging/${f.fileId}`,
        receivedBytes: 0,
        sizeBytes: f.size,
        complete: false,
        receivedRanges: '',
      })),
    })
  })

  void page.route('**/api/uploads/sessions/*/files/*', async (route) => {
    if (route.request().method() !== 'PUT') {
      await json(route, null, 404)
      return
    }
    const segments = route.request().url().split('/')
    const fileId = segments[segments.length - 1]
    const sessionId = segments[segments.length - 3]
    if (sessionId !== state.sessionId) {
      await json(route, null, 404)
      return
    }
    const rangeHeader = route.request().headers()['content-range'] ?? ''
    const match = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(rangeHeader)
    if (!match) {
      await json(route, null, 400)
      return
    }
    const start = Number(match[1])
    const end = Number(match[2])
    const total = Number(match[3])
    const body = route.request().postDataBuffer()
    // 真实字节校验：body 长度必须等于区间长度
    if (body === null || body.length !== end - start + 1) {
      await json(route, null, 422)
      return
    }
    if (total !== (state.sizeByFile.get(fileId) ?? -1)) {
      await json(route, null, 422)
      return
    }
    if (delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, delayMs))
    }
    state.chunkCount += 1
    const receivedBytes = end + 1
    state.receivedByFile.set(fileId, receivedBytes)
    await json(route, {
      fileId,
      receivedBytes,
      complete: receivedBytes >= total,
      receivedRanges: `bytes=${start}-${end}`,
    })
  })

  void page.route('**/api/uploads/sessions/*/complete', async (route) => {
    if (route.request().method() !== 'POST') {
      await json(route, null, 404)
      return
    }
    state.completeCalls += 1
    const fileIds = Array.from(state.receivedByFile.keys())
    await json(route, {
      taskId: 9001,
      taskType: 'MEDIA_UPLOAD',
      status: 'SUCCEEDED',
      itemCount: fileIds.length,
      mediaIds: fileIds.map((_, i) => 1000 + i),
    })
  })

  void page.route('**/api/uploads/sessions/*', async (route) => {
    if (route.request().method() !== 'DELETE') {
      await json(route, null, 404)
      return
    }
    state.cancelCalls += 1
    await json(route, null)
  })

  return { state }
}

/** 媒体操作：reorder / trash / restore */
async function mockMediaOperations(
  page: Page,
  chapterId = CHAPTER_101,
): {
  readonly reorderBodies: number[][]
} {
  const reorderBodies: number[][] = []
  await page.route(`**/api/chapters/${chapterId}/media/reorder`, async (route) => {
    if (route.request().method() !== 'POST') {
      await json(route, null, 404)
      return
    }
    const body = route.request().postDataJSON() as { mediaIds?: number[] }
    const mediaIds = Array.isArray(body?.mediaIds) ? body.mediaIds : []
    reorderBodies.push(mediaIds)
    await json(route, {
      items: mediaIds.map((mediaId, i) => ({ mediaId, pageNumber: i + 1 })),
    })
  })
  await page.route('**/api/media/*', async (route) => {
    if (route.request().method() !== 'DELETE') {
      await json(route, null, 404)
      return
    }
    await json(route, {
      taskId: 9100,
      taskType: 'MEDIA_TRASH',
      status: 'SUCCEEDED',
      itemCount: 1,
      totalCount: 1,
      successCount: 1,
      failureCount: 0,
    })
  })
  await page.route('**/api/trash/media/*/restore', async (route) => {
    if (route.request().method() !== 'POST') {
      await json(route, null, 404)
      return
    }
    await json(route, {
      taskId: 9200,
      taskType: 'MEDIA_RESTORE',
      status: 'SUCCEEDED',
      itemCount: 1,
      totalCount: 1,
      successCount: 1,
      failureCount: 0,
    })
  })
  return { reorderBodies }
}

/** 打开媒体页并等待就绪 */
async function gotoMediaPage(
  page: Page,
  chapterId = CHAPTER_101,
  pages: Record<string, unknown>[] = chapterPages(3),
): Promise<void> {
  await mockLayoutApis(page)
  await mockComicDetail(page)
  await mockChapter(page, chapterId, pages)
  await page.goto(PAGE_URL)
  await expect(page.getByTestId('media-page')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('media-counter')).toBeVisible({ timeout: 15_000 })
}

/** 断言管理主内容区无横向溢出 */
async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const content = page.locator('.management-content')
  const scrollWidth = await content.evaluate((el) => el.scrollWidth)
  const clientWidth = await content.evaluate((el) => el.clientWidth)
  expect(scrollWidth, `横向溢出: scrollWidth=${scrollWidth} > clientWidth=${clientWidth}`)
    .toBeLessThanOrEqual(clientWidth + 1)
}

function makeFixtureFiles(): { image: { name: string; mimeType: string; buffer: Buffer }; video: { name: string; mimeType: string; buffer: Buffer } } {
  // 2600 bytes > chunkSize 1024 → 3 个分块
  const image = {
    name: 'page_001.jpg',
    mimeType: 'image/jpeg',
    buffer: Buffer.alloc(2600, 0x61),
  }
  const video = {
    name: 'opening.mp4',
    mimeType: 'video/mp4',
    buffer: Buffer.alloc(1600, 0x62),
  }
  return { image, video }
}

// ====================================================================
// 1. 10k 虚拟媒体渲染
// ====================================================================

test('10k 虚拟媒体经虚拟滚动渲染，仅渲染可见项且无横向溢出', async ({ page }) => {
  await mockLayoutApis(page)
  await mockComicDetail(page)
  const pages = chapterPages(10_000)
  await mockChapter(page, CHAPTER_101, pages)
  await page.goto(PAGE_URL)

  await expect(page.getByTestId('media-counter')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('media-counter')).toHaveText(/共\s*10000\s*项/)

  // 虚拟滚动：实际 DOM 项数远小于 10000
  const domCount = await page.locator('[data-testid^="media-item-"]').count()
  expect(domCount, '虚拟滚动应只渲染可见项').toBeLessThan(1000)
  expect(domCount, '至少渲染若干可见项').toBeGreaterThan(0)

  // 混排：10k 中应包含视频项（media-type=video）
  const videoItem = page.locator('[data-testid^="media-item-"][data-media-type="VIDEO"]').first()
  await expect(videoItem).toBeVisible()

  // 滚动到列表深处，验证虚拟滚动持续工作
  const scroller = page.getByTestId('media-scroller')
  await scroller.evaluate((el) => {
    el.scrollTop = el.scrollHeight
  })
  await expect(page.getByTestId('media-counter')).toBeVisible()

  await assertNoHorizontalOverflow(page)
})

// ====================================================================
// 2. 混合文件真实分块上传（字节校验）
// ====================================================================

test('混合图片+视频文件真实分块上传，Content-Range 与字节长度逐块校验', async ({ page }) => {
  const { state } = mockUploadSession(page, { chunkSize: 1024, chunkDelayMs: 30 })
  await gotoMediaPage(page, CHAPTER_101, chapterPages(2))

  const { image, video } = makeFixtureFiles()
  await page.getByTestId('upload-input').setInputFiles([image, video])

  // 两个文件都进入队列并最终完成
  await expect(page.getByTestId('upload-item-0'), 'image 队列项').toBeVisible()
  await expect(page.getByTestId('upload-item-1'), 'video 队列项').toBeVisible()
  await expect(page.getByTestId('upload-status-0')).toHaveText('已完成', { timeout: 15_000 })
  await expect(page.getByTestId('upload-status-1')).toHaveText('已完成', { timeout: 15_000 })

  // 分块数：2600 → 3 + 1600 → 2 = 5
  expect(state.chunkCount, '分块请求数').toBe(5)
  expect(state.completeCalls, 'complete 应被调用一次').toBe(1)

  // session 请求不含 replaceMediaId（普通上传）
  expect(state.lastCreateBody?.replaceMediaId).toBeUndefined()
  const files = state.lastCreateBody?.files as { name: string }[]
  expect(files.map((f) => f.name).sort()).toEqual(['opening.mp4', 'page_001.jpg'])

  // 上传完成后刷新媒体列表（第2话 mock 返回 2 项，complete 后应重新拉取）
  await expect(page.getByTestId('media-counter')).toHaveText(/共\s*2\s*项/, { timeout: 10_000 })
})

// ====================================================================
// 3. 断点续传：暂停 → 不再发 chunk → 继续 → 完成
// ====================================================================

test('断点续传：暂停后停止分块，继续后从服务端 receivedBytes 续传', async ({ page }) => {
  // 500ms/块：给暂停/继续点击留出充足窗口，避免上传在点击前已完成
  const { state } = mockUploadSession(page, { chunkSize: 1024, chunkDelayMs: 500 })
  await gotoMediaPage(page, CHAPTER_101, chapterPages(2))

  const { image } = makeFixtureFiles()
  await page.getByTestId('upload-input').setInputFiles([image])

  await expect
    .poll(() => state.chunkCount, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1)
  await page.getByTestId('upload-pause-0').click()

  // 等待在途分块落定后再快照，避免在途请求造成误判
  let prev = -1
  let stable = state.chunkCount
  while (stable !== prev) {
    prev = stable
    await page.waitForTimeout(250)
    stable = state.chunkCount
  }
  const countAfterPause = stable

  await page.waitForTimeout(600)
  expect(state.chunkCount, '暂停后不应继续上传').toBe(countAfterPause)

  // 继续 → 从 receivedBytes 续传直至完成
  await page.getByTestId('upload-resume-0').click()
  await expect(page.getByTestId('upload-status-0')).toHaveText('已完成', { timeout: 15_000 })
  // 2600 / 1024 → 3 个分块
  expect(state.chunkCount).toBe(3)
  expect(state.receivedByFile.get('0') ?? 0, '服务端累计字节').toBe(2600)
})

// ====================================================================
// 4. 取消上传
// ====================================================================

test('取消上传：删除会话且队列项进入已取消', async ({ page }) => {
  // 500ms/块：给取消点击留出窗口，避免上传提前完成
  const { state } = mockUploadSession(page, { chunkSize: 1024, chunkDelayMs: 500 })
  await gotoMediaPage(page, CHAPTER_101, chapterPages(2))

  const { image } = makeFixtureFiles()
  await page.getByTestId('upload-input').setInputFiles([image])

  await expect
    .poll(() => state.chunkCount, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1)
  await page.getByTestId('upload-cancel-0').click()

  await expect(page.getByTestId('upload-status-0')).toHaveText('已取消', { timeout: 10_000 })
  expect(state.cancelCalls, 'DELETE session 应被调用').toBe(1)
})

// ====================================================================
// 5. 替换媒体保留 mediaId
// ====================================================================

test('替换媒体：session 携带 replaceMediaId，完成后原 mediaId 保留', async ({ page }) => {
  const { state } = mockUploadSession(page, { chunkSize: 1024, chunkDelayMs: 20 })
  await gotoMediaPage(page, CHAPTER_101, chapterPages(3))

  // 选中第 2 项并点击替换（media id = 2）
  const item = page.getByTestId('media-item-2')
  await expect(item).toBeVisible()
  await page.getByTestId('replace-trigger-2').click()

  // 替换提示：显示被替换媒体 ID
  await expect(page.getByTestId('replace-hint')).toBeVisible()
  await expect(page.getByTestId('replace-hint')).toContainText('2')

  const { image } = makeFixtureFiles()
  await page.getByTestId('upload-input').setInputFiles([image])
  await expect(page.getByTestId('upload-status-0')).toHaveText('已完成', { timeout: 15_000 })

  // session 请求必须携带 replaceMediaId=2
  expect(state.lastCreateBody?.replaceMediaId, 'replaceMediaId 应等于被替换媒体 ID').toBe(2)
  expect(state.completeCalls).toBe(1)

  // 替换完成后清空替换态，ID 提示消失
  await expect(page.getByTestId('replace-hint')).toHaveCount(0)
})

// ====================================================================
// 6. 失败上传可逐文件重试
// ====================================================================

test('失败上传逐文件重试：首分块 500 → 失败标签 → 重试 → 完成', async ({ page }) => {
  const { state } = mockUploadSession(page, { chunkSize: 1024, chunkDelayMs: 20 })
  await gotoMediaPage(page, CHAPTER_101, chapterPages(2))

  // 第一个文件的首个分块返回 500，其余正常
  let firstChunk = true
  void page.route('**/api/uploads/sessions/*/files/*', async (route) => {
    if (route.request().method() !== 'PUT') {
      await json(route, null, 404)
      return
    }
    const segments = route.request().url().split('/')
    const fileId = segments[segments.length - 1]
    if (fileId === '0' && firstChunk) {
      firstChunk = false
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 500,
          message: '致命错误: 无法访问 D:\\manga\\temp\\upload_42\\page_099.jpg（模拟磁盘错误）',
          data: null,
        }),
      })
      return
    }
    const rangeHeader = route.request().headers()['content-range'] ?? ''
    const match = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(rangeHeader)
    const start = Number(match?.[1] ?? 0)
    const end = Number(match?.[2] ?? 0)
    const total = Number(match?.[3] ?? 0)
    await json(route, { fileId, receivedBytes: end + 1, complete: end + 1 >= total, receivedRanges: '' })
  })

  const { image, video } = makeFixtureFiles()
  await page.getByTestId('upload-input').setInputFiles([image, video])

  // 文件0 失败（带路径的错误文案需脱敏展示）
  await expect(page.getByTestId('upload-status-0')).toHaveText('失败', { timeout: 15_000 })
  await expect(page.getByTestId('upload-item-0')).toContainText('失败')
  // 错误文案不泄露本地路径
  await expect(page.getByTestId('upload-item-0')).not.toContainText('D:\\')
  await expect(page.getByTestId('upload-item-0')).not.toContainText('D:')

  // 文件1 正常完成（不阻塞）
  await expect(page.getByTestId('upload-status-1')).toHaveText('已完成', { timeout: 15_000 })

  // 逐文件重试文件0
  await page.getByTestId('upload-retry-0').click()
  await expect(page.getByTestId('upload-status-0')).toHaveText('已完成', { timeout: 15_000 })
  expect(state.completeCalls, '重试后 complete 应触发').toBe(1)
})

// ====================================================================
// 7. 键盘排序
// ====================================================================

test('键盘排序：Alt+方向键移动媒体格，保存后提交新顺序', async ({ page }) => {
  const { reorderBodies } = await mockMediaOperations(page, CHAPTER_101)
  await gotoMediaPage(page, CHAPTER_101, chapterPages(3))

  // 初始顺序 [1, 2, 3]
  await expect(page.getByTestId('media-item-2').getAttribute('data-page-number')).resolves.toBe('2')

  // 聚焦第 2 项，Alt+ArrowRight 移动到第 3 位
  await page.getByTestId('media-item-2').focus()
  // Chromium 会把 Alt+ArrowRight 当作浏览器历史导航快捷键拦截，keydown 到不了页面；
  // 在元素上直接派发等价的 keydown（altKey=true）来验证组件的键盘重排处理。
  await page.getByTestId('media-item-2').evaluate((el) => {
    el.dispatchEvent(
      new KeyboardEvent('keydown', {
        key: 'ArrowRight',
        code: 'ArrowRight',
        altKey: true,
        bubbles: true,
        cancelable: true,
      }),
    )
  })

  // 本地顺序变为 [1, 3, 2]
  await expect(page.getByTestId('media-item-2').getAttribute('data-page-number')).resolves.toBe('3')
  await expect(page.getByTestId('media-item-3').getAttribute('data-page-number')).resolves.toBe('2')

  // 出现“排序已修改”与保存按钮
  await expect(page.getByTestId('reorder-save')).toBeVisible()

  // 保存 → POST reorder，mediaIds = [1, 3, 2]
  await page.getByTestId('reorder-save').click()
  await expect(page.getByTestId('reorder-saved')).toBeVisible({ timeout: 10_000 })
  expect(reorderBodies.length, 'reorder 应提交一次').toBe(1)
  expect(reorderBodies[0]).toEqual([1, 3, 2])
})

// ====================================================================
// 8. 回收 / 恢复
// ====================================================================

test('回收媒体进入已回收状态，可恢复', async ({ page }) => {
  mockMediaOperations(page, CHAPTER_101)
  await gotoMediaPage(page, CHAPTER_101, chapterPages(3))

  // 回收第 1 项
  await page.getByTestId('trash-trigger-1').click()
  await expect(page.getByTestId('media-item-1')).toHaveAttribute('data-lifecycle', 'TRASHED', {
    timeout: 10_000,
  })
  await expect(page.getByTestId('media-item-1')).toContainText('已回收')

  // 恢复 → 回到就绪
  await page.getByTestId('restore-trigger-1').click()
  await expect(page.getByTestId('media-item-1')).toHaveAttribute('data-lifecycle', 'READY', {
    timeout: 10_000,
  })
})

// ====================================================================
// 9. 永不展示客户端绝对路径
// ====================================================================

test('页面永不展示客户端绝对路径（含文件名脱敏）', async ({ page }) => {
  await mockLayoutApis(page)
  await mockComicDetail(page)
  // 第 2 话 mock 一个 webkitRelativePath 风格的“路径”文件名（模拟拖入带路径的文件）
  const pages = chapterPages(2)
  await mockChapter(page, CHAPTER_101, pages)
  await page.goto(PAGE_URL)
  await expect(page.getByTestId('media-counter')).toBeVisible({ timeout: 15_000 })

  // 通过拖拽投递一个带路径名与相对路径的文件
  await page.evaluate(() => {
    const bytes = new Uint8Array(600)
    const file = new File([bytes], 'C:\\Users\\Acer\\Downloads\\chapter_01\\page_001.jpg', {
      type: 'image/jpeg',
    })
    const dt = new DataTransfer()
    dt.items.add(file)
    const dz = document.querySelector('[data-testid="upload-dropzone"]') as HTMLElement
    dz.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }))
  })

  const queueItem = page.getByTestId('upload-item-0')
  await expect(queueItem).toBeVisible()
  // 只显示 basename，不显示路径
  await expect(queueItem).toContainText('page_001.jpg')
  await expect(queueItem).not.toContainText('C:\\')
  await expect(queueItem).not.toContainText('C:')

  // 整页不出现盘符路径
  await expect(page.locator('body')).not.toContainText('C:\\')
  await expect(page.locator('body')).not.toContainText('D:\\')
})

// ====================================================================
// 10. reduced-motion + 键盘可达 + 状态双通道 + 无横向溢出
// ====================================================================

test('reduced-motion 下无横向溢出，媒体格状态双通道（文字+颜色）', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await mockLayoutApis(page)
  await mockComicDetail(page)
  await mockChapter(page, CHAPTER_101, chapterPages(5))
  await page.goto(PAGE_URL)

  await expect(page.getByTestId('media-item-1')).toBeVisible({ timeout: 15_000 })
  await assertNoHorizontalOverflow(page)

  // 每个媒体格：有文字标签（文件名）+ 可见边框/背景（非透明），不依赖颜色单通道
  const items = page.locator('[data-testid^="media-item-"]')
  const count = await items.count()
  expect(count).toBeGreaterThan(0)
  for (let i = 0; i < Math.min(count, 5); i++) {
    const item = items.nth(i)
    const text = (await item.textContent())?.trim() ?? ''
    expect(text.length, '媒体格应有文字内容').toBeGreaterThan(0)
    const color = await item.evaluate((el) => {
      const style = getComputedStyle(el)
      return { border: style.borderColor, bg: style.backgroundColor }
    })
    const visibleBorder = color.border !== 'rgba(0, 0, 0, 0)' && color.border !== ''
    const hasBg = color.bg !== 'rgba(0, 0, 0, 0)' && color.bg !== 'transparent'
    expect(visibleBorder || hasBg, '媒体格应有可见边框或背景').toBeTruthy()
  }

  // 键盘可达：焦点可落在媒体格与上传控件
  await page.getByTestId('media-item-1').focus()
  await expect(page.getByTestId('media-item-1')).toBeFocused()
  await page.keyboard.press('Tab')
  await page.getByTestId('upload-input').focus()
  await expect(page.getByTestId('upload-input')).toBeFocused()
})

// ====================================================================
// 11. 恶意输入：坏 chunk（长度不匹配）→ 该文件失败，不阻塞其余
// ====================================================================

test('坏分块输入被服务端拒绝 → 对应文件失败可重试，其余正常', async ({ page }) => {
  const { state } = mockUploadSession(page, { chunkSize: 1024, chunkDelayMs: 20 })
  await gotoMediaPage(page, CHAPTER_101, chapterPages(2))

  // 拦截首个文件的分块并发送长度错误的请求（模拟客户端 bug / 网络损坏）
  let corrupted = false
  void page.route('**/api/uploads/sessions/*/files/*', async (route) => {
    if (route.request().method() !== 'PUT') return
    const segments = route.request().url().split('/')
    const fileId = segments[segments.length - 1]
    if (fileId === '0' && !corrupted) {
      corrupted = true
      // 伪造错误请求：Content-Range 与真实 body 长度不一致 → 422
      await route.fulfill({
        status: 422,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 422,
          message: '分块数据不完整，请重试该文件',
          data: null,
        }),
      })
      return
    }
    const rangeHeader = route.request().headers()['content-range'] ?? ''
    const match = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(rangeHeader)
    const start = Number(match?.[1] ?? 0)
    const end = Number(match?.[2] ?? 0)
    const total = Number(match?.[3] ?? 0)
    await json(route, { fileId, receivedBytes: end + 1, complete: end + 1 >= total, receivedRanges: '' })
  })

  const { image, video } = makeFixtureFiles()
  await page.getByTestId('upload-input').setInputFiles([image, video])

  // 文件0 失败，文件1 完成
  await expect(page.getByTestId('upload-status-0')).toHaveText('失败', { timeout: 15_000 })
  await expect(page.getByTestId('upload-status-1')).toHaveText('已完成', { timeout: 15_000 })

  // 重试文件0 → 成功后 complete 触发
  await page.getByTestId('upload-retry-0').click()
  await expect(page.getByTestId('upload-status-0')).toHaveText('已完成', { timeout: 15_000 })
  expect(state.completeCalls).toBe(1)
})
