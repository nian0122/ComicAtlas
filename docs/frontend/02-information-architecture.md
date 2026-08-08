# 02 — 信息架构

**更新日期：** 2026-08-08
**状态：** 与 v1.0 路由结构同步
**维护者：** ComicAtlas 前端组

> 定义整个系统有哪些模块、模块之间是什么关系、每个模块负责什么。
> 路由定义见 `frontend/src/router/index.ts`：阅读端 6 条 + 管理端 8 条主页面路由（另有 `/manage/intercept` 移动端拦截页与 `/manage/storage/:id` 存储详情子页）。

---

## 顶层模块

```
ComicAtlas
├── 阅读端（ReadingLayout）
│   ├── 首页（Home）            # 最近阅读 + 最近导入 + 操作入口
│   ├── 漫画库（Library）       # 浏览、搜索、筛选、排序
│   ├── 漫画详情（Comic Detail）# 信息、目录树、阅读入口
│   ├── 阅读器（Reader）        # 图片/视频混排阅读
│   └── 阅读记录（History）     # 继续阅读入口
└── 管理端（ManagementLayout，/manage）
    ├── 漫画工作区（Comics）    # 列表 + 编辑（元数据/标签/分类/乐观锁）
    ├── 导入与任务中心（Import / Tasks）  # 创建导入任务 + 任务进度监控
    ├── 存储管理（Storage）     # 存储统计、HQ/LQ 状态、批量操作
    ├── 元数据管理（Metadata）  # 分类与标签维护
    ├── DLQ 管理（Dead Letter） # 死信队列查看/重放/清空
    └── 设置（Settings）        # 阅读默认画质等偏好
```

---

## 模块详情

### 首页（Home）

**路由**：`/`

**职责**：阅读端落地页，快速恢复与发现。

```
Home
├── Hero（继续阅读入口）
├── 最近阅读（continueReading）  → /history
├── 最近导入（recentlyAdded）    → /library
└── 操作入口（HomeActionGrid）   → /library / /history 等
```

**去向**：点击漫画 → Comic Detail；更多 → Library / History

---

### 漫画库（Library）

**路由**：`/library`

**职责**：展示所有漫画，提供搜索、筛选、排序、分页。

```
Library
├── 搜索栏       keyword（关键词）
├── 筛选         category（分类）/ tags（多标签，AND/OR）/ status / sourceType
├── 排序         createdAt / updatedAt / title / pageCount / lastReadTime
├── 漫画卡片列表  cover + title + author + 阅读进度
└── 分页
```

**入口**：首页 → 漫画库；导航 → Library

**去向**：点击卡片 → Comic Detail

---

### 漫画详情（Comic Detail）

**路由**：`/comic/:id`

**职责**：展示单本漫画的完整信息 + 目录结构 + 阅读/操作入口。

```
Comic Detail
├── 封面 + 基本信息    title / author / sourceType / pageCount
├── 标签
├── 阅读进度
├── 操作按钮          继续阅读 / 从头开始 / 生成 LQ / 删除（进入回收站流程）
├── Catalog 树        Vol.1 → 第1话、第2话
└── 章节入口          点击章节 → Reader
```

**入口**：Library / Home 点击漫画 → `/comic/:id`

**去向**：点击章节 → Reader；返回 → Library

---

### 阅读器（Reader）

**路由**：`/reader/:chapterId`

**职责**：阅读漫画的核心页面。全屏优先，最小 UI，支持图片与视频混排。

```
Reader
├── 工具栏        返回 / 标题 / 页码 / 上一章 / 下一章 / 设置
├── 内容区域      图片（ProgressiveImage 渐进加载）或视频（VideoPlayer）
├── 章节导航      prevChapterId / nextChapterId（按 global_order）
└── 进度同步      自动记录阅读位置（chapterId + pageNumber）
```

**入口**：Comic Detail / History 点击章节 → `/reader/:chapterId`

**去向**：返回 → Comic Detail；读完 → 自动跳下一章

---

### 阅读记录（History）

**路由**：`/history`

**职责**：展示最近阅读的漫画，快速恢复阅读。

