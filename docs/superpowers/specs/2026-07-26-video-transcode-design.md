# 视频转码补偿接口设计

**日期**: 2026-07-26
**状态**: 已审阅
**范围**: API + Worker + 前端 + DB

---

## 1. 背景与目标

导入时 `VideoNormalizer` 将非标准视频格式（wmv/flv/av/mov/mkv/ts 等）转码为浏览器兼容的 mp4（H.264+AAC）。在 `VideoNormalizer` 实现之前导入的视频漫画，仍存在非 mp4/webm 格式的视频文件，浏览器无法播放。

**核心目标**：
- 提供补偿接口，按漫画批量转码旧视频为 mp4
- 与现有 DeleteHQ/GenerateLQ 保持一致的异步事件架构
- 前端存储管理页面可触发并查看转码状态

**非目标**：
- 不涉及视频元数据修复（已有 `AdminServiceImpl.fixVideoMetadata()`）
- 不涉及封面重新生成
- 不涉及流媒体转码（HLS/DASH）

---

## 2. 架构原则

| 角色 | 读 MySQL | 写 MySQL | transcode_status | 操作文件系统 |
|------|---------|---------|-----------------|------------|
| API | ✅ | ✅ | ✅（唯一写入方） | ❌ |
| Worker | ✅（只读） | ❌ | ❌ | ✅ |

> 与现有架构一致：Worker 读 DB 获取待处理页面的文件路径，执行 ffmpeg 转码，完成后通过 MQ 事件回 API 更新 `transcode_status` 和新的 `hq_path`/codec 字段。

---

## 3. 数据模型

### 3.1 page 表新增列

```sql
ALTER TABLE page ADD COLUMN transcode_status VARCHAR(16) NOT NULL DEFAULT 'NOT_NEEDED';
```

### 3.2 transcode_status 枚举与状态机

**状态转移**：`NOT_NEEDED | FAILED → PENDING → DONE | FAILED`

> 注意：没有独立的 `PROCESSING` 状态。`PENDING` 同时表示"已提交"和"Worker 正在处理"。Worker 是幂等的——它不写 DB，只处理文件并通过 MQ 回传结果。

| 值 | 含义 | 设置时机 | UI 显示 |
|---|------|---------|---------|
| `NOT_NEEDED` | 不需要转码（图片 或 已是 mp4/webm） | 导入时 | — |
| `PENDING` | 转码中（已提交，Worker 处理中） | API `transcodeVideos()` CAS 写入 | 转码中（warning） |
| `DONE` | 转码完成，视频已替换为 mp4 | API `TranscodeCompletedHandler` | 已完成（success） |
| `FAILED` | 转码失败，原文件保留 | API `TranscodeFailedHandler` | 失败（danger） |

**PENDING 语义**：
- PENDING = "任务已提交到 MQ，Worker 正在处理或等待处理"
- 不支持取消。PENDING 记录会一直保持直到 Worker 返回 completed/failed 事件
- re-trigger（重新调用 `transcodeVideos`）是安全的：CAS 保护确保已 PENDING 的记录不会被覆盖或重复入队
- 历史 PENDING 记录：若 Worker 已完成但 completed 事件未持久化（如 DLQ 积压），可通过 DLQ 重放 completed 事件将 PENDING → DONE

### 3.3 历史数据迁移

```sql
UPDATE page SET transcode_status = 'PENDING'
WHERE media_type = 'VIDEO' AND container NOT IN ('mp4', 'webm');
```

---

## 4. API

### 4.1 POST /api/admin/comics/{comicId}/transcode-videos

**描述**：触发漫画的视频转码补偿任务。扫描所有待转码视频，标记为 PENDING，发 MQ。

**响应**：
```json
{
  "comicId": 92,
  "totalVideoPages": 15,
  "pendingCount": 3,
  "alreadyDone": 10,
  "processingCount": 0
}
```

**业务规则**：
- `container IN ('mp4', 'webm')` 的视频跳过
- CAS 保护：仅当 `transcode_status IN ('NOT_NEEDED', 'FAILED')` 时更新为 `PENDING`
- 已是 `PENDING` 的视频跳过（计入 `processingCount`，防止重复入队）
- 已是 `DONE` 的视频跳过（计入 `alreadyDone`）

---

## 5. MQ 事件

### 5.1 事件定义

| 事件 | RoutingKey | Queue | Consumer |
|------|-----------|-------|----------|
| `VideoTranscodeRequestedEvent` | `comic.video.transcode.requested` | `video.transcode.queue` | Worker |
| `VideoTranscodeCompletedEvent` | `comic.video.transcode.completed` | `video.transcode.result.queue` | API |
| `VideoTranscodeFailedEvent` | `comic.video.transcode.failed` | `video.transcode.result.queue` | API |

### 5.2 comic-common DTO

```java
// VideoTranscodeRequestedEvent
record(Long comicId, List<Long> pageIds) implements ComicEvent {}

// VideoTranscodeCompletedEvent
record(Long pageId, String newHqPath,
       String container, String videoCodec, String audioCodec,
       Long fileSize) implements ComicEvent {}

// VideoTranscodeFailedEvent
record(Long pageId, String errorMessage) implements ComicEvent {}
```

