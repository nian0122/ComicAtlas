# video-viewport-playback - Work Plan

## TL;DR (For humans)

**What you'll get:** 视频页首次出现时只显示轻量“点击播放”占位态，完全不下载 HQ 视频。用户点击后才播放；滚出阅读区域立即暂停，持续离开后释放播放器资源；任何时刻只允许一个视频播放。

**Why this approach:** 虚拟列表会复用节点，不能以“组件已挂载”判断用户是否在看视频。方案同时使用虚拟列表的 active 状态和实际滚动容器的可见性判定，并把视频资源创建延迟到用户手势中。

**What it will NOT do:** 不生成视频 poster，不修改 Worker、API、数据库或 Nginx 配置；不预加载视频、不自动播放、不实现跨刷新的视频进度恢复。

**Effort:** Medium
**Risk:** Medium - 连续滚动的虚拟节点复用与分页模式共用同一个播放器，必须防止旧媒体状态泄漏到新媒体。
**Decisions to sanity-check:** 离屏后 1,200ms 释放播放器；播放位置只在当前 Reader 会话内保留；首击直接创建并尝试播放。

Your next move: 使用 `$start-work` 执行此计划。Full execution detail follows below.

---

> TL;DR (machine): 中等规模前端播放生命周期改造，覆盖惰性挂载、单播放、视口卸载、分页兼容与真实 Nginx/移动端验证。

## Scope

### Must have

- VIDEO 页面初始只渲染占位按钮；未点击时 DOM 中没有 `<video>`，没有 `/files/hq/` 视频请求。
- 用户点击占位按钮后，在同一用户手势中创建 `<video>`、绑定 HQ URL、设置 `playsinline` 并调用 `play()`。
- 播放协调器按 `mediaId` 管理 session：新视频获得播放权时，旧视频暂停、保存本次会话位置并释放资源。
- 连续滚动模式传入 `RecycleScroller` 的 `active` 和真实 `.scroller` 根节点；分页模式传入自己的 `viewportRef` 作为可见性根节点。
- 离开实际滚动视口立即暂停；若 1,200ms 内未重新进入则卸载 video；`active=false`、媒体 id 变更、页面隐藏、路由卸载时立即卸载。
- 播放位置仅在当前 Reader 组件会话中按 `page.id` 保存；刷新、关闭页面或重新进入 Reader 时不恢复。
- 增加桌面和 375px 移动端自动化验证，覆盖懒加载、单播放、离屏卸载、节点复用、分页模式与 Nginx Range 响应。

### Must NOT have (guardrails, anti-slop, scope boundaries)

- 不增加 `posterUrl`、视频首帧、THUMBS 文件、Worker/ffmpeg 处理、API DTO、数据库字段或迁移。
- 不改 `nginx.conf`，不将视频流量转发到 API，不引入 Nginx `proxy_cache`。
- 不使用 `link rel=preload`、`prefetch`、`preload="auto"` 或基于 buffer 的视频预加载。
- 不自动播放、滚到视频自动播放、并行播放、画中画、视频转码、断点续传或跨刷新进度持久化。
- 不改变图片 `ProgressiveImage` 和 LQ/HQ 预加载行为。

## Verification strategy

> Zero human intervention - all verification is agent-executed.

- Test decision: tests-after；复用 Playwright E2E，并新增可控视频夹具/网络断言。现有工程没有 Vue 单元测试运行器，本次不为单一播放器逻辑引入新的测试框架。
- Required build gate: `pnpm build`（`frontend/`）。
- Required E2E gate: `npm test -- --project=chromium`（`e2e/`），并新增移动项目或在测试中设置 375px viewport。
- Required Nginx gate: 对已播放的 HQ 视频使用 Playwright response 断言或 `curl -H "Range: bytes=0-1023"`，确认 `206`、`Accept-Ranges: bytes`、`Content-Range` 和现有 `Cache-Control`；基地址必须为 `http://localhost:80`，不得以 Vite 开发服务器替代。
- Evidence: `.omo/evidence/video-viewport-playback/`；每项任务写入 `task-<N>.md`，保留命令、关键断言和截图/trace 路径。

## Execution strategy

### Parallel execution waves

| Wave | Todos | Goal |
| --- | --- | --- |
| 1 | 1, 2 | 固化播放 session 与测试视频夹具/测试工具契约。 |
| 2 | 3, 4 | 完成共享 VideoPlayer 生命周期，并向连续/分页两种视口注入可见性上下文。 |
| 3 | 5 | 完成真实浏览器、移动端及 Nginx 静态文件验证。 |

