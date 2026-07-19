# 未分类 & 无标签筛选 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 阅读端和管理端漫画列表新增"未分类"(category_id IS NULL)和"无标签"(无 comic_tag 关联)筛选能力,前后端用哨兵值 `_NONE` 通信。

**Architecture:** 哨兵值方案——前端下拉/多选里追加 `_NONE` option,watch 实现互斥;后端 SQL `ComicMapper.selectPage` 识别 `_NONE` 后走 `IS NULL` / `NOT EXISTS` 分支。接口签名(`ComicListQuery`)不变。

**Tech Stack:** Spring Boot 3 + MyBatis + Vue3 + Element Plus + Playwright e2e

**Spec:** `docs/superpowers/specs/2026-07-19-uncategorized-untagged-filter-design.md`

## Global Constraints

- 提交信息一律中文(conventional 前缀)
- 哨兵值固定为 `_NONE`(全大写,单个下划线前缀)
- 禁止改动:`ComicListQuery` 类型、API 接口、后端 service 层
- e2e 命令均在 `frontend/` 下执行;webServer 自动启动/复用 `npm run dev`(:5173)
- MySQL 验证:容器的 comic_atlas 库应已有 48 条 category_id=NULL 和 49 条无 tag 的 READY 漫画(验证基准)
- 管理端 `filters.tags` 是 `reactive` 对象属性——watch 方式与阅读端(`ref`)不同,必须用 `() => filters.tags` getter

---

