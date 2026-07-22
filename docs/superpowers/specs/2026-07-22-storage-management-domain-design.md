# Storage Management Domain v1 — 设计文档

**日期**: 2026-07-22
**状态**: 已确认
**分支**: main

---

## 1. 概述

将当前 `StoragePage.vue`（905 行单文件，内联状态 + 类型）重构为独立的 **Storage Management Domain**，遵循关注点分离原则：

- **Store** = 数据事实源（comicList, chapters, summary）
- **StorageService** = 业务逻辑层（参数校验、API 调用、错误转换）
- **Composable** = 视图逻辑（filter, selection, polling）
- **Component** = 展示层（table, drawer, toolbar）
- **Page** = 编排层（组装，零业务逻辑）

后端 API 和 MQ 链路**完全复用**，零改动。所有新增能力在前端实现。

---

## 2. 架构层次

```
StoragePage (编排层，~60 行)
    │
    ├── useStorageStore       (唯一数据源)
    ├── useStorageFilter      (筛选/排序/分页)
    ├── useStorageSelection   (批量选择)
    └── useStoragePolling     (操作后自动轮询)
         │
         ▼
    StorageStore              (数据 + 状态)
         │
         ▼
    StorageService            (业务逻辑 + API 调用)
         │
         ▼
    storageApi                (HTTP 客户端)
         │
         ▼
    Backend API → MQ → Worker (复用，零改动)
```

### 2.1 职责边界

| 层 | 职责 | 禁止 |
|----|------|------|
| Store | 原始数据（comicList, chapters, summary）、busy 标记、行替换 | 不放 filter/sort/page 等视图状态；不知道 Polling 存在 |
| StorageService | 参数校验、API 路由、错误转换。Store 通过它调 API | 不持有状态 |
| Composable | 视图状态（filteredList, pagedList, selectedIds）、轮询逻辑 | 不直接调 API（通过 Store → Service） |
| Component | 纯展示 + 事件 emit | 不直接调 store action（通过 page 编排） |
| Page | 组装各层，协调 store + composable + polling | 零业务逻辑 |

### 2.2 依赖方向（单向）

```
Page → Composable → Store → StorageService → storageApi
              ↑
         Polling (Composable) 知道 Store
         Store 不知道 Polling
```

---

## 3. 文件结构

```
frontend/src/
├── types/
│   └── index.ts                              # 新增: ComicStorageItem, ChapterStorageItem, StorageStats
│
├── services/
│   ├── api.ts                                # 复用已有 storageApi (不变)
│   └── storage.ts                            # StorageService (NEW)
│
├── stores/management/
│   └── storage.ts                            # useStorageStore (NEW)
│
├── composables/storage/
│   ├── useStorageFilter.ts                   # 筛选+排序+分页 (NEW)
│   ├── useStorageSelection.ts                # 批量选择 (NEW)
│   └── useStoragePolling.ts                  # 操作后轮询 (NEW)
│
└── views/management/storage/
    ├── StoragePage.vue                       # 编排层 (REFACTOR)
    ├── StorageSummary.vue                    # 统计卡片 (EXTRACT)
    ├── StorageToolbar.vue                    # 搜索/筛选/排序 (EXTRACT)
    ├── StorageTable.vue                      # 漫画列表表格 (EXTRACT)
    ├── StorageBatchBar.vue                   # 批量操作栏 (EXTRACT)
    ├── StorageChapterDrawer.vue              # 章节抽屉 (EXTRACT)
    └── StorageStatusTag.vue                  # HQ/LQ 状态标签 (NEW)
```

---

## 4. StorageService 设计

