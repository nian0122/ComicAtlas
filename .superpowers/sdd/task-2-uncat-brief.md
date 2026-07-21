# Task 2: 前端阅读端 LibraryPage 新增选项 + 互斥

> 来源:`docs/superpowers/plans/2026-07-19-uncategorized-untagged-filter.md` Task 2。本文件是你的完整需求,精确值逐字使用。

## Global Constraints

- 提交信息一律中文(conventional 前缀)
- 哨兵值固定为 `_NONE`
- 禁止改动:`stores/management/**`、后端代码、类型定义、`e2e/comic-list.spec.ts`
- e2e 命令均在 `frontend/` 下执行;webServer 自动启动/复用 `npm run dev`(:5173)
- vue-tsc:`npx vue-tsc -b --noEmit` 必须 EXIT=0
- 只允许改动: `frontend/src/views/reading/LibraryPage.vue` (模板 + script) 和 `frontend/e2e/library-filter.spec.ts` (末尾追加)

## Files

- Modify: `frontend/src/views/reading/LibraryPage.vue`
- Test: `frontend/e2e/library-filter.spec.ts`

## Steps

- [ ] **Step 1: 写失败测试**

在 `library-filter.spec.ts` 末尾追加以下两个测试(该文件已有 `mockRoutes` helper 和 `CapturedParams` 接口):

```typescript
test('分类筛选支持未分类(_NONE)', async ({ page }) => {
  const captured: CapturedParams = { status: [], category: [] }
  await mockRoutes(page, captured)

  await page.goto('/library')
  const select = page.locator('.category-select select')
  await expect(select).toBeVisible({ timeout: 10000 })

  await select.selectOption({ label: '未分类' })
  await expect.poll(() => captured.category[captured.category.length - 1]).toBe('_NONE')
})

test('标签筛选支持无标签(_NONE)且与正常标签互斥', async ({ page }) => {
  const captured: CapturedParams = { status: [], category: [] }
  await mockRoutes(page, captured)

  await page.goto('/library')
  await expect(page.locator('.comic-poster').first()).toBeVisible({ timeout: 10000 })

  const tagSelect = page.locator('.tag-select')
  await tagSelect.click()
  // 选"无标签"
  await page.locator('.el-select-dropdown__item').filter({ hasText: '无标签' }).click()
  // 点击空白关闭下拉
  await page.locator('body').click()
  await expect.poll(() => captured.category[captured.category.length - 1]).toBe(null) // categoryFilter unchanged
})
```

- [ ] **Step 2: 运行测试确认失败**

Run(workdir `frontend/`): `npx playwright test e2e/library-filter.spec.ts -g "未分类|无标签" --reporter=list`
Expected: 新测试 FAIL(option 不存在)

- [ ] **Step 3: 模板 —— 分类下拉追加选项**

在 `.category-select select` 内 `<option value="">全部分类</option>` 之后插入:

```html
<option value="_NONE">未分类</option>
```

- [ ] **Step 4: 模板 —— 标签多选追加选项**

在 tag `el-select` 内 `v-for="tag in allTags"` 的 `<el-option>` 之后追加:

```html
<el-option label="无标签" value="_NONE" />
```

- [ ] **Step 5: script —— import 追加 `watch`, `nextTick`**

将现有的 `import { ref, computed, onMounted } from 'vue'` 改为:

```typescript
import { ref, computed, onMounted, watch, nextTick } from 'vue'
```

- [ ] **Step 6: script —— 互斥 watch**

在 `loadCategories()` 函数之后、`onMounted()` 之前追加:

```typescript
watch(selectedTags, (val) => {
  if (!val.includes('_NONE')) return
  // _NONE 被选中 → 保留正常标签,移除 _NONE
  if (val.length > 1) {
    nextTick(() => {
      selectedTags.value = val.filter(v => v !== '_NONE')
    })
  }
}, { deep: true })
```

必须用 `nextTick` 包裹赋值,否则 Element Plus el-select 多选的内部 set 会回冲 selectedTags.value。多选正常标签时 `val.includes('_NONE')` 为 false 直接 return,避免无限循环。

- [ ] **Step 7: 运行本文件全部测试**

Run(workdir `frontend/`): `npx playwright test e2e/library-filter.spec.ts --reporter=list`
Expected: 5 passed(原有 3 个 + 新增 2 个)

- [ ] **Step 8: 类型检查**

Run(workdir `frontend/`): `npx vue-tsc -b --noEmit`
Expected: EXIT=0

- [ ] **Step 9: Commit**

```bash
git add frontend/src/views/reading/LibraryPage.vue frontend/e2e/library-filter.spec.ts
git commit -m "feat(阅读端): 漫画库支持未分类与无标签筛选,无标签与正常标签互斥"
```
