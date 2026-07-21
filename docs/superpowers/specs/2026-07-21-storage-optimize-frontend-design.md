# 前端存储优化入口设计 — HQ 删除与 LQ 生成

**日期**: 2026-07-21
**状态**: 草案
**范围**: 前端 + 后端 API

---

## 1. 背景与目标

HQ 删除（释放空间）和 LQ 生成（压缩画质）的前端入口已完成后端 API 和 MQ 链路，但前端入口分散、体验不足：

- **HQ 删除**：`StoragePage` 上只有一个手动输入漫画 ID 的输入框，需要管理员记住 ID，易出错
- **LQ 生成**：完全没有前端入口，`lqApi.generateComic` / `generateChapter` 闲置
- **章节级操作**：后端 API 已支持按章节删除 HQ / 生成 LQ，但前端未暴露
- **决策数据缺失**：管理员看不到每本漫画的 HQ/LQ 占用、状态分布，无法判断"删哪本最划算"

**核心目标**:
- 在 `StoragePage` 建立统一的存储优化工作台：筛选 → 选择 → 批量操作
- 支持整本漫画级和章节级的 HQ 删除 / LQ 生成
- 提供按存储状态筛选、按大小排序等决策辅助
- `ComicListPage` 只保留最小关联入口，不污染漫画管理主流程
- **纯手动触发**：不自动执行，不提示，管理员主动决策

**非目标**:
- 不在阅读端（`DetailPage` / `ReaderPage`）暴露存储优化入口
- 不自动提示"是否生成 LQ"或"是否删除 HQ"
- 不支持 HQ 恢复（删除即永久）
- 不涉及导入时"不保留 HQ"选项

---

## 2. 约束条件

| 约束 | 来源 | 影响 |
|------|------|------|
| 管理后台操作 | 用户要求 | 所有入口在 `/manage/*` 下，不在阅读端 |
| Worker 不直连 MySQL | AGENTS.md | 删除/生成走 MQ 异步，前端需展示"任务已提交"状态 |
| 纯手动触发 | 用户要求 | 无自动提示、无自动执行 |
| 已有 API 复用 | 现有代码 | `hqApi.deleteComic` / `lqApi.generateComic` 等已存在 |
| ComicListPage 职责单一 | 自审结论 | 不在 ComicListPage 加存储操作列，避免拥挤 |

---

## 3. 架构设计

### 3.1 两级入口体系