```ts
// services/storage.ts
// 业务逻辑层：Store 通过它调用 API，不直接碰 storageApi。
// 统一处理参数校验、API 路由、错误转换。

export enum StorageOperationType {
  DeleteHQ = 'DELETE_HQ',
  GenerateLQ = 'GENERATE_LQ',
}

export interface StorageOperation {
  type: StorageOperationType
  comicId: number
  chapterId?: number    // undefined = 整本漫画
}

export const storageService = {
  /** 获取漫画列表 */
  async fetchComics(params: ComicListQuery) {
    return adminApi.storageComics(params)
  },

  /** 获取统计摘要 */
  async fetchSummary() {
    return adminApi.stats()
  },

  /** 获取章节列表 */
  async fetchChapters(comicId: number) {
    return adminApi.storageChapters(comicId)
  },

  /** 扫描并恢复 */
  async scanRecover() {
    return adminApi.scanRecover()
  },

  /** 重建元数据 */
  async rebuild() {
    return adminApi.rebuild()
  },

  /** 统一操作入口：根据 type + chapterId 路由到对应 API */
  async executeOperation(op: StorageOperation): Promise<void> {
    const { type, comicId, chapterId } = op
    switch (type) {
      case StorageOperationType.DeleteHQ:
        if (chapterId != null) {
          await hqApi.deleteChapter(chapterId)
        } else {
          await hqApi.deleteComic(comicId)
        }
        break
      case StorageOperationType.GenerateLQ:
        if (chapterId != null) {
          await lqApi.generateChapter(chapterId)
        } else {
          await lqApi.generateComic(comicId)
        }
        break
    }
  },
}
```

**优势**：
- 以后新增操作类型（RepairHQ、GenerateThumb 等）只需在 `StorageOperationType` 加枚举值 + switch case，组件零改动。
- 参数校验和错误转换集中在一处，避免 store 和 composable 各自复制逻辑。
- 同 Import、Reader 等模块一致的分层风格。

---

## 5. Store 设计

### 5.1 useStorageStore

```ts
// stores/management/storage.ts
interface StorageState {
  // === 数据（Source of Truth） ===
  comicList: ComicStorageItem[]
  chapters: Record<number, ChapterStorageItem[]>   // comicId → chapters（懒加载缓存）
  summary: StorageStats | null

  // === 操作跟踪 ===
  busyState: Record<number, boolean>   // comicId → 是否正在执行操作

  // === 加载状态 ===
  loading: boolean
}
```

### 5.2 Actions

| Action | 签名 | 说明 |
|--------|------|------|
| `loadComics()` | `async ()` | 调用 `storageService.fetchComics()`，写入 comicList |
| `loadSummary()` | `async ()` | 调用 `storageService.fetchSummary()`，写入 summary |
| `loadChapters(comicId)` | `async (number)` | 带缓存：已有数据直接返回，否则调 `storageService.fetchChapters()` |
| `invalidateChapters(comicId)` | `(number)` | 清除章节缓存（row refresh 时若章节状态变化则调用） |
| `executeOperation(op)` | `async (StorageOperation): Promise<void>` | 调 `storageService.executeOperation(op)`，**不管理轮询** |
| `replaceRow(item)` | `(ComicStorageItem)` | 用传入数据原地替换 comicList 中对应行（避免二次请求） |
| `refreshRow(comicId)` | `async (number)` | 重新拉取单条漫画 → 调用 `replaceRow`（用于手动刷新场景） |
| `setBusy(comicId, busy)` | `(number, boolean)` | 设置/清除 `busyState[comicId]` |

### 5.3 关键设计决策

**busyState 使用 Record 而非 number[]**：
```ts
// ✅ 正确：语义明确，O(1) 查询
busyState: Record<number, boolean>
store.busyState[id]  // true | undefined

// ❌ 避免：number[] + includes() 语义模糊
busyComicIds: number[]
```

**refreshRow 支持传入数据**：
```ts
// Polling 场景：已从 API 拿到新数据，直接覆盖，不二次请求
store.replaceRow(freshItem)

// 手动刷新场景：没有数据，重新请求
await store.refreshRow(comicId)
```

**chapters 懒加载缓存**：
```ts
async loadChapters(comicId: number) {
  if (this.chapters[comicId]) return         // 缓存命中
  this.chapters[comicId] = await storageService.fetchChapters(comicId)
}

// 仅在 refreshRow 发现章节状态变化时清除缓存
invalidateChapters(comicId: number) {
  delete this.chapters[comicId]
}
```

