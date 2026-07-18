# ComicAtlas 0.3 — 移动端阅读体验优化设计

**版本**: 0.3  
**日期**: 2026-07-18  
**状态**: Canonical  
**主题**: **Mobile Reading Experience**

---

## 目录

1. [产品定位](#1-产品定位)
2. [架构概览](#2-架构概览)
3. [Reader 状态机](#3-reader-状态机)
4. [移动端工具栏控件布局](#4-移动端工具栏控件布局)
5. [阅读链路响应式布局](#5-阅读链路响应式布局)
6. [管理模块桌面边界](#6-管理模块桌面边界)
7. [Device Policy](#7-device-policy)
8. [Virtualization Policy](#8-virtualization-policy)
9. [目录与组件结构](#9-目录与组件结构)
10. [范围边界](#10-范围边界)

---

## 1. 产品定位

### 核心主题

> **0.3 = Mobile Reading Experience**

ComicAtlas 的两个核心域（Reading / Management）在 0.2 完成了功能分离，0.3 进一步完成**设备定位分离**。

| 域 | 设备策略 | 优先级 |
|---|---|---|
| Reading | Mobile First | ⭐⭐⭐ |
| Management | Desktop Only | ⭐ |

### 使用场景

```
PC                         手机
│                          │
├─ 导入漫画                 ├─ 每天阅读
├─ 修改标签                 ├─ 继续阅读
├─ 整理封面                 └─ 退出
└─ 关闭
```

> **90% 的时间发生在手机。Reader 是整个项目价值最高的页面。**

### 0.3 三大主题

| 优先级 | 主题 | 说明 |
|--------|------|------|
| P0 | Mobile Reader | 沉浸式重设计、工具栏触摸优化、手势交互 |
| P1 | Mobile Reading Flow | Home / Library / Detail / History 移动端布局 |
| P2 | Desktop Management Boundary | `/manage/*` 桌面锁定、移动端拦截 |

### 设计原则（继承自 0.2）

1. **阅读是唯一核心体验。**
2. **阅读和管理彻底分离。**
3. **管理服务于阅读。**

### 0.3 新增设计原则

4. **页面保持统一，交互允许分化。** — View 不拆 Mobile/Desktop 双份，交互逻辑按设备拆分。
5. **Interaction Layer 负责协调状态，不负责渲染 UI。** — 用户输入 → 页面状态，不操作 DOM。
6. **Composable 保持平级，由 View 统一组合，不互相依赖。**
7. **Viewport 永远保持纯渲染组件，不承担交互逻辑。**
8. **Layout 负责响应式，业务组件保持设备无关。** — ComicCard 不感知 Mobile/Desktop。
9. **高频操作留在屏幕，低频操作进入抽屉。** — 移动端工具栏最小化。

---

## 2. 架构概览

### 整体分层

```
┌─────────────────────────────────────────┐
│               Router (不变)              │
│  ReadingLayout / ReaderLayout / Mgmt    │
├─────────────────────────────────────────┤
│            Views (不拆分)                │
│  HomePage / LibraryPage / DetailPage    │
│  ReaderPage / HistoryPage               │
├─────────────────────────────────────────┤
│       Interaction Layer (新增)           │
│  ┌─────────────────────────────────┐    │
│  │ useInteractionMode()            │    │
│  │ → mode: "desktop" | "mobile"     │    │
│  │   (保留 "tablet" 扩展点)          │    │
│  │                                  │    │
│  │ useReaderGesture()               │    │
│  │ → onTap / onSwipe / onPinch     │    │
│  │                                  │    │
│  │ useAutoHide()                    │    │
│  │ → visible / show / hide / pause  │    │
│  └─────────────────────────────────┘    │
├─────────────────────────────────────────┤
│        UI Components (按 mode 拆分)      │
│  ReaderToolbarDesktop / Mobile          │
│  ReaderBottomNav (仅 mobile)             │
│  ReaderSettingsDrawer                    │
│  ReaderViewport (纯渲染，不感知 mode)      │
└─────────────────────────────────────────┘
```

### 关键决策

| 层 | 是否拆分 | 原因 |
|---|---|---|
| Layout | ❌ 不拆 | 产品结构一致 |
| View/Page | ❌ 不拆 | 业务流程一致 |
| Interaction | ✅ 按 mode 分 | 输入方式不同 |
| UI Component | ✅ 按 mode 分 | 视觉布局不同 |

### InteractionMode 检测

```ts
// 返回 InteractionContext，不只返回 mode
interface InteractionContext {
  mode: Ref<'desktop' | 'mobile'>
  coarsePointer: Ref<boolean>
  supportsHover: Ref<boolean>
}

function useInteractionMode(): InteractionContext {
  const width = useBreakpoint()             // 统一断点，不硬编码
  const coarsePointer = useMediaQuery('(pointer: coarse)')
  const supportsHover = useMediaQuery('(hover: hover)')

  const mode = computed(() =>
    width.value <= 768 && coarsePointer.value ? 'mobile' : 'desktop'
  )
  return { mode, coarsePointer, supportsHover }
}
```

**检测逻辑**：`width ≤ 768px AND pointer: coarse`（双重条件），非简单 Touch 检测。

**设计意图**：`InteractionContext` 提供能力描述而非设备标签，不同组件可按需取用（如 Library 只需 `supportsHover`，不需 `mode`）。

### 设备检测工具（独立于 Vue）

```ts
// 独立纯函数，不依赖 Vue Composable 生命周期
export function isMobileReadingDevice(): boolean {
  return window.matchMedia('(pointer: coarse)').matches &&
         window.innerWidth <= 768
}
```

用于 Router Guard 等非 Vue 上下文的场景。

---

## 3. Reader 状态机

### 状态定义

```ts
enum ReaderUiState {
  IMMERSIVE,   // 全屏阅读，无工具栏
  TOOLBAR,     // 工具栏 + 底部导航可见
  SETTINGS,    // 设置抽屉打开（暂停自动隐藏）
}

enum ReaderAction {
  TapCenter,       // 点击页面中央
  OpenSettings,    // 点击 ⋯ 按钮
  CloseSettings,   // 关闭设置（× / 遮罩点击 / 下滑）
  AutoHideTimeout, // 4s 无操作超时
  AndroidBack,     // Android 返回键
}
```

### 状态转换表

| Current State | Action | Next State |
|---------------|--------|------------|
| IMMERSIVE | TapCenter | TOOLBAR |
| TOOLBAR | TapCenter | IMMERSIVE |
| TOOLBAR | AutoHideTimeout | IMMERSIVE |
| TOOLBAR | OpenSettings | SETTINGS |
| SETTINGS | CloseSettings | TOOLBAR |
| TOOLBAR | AndroidBack | IMMERSIVE |
| IMMERSIVE | AndroidBack | Exit Reader |
| SETTINGS | AndroidBack | TOOLBAR |

### 状态图

```
                    ┌─────────────┐
        进入 Reader  │  IMMERSIVE  │
                    └──────┬──────┘
                           │ TapCenter
                           ▼
                    ┌─────────────┐
          Back      │   TOOLBAR   │
      (Android)     │   VISIBLE   │──── 4s 无操作 ────→ IMMERSIVE
                    └──┬───┬──┬──┘
                       │   │  │
          TapCenter    │   │  │ OpenSettings
                       ▼   │  ▼
                  IMMERSIVE │  ┌──────────────┐
                            │  │   SETTINGS   │
              TapBottomNav  │  │     OPEN     │
                 buttons    │  │ (暂停隐藏计时) │
                            │  └──────┬───────┘
                            ▼         │ CloseSettings
                        执行导航      ▼
                      (状态不切换)  TOOLBAR
```

### AutoHide 规则

- 进入 TOOLBAR 时启动 4s 倒计时
- **任何交互**（tap、swipe、pinch、点击按钮）→ 重置计时器
- 切换到 SETTINGS → 暂停计时器
- SETTINGS 关闭回到 TOOLBAR → 重新启动 4s 计时
- 倒计时到 0 → 切换到 IMMERSIVE

### Android Back 规则

采用 Overlay Stack 原则——优先关闭最上层 Overlay：

- SETTINGS → 关闭设置 → TOOLBAR
- TOOLBAR → 隐藏工具栏 → IMMERSIVE
- IMMERSIVE → 退出 Reader

### Composable 架构（平级，不互相依赖）

```
ReaderPage.vue （唯一的组合者）
    │
    ├── useInteractionMode()     → { mode, coarsePointer, supportsHover }
    ├── useReaderGesture(viewportRef)  → { onTap, onSwipe, onPinch }
    │       ↑ 接收 viewport 的 template ref，绑定 pointer/touch 事件
    │       ↑ emit 标准化事件：TapCenter / SwipeLeft / PinchZoom
    │       ↑ 仅负责事件标准化，不操作任何状态
    │
    ├── useAutoHide(timeout=4000) → { visible, show, hide, pause, resume }
    │       ↑ 纯计时器逻辑，不感知 gesture 或 toolbar
    │
    └── useReaderToolbar()       → { state, dispatch(action) }
            ↑ 内部使用 useAutoHide
            ↑ 暴露 dispatch 而非直接 setState
```

**依赖说明**：`useReaderGesture` 需要接收 `viewportRef: Ref<HTMLElement | null>`，在 `onMounted` 后绑定事件。除此之外，所有 composable 保持平级——gesture 不调用 toolbar，toolbar 不调用 gesture，全部由 ReaderPage 协调。

**事件流**：

```
Gesture → emit(TapCenter) → ReaderPage → dispatch(TapCenter) → State Machine → TOOLBAR → UI render
```

Gesture 不直接操作 Toolbar 状态，全部经由 ReaderPage 协调。

---

## 4. 移动端工具栏控件布局

### 移动端布局

```
┌──────────────────────────────┐
│  ← 漫画名               ⋯   │  ← ReaderToolbarMobile
│                              │     高度 48px，半透明背景
│                              │
│                              │
│         漫画内容              │
│                              │
│                              │
├──────────────────────────────┤
│  ← 上一话  ━━━━━━  📂目录  下一话 → │  ← ReaderBottomNav
└──────────────────────────────┘     高度 56px，大触控目标
```

### 桌面端布局（保持不变）

```
┌─────────────────────────────────────────────────────────────┐
│  ← 第35话    12/45   画质▾ 适配▾ 方向▾  - 100% +  ⚙  上一章 下一章 │
└─────────────────────────────────────────────────────────────┘
```

### 控件分配

| 控件 | 桌面 | 移动 | 位置 |
|------|------|------|------|
| 返回按钮 ← | ✅ Toolbar | ✅ Toolbar | 顶部左 |
| 漫画名 | ✅ Toolbar | ✅ Toolbar | 顶部中（仅漫画名，不含长章节标题） |
| 章节标题 | ✅ Toolbar | ❌ | 过长不适合移动端显示 |
| 页码 (12/45) | ✅ Toolbar | ❌ | — |
| 更多入口 ⋯ | ✅ Dropdown | ✅ Toolbar 右 | 打开 SettingsDrawer |
| 画质选择 | ✅ ElSelect | ✅ SettingsDrawer | 移入抽屉 |
| 适配模式 | ✅ ElSelect | ✅ SettingsDrawer | 移入抽屉 |
| 阅读方向 | ✅ ElSelect | ✅ SettingsDrawer | 移入抽屉 |
| 缩放 -/+ | ✅ 独立按钮 | ✅ SettingsDrawer | 移入抽屉（滑块 + ±按钮） |
| 上一章 | ✅ 文字按钮 | ✅ BottomNav ← | 底部左 |
| 目录 | ❌ | ✅ BottomNav 📂 | 底部中 |
| 下一章 | ✅ 文字按钮 | ✅ BottomNav → | 底部右 |

### SettingsDrawer（底部抽屉）

```
┌──────────────────────────────┐
│         阅读设置              │  ← 标题 + 关闭 ×
├──────────────────────────────┤
│  阅读方向  ○ 纵向  ○ 横向     │  ← 高频优先
├──────────────────────────────┤
│  适配模式  ○ 自动  ○ 适宽     │
│            ○ 适高  ○ 原始     │
├──────────────────────────────┤
│  缩放      [-] ━━━●━━━ [+]  │  ← 滑块 + 快捷按钮
│            125%              │
├──────────────────────────────┤
│  画质      ○ 自动  ○ 原图     │  ← 低频
│            ○ 省流             │
├──────────────────────────────┤
│  ─── 高级 ───                │
│  预加载    [══════●══] 开     │  ← enablePreload
│  渐进加载  [══════●══] 开     │  ← enableProgressiveImage
└──────────────────────────────┘
```

**设置排序原则**：按使用频率排列——阅读方向 > 适配模式 > 缩放 > 画质。

**阅读方向说明**：Store 中 `ReadingDirection` 类型为 `'ltr' | 'rtl' | 'vertical' | 'horizontal'`，但移动端只暴露 `vertical` 和 `horizontal` 两个选项。`ltr`/`rtl` 仅桌面端可用，移动端无需暴露。

**抽屉交互**：
- 从底部滑入，高度约 60% 视口
- 背景半透明遮罩，点击遮罩关闭
- 选项即时生效，无需"保存"按钮
- 打开时暂停 AutoHide 计时器

### BottomNav

- 上一话 / 目录 / 下一话 三个按钮，≥ 48px 触控目标
- 中间增加阅读进度条（`━━━━━━●━━━`），非精确页码，仅示意位置
- **不与 SettingsDrawer 重复**：BottomNav 专注导航，Drawer 专注设置

### Safe Area

```css
.reader-toolbar-mobile {
  padding-top: env(safe-area-inset-top);
}
.reader-bottom-nav {
  padding-bottom: env(safe-area-inset-bottom);
}
```

---

## 5. 阅读链路响应式布局

### 原则

> **Layout 负责响应式，业务组件保持设备无关。**

ComicCard、CatalogTree 等业务组件不接受 `mode` prop，由父级 Grid/List 组件统一处理布局适配。

### HomePage 移动端

```
┌──────────────────────────────┐
│         ComicAtlas           │
├──────────────────────────────┤
│    ┌────────────────────┐    │
│    │   继续阅读封面       │    │  ← HomeHero 全宽
│    │   ▶ 继续阅读        │    │
│    └────────────────────┘    │
│                              │
│  继续阅读                     │  ← HomeRow 改为横向 scroll-snap
│  [封][封][封][封]→           │
│                              │
│  最近阅读                     │
│  [封][封][封][封]→           │
│                              │
│  最近加入                     │
│  [封][封][封][封]→           │
│                              │
│  ┌──────────┐ ┌──────────┐   │
│  │  漫画库   │ │  历史    │   │  ← 2 列，触控目标 ≥ 44px
│  └──────────┘ └──────────┘   │
└──────────────────────────────┘
```

**单手操作原则**：高频入口（继续阅读、漫画库、历史）尽量位于屏幕下半区。

### LibraryPage 移动端

```
┌──────────────────────────────┐
│  🔍 搜索...           🔽排序 │  ← 合并为一行
│  [全部] [日漫] [韩漫] [国漫]→│  ← Category 横滚 chips
├──────────────────────────────┤
│  ┌────┐ ┌────┐ ┌────┐       │  ← 最小宽度驱动的自适应网格
│  │封面│ │封面│ │封面│       │     grid-template-columns:
│  │标题│ │标题│ │标题│       │     repeat(auto-fill, minmax(110px, 1fr))
│  └────┘ └────┘ └────┘       │
│  ┌────┐ ┌────┐ ┌────┐       │
│  │ .. │ │ .. │ │ .. │       │
│  └────┘ └────┘ └────┘       │
│                              │
│         [ 加载更多 ]          │
└──────────────────────────────┘
```

**网格策略**：使用 `repeat(auto-fill, minmax(110px, 1fr))` 而非固定列数，自动适配不同屏幕宽度。

### DetailPage 移动端

```
┌──────────────────────────────┐
│                              │
│      ┌────────────────┐      │  ← 封面居中，40-50% 宽，max 220px
│      │    封面图片     │      │
│      └────────────────┘      │
│                              │
│      漫画标题                 │  ← 居中
│      作者 · 分类 · 100页      │
│                              │
│   ┌──────────────────────┐   │
│   │    ▶ 继续阅读         │   │  ← 全宽主按钮（仅一个）
│   └──────────────────────┘   │     history 存在 → "继续阅读"
│                              │     无 history → "开始阅读"
│  信息                        │
│  ┌──────────────────────┐   │
│  │ 作者    张三          │   │  ← info-grid → 单列
│  │ 页数    100页         │   │
│  │ 分类    日漫          │   │
│  │ 标签    [热血][战斗]   │   │
│  └──────────────────────┘   │
│                              │
│  目录（42话）                 │
│  ┌──────────────────────┐   │
│  │ ● 第1卷              │   │  ← CatalogTree
│  │   第1话 觉醒          │   │     一级目录默认展开
│  │   第2话 修炼          │   │     章节用虚拟列表
│  │   第3话 出发          │   │     整行可点击
│  └──────────────────────┘   │
└──────────────────────────────┘
```

**关键决策**：
- PC 左右布局 → 移动端纵向堆叠
- 同时只显示一个主操作按钮
- 封面居中，宽度 40-50%，最大 220px
- CatalogTree 支持大目录：一级目录默认展开，章节使用虚拟列表

### HistoryPage 移动端

使用虚拟列表，按时间分组显示（今天/昨天/更早——0.3 可暂不实现分组，保留扩展）。

### 实现约束

1. 所有阅读链路页面保持单一 View，不创建 Mobile/Desktop 双页面。
2. 响应式适配仅通过布局组件实现，业务组件（ComicCard 等）保持设备无关。
3. 组件不接收 `mode` prop——由父级 Grid/Row 组件负责布局。

---

## 6. 管理模块桌面边界

### 拦截方案

```
用户手机访问 /manage/*

        │
        ▼
┌──────────────────┐
│  路由守卫检测      │  ← isMobileReadingDevice()
│  (beforeEach)    │
└──────┬───────────┘
       │
   YES │          NO
       ▼           ▼
┌──────────────┐  正常渲染 ManagementLayout
│  拦截页       │
│              │
│  管理功能      │
│  面向桌面端    │
│  设计          │
│              │
│  [ 回到首页 ] │
│              │
└──────────────┘
```

### 拦截页内容

> 阅读功能支持手机和平板。
> 
> 管理功能为了更高的编辑效率，仅支持桌面浏览器。
> 
> 请使用电脑访问管理后台。

底部仅一个按钮：**回到阅读首页**。

### 实现约束

| 项 | 决策 |
|---|---|
| 拦截范围 | `/manage` 及其所有子路由 |
| 检测方式 | `isMobileReadingDevice()` 独立函数，不依赖 Vue Composable |
| 检测时机 | 仅在**进入** `/manage/*` 时检测，不因 resize 自动跳转 |
| 用户绕过 | 不允许——拦截页不提供"仍然访问"按钮 |
| 桌面端窄窗口 | 不受影响（`pointer: coarse` 双重条件确保） |
| 开发调试 | `?force-desktop=1` 参数临跳（仅 `import.meta.env.DEV`） |

---

## 7. Device Policy

> **ComicAtlas 0.3 Canonical Device Policy**

| 模块 | Desktop | Tablet | Mobile | 说明 |
|------|---------|--------|--------|------|
| Reader | ✅ | ✅ | ⭐核心 | Mobile First 沉浸式交互 |
| Home | ✅ | ✅ | ⭐优先 | 响应式纵向布局 |
| Library | ✅ | ✅ | ⭐优先 | 自适应网格 |
| Detail | ✅ | ✅ | ⭐优先 | 纵向堆叠布局 |
| History | ✅ | ✅ | ⭐优先 | 虚拟列表 |
| **Management** | ⭐ | ⚠️ | ❌ | 仅保证桌面端 |
| - Comics | ⭐ | ⚠️ | ❌ | — |
| - Import | ⭐ | ⚠️ | ❌ | — |
| - Storage | ⭐ | ⚠️ | ❌ | — |
| - Metadata | ⭐ | ⚠️ | ❌ | — |
| - Settings | ⭐ | ⚠️ | ❌ | — |

**符号说明**：
- ⭐ = 优先保证（主要设计目标）
- ✅ = 保证可用（经过适配）
- ⚠️ = 不承诺适配（可能可用但不保证）
- ❌ = 主动拦截（不可访问）

**Tablet 策略**：Tablet 默认采用 Desktop 体验。

---

## 8. Virtualization Policy

使用 `vue-virtual-scroller` 作为唯一的虚拟列表方案。

| 页面 | 虚拟化 | 原因 |
|------|--------|------|
| Reader | ✅ 必须 | 阅读性能核心，已有实现 |
| CatalogTree | ✅ 推荐 | 大章节列表（100+ chapters） |
| History | ✅ 推荐 | 长历史记录 |
| Library | ⚠️ 可选 | 当前几百本无需，数据量大时启用 |
| Home | ❌ | 数据量小 |
| Detail | ❌ | 单页内容 |
| SettingsDrawer | ❌ | 固定少量选项 |
| Management 页面 | ❌ | 桌面端，无性能压力 |

### Reader 约束

> **Interaction Layer 永远不要直接操作 Scroller 内部状态。**

事件流：`Gesture → ReaderPage → Navigation → Scroller API`。Scroller 保持为独立渲染层。

---

## 9. 目录与组件结构

### 新增/调整的目录结构

```
frontend/src/
├── views/
│   └── reading/
│       └── reader/
│           ├── composables/
│           │   ├── useInteractionMode.ts    # InteractionContext
│           │   ├── useReaderGesture.ts      # onTap/onSwipe/onPinch
│           │   ├── useAutoHide.ts           # 纯计时器
│           │   ├── useReaderToolbar.ts      # 状态机 dispatch
│           │   └── useReaderNavigation.ts   # 章节导航
│           └── components/
│               ├── ReaderToolbar.vue         # 入口，根据 mode 渲染子组件
│               ├── ReaderToolbarDesktop.vue  # 桌面完整工具栏
│               ├── ReaderToolbarMobile.vue   # 移动极简工具栏（← 标题 ⋯）
│               ├── ReaderBottomNav.vue       # 移动端底部导航
│               ├── ReaderSettingsDrawer.vue  # 底部抽屉设置面板
│               ├── ReaderViewport.vue        # 纯渲染（从 components/reading/reader/ 迁移）
│               ├── ReaderImageItem.vue       # 随 ReaderViewport 迁移
│               └── ProgressiveImage.vue      # 随 ReaderViewport 迁移
```

### 现有组件调整

| 组件 | 调整 |
|------|------|
| `ReaderPage.vue` | 更新 import 路径，注入 composables，按 mode 条件渲染子组件 |
| `ReaderToolbar.vue` | 改为入口组件（从 `components/reading/reader/` 迁移至 `views/reading/reader/components/`） |
| `ReaderViewport.vue` | 随 ReaderToolbar 一同迁移至 `views/reading/reader/components/` |
| `ReaderImageItem.vue` | 随 ReaderViewport 迁移（相对 import 依赖） |
| `ProgressiveImage.vue` | 随 ReaderViewport 迁移（相对 import 依赖） |
| `DetailPage.vue` | CSS：info-grid 单列，封面居中，按钮全宽 |
| `HomePage.vue` | HomeRow 增加 scroll-snap 模式 |
| `LibraryPage.vue` | 自适应网格，Category 横滚 |
| `TopNav.vue` | 移动端精简 |
| `router/index.ts` | 新增 `beforeEach` 守卫 |

### 不修改的部分

- Router 结构、Layout 结构
- Store（reader store、comic store、history store）
- API Service 层
- Management 所有页面
- 后端任何代码

### 新增共享工具

| 工具 | 位置 | 说明 |
|------|------|------|
| `useBreakpoint()` | `frontend/src/composables/useBreakpoint.ts` | 返回当前窗口宽度 `Ref<number>`，供组件与断点值（768 / 1024 / 1440）比较 |
| `useMediaQuery()` | `frontend/src/composables/useMediaQuery.ts` | 响应式 MediaQuery 封装，返回 `Ref<boolean>` |
| `isMobileReadingDevice()` | `frontend/src/utils/device.ts` | 纯函数，用于 Router Guard 等非 Vue 上下文 |

---

## 10. 范围边界

### 0.3 包含

- ✅ Reader 状态机 + 移动端沉浸式交互
- ✅ ReaderToolbarMobile + ReaderBottomNav + SettingsDrawer
- ✅ ReaderGesture（tap/swipe）composable
- ✅ HomePage / LibraryPage / DetailPage 移动端响应式布局
- ✅ CatalogTree 虚拟列表（vue-virtual-scroller）
- ✅ HistoryPage 虚拟列表（vue-virtual-scroller）
- ✅ `/manage/*` 路由守卫 + 拦截页
- ✅ Device Policy 文档
- ✅ Virtualization Policy 文档

### 0.3 不包含

- ❌ PWA（Service Worker / 添加到主屏幕 / 离线缓存）→ 0.4
- ❌ Tablet 独立交互模式 → 保留扩展点
- ❌ 亮度控制（黑色遮罩 overlay）
- ❌ 双击缩放切换
- ❌ 阅读设置中的"跳转章节"选择器
- ❌ History 按时间分组
- ❌ Library 搜索结果页虚拟列表
- ❌ 管理模块任何移动端适配

### 后续版本展望

- **0.4**：PWA、亮度控制、tablet 模式、双击缩放切换
- **0.5+**：书签、搜索页面、阅读统计

---

## 变更记录

| 日期 | 版本 | 说明 |
|------|------|------|
| 2026-07-18 | 0.3 | 初始设计，确立 Interaction Layer + Device Policy + Virtualization Policy |