```
┌─────────────────────────────────────────────────────────────┐
│ 第一级：StoragePage（全局工作台）                              │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ [总大小] [HQ占用] [LQ占用] [缩略图]                        │ │  ← 现有统计卡片
│ ├─────────────────────────────────────────────────────────┤ │
│ │ 操作: [扫描并恢复] [重建元数据] [清理未引用文件]            │ │  ← 现有操作区
│ ├─────────────────────────────────────────────────────────┤ │
│ │ 存储优化                                                   │ │
│ │ 筛选: [HQ状态▼] [LQ状态▼] [大小排序▼] [搜索标题...]         │ │
│ │ ┌─────────────────────────────────────────────────────┐ │ │
│ │ │ □ 封面  标题      HQ大小  LQ大小  HQ状态  LQ状态  操作 │ │ │
│ │ │ □ [图] 海贼王      1.2GB   200MB  就绪    就绪   [详情]│ │ │
│ │ │ □ [图] 火影忍者     800MB   150MB  就绪    未生成 [详情]│ │ │
│ │ │                                                     │ │ │
│ │ │ 批量操作: [删除选中HQ] [生成选中LQ]                    │ │ │
│ │ └─────────────────────────────────────────────────────┘ │ │
│ └─────────────────────────────────────────────────────────┘ │
│   ↑ 点击"详情" → 右侧抽屉展开该漫画章节级详情                  │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ 第二级：ComicListPage（最小关联）                              │
│ 每行漫画操作列增加一个存储图标按钮，点击跳转到 StoragePage       │
│ 并自动高亮并滚动到对应漫画行（仅作为快捷入口，不直接操作）     │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 抽屉详情（章节级操作）

点击 StoragePage 表格行的"详情"按钮 → 右侧滑出 `el-drawer`：

```
┌──────────────────────────────┐
│ 海贼王 — 存储详情      [X]   │
├──────────────────────────────┤
│ 总大小: 1.2GB               │
│ HQ: 1.0GB | LQ: 200MB       │
├──────────────────────────────┤
│ 章节列表                      │
│ ┌──────────────────────────┐ │
│ │ 第1话   50MB   10MB  就绪 就绪 │ [删HQ][生LQ] │
│ │ 第2话   80MB   15MB  就绪 未生成│ [删HQ][生LQ] │
│ │ 第3话   70MB   12MB  已删 就绪 │ [生LQ]       │
│ └──────────────────────────┘ │
│ 全选 □  [删除选中HQ] [生成选中LQ]│
└──────────────────────────────┘
```

---

## 4. 后端 API 需求

### 4.1 漫画存储列表（分页）

```http
GET /api/admin/storage/comics?page=1&size=20&hqStatus=READY&lqStatus=NOT_GENERATED&sort=hqSize&order=desc
```

**Query 参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 1 |
| size | int | 否 | 每页条数，默认 20 |
| hqStatus | string | 否 | 筛选 HQ 状态：`ALL` / `HAS_HQ` / `NO_HQ`<br>`HAS_HQ` = 返回 `READY` 或 `MIXED`（还有 HQ 可删）<br>`NO_HQ` = 返回 `DELETED`（全部已删） |
| lqStatus | string | 否 | 筛选 LQ 状态：`ALL` / `NEEDS_LQ` / `READY`<br>`NEEDS_LQ` = 返回 `NOT_GENERATED` 或 `MIXED`（还需要生成）<br>`READY` = 返回 `READY`（全部就绪） |
| sort | string | 否 | 排序字段：`totalSize` / `hqSize` / `lqSize` / `title` |
| order | string | 否 | 排序方向：`asc` / `desc` |
| keyword | string | 否 | 标题模糊搜索 |

**Response**:
```json
{
  "total": 156,
  "pages": 8,
  "current": 1,
  "records": [
    {
      "comicId": 123,
      "title": "海贼王",
      "coverUrl": "/files/thumbs/123/cover.jpg",
      "totalSize": 1258291200,
      "hqSize": 1048576000,
      "lqSize": 209715200,
      "hqStatus": "READY",
      "lqStatus": "READY",
      "chapterCount": 1054,
      "pageCount": 12000
    }
  ]
}
```

**状态聚合规则**：
- `hqStatus`: 遍历该漫画所有 IMAGE 页
  - 如果全部 `DELETED` → `DELETED`
  - 如果全部 `READY` → `READY`
  - 如果混合（部分 `DELETED`、部分 `READY`）→ `MIXED`
- `lqStatus`: 同理
  - 如果全部 `READY` → `READY`
  - 如果全部 `NOT_GENERATED` → `NOT_GENERATED`
  - 如果混合 → `MIXED`

### 4.2 漫画章节存储详情

```http
GET /api/admin/storage/comics/{comicId}/chapters
```

**Response**:
```json
[
  {
    "chapterId": 1001,
    "chapterNo": "001",
    "title": "第1话",
    "pageCount": 20,
    "hqSize": 52428800,
    "lqSize": 10485760,
    "hqStatus": "READY",
    "lqStatus": "READY"
  }
]
```

**状态规则**：单章内所有 IMAGE 页的状态聚合（同 4.1 逻辑）。

### 4.3 复用已有 API

| 操作 | 已有 API | 说明 |
|------|---------|------|
| 删除整本 HQ | `POST /api/comics/{comicId}/delete-hq` | 幂等，202/200/409 |
| 删除单章 HQ | `POST /api/chapters/{chapterId}/delete-hq` | 同上 |
| 生成整本 LQ | `POST /api/comics/{comicId}/lq` | 异步任务 |
| 生成单章 LQ | `POST /api/chapters/{chapterId}/lq` | 异步任务 |

---

## 5. 前端组件设计

### 5.1 StoragePage 改造

**新增 State**:
```typescript
interface ComicStorageItem {
  comicId: number
  title: string
  coverUrl: string
  totalSize: number
  hqSize: number
  lqSize: number
  hqStatus: 'READY' | 'DELETED' | 'MIXED'
  lqStatus: 'READY' | 'NOT_GENERATED' | 'MIXED'
  chapterCount: number
  pageCount: number
}

interface ChapterStorageItem {
  chapterId: number
  chapterNo: string
  title: string
  pageCount: number
  hqSize: number
  lqSize: number
  hqStatus: 'READY' | 'DELETED' | 'MIXED'
  lqStatus: 'READY' | 'NOT_GENERATED' | 'MIXED'
}

// StoragePage.vue 新增
const comicList = ref<ComicStorageItem[]>([])
const loading = ref(false)
const selectedComicIds = ref<number[]>([])
const drawerVisible = ref(false)
const drawerComicId = ref<number | null>(null)
const drawerChapters = ref<ChapterStorageItem[]>([])
const drawerLoading = ref(false)

