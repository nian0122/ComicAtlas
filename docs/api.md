# ComicAtlas API 文档 v0.5

**Base URL**: `http://localhost/api`

所有响应格式：`{ "code": 0, "message": "success", "data": ... }`

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
```
GET /api/comics/{id}/covers/candidates
PUT /api/comics/{id}/cover
{ "pageId": 123 }
```

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
```
GET /api/comics/{comicId}/chapters/{chapterId}/pages
```

### 删除
```
DELETE /api/comics/{id}
```

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

任意非终态 ──► CANCELLED（用户取消）
任意非终态 ──► FAILED（异常/超时）
FAILED ──► PENDING（retry 重置）
```

| 状态 | 含义 |
|------|------|
| `PENDING` | 任务已创建，等待 Worker 消费 |
| `PARSING` | Worker 正在解析来源（DirectoryParser / MetadataAssembler） |
| `IMPORTING` | 文件搬运到 HQ 存储中 |
| `SUCCESS` | 导入完成，metadata.json 已写入，API 侧已落库 |
| `FAILED` | 导入失败，可通过 retry 重置回 PENDING |
| `CANCELLED` | 用户主动取消 |

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
{ "items": [{ "sourceType": "ZIP", "sourcePath": "..." }, { "sourceType": "REGISTER", "sourcePath": "..." }] }

GET  /api/tasks/import/scan?path=D:/downloads  # 扫描目录
```

批量导入支持一次提交多个来源。`scan` 接口预扫描目录结构，返回可导入项列表。

---

## 8. 统计

```
GET /api/dashboard/statistics
```

---

## 9. 操作日志

```
GET /api/operations?module=&action=&page=1&size=20
```

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
POST /api/admin/rebuild                       # metadata.json 恢复数据库
POST /api/admin/comics/{id}/refresh-metadata  # 刷新漫画元数据（重新解析目录）
POST /api/admin/storage/scan-recover          # 扫描 HQ 目录并恢复/创建占位漫画
DELETE /api/admin/comics/{id}?mode=DATABASE_ONLY  # 仅删除数据库记录
```

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
