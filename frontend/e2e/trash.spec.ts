import { expect, test, type Page, type Route } from '@playwright/test'

function success(route: Route, data: unknown) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, message: 'success', data }),
  })
}

test('回收站入口展示已回收漫画并支持恢复', async ({ page }: { page: Page }) => {
  const comicUrls: URL[] = []
  const restoreUrls: string[] = []
  await page.route('/api/comics**', (route) => {
    comicUrls.push(new URL(route.request().url()))
    return success(route, {
      records: [{
        id: 7,
        title: '已回收漫画',
        author: '作者',
        coverUrl: '',
        pageCount: 24,
        categoryId: null,
        categoryName: null,
        status: 'TRASHED',
        progressPercent: 0,
        lastReadChapterId: 0,
        lastReadPage: 0,
        createdAt: '2026-01-01T00:00:00Z',
      }],
      total: 1,
    })
  })
  await page.route('/api/trash/comics/7/restore', (route) => {
    restoreUrls.push(route.request().url())
    return success(route, { taskId: 12, status: 'PENDING' })
  })

  await page.goto('/manage/trash')
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.screenshot({ path: 'C:\\Users\\Acer\\.codex\\visualizations\\2026\\08\\11\\trash-final.png' })
  await expect(page.getByRole('heading', { name: '回收站' })).toBeVisible()
  await expect(page.getByText('已回收漫画', { exact: true })).toBeVisible()
  await expect.poll(() => comicUrls.at(-1)?.searchParams.get('status')).toBe('TRASHED')

  await page.getByRole('button', { name: '恢复' }).click()
  await page.getByRole('button', { name: '恢复', exact: true }).last().click()
  await expect.poll(() => restoreUrls).toHaveLength(1)
})
