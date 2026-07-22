# Storage Management Domain v1 — 实施计划

**日期**: 2026-07-22
**关联 Spec**: `docs/superpowers/specs/2026-07-22-storage-management-domain-design.md`
**目标**: 将 StoragePage.vue（905 行单文件）重构为 Store + Service + Composables + Components 分层架构

---

## 前置条件

- 后端 API 和 MQ 链路**零改动**
- 所有引用 API: `adminApi.storageComics/stats/storageChapters/scanRecover/rebuild`, `hqApi.deleteComic/deleteChapter`, `lqApi.generateComic/generateChapter`
- 导入路径约定: API 从 `@/services/management`，类型从 `@/types`

---

## 实施步骤

### Phase 1: 基础设施（顺序执行）

#### 步骤 1: 类型抽取 — `types/index.ts`

将 StoragePage.vue 内联的 `ComicStorageItem`, `ChapterStorageItem`, `StorageStats` 迁移到 `types/index.ts`。

**MUST DO:**
- 追加到 `types/index.ts` 文件末尾（不改现有类型）
- 保留现有的 status 联合类型（`'READY' | 'DELETED' | 'MIXED' | 'EMPTY'` 等，不降级为 `string`）
- `ComicStorageItem.hqStatus`: `'READY' | 'DELETED' | 'MIXED' | 'EMPTY' | 'PENDING' | 'MISSING'`
- `ComicStorageItem.lqStatus`: `'READY' | 'NOT_GENERATED' | 'MIXED' | 'EMPTY' | 'FAILED'`
- `ChapterStorageItem.hqStatus` / `ChapterStorageItem.lqStatus` 同上
- 导出 `ComicStorageQuery` 接口（page, size, hqStatus, lqStatus, sort, order, keyword）
- 导出 `StorageStats` 接口

**EXPECTED RESULT**: `types/index.ts` 文件末尾新增 ~40 行类型定义，编译通过。

---

#### 步骤 2: 创建 `services/storage.ts` — StorageService

创建业务逻辑层，封装 API 调用和错误处理。

**MUST DO:**
- 从 `@/services/management` import `adminApi`, `hqApi`, `lqApi`
- 实现 `fetchComics(params: ComicStorageQuery)` → 调 `adminApi.storageComics()`
- 实现 `fetchSummary()` → 调 `adminApi.stats()`
- 实现 `fetchChapters(comicId: number)` → 调 `adminApi.storageChapters()`
- 实现 `executeOperation(op: StorageOperation)` → switch 路由到 hqApi/lqApi
- 实现 `scanRecover()` → 调 `adminApi.scanRecover()`
- 实现 `rebuild()` → 调 `adminApi.rebuild()`
- 定义 `StorageOperationType` enum (`DeleteHQ`, `GenerateLQ`)
- 定义 `StorageOperation` interface (`type`, `comicId`, `chapterId?`)
- 每个方法用 try/catch 包装，提取 `err.response?.data?.message` 后 re-throw

**MUST NOT DO:**
- 不持有任何状态（纯函数层）
- 不在 Service 层做 ElMessage 提示（留给 Page 层）

**EXPECTED RESULT**: `services/storage.ts` ~80 行，所有方法可被 Store 调用。

---

#### 步骤 3: 创建 `stores/management/storage.ts` — useStorageStore

创建 Pinia store，作为 Storage Domain 唯一数据源。

**MUST DO:**
- 使用 `defineStore('storage', () => { ... })` 组合式 API
- State:
  - `comicList: Ref<ComicStorageItem[]>` — 原始数据
  - `chapters: Ref<Record<number, ChapterStorageItem[]>>` — comicId → 懒加载缓存
  - `summary: Ref<StorageStats | null>`
  - `busyState: Ref<Record<number, boolean>>` — 操作中标记
  - `loading: Ref<boolean>`
- Actions:
  - `loadComics(params?)` — 调 `storageService.fetchComics()` → 写入 comicList
  - `loadSummary()` — 调 `storageService.fetchSummary()` → 写入 summary
  - `loadChapters(comicId)` — 缓存命中直接返回；否则调 `storageService.fetchChapters()` 并缓存
  - `executeOperation(op)` — 调 `storageService.executeOperation(op)`（不管理轮询）
  - `replaceRow(item)` — 用传入数据原地替换 comicList 中对应行
  - `refreshRow(comicId)` — 调 `storageService.fetchComics(keyword)` → `replaceRow`
  - `setBusy(comicId, busy)` — 设置 `busyState[comicId]`
  - `invalidateChapters(comicId)` — 清除章节缓存