const filters = reactive({
  hqStatus: 'ALL' as 'ALL' | 'READY' | 'DELETED',
  lqStatus: 'ALL' as 'ALL' | 'READY' | 'NOT_GENERATED',
  sort: 'hqSize' as 'totalSize' | 'hqSize' | 'lqSize' | 'title',
  order: 'desc' as 'asc' | 'desc',
  keyword: '',
})

const pagination = reactive({
  page: 1,
  size: 20,
  total: 0,
})
```

**空状态与加载状态**：
- 表格加载中：`loading=true` 时 `el-table` 显示 skeleton/loading 动画
- 筛选结果为空：显示空状态插图 + 文案"没有符合条件的漫画，请调整筛选条件"
- 首次进入页面无数据：显示"暂无漫画数据"

**新增 Methods**:
```typescript
async function loadComicList() { ... }  // 调 GET /api/admin/storage/comics
async function openDrawer(comicId: number) { ... }  // 调 GET /api/admin/storage/comics/{id}/chapters
async function batchDeleteHq() { ... }  // 遍历 selectedComicIds 调 hqApi.deleteComic
async function batchGenerateLq() { ... }  // 遍历 selectedComicIds 调 lqApi.generateComic
async function deleteChapterHq(chapterId: number) { ... }
async function generateChapterLq(chapterId: number) { ... }
```

**表格列定义**:
| 列 | 字段 | 宽度 | 说明 |
|---|------|------|------|
| 复选框 | - | 40px | `el-table-column type="selection"` |
| 封面 | `coverUrl` | 60px | 缩略图 `object-fit: cover` |
| 标题 | `title` | flex | 左对齐 |
| HQ 大小 | `hqSize` | 100px | 右对齐，`formatSize` |
| LQ 大小 | `lqSize` | 100px | 右对齐 |
| HQ 状态 | `hqStatus` | 90px | Tag：`READY`(success) / `DELETED`(info) |
| LQ 状态 | `lqStatus` | 90px | Tag：`READY`(success) / `NOT_GENERATED`(warning) |
| 操作 | - | 160px | [删除HQ] [生成LQ] [详情] |

### 5.2 抽屉组件（行内定义，不拆分新文件）

由于抽屉逻辑简单（表格 + 批量操作），直接在 `StoragePage.vue` 内用 `el-drawer` 实现，不单独拆组件。

```vue
<el-drawer
  v-model="drawerVisible"
  :title="`${drawerComicTitle} — 存储详情`"
  size="500px"
  destroy-on-close
>
  <div v-if="drawerLoading" class="state loading small">...</div>
  <template v-else>
    <div class="drawer-stats">
      <span>HQ: {{ formatSize(drawerTotalHq) }}</span>
      <span>LQ: {{ formatSize(drawerTotalLq) }}</span>
    </div>
    <el-table :data="drawerChapters" @selection-change="onDrawerSelectionChange">
      <el-table-column type="selection" width="40" />
      <el-table-column prop="title" label="章节" />
      <el-table-column prop="hqSize" label="HQ" :formatter="sizeFormatter" width="80" />
      <el-table-column prop="lqSize" label="LQ" :formatter="sizeFormatter" width="80" />
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button v-if="row.hqStatus !== 'DELETED'" type="danger" text size="small" @click="deleteChapterHq(row.chapterId)">删HQ</el-button>
          <el-button v-if="row.lqStatus !== 'READY'" type="primary" text size="small" @click="generateChapterLq(row.chapterId)">生LQ</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="drawer-batch-bar">
      <el-button type="danger" :disabled="!drawerSelectedIds.length" @click="batchDeleteDrawerHq">删除选中HQ</el-button>
      <el-button type="primary" :disabled="!drawerSelectedIds.length" @click="batchGenerateDrawerLq">生成选中LQ</el-button>
    </div>
  </template>
</el-drawer>
```

### 5.3 ComicListPage 最小关联

在 `ComicListPage.vue` 每行操作区（已有编辑/删除按钮附近）增加一个图标按钮：

```vue
<el-button type="info" text :icon="Coin" @click.stop="goStorage(comic.id)">存储</el-button>
```

点击后跳转：
```typescript
function goStorage(comicId: number) {
  router.push({
    path: '/manage/storage',
    query: { highlight: comicId.toString() }
  })
}
```

`StoragePage` 在 `onMounted` 时检查 `route.query.highlight`，滚动到对应行并添加高亮样式（如 `background: var(--bg-secondary)`），3 秒后自动移除。

---

## 6. 交互流程

### 6.1 批量删除 HQ（整本）

```
管理员在 StoragePage 表格勾选多部漫画
  ↓
