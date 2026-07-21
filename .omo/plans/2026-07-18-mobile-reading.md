# 2026-07-18-mobile-reading - Work Plan

## TL;DR (For humans)

**What you'll get:** ComicAtlas 阅读器在手机上变成全屏沉浸式体验——点一下屏幕中间显示工具栏和章节导航，4 秒不动自动隐藏。同时首页、漫画库、详情页的布局在手机上会重新排列（竖着放而不是横着放），目录树和阅读历史不再一次性渲染几百条而是只渲染看得见的部分。手机访问管理后台会被拦住并提示用电脑。

**Why this approach:** 只改交互方式和布局，不改页面结构。手机和电脑共享同一套页面代码，通过一个"当前是什么设备"的检测来决定用哪套工具栏和布局。这样以后加平板模式不需要重写整个阅读器。

**What it will NOT do:** 不会把网站变成可以离线打开的 PWA 应用。不会给平板做独立的交互模式。不会给管理后台做手机适配。不会改后端、数据库、导入流程。

**Effort:** Medium (18 个任务，5 个可并行的模块)
**Risk:** Low — 所有改动都在前端，不碰后端；vue-virtual-scroller 已经装好了；Reader 组件只有 1 个地方被引用，移动它不会影响其他页面。
**Decisions to sanity-check:** 768px 作为手机断点是否合适（目前 7/10 个已有响应式的地方都用它）；点击切换工具栏 vs 上滑显示是否符合使用习惯。

Your next move: approve and start work, or run a high-accuracy review first. Full execution detail follows below.

---

> TL;DR (machine): Medium effort, Low risk, 18 todos across 5 parallel waves — shared infra → Reader state machine + mobile UI → reading flow responsive CSS → management boundary → CatalogTree virtual list. Zero backend changes.

