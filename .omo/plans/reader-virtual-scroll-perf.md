# reader-virtual-scroll-perf - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**What you'll get:** 阅读器滚动、跳转、预加载和图片生命周期四阶段性能优化，保持现有 `RecycleScroller` 精确布局不变。

**Why this approach:** ComicAtlas 已知每页精确尺寸，保留 `RecycleScroller` 并通过 size 缓存 + 前缀和把查找降到 O(log n) 是最贴合当前架构的；再用 IntersectionObserver 输出真实可见范围驱动预加载，比基于 currentPage 的盲预加载更精准。

**What it will NOT do:**
- 不会把虚拟滚动组件换成 `DynamicScroller`。
- 不会为了分数移除任何现有 UI 功能或交互。
- 不会引入 Web Worker 等重型方案。

**Effort:** Medium
**Risk:** Medium - 虚拟滚动与 IntersectionObserver 的边界处理需要仔细验证，否则可能引发 currentPage 抖动或预加载遗漏。
**Decisions to sanity-check:**
- 保留 `RecycleScroller` 而非切 `DynamicScroller`。
- `IntersectionObserver` 只输出 visible range，不直接参与预加载决策。
- `ProgressiveImage` 从双 `<img>` 改为单 `<img>` + 独立 loader。

Your next move: 批准计划后开始执行，或先运行高准确度 review。Full execution detail follows below.

---

> TL;DR (machine): Medium effort / medium risk / keep RecycleScroller, add size cache + prefix sum + IntersectionObserver visible range + PreloadEngine + single-img ProgressiveImage

## Scope
### Must have
- 保留 `RecycleScroller` 与精确尺寸计算，不切换为 `DynamicScroller`。
- 在 `ReaderViewport.vue` 中实现 item size 缓存与前缀和，将页面查找/跳转从 O(n) 降到 O(log n)。
- 引入 `IntersectionObserver` 输出真实可见范围，并驱动 currentPage 更新。
- 新增 `PreloadEngine` 工具类，按 visible range 做 immediate + cascade 预加载，并取消远离请求。
- `ReaderPage.vue` 集成 `PreloadEngine`，替换原有 `preloadPages()` 逻辑。
- 优化 `ProgressiveImage.vue` 生命周期：DOM 中只保留一个 `<img>`，HQ 加载通过独立 loader 完成并正确取消。

### Must NOT have (guardrails, anti-slop, scope boundaries)
- 不切换虚拟滚动组件。
- 不引入 Web Worker、WASM、Service Worker 等重型方案。
- 不改变现有阅读器 UI、交互、URL 路由。
- 不删除 `LQ_ONLY` / `HQ_ONLY` / `AUTO` 三种 quality mode。
- 不为了性能而移除进度保存、键盘快捷键、双击缩放等功能。

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after + `npx vue-tsc --noEmit` + `npm run build`
- Evidence: .omo/evidence/task-<N>-reader-virtual-scroll-perf.<ext>
- 手动 QA 使用 Playwright 或本地 dev server 验证滚动、跳转、章节切换、预加载网络行为。

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.

Wave 1: Phase IA-1 - Size Cache + Prefix Sum + Binary Search
Wave 2: Phase IA-2 - Visible Range + IntersectionObserver
Wave 3: Phase IA-3 - PreloadEngine
Wave 4: Phase IA-4 - ProgressiveImage 生命周期
Wave 5: Final verification

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 | - | 2, 3, 4 | - |
| 2 | 1 | 3, 4 | - |
| 3 | 1, 2 | 4 | - |
| 4 | - | - | 2, 3 |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->

