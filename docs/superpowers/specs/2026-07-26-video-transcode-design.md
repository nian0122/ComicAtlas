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

### 3.2 transcode_status 枚举

| 值 | 含义 | 设置时机 |
|---|------|---------|
| `NOT_NEEDED` | 不需要转码（图片 或 已是 mp4/webm） | 导入时 / 转码完成 |
| `PENDING` | 待转码 | API 标记 |
| `PROCESSING` | 转码中 | Worker 开始处理 |
| `DONE` | 转码完成 | API 收到完成事件 |
| `FAILED` | 转码失败 | API 收到失败事件 |

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
- `transcode_status = 'PROCESSING'` 的视频跳过（防止重复）
- 已标记 `PENDING/DONE` 的视频不重复入队，但前端可显示状态

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
transcodeStatus: 'NOT_NEEDED' | 'PENDING' | 'PROCESSING' | 'DONE' | 'FAILED'
```

### 8.2 StorageTable.vue

- 新增 "转码" 列：`transcodeStatus !== 'NOT_NEEDED'` 时显示状态标签
- 操作列新增 "转码" 按钮：`transcodeStatus === 'PENDING' || transcodeStatus === 'FAILED'` 时显示

### 8.3 StorageStatusTag.vue

新增 `transcode` 类型标签映射：`PENDING`(warning) / `PROCESSING`(warning) / `DONE`(success) / `FAILED`(danger)

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
- 存在 `PROCESSING` → `PROCESSING`
- 存在 `FAILED` 且无待处理 → `FAILED`
- 全部 `DONE` → `DONE`

---