- 导入 `storageService` from `@/services/storage`
- 类型从 `@/types` 导入

**MUST NOT DO:**
- store 不 import polling composable
- store 的 `executeOperation` 不调用 polling.start

**EXPECTED RESULT**: `stores/management/storage.ts` ~100 行，编译通过。

---

### Phase 2: 组合式函数 + 工具组件（可并行 4-7）

#### 步骤 4: 创建 `composables/storage/useStorageFilter.ts`

筛选/排序/分页逻辑，接受 `comicList` ref，输出视图态数据。

**MUST DO:**
- 输入: `comicList: Ref<ComicStorageItem[]>`
- 内部状态（不进 Store）: `filter`, `sort`, `page`, `pageSize`
- `filter`: `{ hqStatus: 'ALL' | 'HAS_HQ' | 'NO_HQ', lqStatus: 'ALL' | 'NEEDS_LQ' | 'READY', keyword: string }`
- `sort`: `{ field: 'totalSize' | 'hqSize' | 'lqSize' | 'title', order: 'asc' | 'desc' }`
- 输出: `filteredList`, `pagedList`, `pagination`（computed）
- 客户端筛选: keyword 做 title 模糊匹配；hqStatus/lqStatus 过滤
- 客户端排序: 按 sort.field + sort.order
- 客户端分页: page * pageSize 切片

**MUST NOT DO:**
- 不做 API 调用（API 分页由 Store.loadComics 处理）
- 不放 filter/sort/page 到 Store

**EXPECTED RESULT**: `composables/storage/useStorageFilter.ts` ~60 行。

---

#### 步骤 5: 创建 `composables/storage/useStorageSelection.ts`

批量选择逻辑。

**MUST DO:**
- 输入: `filteredList: Ref<ComicStorageItem[]>`
- `selectedIds: Ref<number[]>`（用 number[]，不用 Set）
- `hasSelection: computed` — length > 0
- `count: computed` — length
- `toggle(id)` — 切换选中
- `selectAll()` — 当前分页全部选中
- `clear()` — 清空

**EXPECTED RESULT**: `composables/storage/useStorageSelection.ts` ~40 行。

---

#### 步骤 6: 创建 `composables/storage/useStoragePolling.ts`

操作后自动轮询，监控单条漫画状态变化。

**MUST DO:**
- 输入: `store` (useStorageStore 实例)
- 内部维护: `activePolls: Map<number, PollState>`（不进 Store）
- `start(comicId, type)`:
  1. 调 `store.setBusy(comicId, true)`
  2. 每 5s: 调 `store.refreshRow(comicId)` 检查状态
  3. 删除: hqStatus 变为 `DELETED` 或 `EMPTY` → 停止
  4. 生成: lqStatus 变为 `READY` → 停止
  5. 超时 60s → 强制停止
  6. 停止时: `store.setBusy(comicId, false)`
- `stop(comicId)` — 取消单个轮询 + 清理 timer
- `stopAll()` — 清理所有（onUnmounted 调用）
- 使用 `setInterval` + `AbortController`（用于 cleanup）

**MUST NOT DO:**
- 不暴露内部 timer/Map 到 Store
- 不在 Polling 内显示 ElMessage（交给 Page）

**EXPECTED RESULT**: `composables/storage/useStoragePolling.ts` ~80 行。

---

#### 步骤 7: 创建 `StorageStatusTag.vue`

HQ/LQ 状态彩色标签，可复用于 Table 和 Drawer。

**MUST DO:**
- Props: `status: string`, `type: 'hq' | 'lq'`
- 从旧 StoragePage.vue 提取 `hqTagType/hqTagText/lqTagType/lqTagText` 逻辑
- 使用 ElTag 组件
- HQ 状态颜色: READY=success, DELETED=info, MIXED=warning, MISSING/PENDING=danger, EMPTY=''
- LQ 状态颜色: READY=success, NOT_GENERATED=warning, MIXED/FAILED=danger, EMPTY=''

