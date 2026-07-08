# Phase I：Reader Enhancement 实现计划

**目标**：在 v0.1.0 MVP 基础上，把阅读器从「能看」提升到「打开快、翻页顺、不白屏」，优先完成性能闭环，再完善交互。

**关联 release**: v0.1.0

---

## 拆分原则

Phase I 拆分为两个子阶段，每个阶段独立可验收、可回滚：

- **Phase I-A：Reader Performance** — 预加载 + LQ→HQ 渐进加载
- **Phase I-B：Reader Interaction** — Fit 模式 + 缩放

P1/P2 功能（阅读方向、工具栏、缩略图、全屏、双页、手势）放到 Phase II 及以后，不在本阶段实现。

---

## Phase I-A：Reader Performance（P0）

### 目标

> 打开更快、翻页更顺畅、无白屏。

### 功能 1：图片预加载 ⭐⭐⭐⭐⭐

**策略**：

- 当前页立即加载
- 下一页优先预加载
- 前一页缓存
- 预加载窗口：±2 页（共 5 页），不一次性加载全章节

**示例**：当前第 10 页时，后台预加载 `8, 9, 11, 12`。

**实现**：

```ts
const preloadUrls = computed(() => {
  const range = []
  for (let i = currentPage.value - 2; i <= currentPage.value + 2; i++) {
    if (i >= 1 && i <= pages.value.length && i !== currentPage.value) {
      range.push(pages.value[i - 1].lqUrl || pages.value[i - 1].hqUrl)
    }
  }
  return range
})
```

使用隐藏 `<img>` 标签或 `<link rel="preload">` 触发浏览器加载。

### 功能 2：LQ → HQ 渐进加载 ⭐⭐⭐⭐⭐

**加载流程**：

```
进入 Reader
    ↓
立即显示 LQ
    ↓
HQ 下载完成
    ↓
无闪烁替换（保持尺寸，避免 CLS）
```

**画质模式**：

用 `qualityMode` 替代原来的 `hqMode` 开关：

- `AUTO`（默认）：先 LQ，HQ 就绪后自动替换
- `HQ_ONLY`：只加载 HQ
- `LQ_ONLY`：只加载 LQ

**实现要点**：

- 当前页优先加载 HQ
- 非当前页只加载 LQ
- HQ 加载失败时保留 LQ，不显示白屏
- 使用占位尺寸避免布局跳动

---

## Phase I-B：Reader Interaction（P0）

### 目标

> 提供基础交互体验：适配屏幕、支持缩放。

### 功能 3：Fit 模式

支持：

- `fit-width`：宽度撑满容器
- `fit-height`：高度撑满容器
- `fit-screen`：完整显示（可能有黑边）
- `original`：1:1 原始尺寸
- `auto`（默认）：根据图片比例智能选择 `fit-width` 或 `fit-height`

### 功能 4：缩放

PC：

- `Ctrl + 滚轮` 缩放
- `+` / `-` 按钮
- 双击恢复 100%

移动端：

- 本次不做双指缩放，仅保留 fit 模式适配

---

## 新增 Reader Settings Store

把阅读器配置状态从业务状态中剥离：

```ts
// ReaderState：业务状态，生命周期与当前章节绑定
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

// ReaderSettings：用户偏好，可持久化到 localStorage
export interface ReaderSettings {
  qualityMode: 'AUTO' | 'HQ_ONLY' | 'LQ_ONLY'
  fitMode: 'auto' | 'fit-width' | 'fit-height' | 'fit-screen' | 'original'
  zoom: number
  readingDirection: 'ltr' | 'rtl' | 'vertical'
  showToolbar: boolean
  preloadWindow: number
}
```

`ReaderSettings` 使用 Pinia Store 或 Vue Composable，当前会话保持一致，后续可持久化。

---

## 涉及文件

| 文件 | 变更 |
|------|------|
| `frontend/src/stores/reader-store.ts` | 精简为业务状态；移除 `hqMode`，迁移到 settings |
| `frontend/src/stores/reader-settings-store.ts` | 新增 Reader 配置 Store（I-A 引入） |
| `frontend/src/pages/ReaderPage.vue` | 预加载、渐进加载、fit/zoom 渲染 |
| `frontend/src/components/reader/ReaderToolbar.vue` | 新增工具栏：画质模式、fit 模式、缩放按钮（I-B 引入） |
| `frontend/src/types/index.ts` | 补充 `QualityMode`、`FitMode` 等类型 |

---

## 性能指标

| 指标 | 当前基线 | Phase I-A 目标 |
|------|---------|---------------|
| 首图显示时间 | 基线 | < 300 ms（LQ） |
| HQ 替换时间 | 基线 | < 1 s（本地存储） |
| 翻页等待 | 基线 | 接近 0 |
| 预加载窗口 | 无 | ±2 页 |

---

## 验收标准

### Phase I-A

- [ ] Reader 打开时首图（LQ）立即显示
- [ ] HQ 自动替换 LQ，无明显闪烁
- [ ] 翻页基本无等待（预加载命中）
- [ ] 内存占用稳定，不持续增长
- [ ] `AUTO` / `HQ_ONLY` / `LQ_ONLY` 三种画质模式可切换

### Phase I-B

- [ ] 支持 Fit Width / Height / Screen / Original / Auto
- [ ] 支持 Ctrl + 滚轮、+/- 按钮、双击恢复缩放
- [ ] 缩放后翻页不影响阅读体验
- [ ] Fit/Zoom 设置在当前会话保持一致

---

## 实施顺序

```
Phase I-A
├── 新增 ReaderSettings Store
├── 实现 LQ→HQ 渐进加载（AUTO 模式）
├── 实现 ±2 页预加载
└── 验收性能指标

Phase I-B
├── 新增 ReaderToolbar 组件
├── 实现 Fit 模式
├── 实现 Zoom 交互
└── 验收交互指标
```

---

## 风险

- 预加载过多大图可能增加内存，默认 ±2 页是安全窗口。
- LQ→HQ 替换时若图片尺寸不一致可能导致 CLS，需确保占位尺寸固定。
- 缩放后 `scrollIntoView` 定位可能偏移，需重新测试键盘翻页。

---

## 备注

- 每个子阶段独立提交，便于回滚。
- 保持现有 API 不变，所有改动集中在前端。
- 继续遵循 Netflix 暗色设计系统。
