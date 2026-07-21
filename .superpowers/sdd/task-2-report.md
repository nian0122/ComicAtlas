# Task 2 报告：LibraryPage 移除状态筛选

**Status: DONE**
**Commit: e1edbef `feat(阅读端): 漫画库移除状态筛选下拉`**

## 实现内容

按 brief 逐字执行（TDD）：

1. **测试追加**：`frontend/e2e/library-filter.spec.ts` 末尾追加测试 `工具栏不含状态筛选下拉`，复用 Task 1 的 `mockRoutes` / `CapturedParams`，断言 `.status-select` 节点 count 为 0。未改动 Task 1 的 helper 与既有测试。
2. **LibraryPage.vue 4 处删除 + 1 处注释更新**：
   - (a) 模板 `.toolbar-filters` 内整个 `.status-select` 下拉块（原 33-41 行）
   - (b) script `const statusFilter = ref('')`（原 135 行）
   - (c) `onSearch()` 中 `status: statusFilter.value || undefined,`（原 175 行）
   - (d) 桌面端样式 `.status-select { order: 2; }`（原 331 行）
   - 注释由 `搜索 → 状态 → 排序 → 标签 → 标签模式` 更新为 `搜索 → 分类 → 排序 → 标签 → 标签模式`

行号均与 brief 一致，编辑前已核对磁盘实际内容，无漂移。

## TDD Evidence

### RED（Step 2）

命令（workdir `frontend/`）：`npx playwright test e2e/library-filter.spec.ts --reporter=list`

```
  ok 1 [chromium] › e2e\library-filter.spec.ts:65:1 › 漫画库请求恒带 status=READY (1.8s)
  x  2 [chromium] › e2e\library-filter.spec.ts:76:1 › 工具栏不含状态筛选下拉 (6.0s)

    Error: expect(locator).toHaveCount(expected) failed
    Locator:  locator('.status-select')
    Expected: 0
    Received: 1

  1 failed
  1 passed (9.4s)
```

失败原因符合预期：状态下拉当时仍存在于模板中（count 1 ≠ 0），且 Task 1 测试保持 PASS。

### GREEN（Step 4）

同一命令：

```
  ok 1 [chromium] › e2e\library-filter.spec.ts:65:1 › 漫画库请求恒带 status=READY (1.9s)
  ok 2 [chromium] › e2e\library-filter.spec.ts:76:1 › 工具栏不含状态筛选下拉 (969ms)

  2 passed (4.3s)
```

## 类型检查（Step 5）

命令（workdir `frontend/`）：`npx vue-tsc -b --noEmit`
结果：**退出码 0**，无任何报错、无 `statusFilter` 残留引用。

## 变更文件

| 文件 | 变更 |
|------|------|
| `frontend/src/views/reading/LibraryPage.vue` | -14 行（4 处删除）+1 行（注释更新） |
| `frontend/e2e/library-filter.spec.ts` | +10 行（新测试） |

提交仅含以上 2 文件（`git commit` 输出：`2 files changed, 11 insertions(+), 14 deletions(-)`）。未触碰 `.omo/*`、`frontend/playwright-report/`、`poster-status.ts`、`stores/management/**`、其他 e2e 文件。

## Self-Review

- [x] 新测试先 FAIL（`.status-select` count 1），删除后 PASS
- [x] 全文件 grep `statusFilter|status-select` 零匹配；vue-tsc 干净
- [x] 桌面端 order 注释已更新为 `搜索 → 分类 → 排序 → 标签 → 标签模式`
- [x] 精确提交 2 个文件，未沾染无关脏文件
- [x] 新断言不依赖 `'SUCCESS'`（mock 数据用 `'READY'`，由 Task 1 helper 提供）

## Concerns

无。git 提示 LF→CRLF 换行警告为 Windows 环境常规现象，不影响提交内容。
