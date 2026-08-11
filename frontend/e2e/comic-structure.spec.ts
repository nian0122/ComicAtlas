import { test, expect } from '@playwright/test'

/**
 * 目录与媒体结构页（ComicStructurePage）健壮性回归：
 * - 后端对不存在的漫画返回 HTTP 200 + {code:404, message, data:null}（GlobalExceptionHandler 无 @ResponseStatus），
 *   前端必须把 null 兜底为空数组，渲染 for...of 不得崩溃（曾出现 TypeError: n is not iterable）
 * - 正常漫画应正常渲染目录/章节行
 */

const CATALOG_API = /\/api\/comics\/\d+\/catalog/

/** 后端 GlobalExceptionHandler 对业务异常的真实响应体：HTTP 200 + Result.fail */
async function mockCatalogNotFound(page: import('@playwright/test').Page): Promise<void> {
  await page.route(CATALOG_API, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 404, message: '漫画不存在或不可阅读', data: null }),
    })
  )
}

test('加载不存在/非 READY 的漫画时页面不崩溃，目录表格显示空态', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (err) => pageErrors.push(err.message))

  await mockCatalogNotFound(page)
  await page.goto('/manage/structure')

  await page.locator('input[role="spinbutton"]').first().fill('999999')
  const catalogResp = page.waitForResponse((resp) => /\/api\/comics\/999999\/catalog/.test(resp.url()))
  await page.getByRole('button', { name: '加载' }).click()
  await catalogResp

  await expect(page.locator('.structure-page')).toBeVisible()
  expect(pageErrors, `页面渲染抛出了未捕获异常: ${pageErrors.join(' | ')}`).toEqual([])
})

test('正常漫画加载目录树并渲染目录与章节行', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (err) => pageErrors.push(err.message))

  await page.route(CATALOG_API, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: [
          {
            id: 1,
            title: 'Vol 1',
            globalOrder: 1,
            chapters: [{ id: 11, chapterNo: '1', title: '第一话', globalOrder: 1, pageCount: 10, status: 'READY' }],
            children: [],
          },
        ],
      }),
    })
  )
  await page.goto('/manage/structure')

  await page.locator('input[role="spinbutton"]').first().fill('7')
  const catalogResp = page.waitForResponse((resp) => /\/api\/comics\/7\/catalog/.test(resp.url()))
  await page.getByRole('button', { name: '加载' }).click()
  await catalogResp

  await expect(page.getByRole('cell', { name: 'Vol 1' })).toBeVisible()
  await expect(page.getByRole('cell', { name: '第一话' })).toBeVisible()
  expect(pageErrors, `页面渲染抛出了未捕获异常: ${pageErrors.join(' | ')}`).toEqual([])
})