**EXPECTED RESULT**: `views/management/storage/StorageStatusTag.vue` ~50 行。

---

### Phase 3: 组件抽取（可大量并行 8-12）

所有组件从旧 StoragePage.vue 中提取对应 template + style 区块，props 和 emits 接口已由 spec 第 7.2 节定义。

#### 步骤 8: 抽取 `StorageSummary.vue`

统计卡片区域（4 个卡片: 总大小/HQ/LQ/缩略图）。

**MUST DO:**
- Props: `stats: StorageStats`
- 复用 `formatSize` 工具函数（从旧 StoragePage 提取到 `utils/format.ts` 或组件内）
- 保留旧 StoragePage.vue 的 `.stat-grid` / `.stat-card` 样式

**EXPECTED RESULT**: `views/management/storage/StorageSummary.vue` ~60 行（template + style）。

---

#### 步骤 9: 抽取 `StorageToolbar.vue`

搜索框 + 筛选下拉 + 排序选择 + 操作按钮。

**MUST DO:**
- Props: `filter`, `sort`
- Emits: `update:filter`, `update:sort`, `scan-recover`, `rebuild`
- 搜索框: el-input v-model:filter.keyword, 防抖 300ms 后触发 update:filter
- HQ 状态: el-select (ALL/HAS_HQ/NO_HQ)
- LQ 状态: el-select (ALL/NEEDS_LQ/READY)
- 排序: el-select (totalSize/hqSize/lqSize/title) + 升降序切换
- 操作按钮组: "扫描并恢复" + "重建元数据" + "清理未引用文件"(disabled)
- 保留旧 StoragePage 的 `.filter-bar` / `.action-section` 样式

**MUST NOT DO:**
- 不直接在组件内调 API（通过 emit 到 Page）

**EXPECTED RESULT**: `views/management/storage/StorageToolbar.vue` ~100 行。

---

#### 步骤 10: 抽取 `StorageBatchBar.vue`

浮动批量操作栏。

**MUST DO:**
- Props: `count: number`
- Emits: `delete-hq`, `generate-lq`
- 选中 N 部时显示，0 部时隐藏（v-if）
- 两个按钮: "批量删 HQ" + "批量生 LQ"
- 保留旧 StoragePage 的 `.batch-bar` 样式

**EXPECTED RESULT**: `views/management/storage/StorageBatchBar.vue` ~50 行。

---

#### 步骤 11: 抽取 `StorageTable.vue`

漫画列表 el-table。

**MUST DO:**
- Props: `list: ComicStorageItem[]`, `busyState: Record<number, boolean>`, `loading: boolean`, `selectedIds: number[]`
- Emits: `toggle-select`, `select-all`, `delete-hq`, `generate-lq`, `show-chapters`
- 列: 选择框 / 封面(64px缩略图) / 标题 / HQ大小 / LQ大小 / HQ状态(StorageStatusTag) / LQ状态(StorageStatusTag) / 操作
- 操作列: "删 HQ"（busyState[id] 时 disabled + spinner）、"生 LQ"（同上）、"详情"
- 保留 `rowClassName` 高亮行逻辑（`highlightedComicId` 由 Page 传入或通过 route query 处理）
- 保留旧 StoragePage 的 table 相关样式

**EXPECTED RESULT**: `views/management/storage/StorageTable.vue` ~120 行。

---

#### 步骤 12: 抽取 `StorageChapterDrawer.vue`

章节 el-drawer。

**MUST DO:**
- Props: `comicId: number | null`, `chapters: ChapterStorageItem[]`, `busyState: Record<number, boolean>`
- Emits: `close`, `delete-hq-chapter(chapterId)`, `generate-lq-chapter(chapterId)`
- 章节列表 el-table: 选择框 / 章节号 / 标题 / 页数 / HQ大小 / LQ大小 / 状态 / 操作
- 抽屉底栏: HQ 合计 / LQ 合计
- Drawer 批量操作: 全选 / 批量删 HQ / 批量生 LQ
- 保留旧 StoragePage 的 `.chapter-drawer` 样式

**EXPECTED RESULT**: `views/management/storage/StorageChapterDrawer.vue` ~200 行。

