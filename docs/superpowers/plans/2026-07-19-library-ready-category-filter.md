# 阅读端漫画库筛选调整 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 阅读端列表强制只显示导入成功(READY)的漫画;漫画库移除状态筛选下拉,新增分类筛选下拉。

**Architecture:** 纯前端改动,后端零改动。在阅读端 Pinia store(`comic-store.ts`)的 `fetchList()` 请求处硬编码合并 `status: 'READY'`(LibraryPage/HomePage 共用自动生效);LibraryPage 工具栏删状态下拉、在原位置加原生 `<select>` 分类下拉,选中后按分类名传 `category` 参数(后端 `ComicMapper.selectPage` 已支持按 `cat.name` 精确匹配)。

**Tech Stack:** Vue3 `<script setup lang="ts">` + Pinia + Playwright e2e(mock route 模式,`webServer` 自动起 vite dev on :5173)。

**Spec:** `docs/superpowers/specs/2026-07-19-library-ready-category-filter-design.md`

## Global Constraints

- 提交信息一律中文(仓库约定,conventional 前缀如 `feat(阅读端): ...`)
- 禁止改动:`frontend/src/components/reading/comic/poster-status.ts`(SUCCESS 死分支属已知偏离,本次不修)
- 禁止改动:`frontend/src/stores/management/**`、任何后端代码、`frontend/e2e/comic-list.spec.ts`、`frontend/e2e/comic-poster.spec.ts`
- 新增 e2e 断言不得依赖 status `'SUCCESS'`(后端真实值为 `'READY'`)
- 分类下拉使用原生 `<select>` + `.filter-select` 样式模式(与 LibraryPage 现有筛选控件一致),不用 el-select
- e2e 命令均在 `frontend/` 目录下执行;`playwright.config.ts` 的 webServer 会自动启动/复用 `npm run dev`(http://localhost:5173)
- 类型检查:改动 `.ts`/`.vue` 后可用 `npx vue-tsc -b --noEmit`(等价于 build 的类型阶段)确认无类型错误

---

### Task 1: comic-store 固定 READY + e2e 请求参数断言

**Files:**
- Create: `frontend/e2e/library-filter.spec.ts`
- Modify: `frontend/src/stores/comic-store.ts:42`

**Interfaces:**
- Consumes: `comicApi.list(params)`(`@/services/reading`,已存在);响应形状 `{ code, data: { records, total } }` 经 axios 拦截器解包为 `res.data = { records, total }`
- Produces: `fetchList()` 发出的每个 `/api/comics` 请求恒带 `status=READY`,query 中任何 status 值都会被覆盖;`library-filter.spec.ts` 内的 `mockRoutes(page, captured)` helper 与 `CapturedParams` 接口供 Task 2/3 的测试复用

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

---

### Task 2: LibraryPage 移除状态筛选

**Files:**
- Modify: `frontend/src/views/reading/LibraryPage.vue`(模板 33-41 行状态下拉块、script 135/175 行、样式 331 行)
- Test: `frontend/e2e/library-filter.spec.ts`(追加测试)

**Interfaces:**
- Consumes: Task 1 的 `mockRoutes` / `CapturedParams`
- Produces: 工具栏 `.toolbar-filters` 内不再存在 `.status-select` 节点;`onSearch()` 不再构造 `status` 参数(READY 已由 store 层保证)

- [ ] **Step 1: 写失败测试 —— 追加到 `library-filter.spec.ts` 末尾**

```typescript
test('工具栏不含状态筛选下拉', async ({ page }) => {
  const captured: CapturedParams = { status: [], category: [] }
  await mockRoutes(page, captured)

  await page.goto('/library')
  await expect(page.locator('.comic-poster').first()).toBeVisible({ timeout: 10000 })

  await expect(page.locator('.status-select')).toHaveCount(0)
})
```

- [ ] **Step 2: 运行测试,确认失败**

Run(workdir `frontend/`): `npx playwright test e2e/library-filter.spec.ts --reporter=list`
Expected: 新增测试 FAIL —— `.status-select` 当前存在(count 为 1);Task 1 的测试仍 PASS

- [ ] **Step 3: 最小实现 —— 删除状态筛选**

对 `frontend/src/views/reading/LibraryPage.vue` 做 4 处删除:

(a) 删除模板中 `.toolbar-filters` 内的整个状态下拉块(当前 33-41 行):

```html
<!-- 删除以下整块 -->
<div class="filter-select status-select">
  <select v-model="statusFilter" @change="onSearch">
    <option value="">全部状态</option>
    <option value="READY">已就绪</option>
    <option value="IMPORTING">导入中</option>
    <option value="PENDING">等待中</option>
    <option value="FAILED">失败</option>
  </select>
</div>
```

(b) 删除 script 中的 ref 声明(当前 135 行):

```typescript
// 删除此行
const statusFilter = ref('')
```

(c) 删除 `onSearch()` 中的 status 参数行(当前 175 行):

```typescript
function onSearch() {
  store.search({
    keyword: keyword.value || undefined,
    // 删除下面这行
    status: statusFilter.value || undefined,
    sort: sort.value,
    tags: selectedTags.value.length > 0 ? selectedTags.value : undefined,
    tagMode: selectedTags.value.length > 1 ? tagMode.value : undefined,
  })
}
```

(d) 删除桌面端 order 样式(当前 331 行,注释中的控件顺序说明一并更新):

```css
/* 删除此行 */
.status-select { order: 2; }
```

并把 322-323 行的注释 `搜索 → 状态 → 排序 → 标签 → 标签模式` 改为 `搜索 → 分类 → 排序 → 标签 → 标签模式`(分类下拉在 Task 3 落位,此处先行更新注释与最终形态一致)。

- [ ] **Step 4: 运行测试,确认通过**

Run(workdir `frontend/`): `npx playwright test e2e/library-filter.spec.ts --reporter=list`
Expected: PASS (2 passed)

- [ ] **Step 5: 类型检查**

Run(workdir `frontend/`): `npx vue-tsc -b --noEmit`
Expected: 退出码 0,无 `statusFilter` 残留引用报错

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/reading/LibraryPage.vue frontend/e2e/library-filter.spec.ts
git commit -m "feat(阅读端): 漫画库移除状态筛选下拉"
```

---

### Task 3: LibraryPage 新增分类筛选

**Files:**
- Modify: `frontend/src/views/reading/LibraryPage.vue`(模板 `.toolbar-filters` 首位、script imports/refs/onSearch/onMounted、桌面端 order 样式)
- Test: `frontend/e2e/library-filter.spec.ts`(追加测试)

**Interfaces:**
- Consumes: `categoryApi.list()` —— 必须从 `@/services/management` import(`services/reading.ts` 未导出 categoryApi,跟随现有 `tagApi` 同源先例);`CategoryDTO`(`@/types`,已存在:`{ id: number; name: string; sortOrder: number }`);Task 1 的 `mockRoutes`(已 mock `/api/categories**`)
- Produces: `.category-select select` 下拉(首项"全部分类" value='');选中分类后 `onSearch` 传 `category: 分类名`,空值传 `undefined`

- [ ] **Step 1: 写失败测试 —— 追加到 `library-filter.spec.ts` 末尾**

```typescript
test('分类筛选:选中传 category,切回全部不传', async ({ page }) => {
  const captured: CapturedParams = { status: [], category: [] }
  await mockRoutes(page, captured)

  await page.goto('/library')
  const select = page.locator('.category-select select')
  await expect(select).toBeVisible({ timeout: 10000 })
  await expect(select.locator('option')).toHaveCount(3) // 全部分类 + 少年 + 青年

  await select.selectOption({ label: '少年' })
  await expect.poll(() => captured.category[captured.category.length - 1]).toBe('少年')

  await select.selectOption({ label: '全部分类' })
  await expect.poll(() => captured.category[captured.category.length - 1]).toBe(null)
})
```

- [ ] **Step 2: 运行测试,确认失败**

Run(workdir `frontend/`): `npx playwright test e2e/library-filter.spec.ts --reporter=list`
Expected: 新增测试 FAIL —— `.category-select select` 不存在,`toBeVisible` 超时;前两个测试仍 PASS

- [ ] **Step 3: 最小实现 —— 新增分类筛选**

对 `frontend/src/views/reading/LibraryPage.vue` 做 6 处修改:

(a) 模板:在 `.toolbar-filters` 内**首位**(原状态下拉的位置,即 `<div class="toolbar-filters">` 开标签之后)插入:

```html
<div class="filter-select category-select">
  <select v-model="categoryFilter" @change="onSearch">
    <option value="">全部分类</option>
    <option v-for="c in allCategories" :key="c.id" :value="c.name">{{ c.name }}</option>
  </select>
</div>
```

(b) import:categoryApi 与现有 tagApi 同行、CategoryDTO 加入类型 import:

```typescript
// 旧:
import { tagApi } from '@/services/management'
import type { ComicListQuery, ComicListVO, TagDTO } from '@/types'
// 新:
import { tagApi, categoryApi } from '@/services/management'
import type { CategoryDTO, ComicListQuery, ComicListVO, TagDTO } from '@/types'
```

(c) refs:在 `const allTags = ref<TagDTO[]>([])` 之后追加:

```typescript
const categoryFilter = ref('')
const allCategories = ref<CategoryDTO[]>([])
```

(d) 加载函数:在 `loadTags()` 之后追加(模式与 loadTags 一致,失败置空):

```typescript
async function loadCategories() {
  try {
    const res = await categoryApi.list()
    allCategories.value = (res.data as CategoryDTO[]) || []
  } catch (err: unknown) {
    allCategories.value = []
  }
}
```

(e) `onSearch()` 增加 category 参数(放在 keyword 之后):

```typescript
function onSearch() {
  store.search({
    keyword: keyword.value || undefined,
    category: categoryFilter.value || undefined,
    sort: sort.value,
    tags: selectedTags.value.length > 0 ? selectedTags.value : undefined,
    tagMode: selectedTags.value.length > 1 ? tagMode.value : undefined,
  })
}
```

`onMounted` 中在 `loadTags()` 之后追加 `loadCategories()`:

```typescript
onMounted(() => {
  loadTags()
  loadCategories()
  store.fetchList()
})
```

(f) 桌面端 order 样式:在 `.search-input { order: 1; }` 之后补回 order 2(Task 2 删除了 `.status-select` 的位置):

```css
.category-select { order: 2; }
```

- [ ] **Step 4: 运行本文件全部测试,确认通过**

Run(workdir `frontend/`): `npx playwright test e2e/library-filter.spec.ts --reporter=list`
Expected: PASS (3 passed)

- [ ] **Step 5: 类型检查 + 全量 e2e 回归**

Run(workdir `frontend/`): `npx vue-tsc -b --noEmit`
Expected: 退出码 0

Run(workdir `frontend/`): `npx playwright test --reporter=list`
Expected: 全部 PASS(comic-list 2 个 + comic-poster 1 个 + library-filter 3 个)

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/reading/LibraryPage.vue frontend/e2e/library-filter.spec.ts
git commit -m "feat(阅读端): 漫画库新增分类筛选下拉"
```

---

## 验收核对(全部任务完成后)

对照 spec 验收标准:

1. 阅读端漫画库与首页列表只出现 READY 漫画 —— Task 1(store 层固定,HomePage 经 `search({ sort })` 走同一 `fetchList` 自动生效)
2. 漫画库工具栏无状态筛选下拉 —— Task 2
3. 分类下拉选中后只显示该分类、"全部分类"恢复全量 —— Task 3(后端 `cat.name` 精确匹配已就绪)
4. 分类与关键词/标签/排序可叠加 —— Task 3 的 `onSearch` 单一出口保证;`store.search()` 自带 `page: 1` 重置
5. 管理端行为不变 —— 全程未触碰 `stores/management/**` 与管理端页面(Global Constraints 禁改清单)
