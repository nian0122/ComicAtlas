# ComicAtlas 0.2 管理模块设计

**版本**: 0.2  
**日期**: 2026-07-16  
**状态**: Canonical

---

## 设计目标

管理不是后台，而是**仓库维护**。它使用频率低，但需要覆盖个人漫画仓库维护的完整生命周期。

---

## 顶层导航

管理侧只保留 5 项一级导航：

```
漫画    导入    存储    元数据    设置
```

导航按用户任务组织，不按技术模块组织。

---

## 模块职责

### 1. 漫画

漫画的 CRUD 和元数据维护。

#### 漫画列表 (`/manage/comics`)

- 搜索、筛选、分页。
- 点击进入编辑页（不是详情页）。
- 支持批量选择（未来扩展）。

#### 漫画编辑 (`/manage/comics/:id/edit`)

编辑页包含多个区块：

- **基本信息**：标题、副标题、作者、出版社、语言、简介、来源。
- **分类与标签**：Category（单选）、Tag（多选）。
- **封面**：从已有 page 选择、上传（未来）、恢复默认。
- **来源与存储**：source_type、storage_policy、source_path。
- **危险操作**：删除数据库、删除文件、重新扫描、重新生成缩略图、重新生成 LQ、重新解析 Metadata。

危险操作统一放到底部，并需要二次确认。

---

### 2. 导入

漫画导入入口和任务管理。

#### 导入 (`/manage/import`)

- ZIP 导入。
- 目录导入。
- 扫描目录导入（未来可并入存储模块）。

#### 任务 (`/manage/import/tasks`)

- 导入任务列表。
- 失败任务重试。
- 任务状态查看。

原 `TaskCenterPage` 并入此处。

---

### 3. 存储

仓库维护，不是日常操作。

#### 存储统计

- 漫画数量、章节数量、图片数量。
- 硬盘占用：HQ / LQ / Thumbs / Metadata。

#### 扫描

- 扫描 HQ 目录结构。
- 检测缺失或新增文件。

#### 恢复

异步任务驱动的存储恢复，通过任务中心统一管理。

**流程概述**：

```
用户点击"从存储恢复数据库记录"
  ↓
API RecoveryTaskService: 检查无 PENDING/RUNNING 任务 → INSERT recovery_task(PENDING)
  ↓ MQ recovery.requested
Worker RecoveryTaskHandler: 扫描 MANGA_ROOT/hq/{comicId}/ 收集所有数字目录 ID
  ↓ MQ recovery.progress（携带 comicId 列表）
API RecoveryEventHandler: 标记 RUNNING → 逐本调用 RecoveryEngine.processComicDir()
  ↓
  ├─ metadata 文件存在 → 恢复完整漫画（comic/chapter/page/media），状态 READY
  ├─ metadata 文件缺失 → 创建 PLACEHOLDER 漫画（标题为"未知漫画 {comicId}"），不参与普通列表
  ├─ 数据库记录已存在 → skipped，不重复创建
  └─ 异常 → error，记录错误信息，不中断整体流程
  ↓
全部处理完成 → SUCCESS（含 total/recovered/skipped/placeholder/error 计数器）
基础设施故障 → FAILED
```

**RecoveryTask 模型**：

| 字段 | 说明 |
|------|------|
| status | PENDING → RUNNING → SUCCESS / FAILED |
| totalComics | 扫描到的漫画目录总数 |
| recoveredComics | 成功恢复的漫画数 |
| skippedComics | 已存在而跳过的漫画数 |
| placeholderComics | 无 metadata 创建的 PLACEHOLDER 漫画数 |
| errorComics | 处理失败的漫画数 |
| errorMessage | 最新错误信息（单条） |
| retryCount | 重试次数 |

**状态机**：

```text
PENDING ──► RUNNING ──► SUCCESS
   ▲           │
   │           ▼
   └── retry ◄── FAILED
```

- 不支持取消。
- 仅 FAILED 状态可重试，重试时重置为 PENDING 并重新发送 MQ。
- 同一时刻只允许一个恢复任务运行（创建时返回 409 冲突）。

#### 清理

- 垃圾文件清理。
- 孤儿文件检查。

---

### 4. 元数据

Category 和 Tag 的统一管理入口。

#### Category Tab

- 一级分类管理。
- 默认：漫画 / 本子 / CG / 画集 / 小说。
- 支持新增、重命名、排序、删除（无绑定时可删）。

#### Tag Tab

- 标签 CRUD。
- 支持绑定/解绑漫画。

---

### 5. 设置

系统级配置：

- 阅读默认设置。
- 缓存设置。
- 存储路径。
- 代理配置。
- 系统配置。

---

## 内部代码组织

虽然导航只有 5 项，但内部按领域拆分：

```
management/
├── comic/
│   ├── list/
│   └── edit/
│       ├── cover/
│       └── danger/
├── import/
│   ├── import/
│   └── task/
├── storage/
│   ├── stats/
│   ├── scan/
│   ├── recovery/
│   └── cleanup/
├── metadata/
│   ├── category/
│   └── tag/
└── settings/
```

这样 UI 简单，代码结构清晰。

---

## 与现有代码的关系

- 保留现有的 `ComicService`、`TagService`、`AdminService`、`ImportService` 等。
- 保留现有的导入、Worker、MQ、存储、扫描恢复能力。
- 调整的是页面组织、路由、导航，以及删除 Dashboard、OperationLog 等不符合定位的页面。