- [ ] 1. Phase IA-1: Size Cache + Prefix Sum + Binary Search
  What to do / Must NOT do:
  - 在 `ReaderViewport.vue` 内实现 size 缓存：key 包含 pageId、zoom、fitMode、readingDirection、viewportWidth、viewportHeight；仅当这些参数变化时才重建 size 数组。
  - 维护 `prefixHeights: number[]` 与 `prefixWidths: number[]`（horizontal 模式下使用），重建触发器为：`props.pages` 变化、`zoom` 变化、`fitMode` 变化、`readingDirection` 变化、`containerWidth`/`containerHeight` 变化。
  - 将 `deriveCurrentPage` 改为 `upperBound(prefixHeights/prefixWidths, scrollOffset)`，将 `scrollToPage` 改为 `page === 1 ? 0 : prefixSum[page - 2]`。
  - `scrollerItems` 使用缓存后的 size，避免每次渲染重新计算。
  - Must NOT: 改变 `RecycleScroller` 的使用方式；引入外部依赖；删除 horizontal/vertical 方向支持。
  Parallelization: Wave 1 | Blocked by: - | Blocks: 2, 3
  References (executor has NO interview context - be exhaustive):
  - Design spec: docs/superpowers/specs/2026-07-13-reader-virtual-scroll-perf-design.md Phase IA-1
  - Source: frontend/src/components/reader/ReaderViewport.vue (computeItemSize, deriveCurrentPage, scrollToPage, scrollerItems)
  - Type: frontend/src/types/index.ts (PageInfo)
  - Settings: frontend/src/stores/reader-settings-store.ts (FitMode, zoom, ReadingDirection)
  Acceptance criteria (agent-executable):
  - `npx vue-tsc --noEmit` passes.
  - `npm run build` passes.
  - 在 `ReaderViewport.vue` 中不存在对 `props.pages` 的线性累加用于 `deriveCurrentPage` / `scrollToPage`。
  QA scenarios (name the exact tool + invocation): happy + failure, Evidence .omo/evidence/task-1-reader-virtual-scroll-perf.<ext>
  - Happy: 启动 dev server，访问 `/comics/1/read?chapterId=1&page=50`，Toolbar 显示第 50 页，切换 readingDirection 后 `scrollerItems` 尺寸正确更新。
  - Failure: 在 `deriveCurrentPage` 中传入空 `prefixHeights`，函数返回 1 且不抛错。
  - Evidence: Playwright 截图或 console 日志保存到 .omo/evidence/task-1-reader-virtual-scroll-perf.log
  Commit: Y | feat(reader): size cache + prefix sum + binary search for viewport

- [ ] 2. Phase IA-2: Visible Range + IntersectionObserver
  What to do / Must NOT do:
  - 在 `ReaderViewport.vue` 的 slot 模板中为 `ReaderImageItem` 外层包装 `<div :data-index="index">`。
  - 使用 `IntersectionObserver`（threshold `[0, 0.25, 0.5, 0.75, 1]`）观察当前渲染的活跃 item；回调中始终读取 `el.dataset.index` 的当前值，以兼容 RecycleScroller 的节点回收。
  - 选择 ratio 最高的 index 作为当前页，通过 `emit('update:currentPage')` 通知父组件；仅在非程序性滚动期间且页码确实变化时才 emit。
  - 计算当前最小/最大可见 index，通过 `emit('visible-range', { start, end, total })` 通知父组件（`total = props.pages.length`）。
  - 保留 `onScroll` 作为降级/补充；程序性滚动期间 `isProgrammaticScroll` 同时屏蔽 `onScroll` 与 IO 导致的 currentPage 更新，避免 scrollToPage → IO → scrollToPage 反馈循环。
  - Must NOT: 让 IO 直接触发预加载；观察非活跃/已卸载节点；在 SSR 环境下崩溃。
  Parallelization: Wave 2 | Blocked by: 1 | Blocks: 3
  References (executor has NO interview context - be exhaustive):
  - Design spec: docs/superpowers/specs/2026-07-13-reader-virtual-scroll-perf-design.md Phase IA-2
  - Source: frontend/src/components/reader/ReaderViewport.vue
  - Reference: D:/projects/comics_develop/comics15/frontend/src/pages/ReaderPage.vue setupPageObserver
  Acceptance criteria (agent-executable):
  - `npx vue-tsc --noEmit` passes.
  - `npm run build` passes.
  - `ReaderViewport` 组件声明 emits `update:currentPage` 和 `visible-range`。
  QA scenarios (name the exact tool + invocation): happy + failure, Evidence .omo/evidence/task-2-reader-virtual-scroll-perf.<ext>
  - Happy: 启动 dev server，访问 `/comics/1/read?chapterId=1&page=50`，缓慢滚动，Toolbar 当前页与真实可见页一致。
  - Failure: 在 jsdom 等不支持 IntersectionObserver 的环境，currentPage 推导降级到 scroll-based，不抛错。
  - Evidence: Playwright 截图或 console 日志保存到 .omo/evidence/task-2-reader-virtual-scroll-perf.log
  Commit: Y | feat(reader): track visible range via IntersectionObserver

