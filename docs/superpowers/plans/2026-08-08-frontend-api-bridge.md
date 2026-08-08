# 前端 API 封装层补齐实施计划

**状态**: 待执行

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 `frontend/src/services/api.ts` 中 10 个未对接控制器的 API 封装 + `frontend/src/types/index.ts` 对应 TS 类型，使前端 API 层覆盖后端全部 97 端点。不动页面/后端。

**Architecture:** 4 批（类型先行 → 任务/回收站/上传/批量 API → 目录/章节/媒体/操作/Outbox/Admin API → 收尾验证），每批独立提交 + `pnpm build` 门禁。

**Design spec:** `docs/superpowers/specs/2026-08-08-frontend-api-bridge-design.md`

## Global Constraints

- **不改任何前端页面/组件/store 业务逻辑**：只新增 `api.ts` 的 API 对象与 `types/index.ts` 的类型定义。
- **封装模式一致**：与现有 `comicApi`/`importApi` 相同——`(params/body) => api.get/post/put/delete(url, ...)`；`readonly` 字段风格与 types 现有一致。
- **类型字段对齐后端 DTO**：每个新类型字段名/类型与 `api-service/.../dto/` 对应类逐字段核对；枚举用字符串联合类型。
- **URL 精确**：每个端点路径与后端 `@RequestMapping` + `@GetMapping/@PostMapping/...` 精确拼接（含 `{pathVar}`）。
- **验证门禁**：每批 `pnpm build`（frontend 目录）exit 0。
- 提交信息中文"动作 + 内容"；每批 `git status` 确认只含 `frontend/src/services/api.ts`、`frontend/src/types/index.ts` 与新增文件。

## 已核对的后端端点与 DTO（实现依据）

**ManagementTaskController**（`/api/management/tasks`）：GET 列表、GET `/{id}`、GET `/{id}/items`、POST 创建、POST `/{id}/cancel`、POST `/{id}/retry`
- DTO：`ManagementTaskResponse`（id/taskType/operation/targetType/batchId/batch/status/stage/progress/totalCount/successCount/failureCount/cancelledCount/errorMessage/attempt/version/createdAt/updatedAt/startedAt/completedAt）、`ManagementTaskItemResponse`（id/taskId/targetType/targetId/operationType/status/attempt/progress/resultRefType/resultRefId/errorMessage/lockKey）、`CreateManagementTaskRequest`（先读字段再定义）

**TrashLifecycleController**（`/api/trash`）：POST `/{comics|chapters|media}/{id}/restore`、POST `/{comics|chapters|media}/{id}/purge`、GET `/{targetType}/{targetId}/reconcile`、POST `/{targetType}/{targetId}/reconcile`
- DTO：`PurgeRequest`（token）——purge 需传 `{ token }`

**UploadController**（`/api/uploads`）：POST `/sessions`、GET `/sessions/{sessionId}`、PUT `/sessions/{sessionId}/files/{fileId}`、POST `/sessions/{sessionId}/complete`、DELETE `/sessions/{sessionId}`
- DTO：`CreateUploadSessionRequest`（comicId/chapterId/replaceMediaId/files[fileId/name/contentType/size/sha256]）、`CreateUploadSessionResponse`、`UploadSessionStatusResponse`（sessionId/status/totalBytes/totalFiles/expiresAt/completedAt/files）、`UploadFileResponse`（fileId/name/contentType/sizeBytes/sha256/storageName/receivedBytes/receivedRanges/mediaId）、`UploadChunkResponse`（fileId/receivedBytes/complete/receivedRanges）、`UploadCompleteResponse`
- 注意：uploadChunk 需 `Content-Range` 头（读 `UploadController` 确认请求头格式）

**BatchOperationController**（`/api/management/batch`）：POST `/preview`、POST `/`（提交）
- DTO：`BatchPreviewResponse`（operation/selectedCount/eligibleCount/blocked/previewToken/dangerous/expiresAt）、`BatchOperationRequest`（operation/selection/payload/previewToken）

**MediaOperationController**（`/api/management/operations`）：GET `/comics/{comicId}`、GET `/chapters/{chapterId}`、GET `/media/{mediaId}`

**OutboxStatsController**（`/api/management/outbox`）：GET `/stats` → OutboxStatsDTO（pending/failed/total）

**CatalogManagementController**（`/api/comics/{comicId}/catalogs`）：POST 创建、PATCH `/{catalogId}` 重命名、PUT `/{catalogId}/move`、PUT `/{catalogId}/reorder`、DELETE `/{catalogId}`
- DTO：`CatalogCreateRequest`/`CatalogRenameRequest`/`CatalogMoveRequest`/`CatalogReorderRequest`（读字段）

**ChapterManagementController**（`/api/comics/{comicId}/chapters`）：POST 创建、PATCH `/{chapterId}` 重命名、PUT `/{chapterId}/move`、PUT `/{chapterId}/reorder`、DELETE `/{chapterId}`
- DTO：`ChapterCreateRequest`/`ChapterRenameRequest`/`ChapterMoveRequest`/`ChapterReorderRequest`（读字段）

**MediaManagementController**（`/api`）：POST `/chapters/{chapterId}/media/reorder`、DELETE `/media/{mediaId}`
- DTO：`MediaReorderRequest`/`MediaReorderResponse`