## Scope
### Must have
From `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §10:
1. Shared responsive composables (`useBreakpoint`, `useMediaQuery`) + `isMobileReadingDevice()` utility
2. Reader state machine (`ReaderUiState` + `ReaderAction` enums, transition table)
3. Reader composables (`useInteractionMode`, `useReaderGesture`, `useAutoHide`, `useReaderToolbar`, `useReaderNavigation`)
4. Mobile reader UI (`ReaderToolbarMobile`, `ReaderBottomNav`, `ReaderSettingsDrawer`)
5. Reader component migration (`components/reading/reader/` → `views/reading/reader/components/`)
6. `ReaderPage.vue` refactor (inject composables, mode-conditional rendering)
7. HomePage, LibraryPage, DetailPage responsive CSS (mobile-first layouts)
8. CatalogTree `RecycleScroller` (flatten tree, fixed row height 42px)
9. HistoryPage `RecycleScroller` (virtual list for reading history)
10. Router `beforeEach` guard for `/manage/*` on mobile devices
11. Management intercept page (redirect to home)

### Must NOT have (guardrails, anti-slop, scope boundaries)
- NO PWA (Service Worker, manifest, offline cache)
- NO tablet independent interaction mode
- NO layout splitting (keep 3 existing Layouts)
- NO view splitting (no MobileHomePage / DesktopHomePage)
- NO store changes (reader-store, comic-store, history-store untouched)
- NO API service changes
- NO backend changes
- NO management page mobile adaptation
- NO `mode` prop on business components (ComicCard, CatalogTree, etc.)
- NO `as any`, `@ts-ignore`, `@ts-expect-error` anywhere
- NO circular composable dependencies

### Design clarifications (from Metis gap analysis)
1. **`showToolbar` boolean**: `reader-settings-store.showToolbar` is **desktop-only**. On mobile, the state machine (`useReaderToolbar`) is authoritative. No store migration needed — mobile ignores the boolean, desktop continues using it.
2. **SwipeLeft/PinchZoom**: Emitted by `useReaderGesture` but **not wired in 0.3**. BottomNav handles chapter navigation. These are pass-through events for future gesture navigation (0.4+).
3. **Existing `@media` breakpoints**: Remain as-is in 10 existing files. `useBreakpoint()` is for new interaction/JS logic. CSS `@media` and JS composable coexist — they answer different questions (layout vs. interaction mode).
4. **CatalogTree virtualization**: Flat array via computed `flatItems` (recursive walk respecting `expandedIds`). Fixed row height 42px. See todo 18 for implementation details.
5. **Desktop event listeners**: Keyboard/wheel/dblclick handlers in ReaderPage must be guarded by `if (mode === 'desktop')` to avoid dead code and `passive: false` scroll penalty on mobile.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: **tests-after** + Playwright (mobile viewport 375×812 iPhone X)
- Framework: `@playwright/test` (already in project)
- Evidence: `.omo/evidence/task-<N>-2026-07-18-mobile-reading.<ext>`
- LSP diagnostics on every changed file after each todo
- TypeScript `vue-tsc --noEmit` after each wave
- `vite build` after all waves complete

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave.

| Wave | Priority | Todos | Depends on |
|------|----------|-------|------------|
| A | Shared Infra | 3 todos | — |
| B | P0 Reader | 8 todos | A |
| C | P1 Reading Flow | 4 todos | A |
| D | P2 Management | 2 todos | A |
| E | CatalogTree | 1 todo | — |

Execution: Wave A first (unlocks B, C, D). Then B, C, D, E all in parallel. Wave E is independent.

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 | — | 4,13 | 2,3 |
| 2 | — | 4 | 1,3 |
| 3 | — | 16 | 1,2 |
| 4 | 1,2 | 5,6,7,8,9,12,14 | — |
| 5 | 4 | 8 | 6,7,9 |
| 6 | 4 | 8 | 5,7,9 |
| 7 | 4 | 8 | 5,6,9 |
| 8 | 4,5,6,7 | 10 | 9 |
| 9 | 4 | 10 | 5,6,7,8 |
| 10 | 8,9 | 11 | — |
| 11 | 10 | — | — |
| 12 | 4 | — | 13,14,15,17,18 |
| 13 | 1 | — | 12,14,15,17,18 |
| 14 | 4 | — | 12,13,15,17,18 |
| 15 | — | — | 12,13,14,17,18 |
| 16 | 3 | — | 17,18 |
| 17 | — | — | 12,13,14,15,16,18 |
| 18 | — | — | 12,13,14,15,16,17 |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->

### Wave A — Shared Responsive Infrastructure (3 todos, unblocks B/C/D)

- [x] 1. Create `useBreakpoint()` composable
  What to do: Create `frontend/src/composables/useBreakpoint.ts`. Returns `Ref<number>` — the current `window.innerWidth`, reactively updated on resize with debounce (100ms). Export `BREAKPOINTS` constant: `{ mobile: 768, tablet: 1024, desktop: 1440 }`.
  Must NOT do: Do NOT hardcode 768/1024/1440 in any other file. Do NOT use `window.innerWidth` directly — always go through this composable. Do NOT create the composable directory manually — verify it doesn't exist first.
  Parallelization: Wave A | Blocked by: — | Blocks: 4,13 | Can parallelize with: 2,3
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §2 line 130, §9 line 623; `bg_75077a03` finding: no composable dir exists
  Acceptance criteria: `import { useBreakpoint, BREAKPOINTS } from '@/composables/useBreakpoint'` resolves. `useBreakpoint().value` returns a number that changes on window resize (verify in Playwright: resize viewport from 1920 → 375, assert value transitions). `BREAKPOINTS.mobile === 768`.
  QA scenarios: Happy — open app at 1920px, `useBreakpoint().value > 1024`. Resize to 375px, value updates to ≤ 768. Failure — component using useBreakpoint throws no error on SSR/unmounted access. Evidence: `.omo/evidence/task-1-2026-07-18-mobile-reading.txt` (console log of value on resize).
  Commit: Y | `feat(composables): add useBreakpoint with BREAKPOINTS constant`

- [x] 2. Create `useMediaQuery()` composable
  What to do: Create `frontend/src/composables/useMediaQuery.ts`. Returns `Ref<boolean>` — reactive match for a CSS media query string. Uses `window.matchMedia` + `change` event listener. Cleanup on unmount.
  Must NOT do: Do NOT use a different API (e.g. ResizeObserver). Do NOT leak listeners — must cleanup in `onBeforeUnmount` or return a `stop` function.
  Parallelization: Wave A | Blocked by: — | Blocks: 4 | Can parallelize with: 1,3
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §2 line 131-132; §9 line 624
  Acceptance criteria: `useMediaQuery('(pointer: coarse)').value` returns boolean matching the device. Toggling device emulation in DevTools updates the value reactively. Cleanup verified: no stale listeners after component unmount (check via Chrome DevTools Performance monitor).
  QA scenarios: Happy — `useMediaQuery('(hover: hover)')` returns `true` on desktop, `false` on touch device. Failure — calling `useMediaQuery` outside `setup()` context throws clear error. Evidence: `.omo/evidence/task-2-2026-07-18-mobile-reading.txt`.
  Commit: Y | `feat(composables): add useMediaQuery reactive media query hook`

- [x] 3. Create `isMobileReadingDevice()` utility
  What to do: Create `frontend/src/utils/device.ts`. Export pure function `isMobileReadingDevice(): boolean`. Detection: `window.matchMedia('(pointer: coarse)').matches && window.innerWidth <= 768`. No Vue dependency — usable in Router Guard. Reuse `BREAKPOINTS.mobile` from todo 1.
  Must NOT do: Do NOT depend on Vue composable lifecycle. Do NOT use `navigator.userAgent` sniffing. Do NOT create `utils/` dir if it doesn't exist — use `New-Item` only if needed.
  Parallelization: Wave A | Blocked by: — | Blocks: 16 | Can parallelize with: 1,2
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §2 line 148-152, §6 line 499; §9 line 625; `bg_75077a03` finding: no existing device detection
  Acceptance criteria: `import { isMobileReadingDevice } from '@/utils/device'` resolves. On desktop with mouse: returns `false`. On mobile emulation (375px + touch): returns `true`. On narrow desktop window (700px + mouse): returns `false` (has coarse pointer check).
  QA scenarios: Happy — verify 3 scenarios above via Playwright with different viewport + touch emulation combos. Failure — function called when `window` is undefined (SSR) returns `false` without throwing. Evidence: `.omo/evidence/task-3-2026-07-18-mobile-reading.txt`.
  Commit: Y | `feat(utils): add isMobileReadingDevice detection function`

### Wave B — P0 Reader State Machine + Mobile Toolbar (8 todos)

- [x] 4. Create `useInteractionMode()` composable
  What to do: Create `frontend/src/views/reading/reader/composables/useInteractionMode.ts`. Returns `InteractionContext: { mode, coarsePointer, supportsHover }` where mode is `computed(() => width.value <= BREAKPOINTS.mobile && coarsePointer.value ? 'mobile' : 'desktop')`. Uses `useBreakpoint` (todo 1) and `useMediaQuery` (todo 2). Export `InteractionContext` interface per design spec §2 line 123-127. Also export `InteractionMode` type alias `'desktop' | 'mobile'`.
  Must NOT do: Do NOT hardcode 768. Do NOT directly access `window.innerWidth`. Do NOT return just a boolean — must return the full context object.
  Parallelization: Wave B | Blocked by: 1,2 | Blocks: 5,6,7,8,9,12,14 | Can parallelize with: —
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §2 line 121-143; todo 1 (useBreakpoint), todo 2 (useMediaQuery)
  Acceptance criteria: Import resolves. On desktop: `mode === 'desktop'`, `coarsePointer === false`, `supportsHover === true`. On mobile emulation: `mode === 'mobile'`, `coarsePointer === true`. Value is reactive (updates on resize + touch toggle).
  QA scenarios: Happy — validate all 3 fields in both modes. Failure — accessing `.value` before mount returns sensible defaults (no crash). Evidence: `.omo/evidence/task-4-2026-07-18-mobile-reading.txt`.
  Commit: Y | `feat(reader): add useInteractionMode with InteractionContext`

- [x] 5. Create `ReaderUiState` + `ReaderAction` enums
  What to do: Create `frontend/src/views/reading/reader/composables/useReaderToolbar.ts` with exported enums at top. `enum ReaderUiState { IMMERSIVE, TOOLBAR, SETTINGS }`. `enum ReaderAction { TapCenter, OpenSettings, CloseSettings, AutoHideTimeout, AndroidBack }`. Export transition table as `const TRANSITIONS: Record<string, Record<string, ReaderUiState | 'EXIT'>>`.
  Must NOT do: Do NOT use string unions instead of enums. Do NOT put enums in a separate file (co-locate with the state machine).
  Parallelization: Wave B | Blocked by: 4 | Blocks: 8 | Can parallelize with: 6,7,9
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §3 line 163-176, transition table line 181-190
  Acceptance criteria: `ReaderUiState.IMMERSIVE === 0`, `ReaderAction.TapCenter === 0`. `TRANSITIONS[ReaderUiState.IMMERSIVE][ReaderAction.TapCenter] === ReaderUiState.TOOLBAR`. All 8 transitions from spec §3 present.
  QA scenarios: Happy — iterate all 8 transitions, verify correct next state. Failure — undefined action on a state returns undefined (no crash). Evidence: `.omo/evidence/task-5-2026-07-18-mobile-reading.txt`.
  Commit: Y | `feat(reader): add ReaderUiState and ReaderAction enums with transition table`

- [x] 6. Create `useAutoHide()` composable
  What to do: Create `frontend/src/views/reading/reader/composables/useAutoHide.ts`. Accepts `timeout: number` (default 4000). Returns `{ visible, show, hide, pause, resume }`. Uses `setTimeout` internally. `show()` sets visible=true and starts timer. `hide()` sets visible=false and clears timer. `pause()` clears timer without changing visible. `resume()` restarts timer. On unmount, cleanup timer. Pure timer logic — no awareness of gesture, toolbar, or settings.
  Must NOT do: Do NOT import anything from reader components or stores. Do NOT call hide/show automatically based on external state — only expose explicit methods.
  Parallelization: Wave B | Blocked by: 4 | Blocks: 8 | Can parallelize with: 5,7,9
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §3 line 219-223, line 244
  Acceptance criteria: `show()` → `visible === true`, timer starts. After 4000ms → `visible === false`. `pause()` during countdown → `visible` stays true, timer doesn't fire. `resume()` after pause → timer restarts from 4000ms. Unmount → timer cleared (no console warning).
  QA scenarios: Happy — sequence: show → wait 2s → pause → wait 4s → still visible → resume → wait 4s → hidden. Failure — show() called twice in a row doesn't create duplicate timers. Evidence: `.omo/evidence/task-6-2026-07-18-mobile-reading.txt`.
  Commit: Y | `feat(reader): add useAutoHide timer composable`

- [x] 7. Create `useReaderGesture()` composable
  What to do: Create `frontend/src/views/reading/reader/composables/useReaderGesture.ts`. Accepts `viewportRef: Ref<HTMLElement | null>`. Binds `pointerdown`/`pointerup` on viewport element in `onMounted`. Detects tap (down+up within 300ms and 10px) → emits `TapCenter`. Detects swipe (horizontal drag > 50px) → emits `SwipeLeft`/`SwipeRight`. Returns `{ onTap, onSwipe }` as `Ref` event emitters. Unbinds on unmount.
  Note: SwipeLeft/SwipeRight are emitted but NOT wired to chapter navigation in 0.3 — BottomNav handles navigation. These events are pass-through for future use (e.g. 0.4 gesture navigation). PinchZoom is emitted but not used in 0.3.
  Must NOT do: Do NOT call any toolbar or auto-hide functions. Do NOT access stores. Do NOT prevent default scroll behavior (let viewport handle it). Only standardize raw pointer events. Do NOT wire swipe to navigation.
  Parallelization: Wave B | Blocked by: 4 | Blocks: 8 | Can parallelize with: 5,6,9
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §3 line 239-242, line 252; `bg_11d4845e` finding: ReaderViewport is the sole gesture target
  Acceptance criteria: Tap center region → `onTap` event fires. Horizontal swipe > 50px → `onSwipe` fires with direction. Events do NOT fire during scroll (gesture detects intent). Cleanup: no listeners remain after unmount.
  QA scenarios: Happy — Playwright: `page.click('.reader-viewport')` triggers tap. `page.mouse.move(100,200); page.mouse.down(); page.mouse.move(300,200, {steps:5}); page.mouse.up()` triggers SwipeRight. Failure — rapid double-tap doesn't emit 3+ events (debounced). Evidence: `.omo/evidence/task-7-2026-07-18-mobile-reading.txt`.
  Commit: Y | `feat(reader): add useReaderGesture pointer/touch event composable`

- [x] 8. Create `useReaderToolbar()` state machine composable
  What to do: Create the state machine inside `frontend/src/views/reading/reader/composables/useReaderToolbar.ts`. Uses enums from todo 5. Uses `useAutoHide` (todo 6) internally. Exposes `{ state: Ref<ReaderUiState>, dispatch: (action: ReaderAction) => void }`. `dispatch` looks up `TRANSITIONS[state][action]`, updates state. On TOOLBAR state: calls `autoHide.show()`. On IMMERSIVE: calls `autoHide.hide()`. On SETTINGS: calls `autoHide.pause()`. On CloseSettings: calls `autoHide.resume()`. Initial state: IMMERSIVE. State is reactive.
  Must NOT do: Do NOT import gesture composable. Do NOT render UI. Do NOT handle Android back (that's ReaderPage's job).
  Parallelization: Wave B | Blocked by: 4,5,6,7 | Blocks: 10 | Can parallelize with: 9
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §3 line 247-249; todo 5 (enums), todo 6 (autoHide), todo 7 (gesture)
  Acceptance criteria: `dispatch(TapCenter)` from IMMERSIVE → state = TOOLBAR, autoHide timer starts. `dispatch(TapCenter)` from TOOLBAR → state = IMMERSIVE. `dispatch(AutoHideTimeout)` from TOOLBAR → state = IMMERSIVE. `dispatch(OpenSettings)` from TOOLBAR → state = SETTINGS, autoHide paused. `dispatch(CloseSettings)` from SETTINGS → state = TOOLBAR. All 8 transitions from spec §3 verified.
  QA scenarios: Happy — full state machine test: IMMERSIVE → tap → TOOLBAR → wait 4s → IMMERSIVE. TOOLBAR → open settings → SETTINGS → close → TOOLBAR. Failure — dispatch on Exit state (IMMERSIVE + AndroidBack) handled gracefully (state unchanged, caller checks). Evidence: `.omo/evidence/task-8-2026-07-18-mobile-reading.txt`.
  Commit: Y | `feat(reader): add useReaderToolbar state machine composable`

- [x] 9. Create `useReaderNavigation()` composable
  What to do: Create `frontend/src/views/reading/reader/composables/useReaderNavigation.ts`. Returns `{ goBack, goPrevChapter, goNextChapter, goToCatalog }`. Uses `useRouter` and `useReaderStore`. `goBack` → router to `/comic/:comicId`. `goPrevChapter` → router to `/reader/:prevChapterId`. `goNextChapter` → router to `/reader/:nextChapterId`. `goToCatalog` → scrolls to catalog in DetailPage.
  Must NOT do: Do NOT hardcode routes as strings — use named routes. Do NOT navigate if chapterId is null/undefined.
  Parallelization: Wave B | Blocked by: 4 | Blocks: 10 | Can parallelize with: 5,6,7,8
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §9 line 572; current `ReaderPage.vue:66-77` (goBack, goChapter functions)
  Acceptance criteria: `goBack()` when `store.comicId > 0` → navigates to `/comic/:id`. `goPrevChapter()` when `store.prevChapterId` exists → navigates to `/reader/:chapterId`. Navigation is no-op when IDs are null.
  QA scenarios: Happy — with mock store having comicId=1, prevChapterId=5, verify router.push called with correct paths. Failure — calling `goNextChapter()` with null nextChapterId does nothing (no error). Evidence: `.omo/evidence/task-9-2026-07-18-mobile-reading.txt`.
  Commit: Y | `feat(reader): add useReaderNavigation composable`

- [x] 10. Create mobile reader UI components
  What to do: Create 3 new files under `frontend/src/views/reading/reader/components/`:
  1. `ReaderToolbarMobile.vue` — Height 48px, semi-transparent background. Left: ← back button (calls `emit('back')`). Center: comic name (truncated, `max-width: 60vw`). Right: ⋯ button (calls `emit('openSettings')`). Safe area: `padding-top: env(safe-area-inset-top)`. CSS animation: fade-in 200ms.
  2. `ReaderBottomNav.vue` — Height 56px, dark background. Three buttons: ← 上一话 (calls `emit('prevChapter')`), 📂 目录 (calls `emit('catalog')`), 下一话 → (calls `emit('nextChapter')`). Center: thin progress bar `━━━━●━━━` (computed from `currentPage / totalPages`). Buttons ≥ 48px touch target. Safe area: `padding-bottom: env(safe-area-inset-bottom)`. Conditionally show prev/next based on `prevChapterId`/`nextChapterId` props.
  3. `ReaderSettingsDrawer.vue` — Bottom slide-up drawer, ~60vh height. Sections: 阅读方向 (vertical/horizontal radio), 适配模式 (AUTO/WIDTH/HEIGHT/ORIGINAL radio), 缩放 (slider + [-]/[+] buttons, step 5%), 画质 (AUTO/HQ_ONLY/LQ_ONLY radio). Advanced section: 预加载 toggle, 渐进加载 toggle. Title "阅读设置" with × close button. Semi-transparent overlay, tap overlay to close. All settings directly mutate `reader-settings-store` (instant apply, no save button).
  Must NOT do: Do NOT use ElSelect or ElDropdown in mobile components. Do NOT copy desktop toolbar logic — start fresh. Do NOT hardcode colors — use CSS custom properties from `style.css`. Do NOT use `mode` prop — these components are mobile-only.
  Parallelization: Wave B | Blocked by: 8,9 | Blocks: 11 | Can parallelize with: —
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §4 line 258-355; `reader-settings-store.ts` (QualityMode, FitMode, zoom, ReadingDirection, enablePreload, enableProgressiveImage); `bg_11d4845e` finding: ReaderToolbar currently uses ElSelect/ElDropdown
  Acceptance criteria: Each component renders without errors. ReaderToolbarMobile: ← click emits 'back', ⋯ click emits 'openSettings'. ReaderBottomNav: buttons emit correct events, progress bar reflects `currentPage/totalPages`. ReaderSettingsDrawer: changing any setting updates `reader-settings-store` immediately, drawer closes on overlay tap. All touch targets ≥ 48px (verify via computed style).
  QA scenarios: Happy — open drawer, change 阅读方向 to 横向, verify `settings.readingDirection === 'horizontal'` in store. Close drawer, verify autoHide resumes. Failure — rapid open/close drawer doesn't create duplicate overlay elements. Evidence: `.omo/evidence/task-10-2026-07-18-mobile-reading.png` (screenshots of all 3 components).
  Commit: Y | `feat(reader): add ReaderToolbarMobile, ReaderBottomNav, ReaderSettingsDrawer`

- [x] 11. Migrate reader components + refactor ReaderPage.vue
  What to do:
  1. Move 4 files from `components/reading/reader/` to `views/reading/reader/components/`: ReaderToolbar.vue, ReaderViewport.vue, ReaderImageItem.vue, ProgressiveImage.vue.
  2. Rename existing `ReaderToolbar.vue` → `ReaderToolbarDesktop.vue` (keep all existing desktop logic intact — ElSelect, ElDropdown, keyboard shortcuts).
  3. Create new `ReaderToolbar.vue` as entry component: `<ReaderToolbarDesktop v-if="mode==='desktop'" />` + `<ReaderToolbarMobile v-if="mode==='mobile'" />` (from todo 10).
  4. Refactor `ReaderPage.vue`: inject `useInteractionMode` (todo 4), `useReaderGesture` (todo 7), `useReaderToolbar` (todo 8), `useReaderNavigation` (todo 9). Wire gesture events → state machine dispatch. Conditionally render ReaderBottomNav + ReaderSettingsDrawer on mobile. Keep keyboard/wheel/dblclick handlers for desktop mode only (guarded by `if (mode === 'desktop')`). Update import paths for moved components.
  5. Add `touch-action: manipulation` to `.reader-viewport` CSS in ReaderViewport.vue (prevents browser pull-to-refresh/double-tap-zoom from interfering with gesture composable).
  Must NOT do: Do NOT remove any existing desktop functionality (ElSelect, keyboard shortcuts, wheel zoom). Do NOT change ReaderViewport, ReaderImageItem, or ProgressiveImage internal logic — only move files and add touch-action CSS. Do NOT change store or API imports. Do NOT wire swipe to chapter navigation — BottomNav handles navigation; swipe events are emitted but left unhandled in 0.3.
  Parallelization: Wave B | Blocked by: 10 | Blocks: — | Can parallelize with: —
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §9 line 574-610; `bg_11d4845e` finding: only 1 importer (ReaderPage.vue) for reader components; todo 4,7,8,9,10
  Acceptance criteria: All 4 files at new paths. Import paths in ReaderPage.vue updated. Desktop reader functions identically to pre-refactor (toolbar visible, keyboard shortcuts work, ElSelect dropdowns function). Mobile reader: toolbar hidden on load (IMMERSIVE state), tap center → toolbar + bottom nav appear, tap again → hide. Back button navigates to comic detail. Chapter navigation works. Settings drawer opens/closes and settings apply.
  QA scenarios: Happy — Playwright desktop: verify toolbar with ElSelect visible, ArrowRight flips page. Playwright mobile (375×812, touch): verify immersive start, tap → toolbar appears, 4s → auto-hide. Failure — switching between desktop/mobile modes via DevTools doesn't leave stale event listeners. Evidence: `.omo/evidence/task-11-2026-07-18-mobile-reading.png` (before/after desktop + before/after mobile screenshots).
  Commit: Y | `refactor(reader): migrate components, add mobile interaction, refactor ReaderPage`

### Wave C — P1 Reading Flow Responsive (4 todos)

- [x] 12. HomePage mobile responsive layout
  What to do: Edit `frontend/src/views/reading/HomePage.vue`. Add scoped CSS with mobile-first approach:
  - HomeHero: full width, reduced padding (`--space-lg` → `--space-base`)
  - HomeRow items: add `overflow-x: auto; scroll-snap-type: x mandatory;` on mobile. Each item: `scroll-snap-align: start; flex: 0 0 70vw; max-width: 160px`
  - HomeActionGrid: `grid-template-columns: repeat(2, 1fr); gap: var(--space-base);` on mobile. Touch targets ≥ 44px.
  Detection: use `useInteractionMode()` from todo 4. Apply mobile styles when `mode === 'mobile'` via `:class` binding on container. Do NOT use raw @media — use the composable.
  Must NOT do: Do NOT change HomeRow or HomeActionGrid component internals — only add prop-based mode switching. Do NOT remove desktop layout. Do NOT use @media queries directly — use the mode composable.
  Parallelization: Wave C | Blocked by: 4 | Blocks: — | Can parallelize with: 13,14,15,17,18
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §5 line 349-393; current `HomePage.vue`; `bg_75077a03` finding: HomeRow already has @media for arrow hiding
  Acceptance criteria: Mobile viewport (375px): HomeHero is full-width card, HomeRow scrolls horizontally with snap, ActionGrid is 2 columns. Desktop viewport (1440px): layout unchanged from current. No horizontal overflow on mobile. Touch targets ≥ 44px on ActionGrid buttons.
  QA scenarios: Happy — Playwright mobile: verify scroll-snap works (scroll HomeRow, assert items snap into view). Verify ActionGrid buttons tappable (no overlapping). Failure — resize from desktop to mobile mid-session: layout transitions correctly without page reload. Evidence: `.omo/evidence/task-12-2026-07-18-mobile-reading.png`.
  Commit: Y | `style(home): add mobile-first responsive layout for HomePage`

- [x] 13. LibraryPage mobile responsive layout
  What to do: Edit `frontend/src/views/reading/LibraryPage.vue`. Add mobile responsive CSS:
  - Search + sort: merge into single row on mobile (`flex-wrap: wrap`)
  - Category chips: `overflow-x: auto; white-space: nowrap;` horizontal scroll
  - Comic grid: `grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));` (was `--poster-width-md` / `--poster-width-lg`). Replace `updatePosterSize()` window.innerWidth logic with `useBreakpoint()` from todo 1. Add resize listener cleanup.
  Must NOT do: Do NOT keep `window.innerWidth` usage — migrate to `useBreakpoint`. Do NOT change ComicCard component. Do NOT change data fetching logic.
  Parallelization: Wave C | Blocked by: 1 | Blocks: — | Can parallelize with: 12,14,15,17,18
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §5 line 377-414; current `LibraryPage.vue:137-146` (updatePosterSize with window.innerWidth); `bg_75077a03` finding: updatePosterSize runs once, no resize listener
  Acceptance criteria: Mobile (375px): grid shows 3 columns (110px min each). Category chips scroll horizontally. Search + sort on one row. Desktop: grid uses larger columns. `useBreakpoint` replaces `window.innerWidth` — resize updates grid reactively.
  QA scenarios: Happy — Playwright mobile: verify 3-column grid, horizontal chip scroll. Resize to 1920px: verify grid expands to fill. Failure — rapid resize doesn't crash or show wrong column count. Evidence: `.omo/evidence/task-13-2026-07-18-mobile-reading.png`.
  Commit: Y | `style(library): add auto-fill grid and useBreakpoint for LibraryPage`

- [x] 14. DetailPage mobile responsive layout
  What to do: Edit `frontend/src/views/reading/DetailPage.vue`. Add mobile responsive CSS via `useInteractionMode()`:
  - Layout: vertical stack (PC left-right → mobile top-bottom)
  - Cover: centered, `width: min(50%, 220px); margin: 0 auto;`
  - Primary button: single button only. If `lastReadChapterId` exists → "▶ 继续阅读". Otherwise → "开始阅读". Remove secondary button on mobile. Full width, height ≥ 48px.
  - Info grid: `grid-template-columns: 1fr` (single column)
  - Catalog section: full width
  Must NOT do: Do NOT show two buttons on mobile. Do NOT change desktop two-button behavior. Do NOT change data fetching. Do NOT modify CatalogTree — only the wrapping layout.
  Parallelization: Wave C | Blocked by: 4 | Blocks: — | Can parallelize with: 12,13,15,17,18
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §5 line 398-454; current `DetailPage.vue:490` (@media max-width:768px for info-grid); `bg_75077a03` finding: existing @media only changes columns
  Acceptance criteria: Mobile (375px): cover centered at ~50% width, single button (continue or start), info grid single column, catalog full width. Desktop: unchanged two-button layout. No content cut off on mobile.
  QA scenarios: Happy — with history: shows "▶ 继续阅读" only. Without history: shows "开始阅读" only. Button tap navigates correctly. Failure — long comic title doesn't overflow on mobile (text-overflow: ellipsis). Evidence: `.omo/evidence/task-14-2026-07-18-mobile-reading.png`.
  Commit: Y | `style(detail): add vertical stack mobile layout for DetailPage`

- [x] 15. HistoryPage RecycleScroller
  What to do: Edit `frontend/src/views/reading/HistoryPage.vue`. Replace current `v-for` list with `<RecycleScroller>` from vue-virtual-scroller:
  - `:items="historyStore.list"` (flat array of HistoryVO)
  - `:item-size="72"` (fixed row height for history items)
  - `key-field="comicId"`
  - `:buffer="200"`
  - Inside `#default` slot: reuse existing row template (ComicPoster + chapter info)
  Must NOT do: Do NOT change HistoryVO type. Do NOT change data fetching. Do NOT implement time-grouping (today/yesterday) — out of scope for 0.3. Do NOT remove existing ComicPoster usage.
  Parallelization: Wave C | Blocked by: — | Blocks: — | Can parallelize with: 12,13,14,17,18
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §5 line 456-458, §8 line 543; current `HistoryPage.vue`; `RecycleScroller` usage reference: `ReaderViewport.vue`
  Acceptance criteria: History page renders with RecycleScroller. Only visible rows are in DOM (verify: scroll to bottom, check DOM node count < total items). Scroll performance smooth (no jank on 500+ history items). Existing row template (ComicPoster + chapter info) renders correctly inside scroller.
  QA scenarios: Happy — load 500 history items, verify DOM has < 50 nodes. Scroll to bottom, verify last item visible. Failure — rapid scroll doesn't show blank rows (RecycleScroller buffer covers). Evidence: `.omo/evidence/task-15-2026-07-18-mobile-reading.txt` (DOM node count before/after).
  Commit: Y | `perf(history): replace v-for with RecycleScroller for HistoryPage`

### Wave D — P2 Management Desktop Boundary (2 todos)

- [x] 16. Add router beforeEach guard for /manage/*
  What to do: Edit `frontend/src/router/index.ts`. Add `router.beforeEach` guard:
  - Check if `to.path.startsWith('/manage')`
  - If yes, call `isMobileReadingDevice()` (todo 3)
  - If mobile device: `return { name: 'manage-intercept' }` (or redirect to `/` if prefer simpler)
  - If NOT mobile: `return true`
  - If `import.meta.env.DEV && to.query['force-desktop'] === '1'`: skip check (dev bypass)
  Must NOT do: Do NOT check on every navigation — only when entering /manage/*. Do NOT use Vue composable — use pure function `isMobileReadingDevice`. Do NOT check on resize — only on route entry.
  Parallelization: Wave D | Blocked by: 3 | Blocks: — | Can parallelize with: 17,18
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §6 line 470-515; current `router/index.ts` line 54-95 (/manage routes); todo 3 (isMobileReadingDevice)
  Acceptance criteria: Mobile device navigates to `/manage/comics` → redirected. Desktop device navigates to `/manage/comics` → renders ManagementLayout. DEV mode + `?force-desktop=1` → bypasses check. Direct URL entry (not SPA nav) also intercepted.
  QA scenarios: Happy — Playwright mobile (375px, touch): `page.goto('/manage/comics')` → assert redirected. Desktop: `page.goto('/manage/comics')` → assert ManagementLayout visible. Failure — guard doesn't fire on same-route navigation (no infinite loop). Evidence: `.omo/evidence/task-16-2026-07-18-mobile-reading.txt`.
  Commit: Y | `feat(router): add mobile device guard for /manage/* routes`

- [x] 17. Create management intercept page
  What to do: Create `frontend/src/views/management/InterceptPage.vue`. Single centered page:
  - Icon: 📱 (large, 64px)
  - Title: "管理功能面向桌面端设计"
  - Body: "阅读功能支持手机和平板。管理功能为了更高的编辑效率，仅支持桌面浏览器。请使用电脑访问管理后台。"
  - Button: "回到阅读首页" → `router.push('/')`
  - Dark background matching theme (`var(--bg-primary)`)
  Add route: `{ path: '/manage/intercept', name: 'manage-intercept', component: InterceptPage }` (before other /manage child routes so it matches first). Or simpler: redirect to `/` directly from guard without separate page.
  Decision: use separate page (cleaner UX, user knows what happened). Route entry in `/manage` children array.
  Must NOT do: Do NOT provide "continue anyway" button. Do NOT style differently from the rest of the app. Do NOT use a dialog/modal — full page replacement.
  Parallelization: Wave D | Blocked by: — | Blocks: — | Can parallelize with: 12,13,14,15,16,18
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §6 line 478-503; todo 16 (router guard)
  Acceptance criteria: Mobile device redirected to `/manage/intercept` → page renders with message and "回到阅读首页" button. Button click → navigates to `/`. Desktop device never sees this page. Page uses theme colors, no white flash.
  QA scenarios: Happy — verify page renders on mobile redirect. Click button → verify navigation to `/`. Failure — intercept page is NOT reachable by directly typing URL on desktop (guard allows it through, which is expected — but verify it renders anyway if accessed). Evidence: `.omo/evidence/task-17-2026-07-18-mobile-reading.png`.
  Commit: Y | `feat(manage): add mobile intercept page for /manage/*`

### Wave E — CatalogTree Virtual List (1 todo)

- [x] 18. CatalogTree RecycleScroller integration
  What to do: Edit `frontend/src/components/reading/comic/CatalogTree.vue`. Replace recursive `CatalogTreeNode` rendering with `RecycleScroller`:
  1. Create `flatItems` computed: recursively walk `tree[]`, respecting `expandedIds`. Output flat array of `FlatItem` where each item is `{ type: 'header' | 'chapter', ... }`. Headers have `nodeId`, `title`, `count`. Chapters have `chapterId`, `chapterNo`, `title`, `pageCount`, `status`.
  2. Replace template: `<RecycleScroller :items="flatItems" :item-size="42" key-field="flatKey" :buffer="100">`
  3. In `#default` slot: `v-if="item.type === 'header'"` renders expandable header (arrow + title + count, click toggles expandedIds). `v-else` renders `<ChapterRow>` (reuse existing component, pass chapter props).
  4. Keep `provide/inject` for `expandedIds`, `toggleExpanded`, `isExpanded`.
  5. Remove `CatalogTreeNode.vue` import (no longer used recursively — but keep the file for reference or delete).
  Must NOT do: Do NOT change the `CatalogNode` or `ChapterRef` types. Do NOT change the `@select` emit signature. Do NOT remove expand/collapse functionality. Do NOT use dynamic `size-field` — fixed 42px is correct for uniform chapter rows.
  Parallelization: Wave E | Blocked by: — | Blocks: — | Can parallelize with: 12,13,14,15,16,17
  References: `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §8 line 542; `bg_e9f35b23` findings: vue-virtual-scroller installed, current full render = 1500+ DOM for 300 chapters, provide/inject expand state already works; `ReaderViewport.vue` for RecycleScroller reference; current `CatalogTree.vue`, `CatalogTreeNode.vue`, `ChapterRow.vue`
  Acceptance criteria: Catalog with 300 chapters: DOM node count < 100 (vs 1500+ before). Expand/collapse works — clicking header toggles children visibility. Chapter click emits `@select` with correct chapterId. Active chapter highlighting works. Scroll position preserved on expand/collapse.
  QA scenarios: Happy — load comic with 300 chapters, verify DOM nodes < 100. Expand container, verify children appear. Click chapter, verify navigation. Failure — rapid expand/collapse doesn't crash or show duplicate items. Evidence: `.omo/evidence/task-18-2026-07-18-mobile-reading.txt` (DOM count) + `.png` (screenshot).
  Commit: Y | `perf(catalog): replace recursive render with RecycleScroller in CatalogTree`

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE.
- [x] F1. Plan compliance audit — verify all 18 todos marked complete, every Must have item delivered, every Must NOT have item absent. Check: no @media queries introduced outside useBreakpoint pattern, no mode prop on business components, no store/API changes, no new dependencies.
- [x] F2. Code quality review — `vue-tsc --noEmit` passes with zero errors. LSP diagnostics clean on all changed files. No `as any`, `@ts-ignore`, `@ts-expect-error`. `vite build` succeeds.
- [x] F3. Agent-executed Playwright QA — Playwright end-to-end: mobile reading flow (Home → Library → Detail → Reader → immersive interaction → back). Desktop reading flow unchanged. Management mobile block verified. CatalogTree 300-chapter performance verified.
- [x] F4. Scope fidelity — diff against `docs/superpowers/specs/2026-07-18-mobile-reading-design.md` §10: all IN items present, all OUT items absent. No unplanned file changes.

## Commit strategy
- One commit per todo (18 commits total), grouped by wave
- Commit messages in Chinese, format: `<type>(<scope>): <description>`
- Types: `feat` (new feature), `refactor` (code change), `style` (CSS only), `perf` (performance)
- Wave A: 3 commits → push
- Wave B: 8 commits → push (after Wave A merged)
- Wave C: 4 commits → push (parallel with B, after Wave A)
- Wave D: 2 commits → push (parallel with B/C, after Wave A)
- Wave E: 1 commit → push (parallel with B/C/D)
- Final: 1 verification commit (evidence + docs update)

## Success criteria
1. **Mobile Reader immersive**: Tap center shows/hides toolbar, auto-hide after 4s, settings drawer pauses timer, bottom nav navigates chapters. All touch targets ≥ 48px.
2. **Desktop Reader unchanged**: ElSelect dropdowns, keyboard shortcuts, wheel zoom all function as before.
3. **Reading flow responsive**: Home scroll-snap, Library auto-fill grid, Detail single-button vertical stack all render correctly at 375×812.
4. **Management blocked**: Mobile device accessing `/manage/*` redirected to intercept page.
5. **Performance**: CatalogTree 300 chapters → < 100 DOM nodes. HistoryPage 500 items → < 50 DOM nodes.
6. **Zero regressions**: All existing Playwright tests pass. `vue-tsc --noEmit` clean. `vite build` succeeds.
7. **No new dependencies**: `vue-virtual-scroller` already installed. No package.json changes.
