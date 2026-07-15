# netflix-frontend-refactor - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**What you'll get:** ComicAtlas 前端将从后台管理风格改造成 Netflix 式沉浸视觉：新增 Home 首页、Library 封面墙、Detail 沉浸式 Hero，同时统一 Import/Task/History 深色风格，Reader 只改颜色不改功能。

**Why this approach:** 先建立 Design System 并冻结公共组件 API，再让 Home/Library 和辅助页面并行开发，最后做 Detail；这种模块边界拆分能最大限度减少 subagent 之间的代码冲突，同时保证 Demo 第一眼看到的核心页面质量最高。

**What it will NOT do:**
- 不会加入收藏、推荐、榜单、评论或用户体系。
- 不会重构 Reader 的阅读逻辑（虚拟滚动、缩放、适配等）。
- 不会重构 Dashboard / Operation / Settings 页面。

**Effort:** Medium
**Risk:** Medium - Element Plus 默认组件深度集成，需要精准的主题覆盖；Reader 颜色调整有触及复杂逻辑的风险。
**Decisions to sanity-check:**
- 是否接受 Dashboard/Operation 保持原样、只从导航隐藏？
- 是否接受 Home Hero 不使用漫画简介（后端无该字段），而用当前阅读位置作为副标题？
- 是否接受 theme.scss 仅覆盖 Button/Input/Select/Pagination，其余 Element Plus 组件保持默认？

Your next move: 批准计划后开始执行，或先运行高准确度审查。完整执行细节见下文。

---

> TL;DR (machine): Medium effort, medium risk — refactor ComicAtlas frontend to Netflix-style visual design via 4 subagents in 5 waves; deliver Home/Library/Detail dark theme, freeze Reader logic, no new product features.

## Scope
### Must have
- 新增 `HomePage.vue` 作为默认首页，包含：Hero（最近阅读）、继续阅读行、最近加入行、快速操作、底部媒体库信息。
- `Library`（`ComicListPage.vue`）视觉 Netflix 化：更大封面、ComicPoster、固定搜索工具栏、卡片 Hover、保留分页。
- `ComicDetailPage.vue` Hero 增强：阅读进度、More 菜单收起管理操作、章节列表视觉统一。
- `ImportPage.vue` / `TaskCenterPage.vue` / `HistoryPage.vue` 深色风格统一。
- `ReaderToolbar.vue` 颜色统一（仅修改 `<style>` 块）。
- 公共组件：`HeroBanner.vue`、`ComicPoster.vue`、状态映射 `poster-status.ts`。
- Design Token：`styles/tokens.css`、`styles/theme.scss`（覆盖 Button/Input/Select/Dropdown/Pagination/Dialog）、`styles/animation.css`。
- `TopNav.vue` 滚动背景变化效果。
- 路由调整：`/` 重定向到 `/home`，导航中隐藏 Dashboard / Operation。
- Playwright smoke test 覆盖 Home → Library → Detail → Reader → History → Import → Task Center。

### Must NOT have (guardrails, anti-slop, scope boundaries)
- 不得引入收藏、推荐、榜单、评论、用户体系等功能。
- 不得重构 Reader 功能逻辑（Virtual Scroll、Progressive Image、Zoom、Fit、Direction）。
- 不得覆盖 Element Plus Table / Form / Tree / Upload / Drawer / Menu / Scrollbar（Dialog 已覆盖）。
- 不得修改 Dashboard / Operation / Settings 页面。
- 不得修复 `media-url.ts` URL 规范问题（超出范围）。
- **本轮为视觉重构，不得主动修改业务逻辑：禁止改动 API、Store 业务逻辑、Router Guard、Reader Store、Import Store、History Store。如确需调整，必须单独 commit 并说明原因。**
- `ReaderToolbar.vue` 只允许改动 `<style>` 块，禁止改动 `<script>` 或 `<template>`。
- ComicPoster 不得依赖业务 VO，必须保持纯展示 Props。
- 不得删除现有路由 `/dashboard` 和 `/operations`，只从导航中移除链接。

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after + Playwright smoke test
- Evidence: `.omo/evidence/task-<N>-netflix-frontend-refactor.<ext>`
- Every code change must pass `npm run build` and `vue-tsc --noEmit` before commit.
- **Each Wave must remain runnable**: after each wave completes, `npm run build` and `vue-tsc --noEmit` pass, and the touched pages open without `console.error` / `pageerror`.
- Visual/behavioral assertions use Playwright `getComputedStyle`, `page.hover`, `page.mouse.wheel`, `console.error` capture.
- Component API freeze verified via `git diff --name-only` and `git diff` scoped checks.

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.

