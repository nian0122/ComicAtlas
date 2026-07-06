# 07 — 组件设计

> 定义可复用组件的 Props、Events 和职责。不涉及 API，只描述接口契约。

---

## 组件清单

| 组件 | 所属页面 | 复用 |
|------|----------|------|
| ComicCard | Library, History | ✅ |
| SearchBar | Library（全局） | ✅ |
| CatalogTree | Comic Detail | ❌ |
| ReaderToolbar | Reader | ❌ |
| ImageViewer | Reader | ❌ |
| TaskCard | Task Center | ❌ |
| PageHeader | 多个页面 | ✅ |
| EmptyState | 多个页面 | ✅ |

---

## ComicCard

漫画卡片，用于 Library 列表和 History 列表。

**Props**：
```typescript
{
  comic: { id, title, author, coverUrl, pageCount, progressPercent, lastReadPage, status }
}
```

**Events**：
- `@click` → 跳转 Comic Detail

**职责**：
- 渲染封面图（3:4 比例）
- 显示标题、作者
- 显示阅读进度（百分比 + 进度条）

---

## SearchBar

搜索 + 筛选 + 排序组件。

**Props**：
```typescript
{
  modelValue: string       // 搜索关键词
  tags: { name: string }[] // 可选标签列表
  sort: string             // 当前排序
}
```

**Events**：
- `@update:modelValue` → 搜索文本变化
- `@search` → 触发搜索（防抖后或 Enter）
- `@tag-change` → 标签筛选变化
- `@sort-change` → 排序变化

**职责**：
- 输入框（即时搜索 300ms 防抖）
- 标签下拉筛选
- 排序下拉

---

## CatalogTree

递归目录树组件。

**Props**：
```typescript
{
  node: CatalogNode  // { id, title, children, chapters }
}
```

**Events**：
- `@select` → 回传 chapterId

**职责**：
- 递归渲染 `CatalogNode` 树
- 目录节点可折叠/展开
- 章节节点可点击跳转

---

## ReaderToolbar

阅读器顶部工具栏。

**Props**：
```typescript
{
  comicTitle: string
  currentPage: number
  totalPages: number
  prevChapterId: number | null
  nextChapterId: number | null
}
```

**Events**：
- `@back` → 返回 Comic Detail
- `@prev-chapter` → 上一章
- `@next-chapter` → 下一章
- `@settings` → 打开设置

**职责**：
- 返回按钮
- 漫画标题
- 当前页 / 总页数
- 上一章 / 下一章按钮
- 设置按钮

**行为**：
- 滚动时自动隐藏（延迟 1s 后淡出）
- 鼠标移动时重新显示

---

## ImageViewer

图片展示组件。

**Props**：
```typescript
{
  pages: PageInfo[]        // 所有页面
  currentPage: number      // 当前页码
  hqMode: boolean          // HQ/LQ 切换
}
```

**Events**：
- `@page-change` → 翻页

**职责**：
- 渲染当前页图片
- 加载态显示骨架屏（保持 aspect-ratio）
- 错误态显示"加载失败"
- 点击左右区域翻页
- 支持键盘快捷键

---

## TaskCard

任务卡片，用于 Task Center。

**Props**：
```typescript
{
  task: ImportTaskVO       // { id, comicId, status, progress, errorMessage, retryCount, durationMs }
}
```

**Events**：
- `@cancel` → 取消任务
- `@retry` → 重试任务

**职责**：
- 左侧彩色边框（状态色）
- 状态标签
- 进度条
- 错误信息（失败时）
- 操作按钮（取消/重试）

---

## PageHeader

页面标题栏。

**Props**：
```typescript
{
  title: string
}
```

**Slots**：
- `actions` → 右侧操作区（按钮等）

---

## EmptyState

空状态引导。

**Props**：
```typescript
{
  description: string      // "还没有漫画"
  actionLabel?: string     // "去导入"
  actionRoute?: string     // "/import"
}
```

**职责**：
- 居中显示引导文字
- 可选的操作按钮