**Store 不知道 Polling 的存在**：`executeOperation()` 只负责调用 API，不触发轮询。轮询由 Page 协调。

---

## 6. Composable 设计

### 6.1 useStorageFilter

```ts
// 输入：store.comicList (原始数据)
// 输出：filteredList, pagedList, pagination
// 内部状态：filter, sort, page（纯视图态，不进 store）

function useStorageFilter(comicList: Ref<ComicStorageItem[]>) {
  const filter = reactive({ hqStatus: '', lqStatus: '', keyword: '' })
  const sort = reactive({ field: 'totalSize', order: 'desc' })
  const page = ref(1)
  const pageSize = ref(20)

  const filteredList = computed(() => { /* filter + sort */ })
  const pagedList = computed(() => { /* slice */ })
  const pagination = computed(() => ({ page, pageSize, total: filteredList.value.length }))

  return { filter, sort, page, pageSize, filteredList, pagedList, pagination }
}
```

### 6.2 useStorageSelection

```ts
// 输入：filteredList
// 输出：selectedIds, hasSelection, toggle, selectAll, clear

function useStorageSelection(filteredList: Ref<ComicStorageItem[]>) {
  const selectedIds = ref<number[]>([])

  const hasSelection = computed(() => selectedIds.value.length > 0)
  const count = computed(() => selectedIds.value.length)

  function toggle(id: number) { /* 切换 */ }
  function selectAll() { /* 全选当前页 */ }
  function clear() { /* 清空 */ }

  return { selectedIds, hasSelection, count, toggle, selectAll, clear }
}
```

### 6.3 useStoragePolling

```ts
// 输入：store（用于 replaceRow + setBusy）
// 内部维护：timer, AbortController（不进 Store）
// Store 不知道 Polling 存在；Page 负责协调 executeOperation + polling.start

function useStoragePolling(store: ReturnType<typeof useStorageStore>) {
  const activePolls = new Map<number, PollState>()

  async function start(comicId: number, type: StorageOperationType) {
    store.setBusy(comicId, true)
    // 每 5s: store.refreshRow(comicId)
    //   或更优: 调 storageService.fetchComics(keyword=comicId) → store.replaceRow(item)
    // 检查目标状态是否达成
    // 达成 → stop → store.setBusy(comicId, false) → store.replaceRow(item)
  }

  function stop(comicId: number) { /* 取消轮询 + 清理 */ }
  function stopAll() { /* onUnmounted */ }

  return { start, stop, stopAll }
}
```

**轮询策略**：轮询 `GET /api/admin/storage/comics`（加 keyword 过滤单条），或 `refreshRow(comicId)`。不拉全量列表。

**停止条件**：
- HQ 删除：hqStatus 变为 `DELETED`
- LQ 生成：lqStatus 变为 `READY`
- 超时：60s 后强制停止

---

## 7. 组件设计

### 7.1 StoragePage（编排层）