### 5.3 Exchange / Queue / DLX

```
Exchange: comic.video
Queues: video.transcode.queue → DLX comic.video.dlx → video.transcode.dlq
        video.transcode.result.queue → 同上
```

---

## 6. Worker 处理

### 6.1 VideoTranscodeHandler

消费 `requested` 事件，逐页转码：

1. 从 DB 读 `hq_root + hq_path` 定位源文件
2. ffmpeg 转码到 temp 目录：
   ```
   ffmpeg -i {src} -c:v libx264 -crf 23 -preset medium 
          -c:a aac -b:a 128k -movflags +faststart 
          {tempDir}/{pageId}.mp4
   ```
3. 校验 temp 文件存在且大小 > 0
4. **替换原文件**：删原文件 → temp 搬入 HQ 目录
5. 发 `completed`（含 `newHqPath`、codec 信息）

**失败处理**：保留原文件，发 `failed` 事件。DLX 死信队列兜底。

### 6.2 并发控制

- Prefetch = 1：同一 Worker 实例单线程处理
- 不同漫画之间可多实例并行（HQ 路径不冲突）

---

## 7. API 事件处理

### 7.1 TranscodeCompletedHandler

消费 `completed` 事件，更新 `page` 表：

**前置检查**：`transcode_status = 'PENDING'` 才处理，否则 ack 并跳过（幂等保护）。
**更新**：
```sql
UPDATE page SET
  hq_path = #{newHqPath},
  container = #{container},
  video_codec = #{videoCodec},
  audio_codec = #{audioCodec},
  file_size = #{fileSize},
  transcode_status = 'DONE'
WHERE id = #{pageId}
```

> 由于 PENDING 状态本身既是"已提交"也是"处理中"，handler 只需要检查 `= 'PENDING'` 即可。历史 PENDING 记录（Worker 已完成但事件未持久化）可通过 DLQ 重放完成事件来收敛为 DONE。

### 7.2 TranscodeFailedHandler

消费 `failed` 事件：

```sql
UPDATE page SET transcode_status = 'FAILED' WHERE id = #{pageId}
```

---

## 8. 前端改动

### 8.1 类型扩展

```typescript
// ComicStorageItem 新增
transcodeStatus: 'NOT_NEEDED' | 'PENDING' | 'DONE' | 'FAILED'
```

### 8.2 StorageTable.vue

- 新增 "转码" 列：`transcodeStatus !== 'NOT_NEEDED'` 时显示状态标签
- 操作列新增 "转码" 按钮：`transcodeStatus === 'PENDING' || transcodeStatus === 'FAILED'` 时显示

### 8.3 StorageStatusTag.vue

新增 `transcode` 类型标签映射：`PENDING`(warning) / `DONE`(success) / `FAILED`(danger)

### 8.4 StoragePage.vue

- `handleTranscodeVideos(comicId)` → API 调用 → polling 更新
- 按钮 disabled + busyState 轮询，模式同 DeleteHQ/GenerateLQ

### 8.5 API 服务

```typescript
// services/storage.ts
transcodeVideos(comicId: number): Promise<TranscodeResult>
```

---

## 9. 存储聚合查询

`ComicStorageDTO` 新增 `transcodeStatus`，`StorageQueryService` 聚合逻辑：

- 所有视频页 `NOT_NEEDED` → `NOT_NEEDED`
- 存在 `PENDING` → `PENDING`
- 存在 `FAILED` 且无待处理 → `FAILED`
- 全部 `DONE` → `DONE`

---

## 10. 历史 PENDING 记录处理

### 10.1 问题背景

初始设计中存在 `PROCESSING` 状态，后简化为 `NOT_NEEDED|FAILED → PENDING → DONE|FAILED`。历史数据迁移（`UPDATE page SET transcode_status = 'PENDING'`）直接产生了大量 PENDING 记录。

### 10.2 收敛方案

**方案 A：DLQ 重放已完成事件**

若 Worker 已完成转码但 completed 事件因 DLX 路由进入死信队列未消费，可从 DLQ 重放：
- `TranscodeCompletedHandler` 已接受 `PENDING` 状态（前置检查 `= 'PENDING'`）
- 重放后 PENDING → DONE，幂等安全

**方案 B：re-trigger（CAS 保护）**

调用 `POST /api/admin/comics/{comicId}/transcode-videos`：
- CAS 条件 `transcode_status IN ('NOT_NEEDED', 'FAILED')` → 已 PENDING 的记录**不会**被覆盖
- 已 PENDING 的记录被计入 `processingCount`，不会重复发送 MQ
- 因此 re-trigger 是安全的——不会创建重复任务

### 10.3 验证方式

```sql
-- 查看当前 PENDING 记录数
SELECT COUNT(*) FROM page WHERE transcode_status = 'PENDING';

-- 确认 Worker 已完成（PENDING 但文件已是 mp4）
SELECT p.id, p.hq_path, p.container
FROM page p
WHERE p.transcode_status = 'PENDING' AND p.container = 'mp4';
```

> 若 `container = 'mp4'` 但 `transcode_status = 'PENDING'`，说明 completed 事件未被消费，可通过 DLQ 重放修复。

---
