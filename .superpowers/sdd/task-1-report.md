# Task 1 Report: comic-store 固定 READY + e2e 请求参数断言

**Status: DONE**
**Commit: `8ed0f53` feat(阅读端): 漫画列表固定只请求 READY 漫画**

## 实现内容

1. **新建 `frontend/e2e/library-filter.spec.ts`**（74 行，逐字取自任务简报）
   - `CapturedParams` 接口 + `mockRoutes(page, captured)` helper（拦截 `/api/comics**`、`/api/categories**`、`/api/tags**`，记录 status/category query 参数，返回 `{ code, data }` 包裹形状），供后续任务复用
   - 测试「漫画库请求恒带 status=READY」：访问 `/library`，断言每个 `/api/comics` 请求都带 `status=READY`

2. **修改 `frontend/src/stores/comic-store.ts:42`**（一行）
   - 旧：`const res = await comicApi.list(state.query)`
   - 新：`const res = await comicApi.list({ ...state.query, status: 'READY' })`
   - READY 硬编码在请求处，任何 `search()`/`updateQuery()` patch 都无法覆盖

## TDD Evidence

### RED（实现前）

命令（workdir `frontend/`）：`npx playwright test e2e/library-filter.spec.ts --reporter=list`

```
  x  1 [chromium] › e2e\library-filter.spec.ts:65:1 › 漫画库请求恒带 status=READY (2.0s)

    Error: expect(received).toBe(expected) // Object.is equality
    Expected: true
    Received: false
    > 73 |   expect(captured.status.every((s) => s === 'READY')).toBe(true)

  1 failed
```

失败原因与简报预期完全一致：当时 store 不传 status，捕获到的 query 值为 `null`，`every((s) => s === 'READY')` 为 false。（`captured.status.length > 0` 已通过，说明请求确实被拦截并记录——失败点精确落在缺 status 参数上，而非 mock/路由问题。）

### GREEN（实现后）

命令：`npx playwright test e2e/library-filter.spec.ts --reporter=list`

```
  ok 1 [chromium] › e2e\library-filter.spec.ts:65:1 › 漫画库请求恒带 status=READY (1.8s)

  1 passed (3.2s)
```

### 回归（Step 5）

命令：`npx playwright test e2e/comic-list.spec.ts --reporter=list`

```
  ok 1 [chromium] › e2e\comic-list.spec.ts:36:1 › desktop: renders library with posters, sticky toolbar, hover scale and pagination (2.6s)
  ok 2 [chromium] › e2e\comic-list.spec.ts:78:1 › mobile: 3-column grid with sm posters (1.1s)

  2 passed (5.1s)
```

## Files Changed（提交内容）

```
8ed0f53 feat(阅读端): 漫画列表固定只请求 READY 漫画
 frontend/e2e/library-filter.spec.ts | 74 ++++++++++++++++ (new)
 frontend/src/stores/comic-store.ts  |  2 +-
 2 files changed, 75 insertions(+), 1 deletion(-)
```

## Self-Review

- [x] RED 先失败且失败原因正确（status 为 null，非环境/mock 问题）
- [x] GREEN 通过（1 passed）
- [x] 回归 comic-list.spec.ts 通过（2 passed），其 `/api/comics**` 通配 mock 不校验 query，不受影响
- [x] 提交仅含简报指定的 2 个文件；`.omo/*`、`.superpowers/`、`frontend/playwright-report/` 等无关脏文件未触碰、未提交
- [x] 约束遵守：未改 `poster-status.ts`、`stores/management/**`、后端、`comic-list.spec.ts`、`comic-poster.spec.ts`；断言不依赖 `'SUCCESS'`
- [x] 测试输出干净，无多余错误（仅 git 的 LF/CRLF 常规 warning，非测试输出）

## Concerns

无。一处观察（非问题）：`ComicListQuery` 类型含 `status?: string` 字段，故 `{ ...state.query, status: 'READY' }` 类型检查通过；若后续任务从 query 类型中移除 status 字段，此处硬编码字面量仍兼容（对象字面量扩展属性）——后续任务处理时留意即可。
