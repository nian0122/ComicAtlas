# ComicAtlas Design System

## 1. Atmosphere & Identity

ComicAtlas 是一间“夜间编目室”：安静、成熟、精确，既能沉浸阅读，也能像档案员一样快速整理私人漫画库。识别性来自“档案书脊线”——当前导航、选中条目和主要进度都使用一条克制的朱砂线；界面本身保持中性，不从封面取色，也不让媒体内容污染应用的品牌层。

## 2. Color

### Palette

| Role | Token | Value | Usage |
|---|---|---:|---|
| Canvas | `--color-canvas` | `#0e0e0c` | 页面底色 |
| Surface 1 | `--color-surface-1` | `#151513` | 导航、主面板 |
| Surface 2 | `--color-surface-2` | `#1c1c19` | 卡片、输入框 |
| Surface 3 | `--color-surface-3` | `#25231f` | 悬浮、选中表面 |
| Text 1 | `--color-text-1` | `#f4efe6` | 标题、正文 |
| Text 2 | `--color-text-2` | `#bcb4a6` | 次级信息 |
| Text 3 | `--color-text-3` | `#8a8276` | 辅助、禁用 |
| Line subtle | `--color-line-subtle` | `#2b2924` | 分隔线 |
| Line strong | `--color-line-strong` | `#4b463e` | 控件边界 |
| Brand | `--color-brand` | `#f06a4d` | 继续阅读、选中、进度 |
| Brand hover | `--color-brand-hover` | `#ff8065` | 品牌交互悬浮 |
| Focus | `--color-focus` | `#ffd2c7` | 键盘焦点 |
| Success | `--color-success` | `#66c58b` | 成功 |
| Warning | `--color-warning` | `#d8a54f` | 警告、处理中 |
| Danger | `--color-danger` | `#f06b70` | 失败、危险操作 |
| Info | `--color-info` | `#70a6d8` | 信息状态 |

### Rules

- 朱砂只表示主要动作、当前选中和阅读进度；危险操作必须使用独立的 `--color-danger`。
- 表面层级主要依靠暖黑色阶与发丝线，不使用玻璃拟态。
- 成人或高饱和封面不得改变应用 chrome 的颜色。
- 新颜色必须先加入本表，不在组件中写原始色值。

## 3. Typography

| Level | Token | Size | Weight | Line Height | Usage |
|---|---|---:|---:|---:|---|
| Hero | `--text-hero` | `clamp(2rem, 4.2vw, 3.75rem)` | 700 | 1.08 | 首页主标题 |
| Page | `--text-page` | `clamp(1.75rem, 3vw, 2.5rem)` | 700 | 1.15 | 页面标题 |
| Section | `--text-section` | `clamp(1.375rem, 2vw, 1.875rem)` | 700 | 1.2 | 区块标题 |
| Large | `--text-lg` | `1.125rem` | 500 | 1.5 | 引导文案 |
| Body | `--text-md` | `1rem` | 400 | 1.6 | 正文、输入 |
| Small | `--text-sm` | `0.875rem` | 400–600 | 1.5 | 控件、元数据 |
| Caption | `--text-xs` | `0.75rem` | 500 | 1.5 | 辅助信息 |

- UI：`Source Han Sans VF` / `Noto Sans CJK SC` / `Noto Sans JP` / 系统无衬线。
- 编辑性标题：`Source Han Serif VF` / `Noto Serif CJK SC` / `Songti SC` / 系统衬线。
- 数字使用 UI 字体与 `font-variant-numeric: tabular-nums`，不增加第三套字体。
- 卡片标题固定两行；Hero 桌面最多两行、移动最多三行。
- CJK 使用 `line-break: strict; word-break: normal; overflow-wrap: anywhere`，避免单字孤行。

## 4. Spacing & Layout

- 基础单位：4px。
- 间距：`--space-1` 4px、`--space-2` 8px、`--space-3` 12px、`--space-4` 16px、`--space-5` 20px、`--space-6` 24px、`--space-8` 32px、`--space-10` 40px、`--space-12` 48px、`--space-16` 64px。
- 最大内容宽度：`--content-max: 1440px`。
- 页面边距：`--content-gutter: clamp(16px, 3vw, 40px)`。
- 顶栏：64px；管理侧栏：216px。
- 阅读端由文档滚动；管理端由主内容区独占纵向滚动。
- 375px 下主内容必须单轴纵向阅读，不允许主区域出现水平溢出；横向 reel 和数据表必须显式声明自己的滚动职责。

