# Reader 虚拟滚动性能优化设计

## 背景

ComicAtlas 前端阅读器使用 `vue-virtual-scroller` 的 `RecycleScroller` 渲染漫画页面。`RecycleScroller` 要求提供每个 item 的精确尺寸，当前实现通过 `computed` 在每次渲染时重新计算全部页面的 size，并在滚动追踪、页面跳转时使用线性扫描，导致主线程负担随页数线性增长。

参考实现（`comics15/frontend`）使用 `DynamicScroller` + `IntersectionObserver` + 独立 `PreloadEngine`，但 `DynamicScroller` 依赖运行时 DOM 测量与高度估算，不适合 ComicAtlas 这种已经知道每页精确尺寸的场景。

## 设计目标

- 保留 `RecycleScroller` 与精确尺寸计算的优势。
- 将尺寸相关计算从 O(n) 优化到 O(1) / O(log n)。
- 让滚动追踪与页面跳转不随章节页数增长而变慢。
- 引入基于真实可视区的预加载，并取消远离请求，避免内存/带宽堆积。
- 优化 `ProgressiveImage` 生命周期，减少 DOM 节点与在途加载。

## 非目标

- 不更换虚拟滚动组件为 `DynamicScroller`。
- 不引入 Web Worker、WASM 等重型方案。
- 不改变现有阅读器 UI 与交互行为。

## 架构决策

- **保持 `RecycleScroller`**：ComicAtlas 的 `PageInfo` 已包含 `width`/`height`，结合 `fitMode`/`zoom`/`viewport` 可精确计算尺寸，无需 DOM 测量。
- **Size 缓存 + 前缀和**：把 `size` 计算结果缓存，并在尺寸参数变化时重建前缀和数组，使页面查找从 O(n) 降到 O(log n)。
- **`IntersectionObserver` 仅用于可见页通知**：不替代 `RecycleScroller` 的渲染，也不负责预加载决策，只把可见范围 `emit` 给 `ReaderPage`，由 `ReaderPage` 驱动 `PreloadEngine`。
- **独立 `PreloadEngine`**：参考 comics15 的 immediate + cascade + cancel 模型，但数据源改为 `PageInfo[]` 与可见范围。
- **`ProgressiveImage` 单 `<img>` 原则**：DOM 中只保留当前显示的图层，HQ 加载通过独立 `HTMLImageElement` 完成，加载成功后替换 `src` 并销毁 loader。

## 阶段拆分

### Phase IA-1：Size Cache + Prefix Sum + Binary Search

**范围**：`ReaderViewport.vue`

**改动点**：

1. 新增 `ItemSizeCache` 工具/内部逻辑：
   - 缓存 key = `pageId + ':' + zoom + ':' + fitMode + ':' + viewportWidth + ':' + viewportHeight`。
   - 仅当影响尺寸的参数变化时失效并重建。
2. 在 `ReaderViewport` 中维护 `prefixHeights: number[]`：
   - `prefixHeights[i]` 表示第 `0` 页到第 `i` 页的累计高度/宽度。
   - 尺寸参数变化时一次性重建。
3. 替换线性扫描：
   - `deriveCurrentPage(scrollOffset)` → `upperBound(prefixHeights, scrollOffset)`。
   - `scrollToPage(page)` → `page === 1 ? 0 : prefixHeights[page - 2]`。
   - 约定：`prefixHeights[i]` 为第 `0` 页到第 `i` 页的累计高度/宽度，因此第 `page` 页的起始偏移为 `page === 1 ? 0 : prefixHeights[page - 2]`。
4. `scrollerItems` 使用缓存后的 size，避免每次渲染重新计算。

**验收标准**：

- `deriveCurrentPage` 与 `scrollToPage` 不再遍历 `pages` 数组。
- 切换 `currentPage`、`toolbar`、`progress` 等不触发 size 重算。
- 改变 `zoom`、`fitMode`、窗口大小时才重建 size 与前缀和。

### Phase IA-2：Visible Range + IntersectionObserver

**范围**：`ReaderViewport.vue`

**改动点**：

1. 在 `RecycleScroller` 的 item wrapper 内为每个活跃 item 添加 `data-index`。
2. 使用 `IntersectionObserver`（threshold: `[0, 0.25, 0.5, 0.75]`）观察这些 item：
   - 记录每个 index 的 `intersectionRatio`。
   - 选择 ratio 最高的 index 作为当前页（通过 `emit('update:currentPage')`）。
3. 同时计算当前最小/最大可见 index，向外 `emit('visible-range', { start, end })`。
4. 保留 `onScroll` 作为降级/补充，但当前页推导以 IO 结果为准。

**验收标准**：

- `ReaderViewport` 向外提供 `visible-range` 事件。
- 滚动停止后 currentPage 与当前真实可见页一致。
- IntersectionObserver 只观察当前渲染的几十个节点，不越界。

### Phase IA-3：PreloadEngine

**范围**：新增 `src/utils/preload-engine.ts`，修改 `ReaderPage.vue`

**改动点**：

