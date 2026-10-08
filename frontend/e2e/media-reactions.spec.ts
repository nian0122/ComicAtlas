import { expect, test } from '@playwright/test'

test('媒体标记切换条件时旧响应不能覆盖新列表', async ({ page }) => {
  let releaseFirst!: () => void
  let requests = 0
  await page.route('**/api/manage/media/reactions?**', async (route) => {
    requests += 1
    const isFirst = requests === 1
    if (isFirst)
      await new Promise<void>((resolve) => {
        releaseFirst = resolve
      })
    await route.fulfill({
      json: {
        code: 200,
        data: [
          {
            id: isFirst ? 1 : 2,
            chapterId: isFirst ? 100 : 200,
            pageNumber: 1,
            mediaType: 'IMAGE',
            reaction: 'LIKE',
            reactionAt: null,
            status: 'READY',
          },
        ],
      },
    })
  })
  await page.goto('/manage/media-reactions')
  await expect.poll(() => requests).toBe(1)
  await page.getByRole('button', { name: '喜欢', exact: true }).click()
  await expect(page.getByText('章节 200 · 第 1 页', { exact: true })).toBeVisible()
  releaseFirst()
  await page.waitForResponse(
    (response) =>
      response.url().includes('/manage/media/reactions') && !new URL(response.url()).searchParams.has('reaction'),
  )
  await expect(page.getByText('章节 200 · 第 1 页', { exact: true })).toBeVisible()
  await expect(page.getByText('章节 100 · 第 1 页', { exact: true })).toHaveCount(0)
})

test('内容标记从漫画切换到章节时忽略旧漫画响应', async ({ page }) => {
  let releaseComic!: () => void
  let comicRequested = false
  await page.route('**/api/manage/media/reactions?**', (route) => route.fulfill({ json: { code: 200, data: [] } }))
  await page.route('**/api/manage/content/reactions?**', async (route) => {
    const targetType = new URL(route.request().url()).searchParams.get('targetType')
    if (targetType === 'COMIC') {
      comicRequested = true
      await new Promise<void>((resolve) => {
        releaseComic = resolve
      })
    }
    await route.fulfill({
      json: {
        code: 200,
        data: [
          {
            id: 1,
            title: targetType === 'COMIC' ? '旧漫画结果' : '当前章节结果',
            targetType,
            reaction: 'LIKE',
            reactionAt: null,
            status: 'READY',
          },
        ],
      },
    })
  })
  await page.goto('/manage/media-reactions')
  await page.getByRole('tab', { name: '漫画', exact: true }).click()
  await expect.poll(() => comicRequested).toBe(true)
  await page.getByRole('tab', { name: '章节', exact: true }).click()
  await expect(page.getByText('当前章节结果', { exact: false })).toBeVisible()
  const oldResponse = page.waitForResponse(
    (response) => new URL(response.url()).searchParams.get('targetType') === 'COMIC',
  )
  releaseComic()
  await oldResponse
  await expect(page.getByText('当前章节结果', { exact: false })).toBeVisible()
  await expect(page.getByText('旧漫画结果', { exact: false })).toHaveCount(0)
})
