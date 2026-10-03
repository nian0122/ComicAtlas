import { expect, test, type Page } from '@playwright/test'

test('切换标签忽略旧响应，取消失败时保留喜欢', async ({ page }) => {
  let release: () => void = () => {}
  const wait = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route(/\/api\/favorites(?:\?|$)/, async (route) => {
    const type = new URL(route.request().url()).searchParams.get('targetType') || 'COMIC'
    if (type === 'COMIC') await wait
    await route.fulfill({ json: { code: 200, data: [sample(type)] } })
  })
  await page.route(/\/api\/chapters\/\d+\/reaction$/, (route) =>
    route.fulfill({ status: 503, json: { message: '保存失败' } }),
  )
  await page.route('**/files/**', (route) => route.fulfill({ status: 404 }))
  await page.goto('/favorites')
  await page.getByRole('button', { name: '章节', exact: true }).click()
  await expect(page.locator('.favorite-card .title')).toHaveAttribute('href', '/reader/11?page=5')
  release()
  await expect(page.locator('.favorite-card .title')).toHaveAttribute('href', '/reader/11?page=5')
  await page.getByRole('button', { name: '取消喜欢' }).click()
  await expect(page.getByRole('alert')).toContainText('保存失败')
  await expect(page.locator('.favorite-card')).toHaveCount(1)
  await expect(page.getByRole('button', { name: '撤销', exact: true })).toHaveCount(0)
})

const sample = (targetType = 'COMIC', id = 1) => ({
  id,
  targetType,
  comicId: 7,
  comicTitle: '示例漫画',
  chapterId: 11,
  title: targetType === 'COMIC' ? '示例漫画' : '第一章',
  pageNumber: 3,
  mediaType: targetType === 'MEDIA' ? 'VIDEO' : null,
  duration: 65,
  coverUrl: '/files/thumbs/7/cover.webp',
  previewUrl: '/files/thumbs/7/cover.webp',
  reactionAt: '2026-10-03T08:00:00Z',
  lastReadChapterId: 11,
  lastReadPageNumber: 5,
})
async function mock(page: Page) {
  const marks: string[] = []
  let liked = true
  await page.route(/\/api\/favorites(?:\?|$)/, async (route) => {
    const url = new URL(route.request().url())
    const type = url.searchParams.get('targetType') || 'COMIC'
    const rows = type === 'COMIC' && !liked ? [] : [sample(type)]
    await route.fulfill({ json: { code: 200, data: rows } })
  })
  await page.route(/\/api\/(comics|chapters|pages)\/\d+\/reaction$/, async (route) => {
    const reaction = route.request().postDataJSON().reaction
    marks.push(reaction)
    liked = reaction === 'LIKE'
    await route.fulfill({ json: { code: 200, data: {} } })
  })
  await page.route('**/files/**', (route) => route.fulfill({ status: 404 }))
  return marks
}
test('喜欢页分层跳转、取消和撤销持久化', async ({ page }) => {
  const marks = await mock(page)
  await page.goto('/favorites')
  await expect(page.locator('.favorite-card .title')).toHaveAttribute('href', '/comic/7')
  await expect(page.getByRole('link', { name: '继续阅读', exact: true })).toHaveAttribute('href', '/reader/11?page=5')
  await page.getByRole('button', { name: '取消喜欢' }).click()
  await expect(page.getByText('还没有喜欢的漫画')).toBeVisible()
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await expect(page.locator('.favorite-card')).toHaveCount(1)
  expect(marks).toEqual(['NONE', 'LIKE'])
  await page.getByRole('button', { name: '章节', exact: true }).click()
  await expect(page.locator('.favorite-card .title')).toHaveAttribute('href', '/reader/11?page=5')
  await page.getByRole('button', { name: '媒体', exact: true }).click()
  await expect(page.locator('.favorite-card .title')).toHaveAttribute('href', '/videos/11?page=3')
  await expect(page.getByText('1:05')).toBeVisible()
  await expect(page.locator('.favorite-card .source')).toHaveText('示例漫画')
})
test('喜欢页移动导航、排序、刷新和无横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mock(page)
  await page.goto('/favorites?tab=MEDIA&order=oldest')
  await expect(page.locator('.mobile-tabbar a')).toHaveCount(4)
  await expect(page.locator('.mobile-tabbar a[href="/favorites"]')).toHaveClass(/active/)
  await expect(page.getByRole('combobox', { name: '标记时间排序' })).toBeVisible()
  await page.locator('.filters .el-select').click()
  await page.getByRole('option', { name: '最近喜欢' }).click()
  await expect(page).toHaveURL(/order=recent/)
  await page.reload()
  await expect(page.locator('.favorite-card')).toHaveCount(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
test('满页分页和错误重试', async ({ page }) => {
  let failed = true
  await page.route(/\/api\/favorites(?:\?|$)/, async (route) => {
    if (failed) {
      failed = false
      await route.fulfill({ status: 503, json: { message: '服务暂不可用' } })
      return
    }
    const number = Number(new URL(route.request().url()).searchParams.get('page'))
    await route.fulfill({
      json: {
        code: 200,
        data:
          number === 1 ? Array.from({ length: 25 }, (_, index) => sample('COMIC', index + 1)) : [sample('COMIC', 25)],
      },
    })
  })
  await page.route('**/files/**', (route) => route.fulfill({ status: 404 }))
  await page.goto('/favorites')
  await expect(page.getByRole('alert')).toBeVisible()
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.locator('.favorite-card')).toHaveCount(24)
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(page.locator('.favorite-card')).toHaveCount(1)
  await expect(page.getByRole('button', { name: '下一页', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '上一页', exact: true }).click()
  await expect(page.locator('.favorite-card')).toHaveCount(24)
})
