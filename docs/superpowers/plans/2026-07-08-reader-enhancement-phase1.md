# Phase I：Reader Enhancement 实现计划

**目标**：在 v0.1.0 MVP 基础上，以 `vue-virtual-scroller` 为基础重构 Reader 渲染层，完成性能闭环；再做交互增强。

**关联 release**: v0.1.0

---

## 拆分原则

Phase I 拆分为两个子阶段，每个阶段独立可验收、可回滚：

- **Phase I-A：Reader 渲染层重构（性能）** — 引入 `vue-virtual-scroller`，封装 `ProgressiveImage`，实现 LQ→HQ 渐进加载与预加载
- **Phase I-B：Reader Interaction** — Fit 模式 + 缩放

P1/P2 功能（阅读方向、工具栏、缩略图、全屏、双页、手势）放到 Phase II 及以后，不在本阶段实现。

---

## Phase I-A：Reader 渲染层重构（P0）

### 目标

> 打开更快、翻页更顺畅、无白屏，长章节滚动不掉帧，DOM 节点数量稳定。

### 1. 组件拆分

```
ReaderPage
│
├── ReaderStore              // 业务状态
├── ReaderSettingsStore      // 用户偏好（持久化）
│
└── ReaderViewport           // RecycleScroller 容器
         │
         └── ReaderImageItem  // 单页渲染
                  │
                  └── ProgressiveImage  // LQ→HQ + Skeleton + Error
```

新增文件：

- `frontend/src/components/reader/ReaderViewport.vue`
- `frontend/src/components/reader/ReaderImageItem.vue`
- `frontend/src/components/reader/ProgressiveImage.vue`

### 2. 虚拟滚动

使用 `vue-virtual-scroller` 的 `RecycleScroller`：

- API 已返回 `width` / `height`，可提前计算每项高度
- 不需要 `DynamicScroller`，`RecycleScroller` 性能更好
- 目标：300 页漫画滚动无明显掉帧，DOM 节点 ≤ 20

### 3. ProgressiveImage 职责

独立封装：

```vue
<ProgressiveImage
  :lq="page.lqUrl"
  :hq="page.hqUrl"
  :mode="settings.qualityMode"
  :aspect-ratio="page.width / page.height"
/>
```

负责：

- 根据 `qualityMode` 决定首屏显示 LQ 还是 HQ
- `AUTO` 模式下 HQ 就绪后无闪烁替换（fade / opacity）
- 加载失败时保留 LQ，显示重试按钮
- 固定占位尺寸，避免 CLS

### 4. 画质模式

用 `qualityMode` 替代原来的 `hqMode` 开关：

- `AUTO`（默认）：先 LQ，HQ 就绪后自动替换
- `HQ_ONLY`：只加载 HQ
- `LQ_ONLY`：只加载 LQ

### 5. 预加载策略

使用 `new Image()` 建立浏览器缓存，不依赖隐藏 `<img>`。

加载优先级：

1. 当前页：立即加载 HQ
2. 下一页：后台加载 HQ（低优先级）
3. 前后 ±2 页：加载 LQ
4. 更远的页面：不加载

窗口大小由 `ReaderSettings.preloadWindow` 控制，默认 ±2。

### 6. ReaderSettings Store 扩展

```ts
export interface ReaderSettingsState {
  qualityMode: QualityMode
  fitMode: FitMode
  zoom: number
  readingDirection: ReadingDirection
  showToolbar: boolean
  preloadWindow: number
  enablePreload: boolean       // 新增
  enableProgressiveImage: boolean // 新增
}
```

- `enablePreload`：调试性能时可关闭
- `enableProgressiveImage`：调试时可关闭渐进加载

### 7. ReaderStore 精简

移除 `hqMode`，只保留业务状态：

```ts
export interface ReaderState {
  chapterId: number
  chapterTitle: string
  pages: PageInfo[]
  currentPage: number
  prevChapterId: number | null
  nextChapterId: number | null
  comicId: number
  loading: boolean
  error: string | null
}
```

---

## Phase I-B：Reader Interaction（P0）

### 目标

> 提供基础交互体验：适配屏幕、支持缩放。

### 1. Fit 模式

支持：