## 5. Components

### Brand mark

- **Structure**：朱砂方形图记 + `ComicAtlas` 字标。
- **States**：default、hover、focus-visible。
- **Accessibility**：完整可读名称；图记仅装饰。

### Navigation

- **Structure**：桌面顶部品牌、主导航、导入动作；移动顶部品牌与导入动作、底部三项阅读导航。
- **States**：default、hover、active、focus-visible。
- **Layout**：桌面 fixed top bar；移动 fixed top + safe-area bottom tabbar。
- **Motion**：只使用颜色、透明度和 `transform`。

### Button / Icon button

- **Variants**：brand、secondary、ghost、danger。
- **States**：default、hover、active、focus-visible、disabled、loading。
- **Accessibility**：最小触控目标 44px；状态不能只依靠颜色。

### Poster card

- **Structure**：2:3 装裱框、状态、进度、两行标题、元数据。
- **States**：default、hover、focus-visible、loading、missing、importing、failed。
- **Motion**：悬浮仅上移 2px，不放大遮挡相邻内容。
- **Accessibility**：整卡可用 Enter/Space 激活；封面缺失显示统一占位，不暴露破图文本。

### Hero feature

- **Structure**：局部受控封面背景、装裱封面、folio 标签、标题、进度与操作。
- **Layout**：桌面 12 栏式横排；移动紧凑堆叠，高度不锁死。
- **States**：有历史、无历史、缺图、focus-visible。

### Filter bar

- **Structure**：搜索与排序为第一层；分类、标签、模式为第二层。
- **Layout**：桌面 sticky；移动第二层为显式横向 reel。
- **States**：default、focus-within、filtered、disabled。

### Ledger row

- **Structure**：48×72 封面、两行标题、章节页码、进度。
- **States**：default、hover、focus-visible、missing。
- **Layout**：由 History 独立负责，不用样式穿透扭曲 Poster card。

### Management shell

- **Structure**：固定顶部栏、216px 侧栏、单一可滚动主内容。
- **States**：导航 default、hover、active、focus-visible。
- **Responsive**：移动端管理功能仍由现有路由守卫拦截。

### Data table

- **Structure**：暗色表面、sticky header、右对齐等宽数字、文字状态。
- **States**：default、hover、selected、loading、empty、error。
- **Accessibility**：状态文字与颜色同时存在；横向溢出由表格容器自身承担。

## 6. Motion & Interaction

| Type | Token | Duration | Easing |
|---|---|---:|---|
| Micro | `--motion-fast` | 120ms | ease-out |
| Standard | `--motion-standard` | 200ms | `cubic-bezier(.22,1,.36,1)` |
| Emphasis | `--motion-emphasis` | 320ms | `cubic-bezier(.22,1,.36,1)` |

- 只动画 `transform`、`opacity`、`filter`；颜色变化可使用短过渡。
- 每个交互元素必须有 hover、active、focus-visible。
- `prefers-reduced-motion: reduce` 时禁用非必要动画和顺滑滚动。
- 禁止无意义脉冲、漂浮和非交互元素的装饰动画。

## 7. Depth & Surface

采用 mixed 策略，但保持哑光：

- 表面主要由 `surface-1/2/3` 色阶分层。
- 装裱封面使用 `--shadow-mount`：暖色内沿 + 下方柔和阴影。
- 弹层使用 `--shadow-overlay`。
- 普通卡片不使用大面积黑色投影；分隔由发丝线完成。

## 8. Accessibility Constraints & Accepted Debt

### Constraints

- 目标 WCAG 2.2 AA：正文对比度至少 4.5:1，大字 3:1。
- 所有交互完整键盘可达，`focus-visible` 清晰。
- 移动输入字号至少 16px；主要触控目标至少 44×44px。
- 状态、进度和错误不可仅由颜色表达。
- 尊重 `prefers-reduced-motion` 与 safe-area。
- 中文、日文和混合括号长标题在 375/768/1280px 均不得裁切基线、掉字或形成单字孤行。

### Accepted Debt

当前没有用户接受的设计债。未完成项必须作为阻塞项处理，不能静默写入本表。
