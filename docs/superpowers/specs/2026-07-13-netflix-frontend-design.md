# ComicAtlas Frontend Design Spec v1.0

> **Netflix 的视觉风格，Plex 的媒体库思路，Komga 的漫画阅读体验。**

**Date:** 2026-07-13  
**Scope:** 前端视觉重构（v0.1 → Demo）  
**Status:** 待实现

---

## 1. Goals

ComicAtlas 不是视频平台，而是**个人漫画媒体库（Personal Comic Library）**。

本次改版的目标是：

- 借鉴 Netflix 的沉浸式视觉语言（深色背景、大封面、Hero Banner、戏剧化 Hover）。
- 借鉴 Plex 的媒体库展示方式（以封面为核心的 Grid 浏览、信息克制）。
- 借鉴 Komga 的漫画管理体验（目录树、纵向章节列表、阅读入口突出）。
- 在**不改变现有产品逻辑**的前提下，统一前端视觉设计，提升 Demo 的整体质感。

最终效果：用户打开 ComicAtlas 时，第一反应是"这是一个现代的漫画媒体库"，而不是"这是一个管理后台"。

---

## 2. Out of Scope

本次设计**不包含**以下功能，避免 Demo 阶段范围蔓延：

- ❌ 收藏系统
- ❌ 推荐算法
- ❌ 热门榜单
- ❌ 用户体系 / 登录
- ❌ 评论 / 社区
- ❌ 在线资源浏览
- ❌ Reader 功能重构（保持 v1 冻结）
- ❌ Dashboard / Operation / Settings（放到 v0.2 或以后）

这些功能与"个人漫画仓库"定位不符，或在 Demo 阶段收益不高。

---

## 3. Design Principles

### 3.1 Content First（内容优先）

- 封面是第一视觉元素。
- 阅读入口是第一操作。
- 管理功能尽量后置（如 More 菜单）。
- UI 不喧宾夺主。

### 3.2 沉浸优先于效率

- Home / Detail 采用沉浸式 Hero 设计。
- Library 保留 Grid 管理效率，但视觉沉浸化。

### 3.3 个人媒体库，不是流媒体

- 不模仿 Netflix 的"推荐流"和"我的列表"。
- 只展示用户自己的漫画：继续阅读、最近加入、全部漫画。

### 3.4 功能页面够用即可

- Import / Task Center / History 统一深色风格即可，不追求复杂动画或 Netflix 布局。
- Reader v1 冻结，只做颜色统一。

### 3.5 统一组件，减少重复

- 全站封面统一使用 `ComicPoster.vue`。
- Hero 统一使用 `HeroBanner.vue`。
- 通过 Design Token 保证视觉一致。

---

## 4. Information Architecture

### 4.1 最终页面结构

```
Home                 ⭐⭐⭐⭐⭐  新增，沉浸式首页
Library              ⭐⭐⭐⭐⭐  漫画库，Grid 管理
Comic Detail         ⭐⭐⭐⭐⭐  详情，Hero + Catalog
Reader               ⭐⭐⭐⭐⭐  冻结 v1，只改颜色
History              ⭐⭐⭐     阅读历史，统一视觉
Import               ⭐⭐⭐     导入，统一视觉
Task Center          ⭐⭐⭐     任务中心，统一视觉
Dashboard            ⭐         本次不重构，保持现有实现，导航中隐藏
Operation            ⭐         本次不重构，保持现有实现，导航中隐藏
```

### 4.2 导航结构

顶部固定导航：

```
[Logo: ComicAtlas]   Home   Library   History   Tasks   [+ Import]   [Avatar]
```

导航行为：

- 页面顶部时背景透明或半透明。
- 向下滚动后背景变为不透明 `#141414`。
- 当前页面高亮。

---

## 5. Page Design

### 5.1 Home

Home 是 Netflix 视觉的核心页面，结构如下：