- `fit-width`：宽度撑满容器
- `fit-height`：高度撑满容器
- `fit-screen`：完整显示（可能有黑边）
- `original`：1:1 原始尺寸
- `auto`（默认）：根据图片比例智能选择 `fit-width` 或 `fit-height`

### 2. 缩放

PC：

- `Ctrl + 滚轮` 缩放
- `+` / `-` 按钮
- 双击恢复 100%

缩放级别限制为离散值：

```
50%, 75%, 100%, 125%, 150%, 200%
```

原因：避免 VirtualScroller 高度持续变化，降低实现复杂度。

移动端：

- 本次不做双指缩放，仅保留 fit 模式适配

---

## 涉及文件

| 文件 | 变更 |
|------|------|
| `frontend/src/stores/reader-store.ts` | 精简为业务状态；移除 `hqMode` |
| `frontend/src/stores/reader-settings-store.ts` | 新增 Reader 配置 Store，含 `enablePreload`、`enableProgressiveImage` |
| `frontend/src/components/reader/ReaderViewport.vue` | 新增：RecycleScroller 容器 |
| `frontend/src/components/reader/ReaderImageItem.vue` | 新增：单页包装 |
| `frontend/src/components/reader/ProgressiveImage.vue` | 新增：LQ→HQ 渐进加载组件 |
| `frontend/src/pages/ReaderPage.vue` | 改为使用 ReaderViewport，保留工具栏和进度保存逻辑 |
| `frontend/src/types/index.ts` | 补充 `QualityMode`、`FitMode` 等类型 |

---

## 性能指标

| 指标 | 当前基线 | Phase I-A 目标 |
|------|---------|---------------|
| 首图显示时间 | 基线 | < 300 ms（LQ） |
| HQ 替换时间 | 基线 | < 1 s（本地存储） |
| 翻页等待 | 基线 | 接近 0 |
| 预加载窗口 | 无 | ±2 页 |
| 300 页滚动 FPS | 基线 | 无明显掉帧 |
| DOM 节点数量 | 线性增长 | ≤ 20 |

---

## 验收标准

### Phase I-A

- [ ] `ReaderPage` 使用 `RecycleScroller`，长章节滚动流畅
- [ ] 首图（LQ）立即显示，HQ 自动替换无闪烁
- [ ] `AUTO` / `HQ_ONLY` / `LQ_ONLY` 三种画质模式可切换
- [ ] 当前页优先 HQ，下一页后台 HQ，±2 页 LQ 预加载
- [ ] 300 页漫画下 DOM 节点 ≤ 20，滚动无明显掉帧
- [ ] 内存占用稳定，不持续增长

### Phase I-B

- [ ] 支持 Fit Width / Height / Screen / Original / Auto
- [ ] 支持 Ctrl + 滚轮、+/- 按钮、双击恢复缩放
- [ ] 缩放级别限制在 50/75/100/125/150/200%
- [ ] 缩放后翻页不影响阅读体验
- [ ] Fit/Zoom 设置在当前会话保持一致

---

## 实施顺序

```
Phase I-A
├── 新增 ReaderSettings Store
├── 新增 ProgressiveImage 组件
├── 新增 ReaderImageItem 组件
├── 新增 ReaderViewport 组件（RecycleScroller）
├── ReaderPage 改用 ReaderViewport
├── 实现 AUTO/HQ/LQ 画质模式
├── 实现 ±2 页预加载（new Image()）
└── 性能验收

Phase I-B
├── 新增 ReaderToolbar 组件
├── 实现 Fit 模式
├── 实现离散 Zoom 交互
└── 验收交互指标
```

---

## 风险

- `vue-virtual-scroller` 与 Vue 3 组合式 API 需要正确注册和类型声明。
- RecycleScroller 要求每项高度可预估；API 已返回宽高，但需处理缺失情况。
- LQ→HQ 替换时若图片尺寸不一致可能导致 CLS，需确保占位尺寸固定。
- 缩放后 VirtualScroller 高度需重新计算，可能触发滚动抖动。
- 预加载过多大图可能增加内存，默认 ±2 页是安全窗口。

---

## 备注

- 每个子阶段独立提交，便于回滚。
- 保持现有 API 不变，所有改动集中在前端。
- 继续遵循 Netflix 暗色设计系统。
- `vue-virtual-scroller` 已安装（`^2.0.1`）。
