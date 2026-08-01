/**
 * Video Reader 测试辅助模块。
 * 提供 HQ 请求采集、Range 响应断言、视频漫画定位等可复用工具。
 */
import { type Page, type Request, type Response, expect } from '@playwright/test'

// ── HQ 请求采集器 ────────────────────────────────────────────

export interface HqRequestCollector {
  /** URL → 请求列表的映射 */
  requests: Map<string, Request[]>
  /** 开始采集（清空旧数据） */
  start(): void
  /** 停止采集 */
  stop(): void
  /** 按 URL 片段查找请求 */
  getRequestsForUrl(urlPattern: string): Request[]
  /** 注销监听器 */
  dispose(): void
}

/**
 * 创建一个监听 /files/hq/ 请求的采集器。
 * 必须手动调用 start() 开始采集，stop() 停止，
 * 测试结束后调用 dispose() 清理监听器。
 */
export function createHqRequestCollector(page: Page): HqRequestCollector {
  const requests = new Map<string, Request[]>()
  let active = false

  const handler = (request: Request): void => {
    if (!active) return
    if (!request.url().includes('/files/hq/')) return

    const existing = requests.get(request.url()) ?? []
    existing.push(request)
    requests.set(request.url(), existing)
  }

  page.on('request', handler)

  return {
    requests,
    start(): void {
      active = true
      requests.clear()
    },
    stop(): void {
      active = false
    },
    getRequestsForUrl(urlPattern: string): Request[] {
      const result: Request[] = []
      for (const [url, reqs] of requests) {
        if (url.includes(urlPattern)) {
          result.push(...reqs)
        }
      }
      return result
    },
    dispose(): void {
      page.off('request', handler)
    },
  }
}

// ── Range 响应断言 ────────────────────────────────────────────

/**
 * 断言响应满足 HTTP Range 请求规范：
 * - 状态码 206 Partial Content
 * - Accept-Ranges: bytes
 * - Content-Range 头存在
 */
export async function assertRangeResponse(response: Response): Promise<void> {
  expect(response.status(), `Expected 206 for Range request, got ${response.status()} at ${response.url()}`).toBe(206)

  const headers = response.headers()
  expect(
    headers['accept-ranges'],
    `Missing Accept-Ranges header at ${response.url()}`,
  ).toBe('bytes')

  expect(
    headers['content-range'],
    `Missing Content-Range header at ${response.url()}`,
  ).toBeTruthy()
}

// ── 视频漫画定位 ──────────────────────────────────────────────

/**
 * 在漫画库中找到一本含视频的漫画并打开阅读器。
 * 遍历漫画卡片，逐个进入详情页 → 阅读器，检测 .video-player 元素。
 *
 * @returns 已在阅读器页面的 Page 对象
 * @throws 库中没有包含视频的漫画时抛出清晰错误
 */
export async function openVideoComic(page: Page): Promise<Page> {
  await page.goto('/comics')
  await page.waitForSelector('.comic-card', { timeout: 15000 })

  const cards = page.locator('.comic-card')
  const count = await cards.count()

  if (count === 0) {
    throw new Error(
      'No comics found in library. Import at least one comic with video content ' +
      'before running video reader tests.',
    )
  }

  const checkedTitles = new Set<string>()

  for (let i = 0; i < count; i++) {
    // 每次迭代都重新导航到列表页，确保 DOM 状态干净
    await page.goto('/comics')
    await page.waitForSelector('.comic-card', { timeout: 15000 })

    const freshCards = page.locator('.comic-card')
    const card = freshCards.nth(i)

    try {
      await expect(card).toBeVisible({ timeout: 5000 })
    } catch {
      // 卡片可能在视口外或已消失，跳过
      continue
    }

    const title = await card.locator('.comic-title').textContent().catch(() => null)
    if (!title || checkedTitles.has(title.trim())) continue
    checkedTitles.add(title.trim())

    // 进入详情页
    await card.click()
    await page.waitForURL(/\/comics\/\d+$/, { timeout: 10000 })

    // 查找"阅读"按钮
    const readBtn = page.locator('button:has-text("阅读")').first()
    const hasReadBtn = await readBtn.isVisible({ timeout: 3000 }).catch(() => false)
    if (!hasReadBtn) continue

    // 进入阅读器
    await readBtn.click()
    await page.waitForURL(/\/comics\/\d+\/read/, { timeout: 10000 })
    await page.waitForSelector('.reader-toolbar', { timeout: 10000 })

    // 等待内容渲染（阅读器加载状态消失）
    await page.locator('.reader-state').waitFor({ state: 'hidden', timeout: 8000 }).catch(() => {
      // 有的漫画加载很快，reader-state 可能不出现
    })

    // 等待至少一个 reader-image-item 渲染
    await page.waitForSelector('.reader-image-item', { timeout: 10000 }).catch(() => {
      // virtual-scroller 可能在等待尺寸计算
    })

    // 检查视频播放器
    const videoPlayers = page.locator('.video-player')
    const videoCount = await videoPlayers.count()

    if (videoCount >= 2) {
      // 也检查有非视频页面（图片）
      const images = page.locator('.progressive-image')
      const imageCount = await images.count()
      if (imageCount >= 1) {
        return page
      }
      // 有视频但没图片，不符合混合媒体要求，继续找下一本
    }
  }

  throw new Error(
    'No video comic found. ' +
    'Import a comic with video content (at least 2 VIDEO pages and 1 IMAGE page) ' +
    'before running video reader tests. ' +
    `Checked ${checkedTitles.size} comic(s): ${[...checkedTitles].join(', ')}`,
  )
}
