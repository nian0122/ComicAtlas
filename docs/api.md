# ComicAtlas API 文档 v1.0

**Base URL**: `http://localhost/api`

所有响应格式：`{ "code": 200, "message": "success", "data": ... }`。业务失败时 `code` 为 HTTP 语义错误码（400/409/500 等），`message` 为可读错误，管理领域失败响应还可在 `data.reasonCode`（业务原因码）上补充原因。

> 本文覆盖 v1.0 管理控制台新增端点。管理端新领域（任务中心 / 批量 / 回收站 / 上传 / 允许操作）为 T17-T20 实现，端点对照 `api-service` 源码；所有示例均可被 `scripts/qa/verify-management-docs.ps1` 校验。

---

## 1. 漫画

### 列表 & 搜索
```
GET /api/comics?keyword=&tag=&status=&category=&sourceType=&sort=createdAt&page=1&size=20
```

| 参数 | 说明 |
|------|------|
| keyword | 全文搜索：title / titleJpn / author / 标签 |
| tag | 精确标签名筛选 |
| status | IMPORTING / READY / REFRESHING / DELETING / DELETED / RESCANNING |
| category | 分类 |
| sourceType | ZIP / REGISTER / EHENTAI |
| sort | createdAt / updatedAt / title / pageCount / lastReadTime |

### 详情
```
GET /api/comics/{id}
```
返回：title, author, coverUrl, pageCount, sourceType, tags, description

### 元数据编辑
```
GET  /api/comics/{id}/metadata
PUT  /api/comics/{id}/metadata
{ "title": "Title", "author": "Author", "description": "Description" }
```

### 标签绑定
```
GET /api/comics/{id}/tags
PUT /api/comics/{id}/tags
{ "tagIds": [1, 2, 3] }
```

### 封面

> **已移除（v1.0）**：封面候选/设置接口 `GET /api/comics/{id}/covers/candidates`、`PUT /api/comics/{id}/cover` 在当前代码中不存在。封面 URL 由 `FileUrlResolver.resolveCover(comicId)` 生成，对应 `/files/thumbs/{comicId}/cover.webp`，无需候选接口。

### 目录树
```
GET /api/comics/{id}/catalog
```
返回 `CatalogNode[]`：
```json
[{ "id": 1, "title": "Vol.1", "children": [...], "chapters": [
  { "id": 10, "chapterNo": "001", "title": "第1话", "globalOrder": 0, "pageCount": 24 }
]}]
```

### 章节页面
阅读页面统一走 `GET /api/chapters/{id}`（见第 2 节），旧 `GET /api/comics/{comicId}/chapters/{chapterId}/pages` 已移除。

### 删除（进入回收站）
```
DELETE /api/comics/{id}
```
**v1.0 行为变更**：删除不再硬删，而是创建回收任务（`COMIC_DELETE`）把漫画移入回收站，响应体为 `ManagementTaskResponse`。永久删除需走 `POST /api/trash/comics/{id}/purge`（只接受 `TRASHED` 状态 + 二次确认 token + 7 天保留期）。支持可选 `Idempotency-Key` 请求头。

> 兼容说明：`DELETE /api/admin/comics/{id}?mode=DATABASE_ONLY|DELETE_FILES` 同样重定向到回收站，不再绕过回收站；该旧入口保留用于兼容旧调用方，永久清理统一走 `/api/trash`。

---

## 2. 阅读

### 章节详情
```
GET /api/chapters/{id}
```
```json
{
  "chapterId": 10, "chapterTitle": "第1话",
  "pages": [{ "pageNumber": 1, "hqUrl": "/files/hq/35/10/001.jpg", "lqUrl": "..." }],
  "total": 24, "prevChapterId": null, "nextChapterId": 12
}
```

---

## 3. 阅读记录

```
GET    /api/history              # 列表
GET    /api/history/{comicId}    # 获取进度
PUT    /api/history/{comicId}    # 更新进度 { chapterId, pageNumber }
```

---

## 4. 导入 & 任务中心

### 创建任务
```
POST /api/tasks/import
{ "sourceType": "ZIP", "sourcePath": "D:/downloads/comic.zip" }
{ "sourceType": "REGISTER", "sourcePath": "D:/manga/temp/ComicA" }
{ "sourceType": "EHENTAI", "sourcePath": "https://e-hentai.org/g/123456/abc123" }
```

### 任务列表（Dashboard）
```
GET /api/tasks/import?page=1&size=50&status=
```

### 任务详情 / 状态
```
GET /api/tasks/import/{id}
GET /api/tasks/import/{id}/status
```

### 取消 / 重试
```
POST /api/tasks/import/{id}/cancel
POST /api/tasks/import/{id}/retry
```

### ImportTask 状态机

```text
PENDING
   │
   ▼
PARSING        ──► FAILED
   │                ▲
   ▼                │
IMPORTING      ─────┘
   │
   ▼
SUCCESS
```