**Wave 1: Design System** — Subagent 1 单独执行，产出冻结后其他 subagent 才能开始。

**Wave 2: Home + Library** — Subagent 2 使用 Wave 1 公共组件完成 Home 和 Library。

**Wave 3: Secondary Pages** — Subagent 3 使用 Wave 1 公共组件完成 Import、Task Center、History、ReaderToolbar 颜色。

**Wave 4: Comic Detail** — Subagent 4 在 HeroBanner 稳定后完成 Detail。

**Wave 5: Final QA** — Architecture Owner 统一验证 build、类型、Playwright smoke test、视觉一致性。

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1.1-1.7 Design System | — | 2.x, 3.x, 4.x | — |
| 2.1 Home Page | 1.x | 4.x | 2.2, 3.x |
| 2.2 Library Page | 1.x | 4.x | 2.1, 3.x |
| 3.1-3.3 Secondary Pages | 1.x | 4.x | 2.x |
| 4.1 Comic Detail | 1.x, 2.1 | 4.x | — |
| 5.1-5.3 Final QA | 全部 | — | — |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->

- [x] 1. Setup SCSS toolchain and styles directory
  What to do / Must NOT do: Install `sass` as devDependency; create `frontend/src/styles/` directory with `tokens.css`, `theme.scss`, `animation.css`. Update `frontend/src/main.ts` to import `tokens.css` AFTER `style.css` and import `theme.scss`/`animation.css`. Must NOT modify any Vue component in this task.
  Parallelization: Wave 1 | Blocked by: — | Blocks: 2, 3
  References (executor has NO interview context - be exhaustive): frontend/package.json:1-28, frontend/src/style.css:1-152, frontend/src/main.ts:1-15
  Acceptance criteria (agent-executable): `cd frontend && npm install -D sass && npm run build` exits 0; `frontend/src/styles/theme.scss` exists and is imported by `frontend/src/main.ts`; `tokens.css` is imported after `style.css` so aliases override consistently.
  QA scenarios (name the exact tool + invocation): happy: build succeeds with correct import order. failure: build fails or CSS variables resolve incorrectly. Evidence .omo/evidence/task-1-netflix-frontend-refactor.log
  Commit: Y | build(frontend): add sass and styles directory

- [x] 2. Define semantic Design Tokens in tokens.css
  What to do / Must NOT do: Create `frontend/src/styles/tokens.css` with bg/text/brand/hero/card/transition/poster/layout tokens. Must keep existing `--bg`, `--surface`, `--surface-elevated` as aliases to `--bg-primary`/`--bg-surface` for backward compatibility. Must NOT remove existing style.css tokens yet.
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 4, 5, 6, 7
  References (executor has NO interview context - be exhaustive): frontend/src/style.css:1-152, docs/superpowers/specs/2026-07-13-netflix-frontend-design.md:279-325
  Acceptance criteria (agent-executable): `grep -E "--(bg-primary|text-primary|accent|card-hover-scale|poster-width-lg|transition-normal)" frontend/src/styles/tokens.css` returns matches; `npm run build` exits 0.
  QA scenarios: happy: all required tokens present. failure: missing `--poster-width-lg`. Evidence .omo/evidence/task-2-netflix-frontend-refactor.log
  Commit: Y | style(frontend): add semantic design tokens