```
History
├── 记录列表    comic + chapter + 进度 + 最后阅读时间
└── 点击        → Reader（恢复到上次位置）
```

**入口**：导航 → `/history`

**去向**：点击 → Reader；详情 → Comic Detail

---

### 漫画工作区（管理端）

**路由**：`/manage/comics`（列表）、`/manage/comics/:id/edit`（编辑）

**职责**：管理端漫画列表、筛选、批量选择与漫画编辑（元数据、标签、分类、封面）。

```
Comic Workspace
├── 列表 + 筛选（keyword / category / status / tags / sourceType）
├── 批量选择    跨页按筛选或 ID 选择（BatchEditDialog）
├── 编辑页      元数据 / 标签 / 分类（乐观锁 version）
└── 删除        默认软删除进入回收站（7 天保留期；后端 /api/trash 提供恢复与永久清理）
```

**入口**：管理后台 → 漫画工作区

---

### 导入与任务中心（管理端）

**路由**：`/manage/import`（导入）、`/manage/import/tasks`（任务中心）

**职责**：创建 ZIP / REGISTER / EHENTAI 导入任务（支持批量），监控导入任务进度。

```
Import / Task Center
├── 导入页       来源类型（ZIP / REGISTER / EHENTAI）+ 路径输入 + 批量导入
├── 任务列表     进行中 / 失败 / 已完成，进度条 + 状态标签
└── 操作         取消 / 重试 / 立即阅读
```

**去向**：导入提交 → 任务中心查看进度 → 完成后到 Library/详情阅读

---

### 存储管理（管理端）

**路由**：`/manage/storage`（列表）、`/manage/storage/:id`（章节明细）

**职责**：存储统计概览、HQ/LQ 状态、按漫画/章节执行 LQ 生成、HQ 删除、视频转码、导出。

```
Storage
├── 统计概览（StorageSummary）    HQ/LQ 总大小与状态分布
├── 漫画列表（StorageTable）      排序 / 筛选 / 批量操作
├── 章节明细（StorageDetailPage） 章节级 HQ/LQ 状态与操作
└── 操作                        GenerateLQ / DeleteHQ / Transcode / Export / 危险区
```

**入口**：管理后台 → 存储管理

---

### 元数据管理（管理端）

**路由**：`/manage/metadata`

**职责**：分类与标签的维护（增删改）。

```
Metadata
├── 分类 Tab（categoryStore）   新建 / 编辑 / 删除分类
└── 标签 Tab（tagStore）        新建 / 删除标签
```

---

### DLQ 管理（管理端）

**路由**：`/manage/dlq`

**职责**：查看各死信队列积压，重放或清空死信消息（`/api/admin/dlq/*`）。

```
DLQ
├── 队列列表（DlqAccessPanel）   队列名 / 积压数 / 消费者
├── 消息查看（DlqMessageDialog） 查看死信 payload
└── 操作                        Replay（重放）/ Purge（清空）
```

---

### 设置（管理端）

**路由**：`/manage/settings`

**职责**：阅读默认画质等偏好设置（`/api/settings`）。

---

## 模块关系

```
                          ┌──────────┐
                          │   Home   │ ← 首页
                          └────┬─────┘
                               │
                   ┌───────────┼────────────┐
                   ▼           ▼            ▼
             ┌─────────┐ ┌──────────┐ ┌──────────┐
             │ Library │ │  History │ │ Comic Detail
             └────┬────┘ └────┬─────┘ └────┬─────┘
                  │           │            │
                  └───────────┴───┐        │ 点击章节/继续阅读
                                  ▼        ▼
                            ┌──────────┐
                            │  Reader  │
                            └──────────┘

管理端：Comics ↔ Comic Detail（编辑跳转）
        Import → Tasks（创建后查看进度）
        Storage / Metadata / DLQ / Settings（独立管理工具页）
```

- **Home → Library/History → Comic Detail → Reader** 是阅读端主流程
- **Import → Tasks** 是导入流程
- **Comics / Storage / Metadata / DLQ / Settings** 是管理端独立工具页
