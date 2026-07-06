# 09 — 开发计划

> 按依赖顺序排列开发任务。每步完成可独立验证。

---

## 阶段 A：基础框架（Layout）

**目标**：建立全局导航骨架。

- [ ] 创建 `components/layout/TopNav.vue`
- [ ] 创建 `components/common/PageHeader.vue`
- [ ] 创建 `components/common/EmptyState.vue`
- [ ] 更新 `App.vue` → AppLayout（TopNav + `<router-view>`）
- [ ] 验证：所有页面共享 TopNav，路由切换正常

---

## 阶段 B：Library 优化

**目标**：漫画列表从"能用"到"好用"。

- [ ] 提取 `components/comic/ComicCard.vue`（当前内联在 ComicListPage）
- [ ] 提取 `components/comic/SearchBar.vue`（搜索 + 标签 + 排序）
- [ ] 封面加载骨架屏（aspect-ratio 3:4 占位）
- [ ] 空状态引导（"还没有漫画，去导入"）
- [ ] 验证：搜索即时响应、卡片 Hover 效果、空状态显示

---

## 阶段 C：Comic Detail 优化

**目标**：目录树 + 操作体验。

- [ ] 提取 `components/comic/CatalogTree.vue`（当前内联在 ComicDetailPage）
- [ ] 目录节点折叠/展开动画（150ms）
- [ ] LQ 生成按钮 loading 状态
- [ ] 删除二次确认优化
- [ ] 验证：目录展开/折叠、生成 LQ Toast 反馈

---

## 阶段 D：Reader 体验

**目标**：阅读器成为核心体验。

- [ ] 提取 `components/reader/ReaderToolbar.vue`（当前内联）
- [ ] 提取 `components/reader/ImageViewer.vue`（当前内联）
- [ ] 工具栏自动隐藏（滚动后 1s 淡出）
- [ ] 键盘快捷键（← → Space ESC）
- [ ] 点击翻页（左 1/3 上一页，右 2/3 下一页）
- [ ] 章节结束提示（"下一章"按钮高亮）
- [ ] 验证：全键盘操作完成一章阅读

---

## 阶段 E：Task Center + Import

**目标**：任务监控体验。

- [ ] 提取 `components/task/TaskCard.vue`
- [ ] 进行中任务卡片动画（进度条平滑过渡）
- [ ] 自动轮询在有任务时保持，无任务时停止
- [ ] Import 页面增加"前往任务中心"跳转
- [ ] 验证：创建导入 → 跳转 Task Center → 看到进度 → 完成

---

## 阶段 F：Responsive + Polish

**目标**：移动端可用。

- [ ] TopNav 移动端折叠为汉堡菜单
- [ ] Library 卡片网格响应式（xs:2 / sm:3 / md:4 / lg:6 列）
- [ ] Reader 移动端触摸翻页（swipe left/right）
- [ ] Comic Detail 移动端目录折叠
- [ ] 验证：手机浏览器完成阅读全程

---

## 阶段 G：体验细节

**目标**：打磨。

- [ ] 图片预加载（Reader 当前页 ± 2 页）
- [ ] 深色/浅色主题切换（当前仅深色）
- [ ] 全局搜索（TopNav 中的搜索框）
- [ ] 浏览器标题动态更新（当前页标题）