| 状态 | 含义 |
|------|------|
| `PENDING` | 任务已创建，等待 Worker 消费 |
| `PARSING` | Worker 正在解析来源（DirectoryParser / MetadataAssembler） |
| `IMPORTING` | 文件搬运到 HQ 存储中 |
| `SUCCESS` | 导入完成，metadata.json 已写入，API 侧已落库 |
| `FAILED` | 导入失败，可通过 retry 重置回 PENDING |

> v1.0 起任务状态统一收敛到 `ManagementTaskStatus`（QUEUED/RUNNING/.../SUCCEEDED/FAILED）与 `TaskStage`（DOWNLOADING/EXTRACTING/PARSING 子阶段）。`ImportTaskStatus` 枚举保留为导入进度状态（`PENDING/PARSING/IMPORTING/SUCCESS/FAILED`），终态为 SUCCESS/FAILED。

> 完整导入流水线设计见 [`docs/architecture/02-import-pipeline.md`](architecture/02-import-pipeline.md)。

---

## 5. LQ 生成（手动触发）

```
POST /api/comics/{comicId}/lq       # 整本
POST /api/chapters/{chapterId}/lq   # 单章
```

状态：NOT_GENERATED → QUEUED → GENERATING → READY / FAILED

---

## 6. HQ 删除

```
POST /api/comics/{comicId}/delete-hq       # 整本删除 HQ
POST /api/chapters/{chapterId}/delete-hq   # 单章删除 HQ
```

删除漫画/章节的 HQ 高清图片以释放磁盘空间。LQ 缩略图不受影响。
状态：READY → DELETED（通过 MQ 异步完成）

---

## 7. 批量导入

```
POST /api/tasks/import/batch
{ "sourceType": "DIRECTORY", "sourcePaths": ["D:/downloads/A", "D:/downloads/B"] }
# → { "batchId": "...", "total": 2, "succeeded": [...], "failed": [] }

# 目录扫描（异步任务：API 创建 → MQ → Worker 扫描 → 结果回写）
POST /api/tasks/directory-scan
{ "parentPath": "D:/downloads" }
# → { "id": 1, "status": "PENDING", ... }

GET  /api/tasks/directory-scan/{id}
# → { "id": 1, "status": "SUCCESS", "result": { "parentPath": "...", "total": 5, "items": [...] } }
```

批量导入支持一次提交多个来源。目录扫描为异步任务：Worker 在本机文件系统上校验路径并遍历子目录，前端轮询 `GET /api/tasks/directory-scan/{id}` 直到 `status` 为 `SUCCESS`/`FAILED` 后读取 `result`。

> v1.0 新增 `POST /api/tasks/import` 支持可选 `Idempotency-Key` 头，同键同 payload 重放不重复建任务；`POST /api/tasks/import` 请求体字段为 `sourceType`（EHENTAI/ZIP/DIRECTORY）、`sourcePath`（ZIP 文件或目录路径）、`sourceRef`（EHENTAI 画廊 URL）。跨页批量元数据操作请使用新领域接口 `POST /api/management/batch`（见 13.6）。

---

## 8. 统计

> **已移除（v1.0）**：`GET /api/dashboard/statistics` 不存在于当前代码。存储统计请使用 `GET /api/admin/storage/stats`（见第 11 节）。

---

## 9. 操作日志

> **已移除（v1.0）**：`GET /api/operations` 不存在于当前代码。管理任务进度通过 `GET /api/management/tasks` 与 `GET /api/management/outbox/stats` 查看（见第 13 节）。

---

## 10. 标签

```
GET    /api/tags
POST   /api/tags        { "name": "tag-name" }
DELETE /api/tags/{id}
```

---

## 11. 管理

```
POST /api/admin/comics/{id}/refresh-metadata  # 刷新漫画元数据（重新解析目录）
POST /api/admin/storage/scan-recover          # 扫描 HQ 目录并恢复/创建占位漫画（已废弃，见第 12 节）
DELETE /api/admin/comics/{id}?mode=DATABASE_ONLY  # 兼容入口，v1.0 起重定向到回收站
```

> `POST /api/admin/rebuild` 不存在于当前代码，勿调用。存储恢复请使用异步恢复任务（第 12 节）。

### 存储查询

```
GET /api/admin/storage/stats                   # 存储统计摘要（total/hq/lq/thumb/comicCount）
GET /api/admin/storage/comics?hqStatus=&lqStatus=&sort=&keyword=  # 漫画级存储列表
GET /api/admin/storage/comics/{id}/chapters    # 章节级存储详情
```

返回每个漫画/章节的 HQ/LQ 大小和状态，支持按 HQ/LQ 状态筛选和排序。

### 视频转码补偿

```
POST /api/admin/storage/comics/{comicId}/transcode-videos
```