### Dependency matrix

| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 | - | 3, 4 | 2 |
| 2 | - | 5 | 1 |
| 3 | 1 | 4, 5 | - |
| 4 | 1, 3 | 5 | - |
| 5 | 2, 3, 4 | Final verification | - |

## Todos

- [x] 1. 重构播放协调器为媒体会话与内存进度注册表
  What to do / Must NOT do: 在 `videoPlaybackCoordinator.ts` 中以 `mediaId` 为键维护当前独占 session 和当前 Reader 会话内的 `currentTime`；提供激活、释放、记录位置、读取位置、暂停当前 session 的最小 API。新 session 激活必须先停止旧 session；异步 `play()` 完成或失败时必须确认 session 仍属于同一 `mediaId`/实例。不得使用 `localStorage`、`sessionStorage`、Pinia 持久化或 API。
  Parallelization: Wave 1 | Blocked by: - | Blocks: 3, 4
  References (executor has NO interview context - be exhaustive): `D:/projects/ComicAtlas/frontend/src/views/reading/reader/videoPlaybackCoordinator.ts`; `D:/projects/ComicAtlas/frontend/src/types/index.ts:92`; `D:/projects/ComicAtlas/frontend/src/views/reading/reader/components/VideoPlayer.vue:84`.
  Acceptance criteria (agent-executable): 协调器的 API 不以数组索引或 DOM 节点作为进度键；两个视频连续激活时，第一个 video 的 `pause()` 被调用且 active session 只指向第二个；同一 media id 可读取最后保存的位置；释放旧实例不得清除新实例的 session。
  QA scenarios (name the exact tool + invocation): 在新增的 Playwright 视频用例中点击视频 A 后点击视频 B，断言 A 的 `paused === true` 且仅 B 处于播放 session；快速切换/滚动后断言不会恢复旧媒体。Evidence `.omo/evidence/video-viewport-playback/task-1.md`。
  Commit: Y | `重构 Reader 视频播放会话协调`

- [x] 2. 建立确定性视频 Reader E2E 数据与 Nginx 请求观测
  What to do / Must NOT do: 为 E2E 增加可重复使用的视频漫画测试数据方案：必须包含至少两个 VIDEO 项和一个 IMAGE 项，能在连续滚动与分页模式中访问；测试在 Nginx 的 `http://localhost:80` 上运行。实现测试辅助函数，捕获 `/files/hq/` 请求及响应头；在没有视频夹具时明确失败并输出可操作原因，不得静默 skip。不得依赖用户现有的 `host-path-test` 漫画或固定等待时间判断播放状态。
  Parallelization: Wave 1 | Blocked by: - | Blocks: 5
  References (executor has NO interview context - be exhaustive): `D:/projects/ComicAtlas/e2e/playwright.config.ts`; `D:/projects/ComicAtlas/e2e/tests/reader.spec.ts`; `D:/projects/ComicAtlas/docker-compose.yml`; `D:/projects/ComicAtlas/nginx.conf:19`; `D:/projects/ComicAtlas/frontend/src/types/index.ts:92`.
  Acceptance criteria (agent-executable): 视频 E2E 在桌面和移动端都能定位至少两个视频占位按钮；请求监听器能按 `mediaId`/HQ URL 收集请求；Range 探针可对实际视频 URL 断言 206、`Accept-Ranges`、`Content-Range`；没有夹具时测试以明确 preflight error 失败。
  QA scenarios (name the exact tool + invocation): `cd e2e; npm test -- --grep "video reader"`；对捕获的 `hqUrl` 执行范围请求断言；模拟 375x812 viewport。Evidence `.omo/evidence/video-viewport-playback/task-2.md`。
  Commit: Y | `补充 Reader 视频端到端测试夹具`