1. 新建 `PreloadEngine` 类（受 comics15 参考实现启发）：
   - `setUrlResolver(resolver)`：接收 `(index: number) => string | null`，返回对应页要预热的 URL（HQ 或 LQ 视策略而定）。
   - `onVisibleChange(start, end, total)`：根据真实可见范围调度加载。
   - `loadImmediate(start - 1, end + 1)`：立即加载可视区±1 页。
   - `loadCascadeForward(end + 2)` / `loadCascadeBackward(start - 2)`：各向前后预加载 10 页，按 80ms 间隔排队。
   - `cancelFarIndices(start, end)`：清除远离可视区 10 页外的 cascade 任务。
   - `reset(total)`：切换章节时取消所有在途任务。
2. `ReaderPage` 监听 `visible-range`，调用 `preloadEngine.onVisibleChange(...)`。
3. 移除 `ReaderPage.vue` 中原有的 `preloadPages()` 逻辑。

**验收标准**：

- 快速滚动时旧 cascade 定时器被清空，不会堆积。
- 远离可视区的预加载任务被取消。
- 切换章节时所有预加载任务重置。

### Phase IA-4：ProgressiveImage 生命周期

**范围**：`ProgressiveImage.vue`

**改动点**：

1. DOM 中只保留一个 `<img>`：
   - 显示 LQ 时 `src = lq`。
   - HQ 加载成功后切换 `src = hq`。
   - `HQ_ONLY` 模式直接加载并显示 HQ。
2. HQ 加载使用独立 `HTMLImageElement`：
   - `loadHq()` 创建 `new Image()`，设置 `decoding = 'async'`。
   - 成功后将 `src` 替换为 HQ URL，并释放 loader（`src = ''`，移除事件监听）。
   - URL 变化或组件卸载时，取消在途 HQ 加载。
3. LQ 层直接使用 `<img decoding="async">`。
4. 保留 fade 过渡与错误重试。

**验收标准**：

- 同时存在的 `<img>` 元素不超过 1 个。
- URL 变化时旧 HQ loader 被取消，不触发错误状态。
- 组件卸载时无未释放的图片加载。

## 数据流

```text
ReaderPage
  ├─ 加载 chapter → pages[]
  ├─ 监听 visible-range → PreloadEngine.onVisibleChange(start, end, total)
  └─ 监听 currentPage → saveProgress(debounce)

ReaderViewport
  ├─ 缓存 item sizes + 维护 prefixHeights
  ├─ RecycleScroller 渲染可见 item
  ├─ IntersectionObserver → update:currentPage + visible-range
  └─ scrollToPage / deriveCurrentPage 使用 prefixHeights 二分

PreloadEngine
  ├─ immediate queue（visible ± 1）
  ├─ cascade queue（前后各 10，渐进延迟）
  └─ cancel far indices / reset on chapter change

ProgressiveImage
  ├─ 单 <img> 显示当前图层
  ├─ 独立 Image loader 预载 HQ
  └─ loader 生命周期管理
```

## 性能目标

- `deriveCurrentPage` / `scrollToPage` 时间复杂度 O(log n)。
- size 计算仅在尺寸参数变化时触发一次 O(n)。
- 滚动时 currentPage 更新不触发 O(n) 计算。
- 预加载任务数量上限可控，不因快速滚动无限堆积。
- 单页 DOM 中 `<img>` 数量从 2 个降到 1 个。

## 验证策略

- `npm run type-check` 无类型错误。
- `npm run build` 构建成功。
- 手动 QA：
  - 打开一个 200+ 页的章节，快速滚动/跳转不卡顿。
  - 切换 zoom/fitMode/readingDirection 后布局正确恢复。
  - 切换章节后预加载任务被清空。
  - 网络面板中可见 immediate + cascade 的分层请求，远离项请求被取消。

## 回滚策略

按 Phase 拆分提交，每阶段独立可回滚：

1. `feat(reader): size cache + prefix sum + binary search`
2. `feat(reader): visible range via IntersectionObserver`
3. `feat(reader): PreloadEngine for virtual scroller`
4. `feat(reader): ProgressiveImage single-img lifecycle`

## 风险与规避

| 风险 | 规避 |
|---|---|
| 缓存 key 遗漏导致 size 不更新 | key 包含所有影响尺寸的参数（pageId/zoom/fitMode/viewportW/viewportH） |
| IntersectionObserver 与 RecycleScroller 节点回收冲突 | 只观察当前渲染节点，节点销毁时自动取消观察 |
| PreloadEngine 快速滚动堆积 | 每次 `onVisibleChange` 清空旧 cascade 定时器并 cancel far indices |
| 单 `<img>` 切换导致闪烁 | 保持 opacity transition，HQ decode 完成后再替换 |

## 决策记录

- **保留 RecycleScroller**：用户确认，因为 ComicAtlas 已知每页精确尺寸，无需 DynamicScroller 的估算/测量。
- **IntersectionObserver 不负责预加载**：用户确认，IO 只输出 visible range，预加载由 ReaderPage + PreloadEngine 负责，职责清晰。
- **四阶段实施**：用户确认按 Phase IA-1 → IA-2 → IA-3 → IA-4 逐步推进，每阶段可单独验证。
