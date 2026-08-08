# 前端 API 封装层补齐设计

**日期**: 2026-08-08
**状态**: 设计待审阅
**范围**: `frontend/src/services/api.ts` 补齐 10 个未对接控制器的 API 封装 + `frontend/src/types/index.ts` 补齐对应 TS 类型（纯前端新增，不动页面/后端）

## 背景与目标

前端-后端对接调研发现：网络代理（Vite `/api`→8000）、URL 匹配、已封装服务均正常，但 **10 个管理域控制器的 39 个端点未在前端 API 层封装**（任务中心/回收站/分块上传/批量操作/允许操作/Outbox/目录章节管理/媒体管理/Admin 补全）。本设计补齐 API 封装层与类型定义，使前端 API 层完整覆盖后端。

**目标**：`api.ts` 新增 9 个 API 对象 + 补全 `adminApi`；`types/index.ts` 补齐对应 TS 类型（字段与后端 DTO 对齐）；`pnpm build` 零错误。**不修改任何页面/组件/store 逻辑**。

## 现状分析

- 后端 97 端点 / 24 控制器；前端已对接 14 控制器
- 未对接 10 控制器（39 端点）：ManagementTask(6)、Trash(8)、Upload(5)、Batch(2)、MediaOperation(3)、Outbox(1)、CatalogManagement(5)、ChapterManagement(5)、MediaManagement(2)、Admin 补全(2)
- 后端 DTO 齐全（ManagementTaskResponse/UploadSessionStatusResponse/BatchPreviewResponse/PurgeRequest 等，字段已核对）
- 前端 `types/index.ts` 36 个接口中**缺失**上述管理域类型

## 变更设计

### 变更 1：api.ts 新增/补全 API 封装（10 个对象）

| API 对象 | 方法 | 端点 | 说明 |
|---------|------|------|------|
| `managementTaskApi` | list/get/getItems/create/cancel/retry | `/api/management/tasks[/{id}[/items|/cancel|/retry]]` | 任务中心 |
| `trashApi` | restoreComic/restoreChapter/restoreMedia/purgeComic/purgeChapter/purgeMedia/reconcile/reconcileAndRepair | `/api/trash/...` | 回收站（restore 传 body 可为空，purge 传 `PurgeRequest{token}`） |
| `uploadApi` | createSession/getSession/uploadChunk/completeSession/cancelSession | `/api/uploads/sessions...` | 分块上传（uploadChunk 需带 Content-Range 头） |
| `batchApi` | preview/submit | `/api/management/batch/preview`、`/api/management/batch` | 批量操作 |
| `mediaOperationApi` | forComic/forChapter/forMedia | `/api/management/operations/{comics|chapters|media}/{id}` | 允许操作查询 |
| `outboxApi` | stats | `/api/management/outbox/stats` | Outbox 积压 |
| `catalogManagementApi` | create/rename/move/reorder/delete | `/api/comics/{comicId}/catalogs...` | 目录管理 |
| `chapterManagementApi` | create/rename/move/reorder/trash | `/api/comics/{comicId}/chapters...` | 章节管理 |
| `mediaManagementApi` | reorder/trash | `/api/chapters/{chapterId}/media/reorder`、`/api/media/{mediaId}` | 媒体管理 |
| `adminApi` 补全 | scanRecover/deleteComic | `/api/admin/storage/scan-recover`、`/api/admin/comics/{id}` | Admin 补全 |

封装模式与现有 `comicApi`/`importApi` 一致：`(params/body) => api.get/post/put/delete(url, ...)`。

### 变更 2：types/index.ts 补齐类型（字段对齐后端 DTO）

已核对的类型映射：
- `ManagementTaskVO`：id/taskType/operation/targetType/batchId/batch/status/stage/progress/totalCount/successCount/failureCount/cancelledCount/errorMessage/attempt/version/createdAt/updatedAt/startedAt/completedAt
- `ManagementTaskItemVO`：id/taskId/targetType/targetId/operationType/status/attempt/progress/resultRefType/resultRefId/errorMessage/lockKey
- `UploadSessionStatus`：sessionId/status/totalBytes/totalFiles/expiresAt/completedAt/files（`UploadFileResponse`：fileId/name/contentType/sizeBytes/sha256/storageName/receivedBytes/receivedRanges/mediaId）
- `UploadChunkResult`：fileId/receivedBytes/complete/receivedRanges
- `UploadCompleteResult`：对应 `UploadCompleteResponse`
- `PurgeRequest`：token
- `ReconcileResult`：对应 `TrashLifecycleController` reconcile 返回
- `BatchPreviewResult`：operation/selectedCount/eligibleCount/blocked/previewToken/dangerous/expiresAt
- `BatchSubmitRequest`：operation/selection/payload/previewToken（对应 `BatchOperationRequest`）
- `MediaOperationResult`：对应 `MediaOperationController` 返回（allowedOperations/blockedReasons）
- `OutboxStats`：pending/failed/total
- `CatalogManagementRequest`/`ChapterManagementRequest`：增改移排请求体（标题/父级/排序）
- `MediaReorderRequest`：mediaIds 新顺序

类型命名沿用现有风格（`*VO`/`*Result`/`*Request`），枚举字段用字符串联合类型（如 `status: 'PENDING' | 'RUNNING' | ...`）。

### 变更 3：验证

1. `pnpm build`（tsc 类型检查 + vite 构建）零错误
2. URL 精确性：每个封装 URL 与后端 `@RequestMapping`+方法映射逐条核对
3. 类型完整性：新增类型字段与后端 DTO 逐字段比对

## 不做的事（YAGNI）

- 不动前端页面/组件/store 的业务逻辑（任务中心/回收站/上传页仍用现有逻辑，后续单独接 API）
- 不新增页面功能、不改路由
- 不改后端任何代码
- 不新增 npm 依赖

## 验证策略

1. `pnpm build`（frontend 目录）exit 0
2. URL 核对：复用调研脚本，封装后前端调用覆盖率应达 100%（97/97）
3. 类型核对：新增类型字段与后端 DTO 逐字段比对清单

## 提交规划

按依赖顺序拆 4 批（**类型先行**，API 封装依赖类型；每批 `pnpm build` 验证）：

1. `前端补齐管理域类型：management 域 TS 接口（types/index.ts，含任务/回收站/上传/批量/操作/Outbox/目录章节/媒体类型）`
2. `前端补齐管理域 API：任务中心/回收站/上传/批量（managementTaskApi/trashApi/uploadApi/batchApi）`
3. `前端补齐管理域 API：目录/章节/媒体管理 + 允许操作/Outbox/Admin 补全（catalogManagementApi/chapterManagementApi/mediaManagementApi/mediaOperationApi/outboxApi/adminApi）`
4. `收尾验证：前端 API 覆盖率 100% + pnpm build + 类型字段核对清单`

批次 2/3 均依赖批次 1 的类型，批次 4 为纯验证。

## 参考

- `frontend/src/services/api.ts`（现有封装模式）
- `frontend/src/types/index.ts`（现有类型风格）
- 后端 `api-service/.../controller/`（端点来源）+ `dto/`（字段来源）