- [x] 3. Create theme.scss for Element Plus overrides
  What to do / Must NOT do: Override Element Plus Button, Input, Select, Dropdown, Pagination, Dialog to match Netflix dark theme. Must NOT override Table, Form, Tree, Upload, Drawer, Menu, Scrollbar. Must import tokens.css at top.
  Parallelization: Wave 1 | Blocked by: 1, 2 | Blocks: 8, 9, 10, 11
  References (executor has NO interview context - be exhaustive): docs/superpowers/specs/2026-07-13-netflix-frontend-design.md:480-489, frontend/src/main.ts:1-15
  Acceptance criteria (agent-executable): `npm run build` exits 0; create a temporary `frontend/src/pages/__ThemePreview.vue` with el-button, el-input, el-select, el-pagination, el-dialog; Playwright screenshot shows dark background and red accent.
  QA scenarios: happy: components render dark. failure: default Element Plus light theme still visible. Evidence .omo/evidence/task-3-netflix-frontend-refactor.png
  Commit: Y | style(frontend): add Element Plus dark theme overrides

- [x] 4. Add shared animations in animation.css
  What to do / Must NOT do: Create `frontend/src/styles/animation.css` with card hover scale/shadow, page fade-in, button hover, badge pulse. Must NOT add application-specific animations.
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 6
  References (executor has NO interview context - be exhaustive): docs/superpowers/specs/2026-07-13-netflix-frontend-design.md:432-439
  Acceptance criteria (agent-executable): `grep -E "@keyframes (card-hover|fade-in|button-hover)" frontend/src/styles/animation.css` returns matches; imported by main.ts.
  QA scenarios: happy: animation classes exist. failure: missing keyframes. Evidence .omo/evidence/task-4-netflix-frontend-refactor.log
  Commit: Y | style(frontend): add shared animations

- [x] 5. Implement HeroBanner.vue
  What to do / Must NOT do: Create `frontend/src/components/layout/HeroBanner.vue` with full-bleed blurred background, cover image, title, subtitle, description slot, actions slot. Must NOT bind to business VO; must receive raw URLs/strings via props. Hero background 100vw, content max-width `--page-width` centered.
  Parallelization: Wave 1 | Blocked by: 2 | Blocks: 8, 12
  References (executor has NO interview context - be exhaustive): docs/superpowers/specs/2026-07-13-netflix-frontend-design.md:370-382
  Acceptance criteria (agent-executable): Playwright renders HeroBanner with test props and computed background image equals provided URL; content container max-width equals `var(--page-width)`.
  QA scenarios: happy: background and cover render. failure: props not passed correctly. Evidence .omo/evidence/task-5-netflix-frontend-refactor.png
  Commit: Y | feat(frontend): add HeroBanner component

- [x] 6. Implement ComicPoster.vue and poster-status.ts
  What to do / Must NOT do: Create `frontend/src/components/comic/ComicPoster.vue` with pure PosterProps (cover, title, subtitle, progress, status, size, showProgress, showSubtitle, showHover, showButtons). Create `frontend/src/components/comic/poster-status.ts` mapping `ComicListVO.status` to PosterProps.status. Must NOT import ComicListVO into ComicPoster.
  Parallelization: Wave 1 | Blocked by: 2, 4 | Blocks: 8, 9, 11
  References (executor has NO interview context - be exhaustive): docs/superpowers/specs/2026-07-13-netflix-frontend-design.md:385-424, frontend/src/types/index.ts:167-176
  Acceptance criteria (agent-executable): `vue-tsc --noEmit` passes; Playwright hovers `.comic-poster` and asserts `getComputedStyle(el).transform` contains `scale(1.04)`; `toPosterStatus('SUCCESS')` returns `'ready'`.
  QA scenarios: happy: hover scale works. failure: ComicPoster imports ComicListVO directly. Evidence .omo/evidence/task-6-netflix-frontend-refactor.log
  Commit: Y | feat(frontend): add ComicPoster and status mapping