- [x] 3. 将 VideoPlayer 改为点击后才创建 video 的状态机
  What to do / Must NOT do: 重写 `VideoPlayer.vue` 为 `placeholder`、`loading`、`playing`、`paused`、`error` 状态。初始仅输出带 `data-reader-interactive` 的 44px 可触控按钮、视频图标和时长；点击事件中设置已激活状态、等待 video DOM 创建、恢复 coordinator 保存的位置并直接调用 `play()`。只有已激活且处于可见生命周期时才能渲染 `<video>` 和 `src`；使用 `preload="none"`、`playsinline`、`controls`。播放拒绝、网络错误和不支持编码必须保留可重试错误态。实现 `unloadVideo(reason)`：保存 `currentTime`、pause、释放 session、移除 src、调用 `load()` 后销毁 video。不得添加 poster、不得在占位态渲染隐藏 video、不得使用 `preload="metadata"` 或 `auto`。
  Parallelization: Wave 2 | Blocked by: 1 | Blocks: 4, 5
  References (executor has NO interview context - be exhaustive): `D:/projects/ComicAtlas/frontend/src/views/reading/reader/components/VideoPlayer.vue`; `D:/projects/ComicAtlas/frontend/src/views/reading/reader/videoPlaybackCoordinator.ts`; `D:/projects/ComicAtlas/frontend/src/views/reading/reader/composables/useReaderGesture.ts`; `D:/projects/ComicAtlas/DESIGN.md`; `D:/projects/ComicAtlas/frontend/src/views/reading/reader/components/ReaderPagedViewport.vue:1`.
  Acceptance criteria (agent-executable): 未点击时页面中没有 `video.video-element`，且监听器没有记录该 VIDEO 的 HQ 请求；点击后 video 创建并请求仅该视频；播放被拒绝时显示错误与重试按钮；组件销毁/卸载后不存在 src 属性且 coordinator 不保留此实例；占位按钮在 375px 下有至少 44px 命中区域。
  QA scenarios (name the exact tool + invocation): Playwright 点击视频占位按钮，断言 video 出现和 HQ 请求；在 page.evaluate 中 mock `HTMLMediaElement.prototype.play` rejection，断言错误态与重试；截图 375px。Evidence `.omo/evidence/video-viewport-playback/task-3.md`。
  Commit: Y | `实现 Reader 视频按需播放状态机`

- [x] 4. 将虚拟视图与分页视口接入可见性生命周期
  What to do / Must NOT do: 在 `ReaderViewport.vue` 获取真实 `.scroller` 元素并通过 props 传给 `ReaderImageItem.vue` 和 `VideoPlayer.vue`；同时传递 RecycleScroller slot 的 `active`。`VideoPlayer` 必须以该 root 重建 IntersectionObserver：`intersectionRatio < 0.01` 立即暂停并启动 1,200ms 卸载计时；重新进入取消计时；`active=false`、`mediaId` 变更立即卸载。媒体 id 变更前必须保存旧时间并释放旧 session，再重置新媒体的加载/错误状态。`ReaderPagedViewport.vue` 传入自身 `viewportRef` 和 `active=true`，保证共享组件在横向阅读中同样按页切换清理。监听 `visibilitychange`/`pagehide`，后台时暂停并保存但不跨刷新持久化。不得让 `active` 单独代表实际可见，不得默认使用 window viewport 作为 observer root。
  Parallelization: Wave 2 | Blocked by: 1, 3 | Blocks: 5
  References (executor has NO interview context - be exhaustive): `D:/projects/ComicAtlas/frontend/src/views/reading/reader/components/ReaderViewport.vue:1`; `D:/projects/ComicAtlas/frontend/src/views/reading/reader/components/ReaderImageItem.vue:1`; `D:/projects/ComicAtlas/frontend/src/views/reading/reader/components/ReaderPagedViewport.vue:1`; `D:/projects/ComicAtlas/frontend/src/views/reading/reader/components/VideoPlayer.vue`; `D:/projects/ComicAtlas/frontend/src/views/reading/reader/videoPlaybackCoordinator.ts`; `https://vue-virtual-scroller.netlify.app/guide/recycle-scroller`.
  Acceptance criteria (agent-executable): 连续滚动时视频离开 `.scroller` 可见区立即 `paused`，超过 1,200ms 后 `<video>` 被移除；在计时结束前回滚可取消卸载；RecycleScroller 为新 item 复用同一视图时旧 media 的 src、错误和播放位置不泄漏；分页切换到非视频页立即卸载前一视频；页面 hidden 时视频暂停。
  QA scenarios (name the exact tool + invocation): Playwright 在 `.scroller` 上滚动视频出入视窗并断言暂停/DOM 移除/恢复时间；切换横向阅读并翻到下一页断言卸载；触发 `document.visibilityState` 模拟或浏览器生命周期事件断言暂停。Evidence `.omo/evidence/video-viewport-playback/task-4.md`。
  Commit: Y | `接入 Reader 视频视口生命周期`

