import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'

interface MockComicDetail {
  version: number
  title: string
  titleJpn?: string
  author?: string
  description?: string
  categoryId: number | null
  sourceType?: string
  sourceRef?: string
  status: string
  tags: { id: number; name: string; type: string }[]
}

const BASE_DETAIL: MockComicDetail = {
  version: 3,
  title: '测试漫画',
  titleJpn: 'テスト漫画',
  author: '作者A',
  description: '描述内容',
  categoryId: 1,
  sourceType: 'DIRECTORY',
  sourceRef: 'D:/manga/测试漫画',
  status: 'READY',
  tags: [
    { id: 11, name: 'action', type: 'genre' },
    { id: 12, name: 'comedy', type: 'genre' },
  ],
}

function mockDetail(page: Page, detail: MockComicDetail = BASE_DETAIL) {
  return page.route('**/api/comics/1', async (route) => {
    const body = {
      code: 200,
      message: 'success',
      data: {
        id: 1,
        title: detail.title,
        titleJpn: detail.titleJpn ?? null,
        author: detail.author ?? null,
        description: detail.description ?? null,
        coverUrl: 'https://example.com/cover.jpg',
        pageCount: 100,
        fileSize: 1024000,
        sourceType: detail.sourceType ?? null,
        sourceRef: detail.sourceRef ?? null,
        categoryId: detail.categoryId,
        categoryName: detail.categoryId === 1 ? '冒险' : null,
        status: detail.status,
        version: detail.version,
        progressPercent: 0,
        lastReadChapterId: null,
        lastReadPage: null,
        chapters: [],
        tags: detail.tags,
        createdAt: '2026-01-01T00:00:00',
        updatedAt: '2026-01-01T00:00:00',
      },
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

function mockTags(page: Page) {
  return page.route('**/api/tags', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: [
          { id: 11, name: 'action' },
          { id: 12, name: 'comedy' },
          { id: 13, name: 'drama' },
        ],
      }),
    })
  })
}

function mockCategories(page: Page) {
  return page.route('**/api/categories', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: [{ id: 1, name: '冒险', sortOrder: 1 }],
      }),
    })
  })
}

async function gotoEditPage(page: Page) {
  await mockDetail(page)
  await mockTags(page)
  await mockCategories(page)
  await page.goto('/manage/comics/1/edit')
  await page.waitForLoadState('load')
  await page.locator('.edit-form').waitFor({ timeout: 10000 })
}

test('edits all fields and saves with a single PUT', async ({ page }) => {
  await gotoEditPage(page)

  // 表单已回填
  const titleInput = page.getByPlaceholder('输入漫画标题')
  await expect(titleInput).toHaveValue('测试漫画')
  await expect(page.getByPlaceholder('输入日文原标题（可选）')).toHaveValue('テスト漫画')

  // 只拦截到一个 PUT /comics/1
  let putCount = 0
  let lastPayload: Record<string, unknown> | null = null
  await page.route('**/api/comics/1', async (route, request) => {
    if (request.method() === 'PUT') {
      putCount++
      lastPayload = request.postDataJSON() as Record<string, unknown>
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'success',
          data: { id: 1, version: 4, title: '新标题', status: 'READY' },
        }),
      })
      return
    }
    await route.continue()
  })

  await titleInput.fill('新标题')
  await page.getByPlaceholder('输入作者名（可选）').fill('新作者')
  await page.locator('.edit-form').getByRole('button', { name: '保存' }).click()

  await expect(page).toHaveURL(/manage\/comics$/, { timeout: 10000 })
  expect(putCount).toBe(1)
  expect(lastPayload).toMatchObject({
    version: 3,
    title: '新标题',
    author: '新作者',
    titleJpn: 'テスト漫画',
    categoryId: 1,
    tagIds: [11, 12],
  })
})

test('business code 409 reloads latest data and shows hint', async ({ page }) => {
  // 单一 route handler：GET 返回基础详情，PUT 返回业务 409；PUT 后 GET 返回服务端最新版本
  let reloaded = false
  await page.route('**/api/comics/1', async (route, request) => {
    if (request.method() === 'PUT') {
      // 标记后续 GET 应返回服务端已更新的数据（模拟其他编辑者已提交）
      reloaded = true
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 409, message: '数据已被修改，请刷新后重试', data: null }),
      })
      return
    }
    // GET：首次返回 version=3，409 重载后返回 version=4 新标题
    const detail = reloaded ? { ...BASE_DETAIL, version: 4, title: '服务端最新标题' } : BASE_DETAIL
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: {
          id: 1,
          title: detail.title,
          titleJpn: detail.titleJpn ?? null,
          author: detail.author ?? null,
          description: detail.description ?? null,
          coverUrl: 'https://example.com/cover.jpg',
          pageCount: 100,
          fileSize: 1024000,
          sourceType: detail.sourceType ?? null,
          sourceRef: detail.sourceRef ?? null,
          categoryId: detail.categoryId,
          categoryName: detail.categoryId === 1 ? '冒险' : null,
          status: detail.status,
          version: detail.version,
          progressPercent: 0,
          lastReadChapterId: null,
          lastReadPage: null,
          chapters: [],
          tags: detail.tags,
          createdAt: '2026-01-01T00:00:00',
          updatedAt: '2026-01-01T00:00:00',
        },
      }),
    })
  })
  await mockTags(page)
  await mockCategories(page)

  await page.goto('/manage/comics/1/edit')
  await page.locator('.edit-form').waitFor({ timeout: 10000 })

  await page.locator('.edit-form').getByRole('button', { name: '保存' }).click()

  await expect(page.getByText('数据已被修改，已重新加载最新内容')).toBeVisible({ timeout: 10000 })
  // 表单显示服务端最新版本
  await expect(page.getByPlaceholder('输入漫画标题')).toHaveValue('服务端最新标题', { timeout: 10000 })
})

test('non-editable status disables form and never submits PUT', async ({ page }) => {
  let putCount = 0
  await page.route('**/api/comics/1', async (route, request) => {
    if (request.method() === 'PUT') {
      putCount++
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'success', data: null }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: {
          id: 1,
          title: '已回收漫画',
          titleJpn: null,
          author: null,
          description: null,
          coverUrl: 'https://example.com/cover.jpg',
          pageCount: 0,
          fileSize: 0,
          sourceType: null,
          sourceRef: null,
          categoryId: null,
          categoryName: null,
          status: 'TRASHED',
          version: 1,
          progressPercent: 0,
          lastReadChapterId: null,
          lastReadPage: null,
          chapters: [],
          tags: [],
          createdAt: '2026-01-01T00:00:00',
          updatedAt: '2026-01-01T00:00:00',
        },
      }),
    })
  })
  await mockTags(page)
  await mockCategories(page)

  await page.goto('/manage/comics/1/edit')
  await page.locator('.edit-form').waitFor({ timeout: 10000 })

  const saveButton = page.locator('.edit-form').getByRole('button', { name: '保存' })
  await expect(saveButton).toBeDisabled()
  expect(putCount).toBe(0)
})