```text
Hero（最近阅读）
  - 占首页约 70% 视觉权重，是首页第一焦点
  - 背景：当前阅读漫画封面的高斯模糊 + 暗色渐变遮罩
  - 标题：漫画名
  - 副标题：当前阅读位置（章节号 / 页码进度）
  - 操作按钮：[▶ 继续阅读]（红色主按钮）、[详情]（次按钮）
  - **空状态**：当没有阅读历史时，Hero 显示引导文案"开始探索你的漫画库" + [浏览漫画库] 按钮

继续阅读（横向行）
  - 来源：history-store
  - 最多显示 8 本
  - 超过显示"查看更多 →"，链接到 `/history`
  - **空状态**：该行隐藏，不显示

最近加入（横向行）
  - 来源：comic-store，按 createdAt DESC
  - 最多显示 8 本
  - 超过显示"查看更多 →"，链接到 `/comics?sort=createdAt`
  - **空状态**：该行隐藏，不显示

快速操作
  - 📚 漫画库
  - ⬇ 导入
  - 🕒 阅读历史

媒体库信息（底部弱化）
  - 漫画总数 / 总页数 / 最近导入
  - 极简数字，避免过多统计
```

**交互细节：**

- 横向行默认无左右箭头。
- PC：支持 Shift + 滚轮 / 横向滚轮。
- 移动端：直接滑动。
- Hover 横向行时出现左右箭头。
- Home 不放搜索，搜索属于 Library。

### 5.2 Library

Library 保持管理功能，核心不变：

- 搜索
- 状态筛选
- 排序
- Grid 封面墙
- 分页

**视觉升级：**

- 桌面端封面尺寸：使用 `--poster-width-lg`（默认 240px），中等尺寸使用 `--poster-width-md`（默认 200px）。
- 海报比例：**2:3**。
- 背景：纯 `#141414`，无白色 Card。
- 卡片 hover：`scale(1.04)` + 阴影 + 边框高亮 + 封面变亮。
- 默认信息：**标题 + 页数 / 阅读进度**。
- Hover 操作：最多两个按钮，**继续阅读 / 详情**。
- 搜索工具栏：固定顶部。
- 分页：当前实现保留分页，后续可平滑替换为 Infinite Scroll / Virtual Grid，页面结构无需修改。

**布局：**

```text
Comic Library

Search ____________________

Status ▼    Sort ▼

□ □ □ □ □ □ □ □
□ □ □ □ □ □ □ □
□ □ □ □ □ □ □ □

< 1 2 3 4 5 >
```

### 5.3 Comic Detail

Detail 在现有基础上增强，不推倒重做：

```text
Hero 区
  - 背景：封面模糊 + 渐变
  - 左侧：封面大图
  - 标题 / 作者 / 页数 / 标签
  - 阅读进度：章节 + 页码 + 进度条
  - 操作按钮：[▶ 继续阅读]（主）、[开始阅读]（次）
  - 管理操作：收进 "⋯ More" 菜单（生成 LQ、删除等）

Information 区
  - 来源类型 / 大小 / 导入时间等轻量元信息

Catalog / Chapter 区
  - Catalog Tree 保留
  - 章节保持纵向列表
  - 每章显示：标题 / 页数 / 已读或阅读进度
  - 不使用章节封面
```

### 5.4 Reader

**冻结 v1，不改功能。**

只允许以下视觉调整：

- Toolbar 颜色
- Accent Color（进度条、按钮）
- Loading 颜色

不动：

- Virtual Scroll
- Progressive Image
- Zoom / Fit
- Direction
- Toolbar 交互

### 5.5 Import

简洁深色表单：

```text
Import Comic

Source
○ ZIP    ○ Directory

Path
____________________

[ Import ]
```

- 深色背景
- 输入框舒适
- CTA 明显

### 5.6 Task Center

任务卡片/列表：

```text
██████████████████
Importing
██████░░░░░ 63%

██████████████████
FAILED
Network Error
[ Retry ]
```

统一深色风格，状态标签使用红色/绿色/黄色。

### 5.7 History

阅读历史入口，本质仍是阅读入口：

```text
Continue Reading

██████  Chapter 12  54%
```

避免做成社交 Feed 样式。

---

## 6. Design System

### 6.1 Design Token

在 `style.css` 基础上统一并扩展语义化 Token。迁移策略：新组件统一使用语义化 Token；旧组件逐步从 `--bg` / `--surface` 迁移到 `--bg-primary` / `--bg-surface`，本次重构覆盖到的页面必须完成迁移，未覆盖页面保留旧 Token 作为兼容性别名：

