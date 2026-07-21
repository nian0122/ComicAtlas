# Task 2: LibraryPage 移除状态筛选

> 来源:`docs/superpowers/plans/2026-07-19-library-ready-category-filter.md` Task 2。本文件是你的完整需求,其中的精确值逐字使用。

## Global Constraints(约束全任务)

- 提交信息一律中文(仓库约定,conventional 前缀如 `feat(阅读端): ...`)
- 禁止改动:`frontend/src/components/reading/comic/poster-status.ts`(SUCCESS 死分支属已知偏离,本次不修)
- 禁止改动:`frontend/src/stores/management/**`、任何后端代码、`frontend/e2e/comic-list.spec.ts`、`frontend/e2e/comic-poster.spec.ts`
- 新增 e2e 断言不得依赖 status `'SUCCESS'`(后端真实值为 `'READY'`)
- e2e 命令均在 `frontend/` 目录下执行;`playwright.config.ts` 的 webServer 会自动启动/复用 `npm run dev`(http://localhost:5173)
- 类型检查:改动 `.ts`/`.vue` 后用 `npx vue-tsc -b --noEmit` 确认无类型错误

## Files

- Modify: `frontend/src/views/reading/LibraryPage.vue`(模板 33-41 行状态下拉块、script 135/175 行、样式 331 行;行号为 Task 1 完成后的当前行号)
- Test: `frontend/e2e/library-filter.spec.ts`(追加测试,该文件已由 Task 1 创建,含 `mockRoutes` helper 与 `CapturedParams` 接口)

## Interfaces

- Consumes: Task 1 已创建的 `mockRoutes(page, captured)` / `CapturedParams`(位于 `frontend/e2e/library-filter.spec.ts`)
- Produces: 工具栏 `.toolbar-filters` 内不再存在 `.status-select` 节点;`onSearch()` 不再构造 `status` 参数(READY 已由 store 层保证)

## Steps

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