- [x] 7. Reorganize TopNav links and add scroll-aware background
  What to do / Must NOT do: Modify `frontend/src/components/layout/TopNav.vue`: (1) add Home link, (2) keep Library/History/Tasks links, (3) hide Dashboard and Operation links, (4) make background transparent/semi-transparent at scrollY=0 and transition to `#141414` after scrolling past 80px. Must NOT change import-store bootstrap logic. Must NOT delete Dashboard/Operation routes, only hide the nav links.
  Parallelization: Wave 1 | Blocked by: 2 | Blocks: 8, 9, 10, 11, 12
  References (executor has NO interview context - be exhaustive): frontend/src/components/layout/TopNav.vue:1-150, docs/superpowers/specs/2026-07-13-netflix-frontend-design.md:97-101
  Acceptance criteria (agent-executable): Playwright asserts nav contains Home link and does not contain Dashboard/Operation links; active route link has `.router-link-active` or `.active` class; scrolls page to y=100 and asserts `getComputedStyle(nav).backgroundColor === 'rgb(20, 20, 20)'`; at y=0 background is transparent or rgba.
  QA scenarios: happy: nav reorganized, active link highlighted, and scroll effect works. failure: Dashboard link still visible, active link not highlighted, or scroll background opaque. Evidence .omo/evidence/task-7-netflix-frontend-refactor.log
  Commit: Y | feat(frontend): reorganize TopNav and add scroll background transition

- [x] 8. Implement HomePage, home components, and router entry
  What to do / Must NOT do: Create `frontend/src/pages/HomePage.vue`, `components/home/HomeHero.vue`, `components/home/HomeRow.vue`, `components/home/HomeActionGrid.vue`. Update `frontend/src/router/index.ts` to add `/home` route and redirect `/` to `/home`. Home shows Hero from history[0] (call `useHistoryStore().fetchList()`), Continue Reading row (max 8, moreLink /history), Recently Added row (max 8, moreLink /comics?sort=createdAt; fallback to `/comics` if query param not supported), Quick Actions, footer statistics (call `useDashboardStore().fetch()`). Hide rows when empty. Must use HeroBanner and ComicPoster. Must NOT add search to Home.
  Parallelization: Wave 2 | Blocked by: 1-7 | Blocks: 12
  References (executor has NO interview context - be exhaustive): docs/superpowers/specs/2026-07-13-netflix-frontend-design.md:107-146, frontend/src/router/index.ts:1-66, frontend/src/stores/history-store.ts:1-55, frontend/src/stores/comic-store.ts:1-74, frontend/src/stores/dashboard-store.ts:1-10
  Acceptance criteria (agent-executable): Playwright visits `/` and is redirected to `/home`; asserts Hero CTA text is "继续阅读" when history exists; footer statistics show comicCount/pageCount/todayImported; row scroll via Shift+wheel increases `scrollLeft`; empty rows are not rendered when stores are empty.
  QA scenarios: happy: Home renders with data, redirect, and statistics. failure: / does not redirect, Hero missing, or statistics not shown. Evidence .omo/evidence/task-8-netflix-frontend-refactor.png
  Commit: Y | feat(frontend): add Netflix-style Home page and default route

- [x] 9. Refactor ComicListPage to Netflix-style Library
  What to do / Must NOT do: Update `frontend/src/pages/ComicListPage.vue` to use ComicPoster, fixed search/filter toolbar, 2:3 poster grid with `--poster-width-lg` on desktop (`--poster-width-sm` on mobile ≤640px, 3 columns), hover scale 1.04. Keep search, filters, sort, pagination. Must NOT switch to infinite scroll. Must NOT remove existing status/sort functionality.
  Parallelization: Wave 2 | Blocked by: 1-7 | Blocks: 12
  References (executor has NO interview context - be exhaustive): frontend/src/pages/ComicListPage.vue:1-296, docs/superpowers/specs/2026-07-13-netflix-frontend-design.md:146-181
  Acceptance criteria (agent-executable): Desktop: Playwright asserts search toolbar is `position: fixed` after scroll; ComicPoster cards render with title + subtitle; hover produces scale(1.04); pagination still works. Mobile (viewport 375px): asserts 3-column grid and `--poster-width-sm`.
  QA scenarios: happy: Library looks Netflix-like on desktop and mobile. failure: pagination broken, default Element Plus styling visible, or mobile layout broken. Evidence .omo/evidence/task-9-netflix-frontend-refactor.png
  Commit: Y | refactor(frontend): Netflix-style Library page