```css
:root {
  /* Background */
  --bg-primary: #141414;
  --bg-secondary: #181818;
  --bg-surface: #232323;
  --bg: var(--bg-primary);           /* 兼容性别名 */
  --surface: var(--bg-surface);      /* 兼容性别名 */
  --surface-elevated: #222222;       /* 兼容性别名 */

  /* Text */
  --text-primary: #ffffff;
  --text-secondary: #b3b3b3;
  --text-muted: #808080;

  /* Brand */
  --accent: #e50914;
  --accent-hover: #f6121d;
  --accent-bg: rgba(229, 9, 20, 0.15);

  /* Hero */
  --hero-gradient: linear-gradient(to top, rgba(20,20,20,1) 0%, rgba(20,20,20,0.6) 60%, rgba(20,20,20,0.2) 100%);

  /* Card */
  --card-radius: 8px;
  --card-shadow: 0 4px 12px rgba(0, 0, 0, 0.5);
  --card-shadow-hover: 0 16px 32px rgba(0, 0, 0, 0.75);
  --card-hover-scale: 1.04;

  /* Transition */
  --transition-fast: 150ms ease;
  --transition-normal: 250ms ease;
  --transition-slow: 400ms ease;

  /* Poster */
  --poster-width-sm: 140px;
  --poster-width-md: 200px;
  --poster-width-lg: 240px;
  --poster-gap: 16px;

  /* Layout */
  --page-width: 1600px;
  --page-padding: 32px;
  --nav-height: 56px;
}
```

### 6.2 Typography

- 标题：加粗、紧凑字距。
- 正文：清晰可读。
- 中文优先使用系统字体 + Noto Sans SC。

### 6.3 Color

- 背景：深灰 `#141414`。
- 强调：Netflix 红 `#e50914`。
- 成功：`#46d369`。
- 警告：`#e87c03`。
- 错误：`#e50914`。

### 6.4 Spacing & Radius

- 圆角：卡片 8px，按钮 4px， pill 9999px。
- 间距：保持现有 4/8/12/16/24/32/48/64 体系。

### 6.5 Responsive Breakpoints

| 断点 | 范围 | Library Grid 列数 | Poster 尺寸 |
|---|---|---|---|
| mobile | ≤640px | 3 列 | `--poster-width-sm` |
| tablet | 641-1024px | 4-5 列 | `--poster-width-md` |
| desktop | >1024px | 6-8 列 | `--poster-width-lg` |

Hero 高度：desktop 最小 70vh，mobile 最小 50vh。

---

## 7. Components

### 7.1 组件目录结构

```
components/
  layout/
    HeroBanner.vue          # 通用 Hero，Home 和 Detail 复用
    TopNav.vue              # 顶部导航，增加滚动变色
  comic/
    ComicPoster.vue         # 全站唯一封面组件
    CatalogTree.vue         # 目录树（现有，视觉统一）
    ChapterRow.vue          # 章节行（现有，视觉统一）
  home/
    HomeHero.vue            # Home 专属 Hero 包装
    HomeRow.vue             # Home 横向内容行
    HomeActionGrid.vue      # 快速操作入口
  reader/
    ReaderToolbar.vue       # 现有，颜色统一
```

### 7.2 HeroBanner

通用 Hero 组件，Props：

```ts
interface HeroBannerProps {
  background: string        // 背景图 URL
  cover: string             // 封面图 URL
  title: string             // 标题
  subtitle?: string         // 副标题
  description?: string      // 描述
  actions: HeroAction[]     // 按钮组
}
```

### 7.3 ComicPoster

全站唯一封面组件，Props：

```ts
interface PosterProps {
  cover: string              // 封面 URL
  title: string              // 标题
  subtitle?: string          // 副标题（作者 / 页数 / 进度）
  progress?: number          // 阅读进度 0-100
  status?: 'ready' | 'importing' | 'pending' | 'failed'
  size: 'sm' | 'md' | 'lg'   // 尺寸
  showProgress?: boolean     // 是否显示进度条
  showSubtitle?: boolean     // 是否显示副标题
  showHover?: boolean        // 是否启用 hover 效果
  showButtons?: boolean      // hover 是否显示按钮
}
```

`ComicPoster` 不依赖业务 VO，任何需要展示海报的地方都可复用。

**状态映射**：业务侧 `ComicListVO.status`（`PENDING/PARSING/IMPORTING/DOWNLOADING/EXTRACTING/SUCCESS/FAILED/CANCELLED`）需映射为 `PosterProps.status`：

```ts
function toPosterStatus(comicStatus: string): PosterProps['status'] {
  switch (comicStatus) {
    case 'SUCCESS': return 'ready'
    case 'PENDING':
    case 'PARSING': return 'pending'
    case 'IMPORTING':
    case 'DOWNLOADING':
    case 'EXTRACTING': return 'importing'
    case 'FAILED':
    case 'CANCELLED': return 'failed'
    default: return 'ready'
  }
}
```

