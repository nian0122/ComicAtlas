# Task 3: LibraryPage 新增分类筛选

> 来源:`docs/superpowers/plans/2026-07-19-library-ready-category-filter.md` Task 3。本文件是你的完整需求,其中的精确值逐字使用。

## Global Constraints(约束全任务)

- 提交信息一律中文(仓库约定,conventional 前缀如 `feat(阅读端): ...`)
- 禁止改动:`frontend/src/components/reading/comic/poster-status.ts`(SUCCESS 死分支属已知偏离,本次不修)
- 禁止改动:`frontend/src/stores/management/**`、任何后端代码、`frontend/e2e/comic-list.spec.ts`、`frontend/e2e/comic-poster.spec.ts`
- 新增 e2e 断言不得依赖 status `'SUCCESS'`(后端真实值为 `'READY'`)
- 分类下拉使用原生 `<select>` + `.filter-select` 样式模式(与 LibraryPage 现有筛选控件一致),不用 el-select
- e2e 命令均在 `frontend/` 目录下执行;webServer 自动启动/复用 `npm run dev`(http://localhost:5173)
- 类型检查:`npx vue-tsc -b --noEmit` 必须退出码 0

## Files

- Modify: `frontend/src/views/reading/LibraryPage.vue`(模板 `.toolbar-filters` 首位、script imports/refs/onSearch/onMounted、桌面端 order 样式)
- Test: `frontend/e2e/library-filter.spec.ts`(追加测试;该文件已含 `mockRoutes` helper,其中 `/api/categories**` 已 mock 返回 少年/青年 两个分类)

## Interfaces

- Consumes: `categoryApi.list()` —— 必须从 `@/services/management` import(`services/reading.ts` 未导出 categoryApi,跟随现有 `tagApi` 同源先例);`CategoryDTO`(`@/types`,已存在:`{ id: number; name: string; sortOrder: number }`);既有 `mockRoutes`(已 mock `/api/categories**`)
- Produces: `.category-select select` 下拉(首项"全部分类" value='');选中分类后 `onSearch` 传 `category: 分类名`,空值传 `undefined`

## Steps

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

(a) 模板:在 `.toolbar-filters` 内**首位**(即 `<div class="toolbar-filters">` 开标签之后、`.tag-filter` 之前)插入:

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