```vue
<template>
  <StorageSummary :stats="store.summary" />
  <StorageToolbar
    v-model:filter="filter.filter"
    v-model:sort="filter.sort"
    @scan-recover="handleScanRecover"
    @rebuild="handleRebuild"
  />
  <StorageBatchBar
    v-if="selection.hasSelection"
    :count="selection.count"
    @delete-hq="handleBatchDeleteHQ"
    @generate-lq="handleBatchGenerateLQ"
  />
  <StorageTable
    :list="filter.pagedList"
    :busy-state="store.busyState"
    :loading="store.loading"
    :selected-ids="selection.selectedIds"
    @toggle-select="selection.toggle"
    @select-all="selection.selectAll"
    @delete-hq="handleDeleteHQ"
    @generate-lq="handleGenerateLQ"
    @show-chapters="handleShowChapters"
  />
  <StorageChapterDrawer
    v-if="drawerComicId != null"
    :comic-id="drawerComicId"
    :chapters="store.chapters[drawerComicId]"
    :busy-state="store.busyState"
    @close="drawerComicId = null"
    @delete-hq-chapter="handleDeleteHQChapter"
    @generate-lq-chapter="handleGenerateLQChapter"
  />
</template>

<script setup lang="ts">
import { useStorageStore } from '@/stores/management/storage'
import { useStorageFilter } from '@/composables/storage/useStorageFilter'
import { useStorageSelection } from '@/composables/storage/useStorageSelection'
import { useStoragePolling } from '@/composables/storage/useStoragePolling'

const store = useStorageStore()
const filter = useStorageFilter(() => store.comicList)
const selection = useStorageSelection(() => filter.filteredList)
const polling = useStoragePolling(store)

// Page 作为协调者：executeOperation + polling.start
async function handleDeleteHQ(comicId: number) {
  await store.executeOperation({ type: StorageOperationType.DeleteHQ, comicId })
  polling.start(comicId, StorageOperationType.DeleteHQ)
}

async function handleGenerateLQ(comicId: number) {
  await store.executeOperation({ type: StorageOperationType.GenerateLQ, comicId })
  polling.start(comicId, StorageOperationType.GenerateLQ)
}

async function handleDeleteHQChapter(chapterId: number) {
  const comicId = drawerComicId.value!
  await store.executeOperation({ type: StorageOperationType.DeleteHQ, comicId, chapterId })
  polling.start(comicId, StorageOperationType.DeleteHQ)
}

async function handleShowChapters(comicId: number) {
  drawerComicId.value = comicId
  await store.loadChapters(comicId)  // 懒加载 + 缓存
}

async function handleScanRecover() {
  await storageService.scanRecover()
  store.loadComics()
  store.loadSummary()
}

async function handleRebuild() {
  await storageService.rebuild()
  store.loadComics()
  store.loadSummary()
}

onMounted(() => {
  store.loadComics()
  store.loadSummary()
})

onUnmounted(() => {
  polling.stopAll()
})
</script>
```

### 7.2 各组件职责

| 组件 | Props | Emits | 说明 |
|------|-------|-------|------|
| StorageSummary | stats | — | 4 个统计卡片 |
| StorageToolbar | filter, sort | update:filter, update:sort, scan-recover, rebuild | 搜索框 + 状态下拉 + 排序选择 + 操作按钮（扫描恢复、重建元数据） |
| StorageTable | list, busyState, loading, selectedIds | toggle-select, select-all, delete-hq, generate-lq, show-chapters | el-table + 操作列 |
| StorageBatchBar | count | delete-hq, generate-lq | 浮动批量操作栏 |
| StorageChapterDrawer | comicId, chapters, busyState | close, delete-hq-chapter, generate-lq-chapter | el-drawer + 章节表 |
| StorageStatusTag | status, type | — | HQ/LQ 状态彩色标签（复用） |

**StorageToolbar 操作按钮说明**：现有页面的"扫描并恢复"（`adminApi.scanRecover()`）和"重建元数据"（`adminApi.rebuild()`）按钮保留在 StorageToolbar 中，作为工具栏右侧的操作区。调用路径不变：由 Page 层编排，通过 StorageService → storageApi。'清理未引用文件'按钮当前已禁用，保留但隐藏。

---

## 8. 类型定义

从现有 StoragePage.vue 内联接口抽取到 `types/index.ts`：

```ts
export interface ComicStorageItem {
  comicId: number
  title: string
  coverUrl: string
  totalSize: number
  hqSize: number
  lqSize: number
  hqStatus: string
  lqStatus: string
  chapterCount: number
  pageCount: number
}

export interface ChapterStorageItem {
  chapterId: number
  chapterNo: string
  title: string
  pageCount: number
  hqSize: number
  lqSize: number
  hqStatus: string
  lqStatus: string
}

export interface StorageStats {
  totalBytes: number
  hqBytes: number
  lqBytes: number
  thumbBytes: number
  comicCount: number
}
```

---

## 9. 数据流

### 9.1 页面加载

```
StoragePage.onMounted
  → store.loadComics()          // StorageService.fetchComics() → comicList
  → store.loadSummary()         // StorageService.fetchSummary() → summary
  → composables 响应数据变化   → 自动更新 filteredList/pagedList
```