- [ ] 3. Phase IA-3: PreloadEngine
  What to do / Must NOT do:
  - 新增 `src/utils/preload-engine.ts`：实现 `setUrlResolver`、`onVisibleChange`、`reset`、`loadImmediate`、`loadCascadeForward`、`loadCascadeBackward`、`cancelFarIndices`。
  - Immediate 范围：visible ± 1（使用 HQ URL）；Cascade 范围：从 visible 边界向外各 10 页（使用 LQ URL），80ms 间隔；Cancel 范围：visible ± 12 页外。
  - URL resolver 策略：immediate 索引返回 `page.hqUrl`，cascade 索引返回 `page.lqUrl`。
  - `ReaderPage.vue` 监听 `visible-range`，仅在 `settings.enablePreload` 为 true 时调用 `preloadEngine.onVisibleChange(start, end, total)`。
  - 章节切换时调用 `preloadEngine.reset(total)`，清空所有在途任务。
  - 移除 `ReaderPage.vue` 中原有 `preloadPages()` 函数及调用；保留同一 watch 回调中的 `saveProgress` 逻辑。
  - 决定 `preloadWindow` 设置的命运：由于改为按 visible range 固定窗口，UI 中不再暴露 `preloadWindow`，store 中保留字段但不再读取（后续迭代可彻底移除）。
  - Must NOT: 在 `PreloadEngine` 中直接操作 Vue 状态；直接访问 `readerStore.pages`；无限制堆积请求。
  Parallelization: Wave 3 | Blocked by: 1, 2 | Blocks: -
  References (executor has NO interview context - be exhaustive):
  - Design spec: docs/superpowers/specs/2026-07-13-reader-virtual-scroll-perf-design.md Phase IA-3
  - Source: frontend/src/pages/ReaderPage.vue (preloadPages, onMounted, currentPage watch)
  - Reference: D:/projects/comics_develop/comics15/frontend/src/utils/preload-engine.ts
  - API types: frontend/src/types/index.ts (PageInfo)
  - Settings: frontend/src/stores/reader-settings-store.ts (enablePreload, preloadWindow)
  Acceptance criteria (agent-executable):
  - `npx vue-tsc --noEmit` passes.
  - `npm run build` passes.
  - 在 `ReaderPage.vue` 中不再出现 `preloadPages` 函数；`saveProgress` 调用仍保留在 `currentPage` watch 中。
  QA scenarios (name the exact tool + invocation): happy + failure, Evidence .omo/evidence/task-3-reader-virtual-scroll-perf.<ext>
  - Happy: 启动 dev server，访问 `/comics/1/read?chapterId=1&page=50`，通过 Playwright `page.on('request')` 断言 immediate 范围（49-51）请求先于 cascade 范围（39-48, 52-61）发出。
  - Failure: 快速滚动到页末再滚回开头，通过 `page.on('requestfailed')` / pending 计数断言无大量远离可视区请求堆积。
  - Evidence: Playwright 脚本日志保存到 .omo/evidence/task-3-reader-virtual-scroll-perf.log
  Commit: Y | feat(reader): add PreloadEngine driven by visible range

