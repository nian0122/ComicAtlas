# Task 3 报告:LibraryPage 新增分类筛选

**Status:** DONE
**Commit:** `3f4e3a8` feat(阅读端): 漫画库新增分类筛选下拉

## 实现内容

1. **`frontend/e2e/library-filter.spec.ts`**:文件末尾追加测试「分类筛选:选中传 category,切回全部不传」(逐字取自 brief Step 1)——断言 `.category-select select` 可见、3 个 option(全部分类/少年/青年)、选「少年」后最后一次 /api/comics 请求 `category=少年`、切回「全部分类」后 `category` 为 null。
2. **`frontend/src/views/reading/LibraryPage.vue`** 6 处修改(均逐字取自 brief Step 3):
   - (a) 模板:`.toolbar-filters` 首位(`.tag-filter` 之前)插入 `.filter-select.category-select` 原生 `<select>`,首项「全部分类」value=''
   - (b) import:`import { tagApi, categoryApi } from '@/services/management'`;类型 import 加入 `CategoryDTO`
   - (c) refs:`allTags` 之后追加 `categoryFilter = ref('')` 与 `allCategories = ref<CategoryDTO[]>([])`
   - (d) `loadTags()` 之后追加 `loadCategories()`(失败置空,模式一致)
   - (e) `onSearch()` 在 keyword 之后加 `category: categoryFilter.value || undefined`;`onMounted` 在 `loadTags()` 之后加 `loadCategories()`
   - (f) 桌面端媒体查询:`.search-input { order: 1; }` 之后补 `.category-select { order: 2; }`

## TDD Evidence

### RED(先失败)

命令(workdir `frontend/`):`npx playwright test e2e/library-filter.spec.ts --reporter=list`

```
  ok 1 › 漫画库请求恒带 status=READY (1.8s)
  ok 2 › 工具栏不含状态筛选下拉 (936ms)
  x  3 › 分类筛选:选中传 category,切回全部不传 (10.8s)

    Error: expect(locator).toBeVisible() failed
    Locator: locator('.category-select select')
    Expected: visible
    Timeout: 10000ms
    Error: element(s) not found

  1 failed / 2 passed (15.2s)  EXIT=1
```

失败原因符合预期:实现前 `.category-select select` 不存在,`toBeVisible` 超时;前两个既有测试仍 PASS。

### GREEN(实现后通过)

同一命令:

```
  ok 1 › 漫画库请求恒带 status=READY (2.1s)
  ok 2 › 工具栏不含状态筛选下拉 (1.2s)
  ok 3 › 分类筛选:选中传 category,切回全部不传 (989ms)

  3 passed (6.3s)  EXIT=0
```

## 类型检查

`npx vue-tsc -b --noEmit` → 退出码 0,无输出。

## 全量 e2e 回归

`npx playwright test --reporter=list`:

```
  ok 1 comic-list.spec.ts › desktop: renders library with posters, sticky toolbar, hover scale and pagination
  ok 2 comic-list.spec.ts › mobile: 3-column grid with sm posters
  ok 3 comic-poster.spec.ts › ComicPoster scales on hover
  ok 4 library-filter.spec.ts › 漫画库请求恒带 status=READY
  ok 5 library-filter.spec.ts › 工具栏不含状态筛选下拉
  ok 6 library-filter.spec.ts › 分类筛选:选中传 category,切回全部不传

  6 passed (9.3s)  EXIT=0
```

comic-list 2 + comic-poster 1 + library-filter 3 = 6,全部通过。

## 变更文件

- `frontend/src/views/reading/LibraryPage.vue`(修改)
- `frontend/e2e/library-filter.spec.ts`(追加测试)

提交 `3f4e3a8`:2 files changed, 39 insertions(+), 2 deletions(-)。仓库中无关脏文件(`.omo/*`、`.superpowers/`、`frontend/playwright-report/`)未触碰、未提交。

## 自检清单

- [x] 新测试先 FAIL(`.category-select select` toBeVisible 超时),实现后本文件 3 passed
- [x] 全量 6 passed
- [x] `categoryApi` 从 `@/services/management` import(已核实 management.ts re-export 自 api.ts)
- [x] 原生 `<select>` + `.filter-select` 样式模式,未用 el-select
- [x] vue-tsc 退出码 0
- [x] 仅提交 brief 指定的 2 个文件,中文提交信息
- [x] 未改动禁改文件(poster-status.ts、stores/management、后端、comic-list/comic-poster spec)
- [x] 新增断言未依赖 status 'SUCCESS'

## Concerns

无。git 提示 LF→CRLF warning 为 Windows 环境常规行为,不影响内容。
