# Task 1: comic-store 固定 READY + e2e 请求参数断言

> 来源:`docs/superpowers/plans/2026-07-19-library-ready-category-filter.md` Task 1。本文件是你的完整需求,其中的精确值逐字使用。

## Global Constraints(约束全任务)

- 提交信息一律中文(仓库约定,conventional 前缀如 `feat(阅读端): ...`)
- 禁止改动:`frontend/src/components/reading/comic/poster-status.ts`(SUCCESS 死分支属已知偏离,本次不修)
- 禁止改动:`frontend/src/stores/management/**`、任何后端代码、`frontend/e2e/comic-list.spec.ts`、`frontend/e2e/comic-poster.spec.ts`
- 新增 e2e 断言不得依赖 status `'SUCCESS'`(后端真实值为 `'READY'`)
- e2e 命令均在 `frontend/` 目录下执行;`playwright.config.ts` 的 webServer 会自动启动/复用 `npm run dev`(http://localhost:5173)

## Files

- Create: `frontend/e2e/library-filter.spec.ts`
- Modify: `frontend/src/stores/comic-store.ts:42`

## Interfaces

- Consumes: `comicApi.list(params)`(`@/services/reading`,已存在);响应形状 `{ code, data: { records, total } }` 经 axios 拦截器解包为 `res.data = { records, total }`
- Produces: `fetchList()` 发出的每个 `/api/comics` 请求恒带 `status=READY`,query 中任何 status 值都会被覆盖;`library-filter.spec.ts` 内的 `mockRoutes(page, captured)` helper 与 `CapturedParams` 接口供后续任务的测试复用

## Steps

- [ ] **Step 1: 写失败测试 —— 新建 e2e 文件(mock helper + 测试1)**

创建 `frontend/e2e/library-filter.spec.ts`,完整内容:

```typescript
import { test, expect, type Page } from '@playwright/test'

interface CapturedParams {
  status: (string | null)[]
  category: (string | null)[]
}

/**
 * 拦截漫画库页面用到的三个接口:
 * - /api/comics**    记录 status/category 请求参数并返回 24 条 READY 漫画
 * - /api/categories** 返回两个分类(少年/青年)
 * - /api/tags**       返回空标签列表
 * 响应包裹 { code, data } 形状以配合 axios 拦截器解包。
 */
async function mockRoutes(page: Page, captured: CapturedParams) {
  await page.route('/api/comics**', async (route, request) => {
    const url = new URL(request.url())
    captured.status.push(url.searchParams.get('status'))
    captured.category.push(url.searchParams.get('category'))
    const records = Array.from({ length: 24 }, (_, i) => ({
      id: i + 1,
      title: `漫画 ${i + 1}`,
      author: '作者',
      coverUrl: `https://example.com/cover-${i + 1}.jpg`,
      pageCount: 100,
      categoryId: null,
      categoryName: null,
      status: 'READY',
      lqStatus: 'NOT_GENERATED',
      progressPercent: 0,
      lastReadChapterId: 0,
      lastReadPage: 0,
      createdAt: new Date().toISOString(),
    }))
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, data: { records, total: 24 } }),
    })
  })

  await page.route('/api/categories**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        data: [
          { id: 1, name: '少年', sortOrder: 1 },
          { id: 2, name: '青年', sortOrder: 2 },
        ],
      }),
    })
  })

  await page.route('/api/tags**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, data: [] }),
    })
  })
}

test('漫画库请求恒带 status=READY', async ({ page }) => {
  const captured: CapturedParams = { status: [], category: [] }
  await mockRoutes(page, captured)

  await page.goto('/library')
  await expect(page.locator('.comic-poster').first()).toBeVisible({ timeout: 10000 })

  expect(captured.status.length).toBeGreaterThan(0)
  expect(captured.status.every((s) => s === 'READY')).toBe(true)
})
```

- [ ] **Step 2: 运行测试,确认失败**

Run(workdir `frontend/`): `npx playwright test e2e/library-filter.spec.ts --reporter=list`
Expected: FAIL —— `expect(captured.status.every((s) => s === 'READY')).toBe(true)` 为 false(当前 store 不传 status,捕获值为 `null`)

- [ ] **Step 3: 最小实现 —— fetchList 硬编码合并 READY**

修改 `frontend/src/stores/comic-store.ts` 的 `fetchList()`(当前第 42 行):

```typescript
// 旧:
const res = await comicApi.list(state.query)
// 新(阅读端只展示导入成功的漫画;硬编码在请求处,任何 search() patch 都无法覆盖):
const res = await comicApi.list({ ...state.query, status: 'READY' })
```

- [ ] **Step 4: 运行测试,确认通过**

Run(workdir `frontend/`): `npx playwright test e2e/library-filter.spec.ts --reporter=list`
Expected: PASS (1 passed)

- [ ] **Step 5: 回归 —— 现有 e2e 不受影响**

Run(workdir `frontend/`): `npx playwright test e2e/comic-list.spec.ts --reporter=list`
Expected: PASS(其 mock 为 `/api/comics**` 通配,不校验 query 参数)

- [ ] **Step 6: Commit**

```bash
git add frontend/e2e/library-filter.spec.ts frontend/src/stores/comic-store.ts
git commit -m "feat(阅读端): 漫画列表固定只请求 READY 漫画"
```
