# Phase I：Reader Enhancement 实现计划

**目标**：在 v0.1.0 MVP 基础上，把阅读器从「能看」提升到「好看、好用」，优先解决长章节加载与翻页体验问题。

**关联 release**: v0.1.0

---

## 范围

本次 Phase I 聚焦 Reader 体验，不 touching 导入、任务中心、漫画库等其他模块。

### In Scope

- 图片预加载与渐进加载
- 阅读方向切换（LTR / RTL / 滚动）
- 缩放与_fit 模式_
- 全屏模式
- 工具栏自动隐藏 / 显隐切换
- 页码缩略图快速跳转
- 双页模式（简单版，连续两页拼合）
- 触摸/手势翻页（移动端基础支持）

### Out of Scope

- 阅读统计 / 阅读时长
- 注释、书签、高亮
- 在线阅读（EHENTAI 等远程来源）
- AI 翻译 / OCR

---

## 优先级

| 优先级 | 功能 | 理由 |
|--------|------|------|
| P0 | 预加载 + 渐进加载 | 当前长章节翻页会白屏，体验最差 |
| P0 | 缩放 / fit 模式 | 不同尺寸屏幕适配核心能力 |
| P1 | 阅读方向切换 | 用户习惯差异大（日漫 RTL、Webtoon 滚动） |
| P1 | 工具栏自动隐藏 | 沉浸式阅读基础 |
| P1 | 页码缩略图跳转 | 长章节快速定位 |
| P2 | 全屏模式 | 沉浸体验，实现成本低 |
| P2 | 双页模式 | PC 大屏体验提升 |
| P2 | 触摸手势 | 移动端体验提升 |

---

## 技术方案

### 1. 预加载

在 `reader-store` 中维护一个 `preloadRange`，当前页前后各 N 页（默认 3 页）提前加载 `lqUrl` 或 `hqUrl`。

```ts
// 伪代码
const preloadUrls = computed(() => {
  const range = []
  for (let i = currentPage.value - 3; i <= currentPage.value + 3; i++) {
    if (i >= 1 && i <= pages.value.length && i !== currentPage.value) {
      range.push(pages.value[i - 1].lqUrl || pages.value[i - 1].hqUrl)
    }
  }
  return range
})
```

使用 `<link rel="preload">` 或隐藏的 `<img>` 标签实现。

### 2. 渐进加载

优先显示 LQ，点击切换 HQ。保留现有 `hqMode` 开关，但增加「自动 LQ 优先」策略：

- 默认加载 LQ
- 当前页自动加载 HQ
- 非当前页只加载 LQ

### 3. 缩放 / Fit 模式

在 reader-store 中增加 `zoomMode`：

- `fit-width`：宽度 100%
- `fit-height`：高度 100%（单页模式）
- `fit-screen`：完整显示（可能有黑边）
- `actual`：1:1 原始尺寸
- `zoom`：自定义缩放比例

通过 CSS transform 或 CSS object-fit 实现。

### 4. 阅读方向

在 reader-store 中增加 `readingDirection`：

- `ltr`：左→右，下→上滚动
- `rtl`：右→左，下→上滚动
- `vertical`：连续垂直滚动（Webtoon 模式）

方向改变时：

- 调整布局（flex-direction / writing-mode）
- 调整键盘映射（左/右箭头含义互换）

### 5. 工具栏自动隐藏

增加 `toolbarVisible` 状态：

- 鼠标移动 / 点击页面时显示
- 3 秒无操作后隐藏
- 移动端点击页面切换显隐

### 6. 页码缩略图跳转

底部抽屉式缩略图条，显示当前章节所有页面缩略图（复用 `lqUrl`），点击跳转。

### 7. 双页模式

在 reader-store 中增加 `spreadMode`：

- `single`：单页
- `double`：两页并排

双页模式下，页码对齐到偶数页开始（封面单页）。

### 8. 全屏模式

调用 `document.documentElement.requestFullscreen()`，退出时 `document.exitFullscreen()`。

### 9. 触摸手势

- 左滑 / 右滑：翻页
- 双击：放大 / 还原
- 双指缩放：自定义 zoom

---

## 涉及文件

| 文件 | 变更 |
|------|------|
| `frontend/src/stores/reader-store.ts` | 新增 zoomMode、readingDirection、spreadMode、preloadRange、toolbarVisible 等状态与方法 |
| `frontend/src/pages/ReaderPage.vue` | 重构布局，支持方向/缩放/双页/工具栏隐藏/缩略图条 |
| `frontend/src/components/reader/ReaderToolbar.vue` | 新增工具栏组件，集中模式切换按钮（新增独立文件） |
| `frontend/src/components/reader/ThumbnailStrip.vue` | 新增缩略图跳转条（新增独立文件） |
| `frontend/src/types/index.ts` | 扩展 ReaderDTO / PageInfo 类型（如需要） |
| `frontend/src/services/api.ts` | 如需新增 LQ 缩略图接口（可选） |
| `frontend/src/pages/ReaderPage.vue` | 键盘/触摸事件处理 |

---

## 验收标准

- [ ] 长章节（>100 页）连续翻页无明显白屏
- [ ] 切换阅读方向后，键盘左/右箭头方向正确
- [ ] fit-width / fit-height / actual 三种模式可切换
- [ ] 工具栏 3 秒无操作自动隐藏，鼠标移动恢复
- [ ] 缩略图条可点击跳转任意页
- [ ] 全屏模式下布局正常
- [ ] 双页模式下两页并排显示
- [ ] 移动端基础滑动手势可用
- [ ] 所有模式切换后，进度保存与恢复正确

---

## 实施顺序

```
Week 1
├── reader-store 状态扩展
├── 预加载 + LQ 优先策略
└── 缩放 / fit 模式

Week 2
├── 工具栏自动隐藏
├── 阅读方向切换
└── 键盘映射适配

Week 3
├── 页码缩略图跳转
├── 全屏模式
└── 双页模式

Week 4
├── 触摸手势
├── E2E 补充
└── 验收
```

---

## 风险

- 双页模式与 RTL 组合时边界情况多，建议最后做。
- 缩放后滚动定位可能偏移，需要仔细测试。
- 预加载过多大图可能影响内存，默认预加载 3 页即可。

---

## 备注

- 保持现有 API 不变，所有改动集中在前端。
- 继续遵循 Netflix 暗色设计系统。
- 每个功能独立提交，便于回滚。
