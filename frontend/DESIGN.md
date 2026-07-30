# ComicAtlas Design System

## 1. Atmosphere & Identity

ComicAtlas 是一座“私人放映馆”：打开应用先看到作品，而不是看到工具。阅读端采用内容先行的黑色影院，封面与漫画页是主要色彩来源；品牌层只保留克制的朱砂红，用于播放、续读、当前状态和关键进度。PC 管理端像放映馆后台，延续同一色彩与字体，但通过更紧凑的导航、清晰的表格层级和稳定的滚动职责表达“控制台”属性。

双端边界是产品规则，而不只是响应式样式：

- 移动端只提供首页、漫画库、历史、详情和阅读器，不展示导入、管理或存储入口。
- PC 端同时提供阅读与漫画仓库管理，阅读导航与管理工作台使用不同壳层。
- 768px 作为布局分界；设备能力守卫继续负责阻止粗指针移动设备访问 `/manage/*`。

### Stitch 移动端参考契约

移动端阅读端以 `stitch_user_experience_enhancement` 的四个交付页面为像素级参考：

- `comicatlas_apple_tv_infusion_4/code.html`：首页，4:5 电影海报 Hero、续读与最近更新横向片单。
- `comicatlas_apple_tv_infusion_3/screen.png`：漫画库，双列 2:3 封面网格、顶部搜索、横向筛选胶囊。
- `comicatlas_apple_tv_infusion_1/screen.png`：阅读历史，16:9 横向缩略图与账本式阅读信息。
- `comicatlas_apple_tv_infusion_2/screen.png`：漫画详情，封面舞台、红色标题、元数据、续读动作、进度与简介。

参考稿中首页的 `screen.png` 无有效图像数据，因此该页以同目录 `code.html` 为精确结构依据；内容图片、漫画名称与业务数据继续来自 ComicAtlas API，不把参考稿的示例内容硬编码进产品。

## 2. Color

### Palette

| Role | Token | Value | Usage |
|---|---|---:|---|
| Canvas | `--color-canvas` | `#080808` | 阅读端最深背景 |
| Surface 1 | `--color-surface-1` | `#111111` | 导航、管理侧栏 |
| Surface 2 | `--color-surface-2` | `#181818` | 卡片、输入框 |
| Surface 3 | `--color-surface-3` | `#242424` | 悬浮、选中表面 |
| Text 1 | `--color-text-1` | `#f5f5f1` | 标题、正文 |
| Text 2 | `--color-text-2` | `#c6c6c2` | 次级信息 |
| Text 3 | `--color-text-3` | `#8c8c88` | 辅助、禁用 |
| Line subtle | `--color-line-subtle` | `#292929` | 分隔线 |
| Line strong | `--color-line-strong` | `#4a4a4a` | 控件边界 |
| Brand | `--color-brand` | `#e50914` | 继续阅读、选中、进度 |
| Brand pale | `--color-brand-pale` | `#ffb3b6` | 漫画库移动导航选中 |
| Brand hover | `--color-brand-hover` | `#f6121d` | 品牌交互悬浮 |
| Focus | `--color-focus` | `#ffffff` | 键盘焦点 |
| Success | `--color-success` | `#66c58b` | 成功 |
| Warning | `--color-warning` | `#d8a54f` | 警告、处理中 |
| Danger | `--color-danger` | `#f06b70` | 失败、危险操作 |
| Info | `--color-info` | `#70a6d8` | 信息状态 |

透明叠层与环境效果也必须由 token 提供：`--color-overlay-soft`、`--color-overlay-hover`、`--color-overlay-scrim`、`--color-progress-track`、`--color-border-faint`、`--nav-gradient`、`--nav-solid`、`--nav-shadow`、`--brand-shadow`、`--title-shadow`、`--hero-mobile-gradient`、`--page-atmosphere`。组件不得自行创建新的 `rgb()` 叠层。

### Rules

- 朱砂红只表示主要动作、当前选中和阅读进度；危险操作必须使用独立的 `--color-danger`。
- 阅读端表面层级主要依靠中性黑色阶、封面光晕与方向性渐变，不使用玻璃拟态。
- 封面可以为 Hero 局部提供环境色，但不得改变导航、按钮或管理端 chrome 的品牌色。
- 新颜色必须先加入本表，不在组件中写原始色值。

## 3. Typography

| Level | Token | Size | Weight | Line Height | Usage |
|---|---|---:|---:|---:|---|
| Hero | `--text-hero` | `clamp(2rem, 4.6vw, 4rem)` | 800 | 1.02 | 首页主标题 |
| Page | `--text-page` | `clamp(1.75rem, 3vw, 2.5rem)` | 700 | 1.15 | 页面标题 |
| Section | `--text-section` | `clamp(1.375rem, 2vw, 1.875rem)` | 700 | 1.2 | 区块标题 |
| Large | `--text-lg` | `1.125rem` | 500 | 1.5 | 引导文案 |
| Body | `--text-md` | `1rem` | 400 | 1.6 | 正文、输入 |
| Small | `--text-sm` | `0.875rem` | 400–600 | 1.5 | 控件、元数据 |
| Caption | `--text-xs` | `0.75rem` | 500 | 1.5 | 辅助信息 |