该映射函数放在 `components/comic/poster-status.ts`，由调用方在传入 `ComicPoster` 前使用。

### 7.4 HomeRow

Home 横向内容行，Props：

```ts
interface RowItem {
  id: string | number
  cover: string
  title: string
  subtitle?: string
  progress?: number
  link?: string
}

interface HomeRowProps {
  title: string
  items: RowItem[]
  moreLink?: string         // 查看更多链接
}
```

### 7.5 HomeActionGrid

三个快速入口：漫画库、导入、历史。

---

## 8. Technical Architecture

### 8.1 技术栈

- Vue 3 + `<script setup lang="ts">`
- Vite
- Pinia
- Vue Router
- Element Plus（仅基础控件）
- vue-virtual-scroller（Reader）

### 8.2 主题覆盖

创建 `styles/theme.scss`，Demo 阶段**仅覆盖以下高频组件**：

- Button
- Input
- Select / Dropdown
- Pagination
- Dialog

Table、Form、Tree、Upload、Menu、Scrollbar、Drawer 等在本次 Demo 中不覆盖。OperationLogPage 和 DashboardPage 不在本次重构范围内，保持现有实现。

> 注意：当前项目无 SCSS 预处理器，需先安装 `sass` 作为 devDependency。

### 8.3 动画

统一公共动画文件 `styles/animation.css`：

- 卡片 hover scale + shadow
- 页面淡入
- Hero 背景渐变
- 按钮 hover

### 8.4 数据流

- Home Hero / 继续阅读：来自 `history-store`
- 最近加入：来自 `comic-store`（createdAt DESC）
- 媒体库统计：来自后端 statistics API
- Library：复用现有 `comic-store`
- Detail：复用现有详情数据

### 8.5 路由调整

- 新增 `/home` 路由。
- 默认路由 `/` 重定向到 `/home`。
- 其他路由保持不变。

---

## 9. Development Plan

### 第一阶段：Design System

**目标：** 建立视觉基础，冻结公共组件 API。

**产出：**

- `styles/tokens.css` / `styles/theme.scss` / `styles/animation.css`
- `components/layout/HeroBanner.vue`
- `components/comic/ComicPoster.vue`
- `TopNav.vue` 滚动效果

**验收：**

- Build 通过
- 类型检查通过
- 基础组件在 Story/临时页面可预览

### 第二阶段：Home + Library

**目标：** 完成 Demo 核心视觉页面。

**产出：**

- `pages/HomePage.vue`
- `components/home/HomeHero.vue`
- `components/home/HomeRow.vue`
- `components/home/HomeActionGrid.vue`
- `pages/ComicListPage.vue` 视觉升级
- 复用 `ComicPoster`

**验收：**

- Home 页面可正常展示
- Library Grid 视觉统一
- 无 Element Plus 默认风格

### 第三阶段：Secondary Pages

**目标：** 完成辅助页面视觉统一。

**产出：**

- `pages/ImportPage.vue` 视觉统一
- `pages/TaskCenterPage.vue` 视觉统一
- `pages/HistoryPage.vue` 视觉统一
- Reader 颜色微调

**验收：**

- 辅助页面深色风格统一
- Reader 颜色一致

### 第四阶段：Comic Detail + Theme 统一与 QA

**目标：** 完成详情页，并做全站视觉一致检查。

**产出：**

- `pages/ComicDetailPage.vue` 增强
- Detail Hero 复用 `HeroBanner`
- `theme.scss` 最终调优
- 全局样式检查
- 移动端适配检查

**验收：**

- Detail Hero 复用 `HeroBanner`
- 所有页面无白色/默认 Element Plus 风格
- 阅读入口突出
- 封面展示一致
- Build 和类型检查通过

---

## 10. Subagent Plan

按**模块边界**拆分，减少冲突。

### Subagent 1：Design System（基础视觉）

**职责：**

- Design Token（`tokens.css`、`theme.scss`、`animation.css`）
- `HeroBanner.vue`
- `ComicPoster.vue`
- 公共动画
- 深色主题规范
- Element Plus Theme Override
- `TopNav.vue` 滚动效果

**权限：**

- ✅ 可以创建和修改公共组件
- ❌ 不修改业务逻辑