触发漫画的视频转码补偿任务。扫描漫画下所有非标准格式视频（非 mp4/webm），标记为 PENDING 并发送 MQ。

**响应**：
```json
{
  "comicId": 92,
  "totalVideoPages": 15,
  "notNeededCount": 5,
  "submittedCount": 3,
  "pendingCount": 3,
  "doneCount": 7,
  "failedCount": 0
}
```

| 字段 | 说明 |
|------|------|
| `totalVideoPages` | 漫画下所有 VIDEO 类型页面总数 |
| `notNeededCount` | 无需转码的页面数（transcode_status = NOT_NEEDED） |
| `submittedCount` | 本次从 NOT_NEEDED/FAILED 标记为 PENDING 并提交的数量 |
| `pendingCount` | 当前待处理的页面总数，包含本次提交数量 |
| `doneCount` | 已完成的页面数（transcode_status = DONE） |
| `failedCount` | 当前转码失败的页面数 |

**transcode_status 状态机**：
```
NOT_NEEDED ──┐
             ├──► PENDING ──► DONE
FAILED ──────┘       │
                     └──► FAILED
```

| 状态 | 含义 |
|------|------|
| `NOT_NEEDED` | 不需要转码（图片或已是 mp4/webm） |
| `PENDING` | 已提交转码任务，Worker 正在处理或等待处理 |
| `DONE` | 转码完成，视频已替换为 mp4（H.264/AAC） |
| `FAILED` | 转码失败，原文件保留，可 re-trigger |

**注意事项**：
- 不支持取消。PENDING 记录会一直保持直到 Worker 返回结果。
- re-trigger 是安全的：CAS 保护（`WHERE transcode_status IN ('NOT_NEEDED','FAILED')`）确保已 PENDING 的记录不会被覆盖或重复入队。
- 已是 mp4/webm 的视频不会被标记为 PENDING。
- 历史 PENDING 记录可通过 DLQ 重放已完成事件收敛为 DONE（handler 接受 PENDING 状态）。

### 存储扫描恢复（已废弃）

