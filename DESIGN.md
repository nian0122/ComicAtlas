# ComicAtlas Design System

> 受 Netflix 视觉语言启发的个人漫画仓库设计系统。

---

## 1. Visual Theme & Atmosphere

ComicAtlas 采用 Netflix 式的深色沉浸式影院风格。背景沉入接近纯黑的 `#141414`，让漫画封面成为绝对主角。UI 极度克制，几乎不用装饰元素；所有交互信号由标志性的 Netflix 红（`#E50914`）承担。

**Key Characteristics:**
- 深色影院背景（`#141414`）
- 单一品牌红（`#E50914`）作为 CTA、Logo、激活态
- 海报式封面，纵向 2:3 比例
- 横向内容行（row），可左右滚动
- 卡片悬停放大并透出更多信息
- 顶部导航在页面向下滚动时背景加深
- 字体干净、现代，以无衬线为主

---

## 2. Color Palette & Roles

### Primary Brand
- **Background** (`--bg`): `#141414` — 页面背景
- **Surface** (`--surface`): `#181818` — 卡片、面板
- **Surface Elevated** (`--surface-elevated`): `#222222` — 悬停、交互表面
- **Brand Red** (`--accent`): `#E50914` — CTA、Logo、激活态
- **Brand Red Hover** (`--accent-hover`): `#f40612` — 悬停态

### Text
- **Text Primary** (`--text-h`): `#ffffff` — 标题、重要文字
- **Text Secondary** (`--text`): `#b3b3b3` — 正文、辅助文字
- **Text Muted** (`--text-muted`): `#808080` — 占位、禁用

### Semantic
- **Success** (`--success`): `#46d369`
- **Warning** (`--warning`): `#e87c03`
- **Danger** (`--danger`): `#E50914`

### Surface & Border
- **Border** (`--border`): `#2a2a2a`
- **Border Strong** (`--border-strong`): `#404040`

### Shadows
- **Card Hover**: `rgba(0,0,0,0.75) 0 16px 32px`
- **Dialog**: `rgba(0,0,0,0.8) 0 16px 40px`

---

## 3. Typography Rules

### Font Families
- **UI / Body**: `system-ui, -apple-system, BlinkMacSystemFont, "Helvetica Neue", "Noto Sans SC", sans-serif`
- **Mono**: `"JetBrains Mono", monospace`

### Hierarchy

| Role | Size | Weight | Line Height | Letter Spacing | Use |
|------|------|--------|-------------|----------------|-----|
| Hero Title | 48px | 700 | 1.1 | -0.02em | 首页大标题 |
| Section Title | 20px | 700 | 1.2 | 0 | 区块标题 |
| Card Title | 14px | 600 | 1.3 | 0 | 卡片标题 |
| Body | 14px | 400 | 1.5 | 0 | 默认正文 |
| Caption | 12px | 400 | 1.4 | 0 | 辅助文字 |
| Nav Link | 14px | 400/600 | 1.0 | 0 | 导航 |
| Button | 14px | 600 | 1.0 | 0 | 按钮 |

---

## 4. Component Stylings

### Buttons

**Primary Red Button**
- Background: `--accent`
- Text: `#ffffff`
- Padding: 8px 20px
- Radius: 4px
- Hover: `--accent-hover`

**Secondary Button**
- Background: `rgba(255,255,255,0.2)`
- Text: `#ffffff`
- Padding: 8px 20px
- Radius: 4px
- Hover: `rgba(255,255,255,0.3)`

### Cards (Poster)
- Aspect ratio: 2:3
- Radius: 4px
- No border
- Hover: scale(1.05) + shadow + 信息浮层

### Inputs
- Background: `rgba(255,255,255,0.1)`
- Text: `#ffffff`
- Border: 1px solid `--border-strong`
- Radius: 4px
- Focus: border 变为 `--accent`

### Navigation
- 透明背景，滚动后变为 `--surface`
- Logo 红色
- 链接白色，悬停灰色
- 左侧 Logo + 主导航，右侧搜索/头像

---

## 5. Layout Principles

### Spacing
- Base: 4px
- Scale: 4, 8, 12, 16, 24, 32, 48, 64

### Grid
- 最大宽度：100%（全宽沉浸式）
- 封面网格：2:3 海报，间距 8px
- 横向行：overflow-x auto，间距 16px

### Whitespace
- 大面积深色留白
- 标题紧贴内容行左侧
- 封面之间紧密排列，营造内容墙感

---

## 6. Depth & Elevation

| Level | Treatment | Use |
|-------|-----------|-----|
| Base | `#141414` | 页面背景 |
| Surface | `#181818` | 卡片、面板 |
| Elevated | `#222222` + 重阴影 | 悬停卡片、弹窗 |

---

## 7. Do's and Don'ts

### Do
- 使用 `#141414` 作为背景
- 用红色 `#E50914` 作为唯一品牌强调色
- 封面比例 2:3
- 卡片悬停放大
- 导航滚动后加深背景

### Don't
- 不要添加第二种品牌色
- 不要给海报加粗边框
- 不要使用大面积渐变色
- 不要让 UI 装饰抢封面风头

---

## 8. Responsive

| Width | Columns | Notes |
|-------|---------|-------|
| <640px | 2 | 单列标题行 |
| 640–1024px | 4 | 2 行标题 |
| >1024px | 6+ | 横向滚动行 |

---

## 9. Quick Reference

- Background: `#141414`
- Surface: `#181818`
- Text: `#ffffff`
- Secondary text: `#b3b3b3`
- Accent: `#E50914`
- Border: `#2a2a2a`