- UI 与标题优先使用 `Plus Jakarta Sans`，中文回退到 `Source Han Sans VF` / `Noto Sans CJK SC` / `Microsoft YaHei` / 系统无衬线，以粗细对比代替衬线/无衬线切换。
- Hero 与作品标题使用 700–800；导航与按钮使用 600–700；正文使用 400。
- 数字使用 UI 字体与 `font-variant-numeric: tabular-nums`，不增加第三套字体。
- 卡片标题固定两行；Hero 桌面最多两行、移动最多三行。
- CJK 使用 `line-break: strict; word-break: normal; overflow-wrap: anywhere`，避免单字孤行。

## 4. Spacing & Layout

- 基础单位：4px。
- 间距：`--space-1` 4px、`--space-2` 8px、`--space-3` 12px、`--space-4` 16px、`--space-5` 20px、`--space-6` 24px、`--space-8` 32px、`--space-10` 40px、`--space-12` 48px、`--space-16` 64px。
- 最大内容宽度：`--content-max: 1440px`。
- 页面边距：`--content-gutter: clamp(16px, 3vw, 40px)`。
- 桌面顶栏：68px；移动顶栏与底部导航：64px；管理侧栏：232px。
- 移动页面横向沟槽固定为 16px；首页和详情的影像舞台可突破沟槽到屏幕边缘。
- 阅读端由文档滚动；管理端由主内容区独占纵向滚动。
- 375px 下主内容必须单轴纵向阅读，不允许主区域出现水平溢出；横向 reel 和数据表必须显式声明自己的滚动职责。

## 5. Components

### Brand mark

- **Structure**：红色 `CA` 图记 + `COMICATLAS` 紧凑字标。
- **States**：default、hover、focus-visible。
- **Accessibility**：完整可读名称；图记仅装饰。

### Navigation

- **Structure**：桌面顶部品牌、阅读导航、管理入口与导入动作；移动顶部根据路由切换首页品牌头像、漫画库菜单搜索、历史品牌、详情返回分享，底部保留首页、漫画库、历史三项阅读导航。
- **States**：default、hover、active、focus-visible。
- **Layout**：桌面 fixed top bar；移动 fixed top + 贴合屏幕底边的全宽 safe-area tabbar，不使用悬浮胶囊。
- **Motion**：只使用颜色、透明度和 `transform`。

### Button / Icon button

- **Variants**：brand、secondary、ghost、danger。
- **States**：default、hover、active、focus-visible、disabled、loading。
- **Accessibility**：最小触控目标 44px；状态不能只依靠颜色。

### Poster card

- **Structure**：2:3 封面、状态、底部进度、两行标题、元数据；封面本身承担主要色彩。
- **States**：default、hover、focus-visible、loading、missing、importing、failed。
- **Motion**：桌面悬浮上移 4px并轻微放大到 1.025；移动端关闭悬浮遮罩，整卡直接进入阅读或详情。
- **Accessibility**：整卡可用 Enter/Space 激活；封面缺失显示统一占位，不暴露破图文本。

### Hero feature

- **Structure**：全宽封面背景、电影式多向渐变、封面、标题、进度与播放式主操作。
- **Layout**：桌面横排并允许背景溢出内容沟槽；移动端转为竖向焦点卡，文案固定在封面下缘，不锁死视口高度。
- **States**：有历史、无历史、缺图、focus-visible。

### Filter bar

- **Structure**：搜索与排序为第一层；分类、标签、模式为第二层。
- **Layout**：桌面 sticky；移动第二层为显式横向 reel。
- **States**：default、focus-within、filtered、disabled。

### Ledger row

- **Structure**：移动端使用 16:9 横向缩略图、两行标题、章节页码、进度与播放入口；桌面保持紧凑账本行。
- **States**：default、hover、focus-visible、missing。
- **Layout**：由 History 独立负责，不用样式穿透扭曲 Poster card。

### Management shell

- **Structure**：固定顶部栏、232px 侧栏、单一可滚动主内容；顶部明确标注“仓库控制台”，并提供返回阅读端的切换动作。
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

采用影院式 mixed 策略：

- 表面主要由 `surface-1/2/3` 色阶分层，普通内容卡不绘制可见边框。
- 封面使用 `--shadow-mount`：深色投影 + 极细亮边，悬浮时提高亮度与高度。
- 弹层使用 `--shadow-overlay`。
- Hero 使用多方向暗场渐变保证文字对比，并让背景封面从右侧或上方显色。

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