- [x] 10. Unify Import and Task Center dark theme
  What to do / Must NOT do: Update `frontend/src/pages/ImportPage.vue`, `frontend/src/pages/TaskCenterPage.vue`, and `frontend/src/components/task/TaskCard.vue` styles to match dark Netflix theme using Design Tokens. Must NOT change form logic, task status logic, or polling.
  Parallelization: Wave 3 | Blocked by: 1-7 | Blocks: 13
  References (executor has NO interview context - be exhaustive): frontend/src/pages/ImportPage.vue:1-424, frontend/src/pages/TaskCenterPage.vue:1-294, frontend/src/components/task/TaskCard.vue:1-430, docs/superpowers/specs/2026-07-13-netflix-frontend-design.md:222-241
  Acceptance criteria (agent-executable): Playwright visits `/import` and `/tasks` and asserts both pages have `rgb(20, 20, 20)` background and no light Element Plus components are visible.
  QA scenarios: happy: Import and TaskCenter are dark. failure: white backgrounds or light cards remain. Evidence .omo/evidence/task-10-netflix-frontend-refactor.png
  Commit: Y | style(frontend): dark theme for Import and Task Center

- [x] 11. Unify HistoryPage dark theme and ReaderToolbar color-only update
  What to do / Must NOT do: Update `frontend/src/pages/HistoryPage.vue` to render history entries using `ComicPoster` directly (replace HistoryCard cover rendering; may keep HistoryCard wrapper for layout or remove it if redundant). Apply dark theme using Design Tokens. Modify ONLY the `<style>` block of `ReaderToolbar.vue` to use Design Tokens. Must NOT change history data grouping logic, Reader script/template, or touch ReaderViewport/ProgressiveImage/ReaderPage.
  Parallelization: Wave 3 | Blocked by: 1-7 | Blocks: 13
  References (executor has NO interview context - be exhaustive): frontend/src/pages/HistoryPage.vue:1-297, frontend/src/components/history/HistoryCard.vue:1-212, frontend/src/components/reader/ReaderToolbar.vue:1-270, docs/superpowers/specs/2026-07-13-netflix-frontend-design.md:204-220
  Acceptance criteria (agent-executable): Playwright visits `/history` and asserts dark background and ComicPoster elements visible; `git diff frontend/src/components/reader/ReaderToolbar.vue` contains only `<style>` block changes; reader toolbar uses `--accent` color.
  QA scenarios: happy: History uses ComicPoster and Reader color-only changes. failure: Reader script/template diff detected or History still uses old card style. Evidence .omo/evidence/task-11-netflix-frontend-refactor.diff
  Commit: Y | style(frontend): dark theme for History and ReaderToolbar colors

- [x] 12. Enhance ComicDetailPage Hero, Information section, and chapter list
  What to do / Must NOT do: Update `frontend/src/pages/ComicDetailPage.vue`: (1) use HeroBanner for Hero with title/subtitle/description/actions, (2) create a separate Information section below Hero showing Author / Pages / Category / Tags / Import Time / Source Type, (3) show reading progress (chapter/page/progress bar), (4) move management actions into "⋯ More" menu, (5) unify chapter list visual style. Must NOT remove CatalogTree. Must NOT change chapter navigation logic. Target net change ≤ +200 LOC; do not sacrifice clarity to meet the target.
  Parallelization: Wave 4 | Blocked by: 1-7, 8 | Blocks: 13
  References (executor has NO interview context - be exhaustive): frontend/src/pages/ComicDetailPage.vue:1-645, docs/superpowers/specs/2026-07-13-netflix-frontend-design.md:183-205
  Acceptance criteria (agent-executable): Playwright visits comic detail and asserts HeroBanner renders; Information section displays author/pageCount/category/tags/sourceType/createdAt; "继续阅读" is primary button; More menu opens and contains LQ/Delete actions; catalog tree expands/collapses.
  QA scenarios: happy: Detail has immersive Hero + Information + chapter list. failure: HeroBanner not used, Information section missing, or management actions still visible. Evidence .omo/evidence/task-12-netflix-frontend-refactor.png
  Commit: Y | refactor(frontend): Netflix-style Detail page