点击底部"删除选中 HQ"
  ↓
ElMessageBox.confirm("将删除 X 部漫画的 HQ 原图，共约 Y GB。")
  ↓
遍历 comicIds 调 hqApi.deleteComic(id)
  ├─ 200 → 该漫画已删除，跳过
  ├─ 202 → 任务已提交，计数
  └─ 409 → 该漫画 LQ 未就绪，收集错误信息
  ↓
  结果展示（ElMessage）：
  - 全部成功："5 部漫画 HQ 删除任务已提交"
  - 部分成功："3 部已提交删除，2 部失败"
  - 有 409 错误：弹出一个汇总通知（或 MessageBox），列出每部失败漫画名及未就绪页信息
  ↓
  loadComicList() 刷新表格
```

### 6.2 章节级删除 HQ

```
管理员点击某行"详情"
  ↓
抽屉打开，显示章节列表
  ↓
勾选第1话、第2话
  ↓
点击"删除选中 HQ"
  ↓
调 hqApi.deleteChapter(chapterId) 逐个发送
  ↓
结果显示 + 刷新抽屉列表
```

### 6.3 LQ 生成流程

与 HQ 删除流程对称，调 `lqApi.generateComic` / `generateChapter`。

---

## 7. 错误处理

| 场景 | 行为 |
|------|------|
| 批量操作部分成功 | 前端收集结果，分别显示成功数和失败详情 |
| LQ 未就绪（409） | 显示具体漫画名 + 未就绪页信息 |
| 网络错误 | ElMessage.error('网络错误，请重试') |
| 任务提交成功但 Worker 失败 | 前端无法感知，依赖刷新后状态未变来发现；长期可接入 task 状态查询 |
| 抽屉内章节列表加载失败 | 抽屉内显示错误状态 + 重试按钮 |

---

## 8. 新增/修改文件清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `frontend/src/views/management/StoragePage.vue` | **大幅修改** | 移除现有"HQ 存储优化"手动输入框区域；新增漫画存储列表表格、筛选栏、批量操作栏、抽屉 |
| `frontend/src/views/management/ComicListPage.vue` | 修改 | 每行增加"存储"图标按钮，跳转 StoragePage |
| `frontend/src/services/api.ts` | 修改 | 新增 `adminApi.storageComics()` / `adminApi.storageChapters()` |
| `frontend/src/router/index.ts` | 无需修改 | StoragePage 已有路由 `/manage/storage` |
| `api-service/.../controller/AdminStorageController.java` | 新建 | `GET /admin/storage/comics` / `GET /admin/storage/comics/{id}/chapters` |
| `api-service/.../service/StorageQueryService.java` | 新建 | 存储状态查询 + 聚合逻辑 |
| `api-service/.../mapper/MediaMapper.java` | 修改 | 新增按 comicId 查询存储状态的方法 |

---

## 9. 状态显示规范

### 9.1 HQ 状态 Tag

| 状态 | Tag 类型 | 文案 |
|------|---------|------|
| READY | success | HQ 就绪 |
| DELETED | info | HQ 已删 |
| MIXED | warning | 部分已删 |

### 9.2 LQ 状态 Tag

| 状态 | Tag 类型 | 文案 |
|------|---------|------|
| READY | success | LQ 就绪 |
| NOT_GENERATED | warning | 未生成 |
| MIXED | danger | 部分失败 |

---

## 10. 性能考虑

| 问题 | 对策 |
|------|------|
| 漫画数量多（1000+） | 后端分页 + 前端虚拟滚动（`el-table` 自带） |
| 封面缩略图加载 | 懒加载 `loading="lazy"`，失败时显示占位图 |
| 批量操作大量漫画 | 受控并发发送（每批 3-5 个并行，避免并发压垮后端），显示进度"12/50" |
| 抽屉章节列表长 | 章节通常 < 1000，`el-table` 默认滚动足够 |

---

## 附录：与现有 HQ 删除设计的衔接

本设计是 [2026-07-21-hq-delete-design.md](2026-07-21-hq-delete-design.md) 的前端入口补全，复用其全部后端 API 和 MQ 链路：
- `hqApi.deleteComic` / `hqApi.deleteChapter` → 已有
- `lqApi.generateComic` / `lqApi.generateChapter` → 已有
- 幂等、409 校验、MQ 异步 → 已有

新增内容仅为：
1. 后端查询 API（存储状态聚合）
2. 前端 StoragePage 改造（列表 + 筛选 + 批量 + 抽屉）
3. ComicListPage 最小关联（图标跳转）