### 9.2 删除 HQ（单本漫画）

```
用户点击 "删 HQ"
  → page.handleDeleteHQ(comicId)
  → store.executeOperation({ type: DeleteHQ, comicId })   // 仅调 API
     → StorageService.executeOperation() → hqApi.deleteComic()
  → store.setBusy(comicId, true)
  → polling.start(comicId, DeleteHQ)                       // Page 协调，Store 不知情
     │ 每 5s: store.refreshRow(comicId) → StorageService.fetchComics() → store.replaceRow(item)
     │ hqStatus 变为 'DELETED'
     └ → polling.stop(comicId)
        → store.setBusy(comicId, false)
        → store.replaceRow(item)      // 使用轮询返回的数据，避免二次请求
```

### 9.3 批量删除 HQ

```
用户选中 5 本 → 点 "批量删 HQ"
  → page.handleBatchDeleteHQ()
  → forEach id:
       store.executeOperation({ type: DeleteHQ, comicId: id })
       store.setBusy(id, true)
       polling.start(id, DeleteHQ)
  → selection.clear()
  → 每个 ID 独立轮询，完成后各自 replaceRow
```

### 9.4 章节抽屉

```
用户点击 "详情"
  → page.handleShowChapters(comicId)
  → store.loadChapters(comicId)
     → 缓存命中 → 直接返回
     → 缓存未命中 → StorageService.fetchChapters() → chapters[comicId]
  → 关闭抽屉：不清除缓存
  → refreshRow 发现章节状态变化 → store.invalidateChapters(comicId)
```

---

## 10. 不纳入 v1

| 项目 | 原因 |
|------|------|
| StorageProgress（操作队列进度条） | 当前操作量小，不需要进度条。延后到后续版本 |
| 智能存储建议（Top N / 优化建议） | 与产品定位不符——管理服务于阅读，非磁盘分析器 |
| 新 MQ 事件类型 | 现有 HQ/LQ 链路已完整可用 |
| WebSocket 实时推送 | 对个人项目过度设计，5s 轮询已足够 |

---

## 11. 实现顺序

| 步骤 | 内容 | 依赖 | 可并行 |
|------|------|------|--------|
| 1 | 类型抽取：`ComicStorageItem` 等 → `types/index.ts` | 无 | — |
| 2 | 创建 `services/storage.ts`（StorageService） | 步骤 1 | — |
| 3 | 创建 `stores/management/storage.ts` | 步骤 1,2 | — |
| 4 | 创建 `composables/storage/useStorageFilter.ts` | 步骤 1 | 可与 5,6 并行 |
| 5 | 创建 `composables/storage/useStorageSelection.ts` | 无 | 可与 4,6 并行 |
| 6 | 创建 `composables/storage/useStoragePolling.ts` | 步骤 3 | 可与 4,5 并行 |
| 7 | 创建 `StorageStatusTag.vue` | 步骤 1 | 可与 8-12 并行 |
| 8 | 抽取 `StorageSummary.vue` | 步骤 3 | 可与 7,9-12 并行 |
| 9 | 抽取 `StorageToolbar.vue` | 步骤 4 | 可与 7,8,10-12 并行 |
| 10 | 抽取 `StorageBatchBar.vue` | 步骤 5 | 可与 7-9,11,12 并行 |
| 11 | 抽取 `StorageTable.vue` | 步骤 3,4,5,7 | 依赖较多，建议步骤 7-10 完成后 |
| 12 | 抽取 `StorageChapterDrawer.vue` | 步骤 3,6,7 | 可与 11 并行 |
| 13 | 重构 `StoragePage.vue` 为编排层 | 步骤 3-12 | — |
| 14 | 替换旧文件 + 删除旧 StoragePage.vue | 步骤 13 | — |
| 15 | 端到端验证 | 所有步骤 | — |

**并行窗口**：步骤 1→2→3 必须顺序执行（层层依赖），之后步骤 4-12 可以大规模并行。