---

### Phase 4: 重构编排层 + 验证（顺序执行）

#### 步骤 13: 重构 `StoragePage.vue` 为编排层

用新 Store + Composables + Components 替换旧实现。

**MUST DO:**
- 导入: `useStorageStore`, `useStorageFilter`, `useStorageSelection`, `useStoragePolling`
- 导入: 所有新组件 + `StorageService`
- `onMounted`: `store.loadComics()` + `store.loadSummary()`
- `handleDeleteHQ(comicId)`: `await store.executeOperation(...)` → `polling.start(comicId, DeleteHQ)`
- `handleGenerateLQ(comicId)`: `await store.executeOperation(...)` → `polling.start(comicId, GenerateLQ)`
- `handleDeleteHQChapter(chapterId)`: 同理（含 chapterId）
- `handleGenerateLQChapter(chapterId)`: 同理
- `handleShowChapters(comicId)`: `drawerComicId = comicId` → `store.loadChapters(comicId)`
- `handleScanRecover`: `storageService.scanRecover()` → `store.loadComics()` + `store.loadSummary()`
- `handleRebuild`: `storageService.rebuild()` → `store.loadComics()` + `store.loadSummary()`
- `handleBatchDeleteHQ`: `selection.selectedIds.forEach(id => ...)` 独立操作 + 独立轮询
- `handleBatchGenerateLQ`: 同上
- 保留 `route.query.highlight` 高亮逻辑
- `onUnmounted`: `polling.stopAll()`
- 页面 template: 组装 StorageSummary + StorageToolbar + StorageBatchBar + StorageTable + StorageChapterDrawer
- 确认弹窗（ElMessageBox.confirm）保留在 Page 层操作函数中

**MUST NOT DO:**
- 不在 Page 中包含任何数据获取/筛选/排序/选择逻辑（全部在 Store 和 Composables 中）

**EXPECTED RESULT**: `StoragePage.vue` ~80 行 template + ~80 行 script，零业务逻辑。

---

#### 步骤 14: 清理旧文件

**MUST DO:**
- 确认新 `views/management/storage/StoragePage.vue` 功能完整
- 删除旧的 `views/management/StoragePage.vue`
- 确认 `router/index.ts` 中 import 路径指向新文件: `@/views/management/storage/StoragePage.vue`

**EXPECTED RESULT**: 旧文件移除，路由不变。

---

#### 步骤 15: 端到端验证

**MUST DO:**
- `npm run build` 或 `npm run type-check` 确保 TypeScript 编译通过
- 验证页面: 访问 `/manage/storage`，确认统计卡片、表格、筛选、排序、分页正常
- 验证操作: 删 HQ / 生 LQ → 确认 busy 状态 + 轮询刷新
- 验证抽屉: 打开章节详情 → 确认懒加载 + 缓存
- 验证批量: 多选 → 批量删 HQ/生 LQ
- 验证操作按钮: 扫描并恢复 / 重建元数据
- 验证高亮: ComicListPage → Storage 快捷跳转，确认 highlight 高亮
- 验证样式: 与旧 StoragePage 视觉一致

**EXPECTED RESULT**: 所有功能正常，无编译错误，无运行时异常。

---

## 并行执行策略

```
Phase 1: 步骤 1 → 步骤 2 → 步骤 3        (顺序，层层依赖)

Phase 2: 步骤 4 | 步骤 5 | 步骤 6 | 步骤 7  (全部并行，可同时委派 4 个 agent)

Phase 3: 步骤 8 | 9 | 10 | 11 | 12        (全部并行，可同时委派 5 个 agent)

Phase 4: 步骤 13 → 步骤 14 → 步骤 15       (顺序)
```

**最大并行度**: Phase 2 + Phase 3 可在 Phase 1 完成后一次性委派 **9 个 agent** 并行执行。

---

## 风险与回滚

- **编译失败**: 每个步骤完成后运行 `lsp_diagnostics` 检查
- **功能回归**: Phase 4 完成前不删除旧文件，可随时对比新旧行为
- **样式丢失**: 所有组件从旧 StoragePage.vue 提取对应 `<style scoped>` 区块
- **导入路径错误**: 统一使用 `@/` 别名，与项目现有 import 一致