> **废弃**：`POST /api/admin/storage/scan-recover` 已废弃，请使用异步恢复任务接口（参见下方 [12. 恢复任务](#12-恢复任务)）。
> 该端点同步阻塞，且在大量漫画时可能超时。目前仍保留以兼容旧版调用，未来版本将移除。

```
POST /api/admin/storage/scan-recover
```

扫描 `MANGA_ROOT/hq/` 下的 `{comicId}/{chapterId}/*.jpg` 目录结构：
- 若 comic 数据库记录已存在 → 计入 `existingComics`
- 若目录存在 `metadata/{comicId}.json` → 恢复为完整漫画，状态 `READY`
- 若无 metadata → 创建 `PLACEHOLDER` 漫画，状态 `PLACEHOLDER`，不参与普通列表

响应：
```json
{
  "scannedComics": 3,
  "existingComics": 1,
  "restoredComics": 1,
  "placeholderComics": 1,
  "restoredChapters": 2,
  "restoredPages": 20,
  "placeholders": ["漫画 999999"],
  "errors": []
}
```

> 存储模型与布局设计见 [`docs/architecture/03-storage.md`](architecture/03-storage.md)。

---

## 12. 恢复任务

异步任务驱动的存储恢复。Worker 扫描 HQ 目录结构，API 侧逐本调用恢复引擎重建数据库记录。

### 创建恢复任务

```
POST /api/tasks/recovery
```

创建一个恢复任务。同一时刻只允许一个 PENDING 或 RUNNING 状态的任务，冲突时返回 409。

响应 `RecoveryTaskVO`：

```json
{
  "id": 1,
  "status": "PENDING",
  "totalComics": 0,
  "recoveredComics": 0,
  "skippedComics": 0,
  "placeholderComics": 0,
  "errorComics": 0,
  "errorMessage": null,
  "retryCount": 0,
  "createdAt": "2026-07-29T10:00:00",
  "startedAt": null,
  "endedAt": null
}
```

### 任务列表

```
GET /api/tasks/recovery?page=1&size=20
```

返回分页列表，按创建时间倒序排列。

### 任务详情

```
GET /api/tasks/recovery/{id}
```

返回 `RecoveryTaskVO`，包含完整计数器字段。前端可通过轮询该接口查看进度（`totalComics` / `recoveredComics` 等字段实时更新）。

### 重试

```
POST /api/tasks/recovery/{id}/retry
```

仅 `FAILED` 状态可重试。重试时状态重置为 `PENDING`，`retryCount` 递增，重新发送 MQ 到 Worker。

### 恢复任务状态机

```text
PENDING ──► RUNNING ──► SUCCESS
   ▲           │
   │           ▼
   └── retry ◄── FAILED
```

| 状态 | 含义 |
|------|------|
| `PENDING` | 任务已创建，等待 Worker 扫描 |
| `RUNNING` | Worker 已扫描完成，API 正在逐本恢复数据库记录 |
| `SUCCESS` | 全部漫画处理完成 |
| `FAILED` | Worker 扫描失败或 API 处理异常 |

> 恢复任务不支持取消。创建后必须等待至终态（SUCCESS/FAILED）。

### 恢复流程

1. API 创建 `recovery_task`（PENDING）→ 发送 `RecoveryRequestedEvent` 到 `comic.recovery`
2. Worker `RecoveryTaskHandler` 扫描 `MANGA_ROOT/hq/` 下所有数字目录（comicId）→ 发送 `RecoveryScanCompletedEvent`
3. API `RecoveryEventHandler` 标记 RUNNING → 逐本调用 `RecoveryEngine.processComicDir()`
4. 每个漫画目录：
   - 若数据库已有记录 → `skipped`
   - 若 `metadata/{comicId}.json` 存在 → 恢复完整 comic/chapter/page/media 记录，状态 `READY` → `recovered`
   - 若 metadata 缺失 → 创建 `PLACEHOLDER` 漫画（标题"未知漫画 {comicId}"），不参与普通列表 → `placeholder`
   - 异常 → `error`，记录错误信息，不中断整体流程
5. 全部处理完成 → `SUCCESS`；基础设施故障 → `FAILED`

### 恢复结果解读

| 计数 | 含义 |
|------|------|
| totalComics | HQ 目录下扫描到的漫画目录总数 |
| recoveredComics | 有 metadata 且成功恢复的漫画数 |
| skippedComics | 数据库已有记录，跳过的漫画数 |
| placeholderComics | 无 metadata，创建占位漫画的数量 |
| errorComics | 处理异常的数量 |

成功恢复（`recovered`）的漫画会立即出现在漫画库中。`placeholder` 漫画可通过状态筛选 `PLACEHOLDER` 在管理后台查看，需手动补充元数据或重新导入。

### 恢复任务与导入任务的区别

| 特性 | 恢复任务 | 导入任务 |
|------|---------|---------|
| 触发方式 | POST /api/tasks/recovery | POST /api/tasks/import |
| 输入 | 无（自动扫描 HQ 目录） | sourceType + sourcePath |
| Worker 职责 | 扫描 HQ 目录，收集 comicId | 解析来源、搬文件、写 metadata |
| API 职责 | 逐本调用 RecoveryEngine 恢复 DB | 读 metadata.json 落库 |
| 取消 | 不支持 | 支持（非终态） |
| 并发数 | 同一时刻仅 1 个 | 无限制 |

---

## 存储说明

| URL | 物理路径 | 缓存 |
|-----|---------|------|
| `/files/hq/*` | `D:/manga/hq/` | 60d |
| `/files/lq/*` | `D:/manga/lq/` | 30d |
| `/files/thumbs/*` | `D:/manga/thumbs/` | 7d |

---

## 13. 管理控制台（v1.0）

管理端使用独立的显式边界客户端 `frontend/src/services/management/http.ts`，响应解析在 `frontend/src/types/management/`。所有枚举字段在前端经 `parseEnum` 边界解析，未知枚举值降级为“未知状态”而不崩溃。

### 13.1 漫画工作区（列表 / 详情 / 更新 / 回收）

`ComicController`、`CatalogManagementController`、`ChapterManagementController`。

```
GET    /api/comics                                # 列表（含 lifecycle/activeTask/allowedOperations）
POST   /api/comics                                # 创建空漫画（DRAFT）
GET    /api/comics/{id}                           # 详情（含 version 乐观锁）
PUT    /api/comics/{id}                           # 更新（version 必填，冲突 → 409）
DELETE /api/comics/{id}                           # 回收（创建管理任务，可选 Idempotency-Key）
GET    /api/comics/{id}/metadata                  # 元数据
PUT    /api/comics/{id}/metadata                  # 更新元数据
GET    /api/comics/{id}/tags                      # 标签 ID 列表
PUT    /api/comics/{id}/tags                      # 绑定标签 { "tagIds": [1,2] }
GET    /api/comics/{id}/catalog                   # 目录树（只读）
```

`ComicListVO` 新增字段：`lifecycle`、`activeTask`（`{ id, taskType, status, progress, errorMessage }`）、`allowedOperations`、`progressPercent`。`ComicDetailVO` 另有 `version`、`chapters`、`tags`。

### 13.2 目录 CRUD

```
POST   /api/comics/{comicId}/catalogs             # 创建 { title, parentId?, sortOrder? }
PATCH  /api/comics/{comicId}/catalogs/{catalogId} # 重命名 { title }
PUT    /api/comics/{comicId}/catalogs/{catalogId}/move    # 移动 { parentId? }（body 可空）
PUT    /api/comics/{comicId}/catalogs/{catalogId}/reorder # 排序 { sortOrder }
DELETE /api/comics/{comicId}/catalogs/{catalogId}?reparentTo={catalogId}  # 删除（可重挂子级）
```

### 13.3 章节 CRUD

```
POST   /api/comics/{comicId}/chapters             # 创建 { title, chapterNo?, catalogId? }
PATCH  /api/comics/{comicId}/chapters/{chapterId} # 重命名 { title?, chapterNo? }
PUT    /api/comics/{comicId}/chapters/{chapterId}/move    # 移动 { catalogId? }（body 可空）
PUT    /api/comics/{comicId}/chapters/{chapterId}/reorder # 排序 { targetGlobalOrder }
DELETE /api/comics/{comicId}/chapters/{chapterId} # 回收章节（创建 CHAPTER_TRASH 任务）
```

### 13.4 允许操作查询（按钮权限唯一来源）

前端不得自算操作矩阵，一律查询本接口。

```
GET /api/management/operations/comics/{comicId}
GET /api/management/operations/chapters/{chapterId}
GET /api/management/operations/media/{mediaId}
```

响应 `AllowedOperations`：

```json
{ "allowed": ["READ","EDIT","DELETE","LQ_GENERATE","HQ_DELETE","METADATA_REFRESH"],
  "blockedReasons": { "TRANSCODE": "媒体类型不是视频" } }
```

`blockedReasons` 的 key 可为单个操作名，也可为 `"*"`（全部阻塞）。

### 13.5 任务中心（ManagementTask）

```
GET    /api/management/tasks                     # 分页列表
GET    /api/management/tasks/{id}                # 详情
GET    /api/management/tasks/{id}/items          # 逐目标项
POST   /api/management/tasks                     # 创建异步命令（需 Idempotency-Key）
POST   /api/management/tasks/{id}/cancel         # 取消
POST   /api/management/tasks/{id}/retry          # 重试（仅终态）
```

列表查询参数：`page`（默认 1）、`size`（默认 20）、`type`、`status`、`batchId`、`targetType`、`targetId`。

创建请求 `CreateManagementTaskRequest`：

```json
{
  "taskType": "LQ_GENERATE",
  "operation": "LQ_GENERATE",
  "targetType": "COMIC",
  "batchId": null,
  "targets": [ { "targetType": "COMIC", "targetId": 12, "operationType": "LQ_GENERATE" } ]
}
```

### 13.6 批量操作

```
POST /api/management/batch/preview   # 预览：命中/可执行/被阻塞 + 危险操作 token
POST /api/management/batch           # 创建批量任务（危险操作需 previewToken + Idempotency-Key）
```

选择判别联合（`BatchSelection`）：

```json
{ "type": "IDS", "ids": [1,2,3] }
{ "type": "FILTER", "query": { "status": "READY" }, "excludedIds": [5] }
```

`POST /api/management/batch/preview` 示例：

```json
{
  "operation": "COMIC_PURGE",
  "selection": { "type": "FILTER", "query": { "status": "TRASHED" } }
}
```

预览响应（危险操作签发 token，TTL 300 秒）：

```json
{
  "operation": "COMIC_PURGE",
  "selectedCount": 3,
  "eligibleCount": 3,
  "blocked": [],
  "dangerous": true,
  "previewToken": "a1b2c3...",
  "expiresAt": "2026-08-04T10:05:00"
}
```

创建时危险操作（当前仅 `COMIC_PURGE`）必须携带 `previewToken`。批量上限 `comic.batch.max-items`（默认 10000），超限返回 `BATCH_SIZE_EXCEEDED`。

### 13.7 回收站

回收入口沿用既有端点：`DELETE /api/comics/{id}`、`DELETE /api/comics/{comicId}/chapters/{chapterId}`、`DELETE /api/media/{mediaId}`。本组端点管理恢复 / 永久清理 / 对账。

```
POST  /api/trash/comics/{comicId}/restore                   # 恢复漫画
POST  /api/trash/comics/{comicId}/chapters/{chapterId}/restore  # 恢复章节
POST  /api/trash/media/{mediaId}/restore                    # 恢复媒体
POST  /api/trash/comics/{comicId}/purge                     # 永久清理（需 token）
POST  /api/trash/comics/{comicId}/chapters/{chapterId}/purge   # 永久清理章节
POST  /api/trash/media/{mediaId}/purge                      # 永久清理媒体
GET   /api/trash/{targetType}/{targetId}/reconcile          # 对账（只读）
POST  /api/trash/{targetType}/{targetId}/reconcile          # 对账并修复可安全恢复的 DB 状态
```

`purge` 请求体：`{ "token": "..." }`。永久清理前置条件：目标必须处于 `TRASHED` 状态、距 `trashed_at` 超过 7 天保留期（`RETENTION_DAYS = 7`）、token 二次确认。回收站列表通过 `GET /api/comics?status=TRASHED` 获取。

### 13.8 分块上传（Upload Session）

原始字节流上传（**非 multipart**），无 `spring.servlet.multipart` 配置。限制见 `storage.upload.*`。

```
POST   /api/uploads/sessions                     # 创建会话
GET    /api/uploads/sessions/{sessionId}         # 查询状态
PUT    /api/uploads/sessions/{sessionId}/files/{fileId}  # 上传分块
POST   /api/uploads/sessions/{sessionId}/complete       # 完成并提交
DELETE /api/uploads/sessions/{sessionId}         # 取消
```

创建会话请求：

```json
{
  "comicId": 12,
  "chapterId": 34,
  "replaceMediaId": null,
  "files": [
    { "fileId": "f1", "name": "001.jpg", "contentType": "image/jpeg",
      "size": 2048000, "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08" }
  ]
}
```

分块 PUT 请求头：

- `Content-Range: bytes {start}-{end}/{total}`（total 必须等于清单声明的 `size`）
- `X-Sha256`（可选，块校验）
- `Content-Type: application/octet-stream`，请求体为原始二进制

创建会话响应返回 `chunkSize`（默认 16 MiB）、`expiresAt`（TTL 默认 24h）、文件接收进度 `receivedRanges`。上传限制：

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `storage.upload.chunk-size` | 16 MiB | 单块大小 |
| `storage.upload.max-file-size` | 20 GiB | 单文件上限 |
| `storage.upload.max-session-size` | 100 GiB | 单会话总大小上限 |
| `storage.upload.max-files` | 10000 | 单会话文件数上限 |
| `storage.upload.session-ttl` | 24h | 会话过期时间 |
| `storage.upload.free-space-min-bytes` | 5 GiB | 磁盘剩余空间低于此值拒绝新块 |
| `storage.upload.free-space-min-ratio` | 0.10 | 磁盘剩余比例低于此值拒绝新块 |

上传文件先落入 `STAGING`（不经 Nginx 暴露，不可下载），`complete` 后由 Worker 分析并搬入 HQ，`complete` 响应返回 `taskId`（`MEDIA_UPLOAD` 任务）。

### 13.9 媒体管理

```
POST   /api/chapters/{chapterId}/media/reorder    # 重排 { mediaIds: [1,2,3] }
DELETE /api/media/{mediaId}                       # 媒体回收（MEDIA_TRASH）
```

### 13.10 Outbox 统计

```
GET /api/management/outbox/stats
```

响应 `OutboxStats`：`{ "pending": 0, "failed": 0, "total": 120 }`，用于监控消息积压。

---

## 14. 枚举域（v1.0）

### 14.1 生命周期状态

**Comic**（`ComicStatus`，`GET /api/comics` 的 `lifecycle` 字段）：

| 状态 | 含义 | 阅读列表可见 |
|------|------|-------------|
| `DRAFT` | 预创建，未开始导入 | 否 |
| `IMPORTING` | 导入进行中 | 否 |
| `IMPORT_FAILED` | 导入失败（可重试/删除） | 否 |
| `READY` | 正常可读 | 是 |
| `RECOVERY_REQUIRED` | 文件存在但 DB 记录缺失，等待恢复扫描 | 否 |
| `DELETING` | 删除排队中 | 否 |
| `TRASHING` | 回收中（文件按清单移入 TRASH） | 否 |
| `TRASHED` | 已删除但可恢复（软删除） | 否 |
| `RESTORING` | 恢复中 | 否 |
| `PURGING` | 物理删除排队中 | 否 |
| `DELETED` | 已永久删除（终态） | 否 |

**Chapter**（`ChapterLifecycleStatus`）：`DRAFT / READY / DELETING / TRASHING / TRASHED / RESTORING / PURGING / DELETED`。

**Media**（`MediaLifecycleStatus`）：`STAGING / READY / DELETING / TRASHING / TRASHED / RESTORING / PURGING / DELETED`。

### 14.2 任务状态与阶段

**ManagementTaskStatus**：`QUEUED / RUNNING / CANCELLING / CANCELLED / SUCCEEDED / PARTIALLY_SUCCEEDED / FAILED`。终态：`CANCELLED / SUCCEEDED / PARTIALLY_SUCCEEDED / FAILED`。

**TaskStage**（导入子阶段，与主状态并列）：`DOWNLOADING / EXTRACTING / PARSING`。

**TaskType**（21 个）：

```
IMPORT, RECOVERY, EXPORT, DIRECTORY_SCAN,
LQ_GENERATE, LQ_REGENERATE, HQ_DELETE, TRANSCODE,
METADATA_REFRESH, METADATA_UPDATE,
COMIC_DELETE, MEDIA_UPLOAD, MEDIA_REPLACE, MEDIA_TRASH, CHAPTER_TRASH,
COMIC_RESTORE, CHAPTER_RESTORE, MEDIA_RESTORE,
COMIC_PURGE, CHAPTER_PURGE, MEDIA_PURGE
```

### 14.3 操作名（OperationName）

`READ / EDIT / DELETE / RECOVER / PURGE / RECONCILE / IMPORT / RETRY_IMPORT / LQ_GENERATE / LQ_REGENERATE / HQ_DELETE / TRANSCODE / METADATA_REFRESH`

### 14.4 目标类型（TargetType）

`COMIC / CHAPTER / MEDIA / DIRECTORY / SYSTEM`

### 14.5 页面级状态

**HqStatus**：`PENDING / READY / MISSING / DELETE_QUEUED / DELETING / DELETED / FAILED`
**LqStatus**：`NOT_GENERATED / QUEUED / GENERATING / READY / MISSING / FAILED`
**TranscodeStatus**：`NOT_NEEDED / QUEUED / TRANSCODING / READY / FAILED`
**UploadSessionStatus**：`ACTIVE / COMPLETED / CANCELLED / EXPIRED / FAILED`
**TrashManifestStatus**：`TRASHED / COMPENSATED / PARTIAL / RESTORED / PURGED`

---

## 15. 状态迁移矩阵（ManagementStateMachine）

同状态迁移恒合法（no-op）。非法迁移返回 409，`reasonCode` 形如 `READY_TO_PURGING_FORBIDDEN`。

### Comic

```
DRAFT           → IMPORTING, TRASHING
IMPORTING       → READY, IMPORT_FAILED
IMPORT_FAILED   → IMPORTING, TRASHING
READY           → TRASHING, RECOVERY_REQUIRED
RECOVERY_REQUIRED → READY, TRASHING
DELETING        → TRASHED, RESTORING
TRASHING        → TRASHED, READY
TRASHED         → RESTORING, PURGING
RESTORING       → READY, TRASHED
PURGING         → DELETED
DELETED         → （终态）
```

### Chapter

```
DRAFT → READY, TRASHING     READY → TRASHING
DELETING → TRASHED, RESTORING   TRASHING → TRASHED, READY
TRASHED → RESTORING, PURGING    RESTORING → READY
PURGING → DELETED           DELETED →（终态）
```

### Media

```
STAGING → READY             READY → TRASHING
DELETING → TRASHED, RESTORING   TRASHING → TRASHED, READY
TRASHED → RESTORING, PURGING    RESTORING → READY
PURGING → DELETED           DELETED →（终态）
```

### HQ / LQ / Transcode

```
HQ:       PENDING → READY, MISSING      READY → MISSING, DELETE_QUEUED
          MISSING → READY, DELETE_QUEUED  DELETE_QUEUED → DELETING, READY
          DELETING → DELETED, FAILED     FAILED → DELETE_QUEUED
LQ:       NOT_GENERATED → QUEUED         QUEUED → GENERATING, FAILED
          GENERATING → READY, FAILED     READY → MISSING
          MISSING → READY, QUEUED        FAILED → QUEUED
Transcode: NOT_NEEDED → QUEUED           QUEUED → TRANSCODING, FAILED
          TRANSCODING → READY, FAILED    FAILED → QUEUED
```

---

## 16. 操作权限矩阵（OperationPolicyService）

前端不自行实现，调用 `GET /api/management/operations/...`。规则表如下：

| 目标 | 状态 | 允许操作 |
|------|------|---------|
| Comic | `DRAFT` | IMPORT, EDIT, DELETE |
| Comic | `IMPORT_FAILED` | RETRY_IMPORT, EDIT, DELETE |
| Comic | `READY` | READ, EDIT, DELETE, LQ_GENERATE, HQ_DELETE, METADATA_REFRESH |
| Comic | `RECOVERY_REQUIRED` | RECOVER, DELETE |
| Comic | `TRASHED` | RECOVER, PURGE |
| Comic | `IMPORTING/DELETING/RESTORING/PURGING/DELETED` | 全部阻塞（`"*"`） |
| Comic | `TRASHING` | RECONCILE |
| Chapter | `READY` | READ, EDIT, DELETE, LQ_GENERATE, HQ_DELETE |
| Chapter | `TRASHED` | RECOVER, PURGE |
| Media | `READY` | READ, DELETE, LQ_GENERATE, HQ_DELETE, TRANSCODE |
| Media | `STAGING` | 全部阻塞（`"*"`） |
| Media | `TRASHED` | RECOVER, PURGE |

---

## 17. 错误码与原因码

响应包装 `code` 采用 HTTP 语义：

| code | 场景 | 说明 |
|------|------|------|
| 200 | 成功 | `message: "success"` |
| 400 | 参数校验失败 / 批量操作缺 categoryId | `MethodArgumentNotValidException` / `ConstraintViolationException` |
| 409 | 状态冲突 / 数据重复 / 幂等冲突 / 批量阻塞 | `IllegalStateTransitionException`、`DuplicateKeyException`、`ConflictException`、`BatchConflictException` |
| 500 | 服务器内部错误 | `BusinessException`（默认）/ 未捕获异常 |

批量操作原因码（`data.reasonCode`，来自 `BatchReasonCode`）：

```
EMPTY_SELECTION, BATCH_SIZE_EXCEEDED, PREVIEW_TOKEN_REQUIRED,
PREVIEW_TOKEN_EXPIRED, PREVIEW_CONDITION_CHANGED, IDEMPOTENCY_CONFLICT,
OP_NOT_ALLOWED, COMIC_NOT_FOUND
```

非法状态迁移原因码：`{当前状态}_TO_{目标状态}_FORBIDDEN`。

---

## 18. Idempotency-Key 与 Outbox/Inbox

### 18.1 Idempotency-Key 请求头

以下端点接受可选 `Idempotency-Key` 头，同键同 payload 重放返回既有任务，不重复创建；同键不同 payload 返回 409（`IDEMPOTENCY_CONFLICT`）：

- `POST /api/tasks/import`
- `POST /api/management/tasks`
- `POST /api/management/batch`
- `DELETE /api/comics/{id}`
- 上传会话内部使用 `upload:{sessionId}` 作为幂等键（非请求头）

幂等记录保存在 `management_task.idempotency_key`（唯一索引）+ `idempotency_payload_hash`（SHA-256）。

### 18.2 事务性 Outbox

- 业务事务内 `OutboxService.enqueue(event, exchange, routingKey, ...)` 写 `outbox_message` 表，**业务代码不直接操作 RabbitMQ**。
- `OutboxRelay` 定时（默认每 5 秒，`outbox.relay.poll-interval-ms`）以 `FOR UPDATE SKIP LOCKED` 拉取 `PENDING` 消息，批量发布（默认 50 条/批），发布确认失败自动指数退避重试（最多 10 次，超出转 `FAILED`）。
- `outbox_message.status`：`PENDING / PUBLISHED / FAILED`。
- 保留期（`OutboxCleanupTask`）：PUBLISHED/processed 30 天、FAILED/任务 90 天（可配置 `outbox.cleanup.*`）。

### 18.3 结果 Inbox（幂等消费）

- 消费方（API 事件 handler）先查 `inbox_receipt`：`event_id` 相同且 `payload_hash` 相同 → 跳过（重复投递）；`event_id` 相同但 hash 不同 → 告警并跳过（隔离异常事件）。
- `markProcessed` 与业务更新在同一事务内，实现 exactly-once 结果落库。

### 18.4 MQ 路由表

| Exchange | RoutingKey | Queue | Consumer |
|----------|-----------|-------|----------|
| comic.management | command.requested | management.command.queue | Worker ManagementCommandDispatcher |
| comic.management | command.completed | management.result.queue | API ManagementCommandResultHandler |
| comic.management | command.failed | management.result.queue | API ManagementCommandResultHandler |
| comic.management | command.progress | management.result.queue | API ManagementCommandResultHandler |
| comic.management | command.cancel | management.cancel.queue | （已声明，待消费方） |

其余队列（导入/删除/LQ/HQ/恢复/扫描/导出/转码）见 AGENTS.md 的 RABBITMQ 表。所有主队列配置 DLX + DLQ。

---

## 19. 分页与旧 API 兼容窗口

### 19.1 分页约定

统一使用 **1-based `page` + `size`**（MyBatis-Plus `IPage`），无 `offset/limit`：

- `page` 默认 1，`size` 默认 20
- 响应形状：`{ records, total, size, current, pages }`

### 19.2 旧 API 兼容窗口

| 旧调用 | v1.0 行为 | 退役计划 |
|--------|-----------|---------|
| `DELETE /api/comics/{id}` | 改为回收（原硬删语义不再提供） | 永久删除走 `/api/trash/.../purge` |
| `DELETE /api/admin/comics/{id}?mode=DATABASE_ONLY\|DELETE_FILES` | 均重定向到回收站 | 保留兼容，未来移除 `mode` 参数 |
| `POST /api/admin/storage/scan-recover` | 同步扫描（已废弃） | 用 `POST /api/tasks/recovery` |
| `GET /api/comics/{id}/catalog` | 目录树（只读，兼容） | 目录管理用 `/api/comics/{comicId}/catalogs` |
| `POST /api/comics/batch/update` | 保留（分类/标签批量） | 批量操作建议迁移到 `/api/management/batch` |
| 阅读端接口（`/api/comics`、`/api/chapters/{id}`、`/api/history`） | 兼容，只返回阅读所需字段 | 长期保留 |