- [x] 13. Final theme tuning, global style audit, and Playwright smoke test
  What to do / Must NOT do: Run Playwright checks on in-scope pages (Home/Library/Detail/Import/Task/History) to verify body background is `rgb(20, 20, 20)` and no uncovered Element Plus components render with light theme. Write and run a Playwright smoke test walking Home → Library → Detail → Reader → History → Import → Task Center, capturing console/page errors. Must NOT modify out-of-scope pages (Dashboard/Operation).
  Parallelization: Wave 5 | Blocked by: 1-12 | Blocks: 14
  References (executor has NO interview context - be exhaustive): docs/superpowers/specs/2026-07-13-netflix-frontend-design.md:686-722
  Acceptance criteria (agent-executable): Playwright asserts `getComputedStyle(document.body).backgroundColor === 'rgb(20, 20, 20)'` on all in-scope pages; uncovered EP components (e.g. el-table) are only present in out-of-scope pages; smoke test passes with `consoleErrors.length === 0` and `pageErrors.length === 0`.
  QA scenarios: happy: dark backgrounds verified and smoke test passes. failure: light EP component in in-scope page or console error. Evidence .omo/evidence/task-13-netflix-frontend-refactor.log
  Commit: Y | test(frontend): add Playwright smoke test and final theme audit

- [x] 14. Final build and type check
  What to do / Must NOT do: Run `npm run build` and `vue-tsc --noEmit` on the whole frontend. Must NOT suppress type errors.
  Parallelization: Wave 5 | Blocked by: 13 | Blocks: —
  References (executor has NO interview context - be exhaustive): frontend/package.json:6-9
  Acceptance criteria (agent-executable): `cd frontend && npm run build` exits 0; `cd frontend && npx vue-tsc --noEmit` exits 0.
  QA scenarios: happy: build and type check pass. failure: type error or build failure. Evidence .omo/evidence/task-14-netflix-frontend-refactor.log
  Commit: Y | chore(frontend): final build verification

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [x] F1. Plan compliance audit — verify every todo completed and evidence files exist in `.omo/evidence/`.
- [x] F2. Code quality review — verify no `any`, no `@ts-ignore`, no empty catch blocks, no oversized files (>300 LOC pure code), no duplicate ComicPoster-like components.
- [x] F3. Real manual QA — run Playwright smoke test and capture screenshots of Home, Library, Detail on desktop and mobile viewport.
- [x] F4. Scope fidelity — confirm no recommendation/collection/ranking features introduced, Reader logic unchanged, Dashboard/Operation untouched.

## Commit strategy
- 每个 todo 完成后单独 commit，使用中文提交信息（项目约定）。
- **Wave 1 完成后必须冻结 Design System API（HeroBanner、ComicPoster、Design Token、theme.scss），其他 Subagent 在冻结后才能开始。**
- 每个 Wave 完成后必须通过 `npm run build`、`vue-tsc --noEmit` 和 Playwright 无错检查，并由 Architecture Owner review 后才能进入下一 Wave。
- Subagent 2/3/4 在各自 feature 分支工作，完成后通过 PR/MR 合并，由 Architecture Owner review。
- 禁止 commit 未经 `npm run build` 和 `vue-tsc --noEmit` 通过的代码。
- 如确需修改业务逻辑，必须单独 commit（不与 UI commit 混合），并在 message 中注明原因。
- 禁止 amend 已 push 的 commit；如需修正，使用新 commit。

## Version note
本次重构作为 **v0.2.0** 主线：Netflix/Plex 风格 UI 重构。v0.1.0 MVP 已完成，v0.3.0 规划为元数据与目录增强。

## Success criteria
- Home 页面默认展示，Hero 占约 70% 视觉权重，有阅读历史时显示"继续阅读"，无历史时显示"浏览漫画库"。
- Library 使用 ComicPoster 封面墙，搜索工具栏固定，保留搜索/筛选/排序/分页。
- Comic Detail 使用 HeroBanner，阅读进度清晰，管理操作收进 More 菜单。
- Import / Task Center / History 页面深色风格统一，无白色背景。
- Reader Toolbar 颜色统一，且 diff 仅包含 `<style>` 块改动。
- 所有 in-scope 页面无未覆盖的 Element Plus 默认风格（Button/Input/Select/Pagination 已覆盖）。
- `npm run build` 和 `vue-tsc --noEmit` 通过。
- Playwright smoke test 通过 Home → Library → Detail → Reader → History → Import → Task Center，无 console/page 错误。
- 未引入收藏、推荐、榜单、评论、用户体系等功能。
- Dashboard / Operation / Settings 页面未重构。