**产出冻结后：**

- HeroBanner API
- ComicPoster API
- Design Token 命名规范
- theme.scss 结构
- 组件目录结构

### Subagent 2：Home + Library（核心业务页面）

**职责：**

- `pages/HomePage.vue`
- `pages/ComicListPage.vue`
- `components/home/*`
- Home 数据绑定
- Library Grid 视觉升级
- 搜索工具栏固定

**权限：**

- ❌ 只能使用公共组件，不能修改

### Subagent 3：Secondary Pages（辅助页面）

**职责：**

- `pages/ImportPage.vue`
- `pages/TaskCenterPage.vue`
- `pages/HistoryPage.vue`
- Reader 颜色微调

**权限：**

- ❌ 只能使用公共组件，不能修改
- ❌ 不能修改 Reader 功能逻辑
- ⚠️ Reader 颜色调整只允许修改 `ReaderToolbar.vue` 的 `<style>` 块，不允许改动 `<script>` 或 `<template>`

### Subagent 4：Comic Detail（依赖 HeroBanner）

**职责：**

- `pages/ComicDetailPage.vue` 增强
- HeroBanner 在详情页的应用

**权限：**

- ❌ 只能使用公共组件，不能修改

### 协调机制

1. **Subagent 1 先完成并合并。**
2. **Subagent 2 和 Subagent 3 并行开发。**
3. **指定 Architecture Owner**（可由主工程师担任），负责：
   - Review PR
   - 控制组件边界
   - 保证命名一致
   - 保证视觉一致
   - 最终 Build 和类型检查

### 开发顺序

```text
Subagent 1: Design System
    ↓ 冻结公共组件 API
Subagent 2: Home + Library  ─┐
                             ├─ 并行
Subagent 3: Import/Task/History/Reader ─┘
    ↓
Subagent 4: Comic Detail（依赖 HeroBanner）
    ↓
Architecture Owner: QA + Build + 合并
```

---

## 11. Demo Acceptance Criteria

用户首次进入系统的完整流程应满足：

```text
Home
 ↓
Library
 ↓
Comic Detail
 ↓
Reader
 ↓
History
```

### 11.1 视觉统一（in-scope 页面）

- [ ] Home / Library / Detail / Import / Task / History 页面背景为深色 `#141414`，无白色后台页面。
- [ ] in-scope 页面无未覆盖的 Element Plus 默认风格组件（Button / Input / Select / Pagination 已覆盖）。
- [ ] in-scope 页面中的 Grid/Row 封面统一使用 `ComicPoster`；Hero 封面统一使用 `HeroBanner`。

### 11.2 阅读入口突出

- [ ] 有阅读历史时，Home Hero 主按钮文字为"继续阅读"；无历史时为主按钮"浏览漫画库"。
- [ ] Comic Detail Hero 中 [▶ 继续阅读] 比 [开始阅读] 视觉更突出（主按钮 vs 次按钮）。
- [ ] Library 卡片 Hover 显示"继续阅读"和"详情"两个入口。

### 11.3 交互可用（Playwright / agent-executable）

- [ ] TopNav：滚动前背景透明或半透明；滚动 80px 后背景为 `rgb(20, 20, 20)`。
- [ ] Home 横向行：按住 Shift + 滚轮时 `scrollLeft` 增加；Hover 行时出现左右箭头。
- [ ] Library 搜索/筛选工具栏固定顶部（滚动页面时工具栏仍在视口顶部）。
- [ ] Detail 目录树可正常展开/折叠（点击目录节点切换子章节显示）。
- [ ] ComicPoster Hover：`transform` 包含 `scale(1.04)`，阴影为 `--card-shadow-hover`。

### 11.4 构建质量

- [ ] `npm run build` 成功。
- [ ] `vue-tsc --noEmit` 类型检查通过。
- [ ] Playwright 遍历 Home → Library → Detail → Reader → History → Import → Task Center，无 `console.error` 和 `pageerror`。

### 11.5 产品边界

- [ ] 未引入推荐、收藏、榜单、评论、用户体系等功能。
- [ ] Reader 未新增复杂功能；`ReaderToolbar.vue` diff 仅包含 `<style>` 块改动。
- [ ] Settings / Dashboard / Operation 未在 Demo 中重构。

---

## Appendix: Reference

- Netflix: https://www.netflix.com
- Plex: https://www.plex.tv
- Komga: https://komga.org