- [ ] 4. Phase IA-4: ProgressiveImage 生命周期
  What to do / Must NOT do:
  - 重构 `ProgressiveImage.vue`：DOM 中只保留一个 `<img>`；当前代码已有一个独立的 `hqLoader`（`ProgressiveImage.vue:106`），但 DOM 中仍同时渲染 LQ 和 HQ 两个 `<img>`，需要移除 HQ `<img>` DOM 元素。
  - LQ 模式/自动模式先显示 LQ；通过现有 `hqLoader` 或新建独立 `HTMLImageElement` 加载 HQ，加载成功后替换 `<img>` 的 `src` 并销毁 loader。
  - HQ_ONLY 模式直接加载并显示 HQ。
  - `<img>` 与 HQ loader 均设置 `decoding = 'async'`；URL 变化或组件卸载时取消在途 HQ 加载（移除 onload/onerror，设置 src=''，释放引用）。
  - 保留 skeleton、error 状态、双击/点击重试、fade transition。
  - Must NOT: 同时渲染 LQ 和 HQ 两个 `<img>`；在 URL 变化时保留旧的 HQ loader。
  Parallelization: Wave 4 | Blocked by: - | Blocks: -
  References (executor has NO interview context - be exhaustive):
  - Design spec: docs/superpowers/specs/2026-07-13-reader-virtual-scroll-perf-design.md Phase IA-4
  - Source: frontend/src/components/reader/ProgressiveImage.vue
  - Settings: frontend/src/stores/reader-settings-store.ts (QualityMode)
  Acceptance criteria (agent-executable):
  - `npx vue-tsc --noEmit` passes.
  - `npm run build` passes.
  - 在 `ProgressiveImage.vue` 模板中只存在一个 `<img>` 标签。
  QA scenarios (name the exact tool + invocation): happy + failure, Evidence .omo/evidence/task-4-reader-virtual-scroll-perf.<ext>
  - Happy: 启动 dev server，访问 `/comics/1/read?chapterId=1&page=1`，AUTO 模式下先看到 LQ，HQ 加载完成后通过 Playwright 截图像素对比或 class 断言确认高清图显示。
  - Failure: 使用 Playwright 连续触发 10 次页码跳转，断言 pending HQ 请求数不超过 5 个（旧 loader 被取消）。
  - Evidence: Playwright 截图 + 请求日志保存到 .omo/evidence/task-4-reader-virtual-scroll-perf.png
  Commit: Y | feat(reader): single-img ProgressiveImage with cancelable HQ loader

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit：对照本计划逐项检查四个 Phase 是否完整落地，依赖关系是否满足。
- [ ] F2. Code quality review：运行 `npx vue-tsc --noEmit` 与 `npm run build`，确认无类型/构建错误；检查无 `console.log` 遗留、无死代码。
- [ ] F3. Real manual QA：使用 Playwright 访问本地 dev server 的阅读器路由，验证 200+ 页章节快速滚动/跳转无卡顿，章节切换后旧预加载任务被清空。
- [ ] F4. Scope fidelity：确认未切换 `RecycleScroller`、未移除进度保存/快捷键/双击缩放、未引入 Web Worker。

## Commit strategy
- 每个 Phase 一个独立 commit，按顺序执行：
  1. `feat(reader): size cache + prefix sum + binary search for viewport`
  2. `feat(reader): track visible range via IntersectionObserver`
  3. `feat(reader): add PreloadEngine driven by visible range`
  4. `feat(reader): single-img ProgressiveImage with cancelable HQ loader`
- 不强制 push，仅在用户要求时执行 `git push`。

## Success criteria
- `ReaderViewport` 中 `deriveCurrentPage` / `scrollToPage` 不再使用线性扫描。
- size 计算仅在 `pages` / `zoom` / `fitMode` / `readingDirection` / `viewport` 变化时触发一次。
- `ReaderPage` 通过 `visible-range` 驱动预加载，原 `preloadPages` 逻辑被移除；`saveProgress` 逻辑保留。
- `ProgressiveImage` 模板中只有一个 `<img>`，HQ loader 可取消。
- `npx vue-tsc --noEmit` 与 `npm run build` 全部通过。
- Playwright QA：200+ 页章节快速滚动/跳转无卡顿，章节切换后旧预加载任务被清空。