### Task 1: 后端 SQL 哨兵值分支

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/mapper/ComicMapper.java`

**Interfaces:**
- Consumes: 现有 `query.category`(String) 和 `query.tags`(List\<String\>)
- Produces: category `_NONE` → `c.category_id IS NULL`;tags 含 `_NONE` → `NOT EXISTS (SELECT 1 FROM comic_tag ct WHERE ct.comic_id = c.id)`

- [ ] **Step 1: RED — 验证当前 SQL 不处理 `_NONE`**

在 MySQL 上执行当前逻辑等价查询,确认 `_NONE` 作为普通分类名匹配不到任何数据:

```bash
docker exec comicatlas-mysql mysql -uroot -proot comic_atlas -e "SELECT COUNT(*) FROM comic WHERE status='READY' AND EXISTS (SELECT 1 FROM category WHERE id=category_id AND name='_NONE');"
```
Expected: 0(没有名为 `_NONE` 的分类)

- [ ] **Step 2: 修改 category 条件为 `<choose>`**

将 `ComicMapper.java` 第 51-53 行的:

```xml
<if test='query.category != null and query.category != ""'>
    AND EXISTS (SELECT 1 FROM category cat WHERE cat.id = c.category_id AND cat.name = #{query.category})
</if>
```

替换为:

```xml
<if test='query.category != null and query.category != ""'>
    <choose>
        <when test='query.category == "_NONE"'>
            AND c.category_id IS NULL
        </when>
        <otherwise>
            AND EXISTS (SELECT 1 FROM category cat WHERE cat.id = c.category_id AND cat.name = #{query.category})
        </otherwise>
    </choose>
</if>
```

- [ ] **Step 3: 修改 tags 条件为 `<choose>`**

将 `ComicMapper.java` 第 32-47 行(整个 `<if test='query.tags...'>` 块)替换为:

```xml
<if test='query.tags != null and query.tags.size > 0'>
    <choose>
        <when test='query.tags.contains(&quot;_NONE&quot;)'>
            AND NOT EXISTS (SELECT 1 FROM comic_tag ct WHERE ct.comic_id = c.id)
        </when>
        <otherwise>
            <choose>
                <when test='query.tagMode == &quot;AND&quot;'>
                    AND (SELECT COUNT(DISTINCT t.name) FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                         WHERE ct.comic_id = c.id AND t.name IN
                         <foreach collection='query.tags' item='tagName' open='(' separator=',' close=')'>#{tagName}</foreach>
                        ) = #{query.tags.size}
                </when>
                <otherwise>
                    AND EXISTS (SELECT 1 FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                                WHERE ct.comic_id = c.id AND t.name IN
                                <foreach collection='query.tags' item='tagName' open='(' separator=',' close=')'>#{tagName}</foreach>
                               )
                </otherwise>
            </choose>
        </otherwise>
    </choose>
</if>
```

- [ ] **Step 4: GREEN — MySQL 验证两条新分支**

```bash
# 验证未分类
docker exec comicatlas-mysql mysql -uroot -proot comic_atlas -e "SELECT COUNT(*) FROM comic WHERE status NOT IN ('PLACEHOLDER','DELETED') AND category_id IS NULL;"
# Expected: 48(或接近,取决于当前 DB 状态)

# 验证无标签
docker exec comicatlas-mysql mysql -uroot -proot comic_atlas -e "SELECT COUNT(*) FROM comic c WHERE status NOT IN ('PLACEHOLDER','DELETED') AND NOT EXISTS (SELECT 1 FROM comic_tag ct WHERE ct.comic_id=c.id);"
# Expected: 49(或接近)
```

- [ ] **Step 5: 编译**

Run(workdir repo root): `.\mvnw.cmd -q -pl api-service compile`(或 `mvn -q -pl api-service compile`)
Expected: EXIT=0

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/comic/mapper/ComicMapper.java
git commit -m "feat(后端): ComicMapper 支持未分类(_NONE→IS NULL)和无标签(_NONE→NOT EXISTS)筛选"
```

---

### Task 2: 前端阅读端 LibraryPage 新增选项 + 互斥

**Files:**
- Modify: `frontend/src/views/reading/LibraryPage.vue`
- Test: `frontend/e2e/library-filter.spec.ts`(追加测试)

**Interfaces:**
- Consumes: Task 1 后端 SQL 哨兵值分支;现有 `categoryFilter`(ref) 和 `selectedTags`(ref)
- Import 新增: `watch` from `vue`

- [ ] **Step 1: 写失败测试**

在 `library-filter.spec.ts` 末尾追加:

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
  await expect(page.locator('.comic-poster').first()).toBeVisible()

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
Expected: FAIL(option 不存在或 selectOption 超时)

- [ ] **Step 3: 模板修改 —— 分类下拉追加选项**

在 `.category-select select` 内 `<option value="">全部分类</option>` 之后插入:

```html
<option value="_NONE">未分类</option>
```

- [ ] **Step 4: 模板修改 —— 标签多选追加选项**

在 tag `el-select` 内 `v-for="tag in allTags"` 的 `<el-option>` 之后追加:

```html
<el-option label="无标签" value="_NONE" />
```

- [ ] **Step 5: script 修改 —— 新增互斥 watch**

在 import 行追加 `watch`:

```typescript
import { ref, computed, onMounted, watch } from 'vue'
```

在 `loadCategories()` 函数之后、`onMounted()` 之前追加:

```typescript
watch(selectedTags, (val) => {
  if (!val.includes('_NONE')) return
  // _NONE 被选中 → 保留正常标签,移除 _NONE
  // (用户不管是从无标签状态点正常标签,还是选了正常标签又点无标签,行为一致:清除 _NONE 保留真实标签)
  if (val.length > 1) {
    nextTick(() => {
      selectedTags.value = val.filter(v => v !== '_NONE')
    })
  }
}, { deep: true })
```

注意:必须用 `nextTick` 包裹赋值,否则 Element Plus `el-select` 多选的内部 `set` 会回冲 `selectedTags.value`;多选正常标签时 `val.includes('_NONE')` 为 false 直接 return,不进入过滤分支,避免无限循环。

- [ ] **Step 6: import 补充 `watch`, `nextTick`**

```typescript
import { ref, computed, onMounted, watch, nextTick } from 'vue'
```

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

---

### Task 3: 前端管理端 ComicListPage 新增选项 + 互斥

**Files:**
- Modify: `frontend/src/views/management/ComicListPage.vue`

**Interfaces:**
- Consumes: Task 1 后端 SQL;现有 `filters.tags`(reactive 数组属性);`categoryStore.list`
- Import 新增: `watch`, `nextTick` from `vue`

- [ ] **Step 1: 模板 —— 分类 el-select 追加选项**

在分类 `<el-select>` 内 `v-for="c in categoryStore.list"` 的 `<el-option>` **之前**插入:

```html
<el-option label="未分类" value="_NONE" />
```

- [ ] **Step 2: 模板 —— 标签 el-select 追加选项**

在标签 `<el-select>` 内现有 tag option 之后追加:

```html
<el-option label="无标签" value="_NONE" />
```

- [ ] **Step 3: script —— import 追加 `watch`, `nextTick`**

```typescript
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
```

- [ ] **Step 4: script —— 互斥 watch**

在 `applyFilters()` 之前追加(管理端 `filters.tags` 是 reactive 属性,watch 用 getter):

```typescript
watch(() => filters.tags, (val) => {
  if (!val.includes('_NONE')) return
  if (val.length > 1) {
    nextTick(() => {
      filters.tags = val.filter(v => v !== '_NONE')
    })
  }
}, { deep: true })
```

注意:与阅读端逻辑一致——`_NONE` 被选中时保留正常标签移除 `_NONE`;多选正常标签时不进入过滤分支避免无限循环;`nextTick` 处理 Element Plus 内部状态时序。

- [ ] **Step 5: 回归 —— 全量 e2e**

Run(workdir `frontend/`): `npx playwright test --reporter=list`
Expected: 全部 PASS

- [ ] **Step 6: 类型检查**

Run(workdir `frontend/`): `npx vue-tsc -b --noEmit`
Expected: EXIT=0

- [ ] **Step 7: Commit**

```bash
git add frontend/src/views/management/ComicListPage.vue
git commit -m "feat(管理端): 漫画列表支持未分类与无标签筛选,无标签与正常标签互斥"
```

---

## 验收核对(全部任务完成后)

对照 spec 验收标准:

1. 阅读端分类下拉含"未分类",选中后只显示未设定分类的漫画 → Task 2(模板 + e2e 断言 category=_NONE)
2. 阅读端标签多选含"无标签",选中后只显示无标签的漫画 → Task 2(模板 + e2e 互斥断言)
3. "无标签"与正常标签互斥 → Task 2/3(watch + nextTick)
4. 管理端同步具备 → Task 3
5. 现有筛选不受影响 → Task 2 Step 7(全量 e2e 回归 5 passed) + 后端 API 重启后手动验证