**AdminController**（`/api/admin`）：POST `/storage/scan-recover`、DELETE `/comics/{id}`

---

### Task 1: 类型先行（types/index.ts 补齐 management 域类型）

**Files (Modify):**
- `frontend/src/types/index.ts`

- [ ] **Step 1: 读后端全部相关 DTO**
- 读 `api-service/.../management/dto/`（ManagementTaskResponse/ItemResponse/CreateRequest）、`management/trash/`（PurgeRequest、reconcile 返回）、`upload/dto/`（Create/Status/File/Chunk/Complete）、`management/batch/dto/`（Preview/Request/SelectionVO/PayloadDTO）、`management/operation/`（允许操作返回）、`outbox/`（OutboxStatsDTO）、`comic/dto/`（Catalog/Chapter 管理 Request）、`upload/dto/`（MediaReorder）。
- [ ] **Step 2: 在 types/index.ts 追加 management 域类型**
- 新增：`ManagementTaskVO`、`ManagementTaskItemVO`、`CreateManagementTaskRequest`、`UploadSessionStatus`、`UploadFileStatus`、`UploadChunkResult`、`UploadCompleteResult`、`CreateUploadSessionRequest`、`TrashPurgeRequest`、`ReconcileResult`、`BatchPreviewResult`、`BatchSubmitRequest`、`MediaOperationResult`、`OutboxStats`、`CatalogManagementRequest`、`ChapterManagementRequest`、`MediaReorderRequest`/`MediaReorderResult`
- 命名沿用现有风格（`*VO`/`*Result`/`*Request`），枚举用字符串联合类型，字段 `readonly` 与现有一致。
- [ ] **Step 3: 验证 + 提交**
```bash
pnpm build   # frontend 目录，exit 0
git add frontend/src/types/index.ts
git commit -m "前端补齐管理域类型：management 域 TS 接口（任务/回收站/上传/批量/操作/Outbox/目录章节/媒体）"
```

---

### Task 2: 任务中心/回收站/上传/批量 API

**Files (Modify):**
- `frontend/src/services/api.ts`

- [ ] **Step 1: 新增 managementTaskApi / trashApi / uploadApi / batchApi**
- `managementTaskApi`：list(params)/get(id)/getItems(id)/create(data)/cancel(id)/retry(id)
- `trashApi`：restore(targetType, id)/purge(targetType, id, token)/reconcile(targetType, targetId)/reconcileAndRepair(targetType, targetId)——注意 purge 请求体 `{ token }`、restore 是否空 body（读 `TrashLifecycleController` 确认）
- `uploadApi`：createSession(data)/getSession(sessionId)/uploadChunk(sessionId, fileId, chunk, contentRange)/completeSession(sessionId)/cancelSession(sessionId)——uploadChunk 需 `Content-Range` 头与原始字节（读 `UploadController` 确认请求参数）
- `batchApi`：preview(data)/submit(data)
- 复用 types 中 Task 1 新增类型。
- [ ] **Step 2: 验证 + 提交**
```bash
pnpm build
git add frontend/src/services/api.ts
git commit -m "前端补齐管理域 API：任务中心/回收站/上传/批量（managementTask/trash/upload/batch）"
```

---

### Task 3: 目录/章节/媒体管理 + 操作/Outbox/Admin API

**Files (Modify):**
- `frontend/src/services/api.ts`

- [ ] **Step 1: 新增 catalogManagementApi / chapterManagementApi / mediaManagementApi / mediaOperationApi / outboxApi + 补全 adminApi**
- `catalogManagementApi`：create(comicId, data)/rename(comicId, catalogId, data)/move(comicId, catalogId, data)/reorder(comicId, catalogId, data)/delete(comicId, catalogId)
- `chapterManagementApi`：create(comicId, data)/rename(comicId, chapterId, data)/move(comicId, chapterId, data)/reorder(comicId, chapterId, data)/trash(comicId, chapterId)
- `mediaManagementApi`：reorder(chapterId, data)/delete(mediaId)
- `mediaOperationApi`：forComic(comicId)/forChapter(chapterId)/forMedia(mediaId)
- `outboxApi`：stats()
- `adminApi` 补全：scanRecover()/deleteComic(id, mode)
- [ ] **Step 2: 验证 + 提交**
```bash
pnpm build
git add frontend/src/services/api.ts
git commit -m "前端补齐管理域 API：目录/章节/媒体管理及操作/Outbox/Admin 补全"
```

---

### Task 4: 收尾验证

- [ ] **Step 1: API 覆盖率核对**
- 复用调研脚本（后端 97 端点 vs 前端调用），封装后前端 URL 覆盖率应达 100%。
- 重点核验：ManagementTask/Trash/Upload/Batch/Operations/Outbox/CatalogMgmt/ChapterMgmt/MediaMgmt/Admin 端点均有前端调用。
- [ ] **Step 2: 类型字段核对**
- 抽查 3-5 个新类型与后端 DTO 逐字段比对（字段名/类型一致）。
- [ ] **Step 3: 全量门禁**
```bash
pnpm build   # exit 0
git log --oneline -5
git status --short   # 干净
```
- [ ] **Step 4: 汇总**
- 输出 4 批提交清单 + 覆盖率统计 + build 结果到最终报告。