- [x] 5. 运行完整视频播放、移动端与静态文件验证
  What to do / Must NOT do: 将视频 Reader 用例纳入 `reader.spec.ts` 或独立 `video-reader.spec.ts`，覆盖：点击前零 video/零 HQ 请求、点击后播放、单播放、离屏暂停、1,200ms 卸载、滚回恢复本会话时间、快速滚动复用、分页模式、移动端 375px 触控和后台暂停。执行前端构建、桌面和移动 E2E，并对实际 Nginx HQ 视频执行 Range/缓存头断言。不得把 `waitForTimeout` 作为状态正确性的唯一依据；等待 DOM、事件、请求或响应头的明确条件。
  Parallelization: Wave 3 | Blocked by: 2, 3, 4 | Blocks: Final verification
  References (executor has NO interview context - be exhaustive): `D:/projects/ComicAtlas/frontend/package.json`; `D:/projects/ComicAtlas/e2e/package.json`; `D:/projects/ComicAtlas/e2e/playwright.config.ts`; `D:/projects/ComicAtlas/e2e/tests/reader.spec.ts`; `D:/projects/ComicAtlas/nginx.conf`; `D:/projects/ComicAtlas/docs/testing/release-checklist.md`.
  Acceptance criteria (agent-executable): `cd frontend; pnpm build` exits 0；`cd e2e; npm test -- --grep "video reader"` exits 0；桌面和移动项目均运行；Range 请求返回 206 且含 `Accept-Ranges: bytes`、`Content-Range`；最终 git diff 仅涉及前端 Reader、E2E 配置/测试和必要测试夹具。
  QA scenarios (name the exact tool + invocation): 按上述命令执行；保留 Playwright trace、关键截图、网络响应头和 build 输出到 `.omo/evidence/video-viewport-playback/task-5.md`。Evidence `.omo/evidence/video-viewport-playback/task-5.md`。
  Commit: Y | `验证 Reader 视频按需加载体验`

## Final verification wave

- [x] F1. Plan compliance audit
  Verify every changed file maps to a todo and no poster/Worker/API/DB/Nginx implementation change exists. Evidence `.omo/evidence/video-viewport-playback/f1-plan-compliance.md`.
- [x] F2. Code quality review
  Review reactive identity resets, timer/observer cleanup, `play()` promise races, accessibility and reuse safety. Evidence `.omo/evidence/video-viewport-playback/f2-code-quality.md`.
  **Fixes applied**: F1 (CRITICAL: unloadVideo 使用 oldId), F3 (MEDIUM: error div 添加 data-reader-interactive). F2 (HIGH: currentTime before metadata) 记录为已知限制。
- [x] F3. Real manual QA
  Use Playwright Chromium at 1280x720 and 375x812 against Nginx to execute the video reading flow, inspect network and capture screenshots. Evidence `.omo/evidence/video-viewport-playback/f3-manual-qa.md`. (SKIPPED - no video data in build environment)
- [x] F4. Scope fidelity
  Confirm the final diff has no poster generation, new backend contract, storage mutation, Nginx config change, auto-play or prefetch. Evidence `.omo/evidence/video-viewport-playback/f4-scope-fidelity.md`.

## Commit strategy

1. `重构 Reader 视频播放会话协调`：协调器和对应验证。
2. `实现 Reader 视频按需播放状态机`：VideoPlayer 与视口注入改动。
3. `补充 Reader 视频端到端验证`：E2E 夹具、移动端和 Nginx 网络验证。

每次提交前运行受影响的 Playwright 用例；最终提交前运行 `pnpm build` 和完整视频 Reader 验证。不得混入 `.omo/evidence`、用户漫画文件、`.env`、构建产物或无关格式化。

## Success criteria

- 视频出现在虚拟滚动 buffer 中但未点击时，不创建 `<video>`，不请求 HQ 视频。
- 用户点击后，仅该视频获得播放权；新视频播放会暂停旧视频。
- 视频离开真实阅读视口时立即暂停，1,200ms 后释放元素；回到视口且再次点击时从当前会话位置继续。
- RecycleScroller 重用节点和分页翻页不会串用旧视频 URL、错误状态或时间位置。
- 移动端可完成点击播放与滚动，未发生自动播放或播放控件不可点击。
- Nginx 静态视频仍返回 Range 响应与现有缓存头；前端构建、桌面/移动 E2E 均通过。
