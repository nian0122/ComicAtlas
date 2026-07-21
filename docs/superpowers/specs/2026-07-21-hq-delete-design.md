# HQ 删除设计 — 只保留 LQ

**日期**: 2026-07-21
**状态**: 已审阅
**范围**: API + Worker + 前端

---

## 1. 背景与目标

用户可以在确认 LQ 已生成且可用后，主动删除 HQ 原图以释放磁盘空间。删除后漫画仍可正常阅读（使用 LQ），但不再支持 HQ 画质。

**核心目标**:
- 提供用户主动触发删除 HQ 的功能（整本或按章）
- 删除前强制校验：所有图片页的 LQ 必须 `READY`
- 删除后前端自动降级画质设置，禁用 HQ_ONLY 模式
- 视频页（无 LQ）不被误删

**非目标**:
- 不支持 HQ 恢复（删除即永久）
- 不涉及导入时"不保留 HQ"选项（独立功能）
- 不涉及 LQ 自动生成（仍禁）

---

## 2. 约束条件

| 约束 | 来源 | 影响 |
|------|------|------|
| Worker 不直连 MySQL | AGENTS.md | 删除操作必须走 MQ 异步 |
| Worker 直接操作文件系统 | 现有架构 | Worker 负责实际删除文件 |
| 页面存储不存绝对路径 | AGENTS.md | 删除后 `hqRoot/hqPath` 设 null |
| URL 统一走 FileUrlResolver | AGENTS.md | HQ URL 自动失效 |
| 封面独立存储于 thumbs/ | 现有架构 | 封面不受 HQ 删除影响 |
| 视频无 LQ | 现有设计 | 视频 HQ 不能被删除 |

---

## 3. 数据模型变更

### 3.1 HqStatus 枚举

```java
public enum HqStatus { PENDING, READY, MISSING, DELETED }
```

新增 `DELETED`：表示 HQ 文件已被用户主动删除，不可恢复。

### 3.2 Media（page 表）字段语义

| 字段 | HQ 删除前 | HQ 删除后（IMAGE） | HQ 删除后（VIDEO） |
|------|-----------|-------------------|-------------------|
| `hqStatus` | `READY` | `DELETED` | 不变（仍 `READY`） |
| `hqRoot` | `"HQ"` | `null` | 不变 |
| `hqPath` | `"123/001/001.jpg"` | `null` | 不变 |
| `lqStatus` | `READY` | `READY` | 不变 |
| `lqRoot` | `"LQ"` | `"LQ"` | 不变 |
| `lqPath` | `"123/001/001.webp"` | `"123/001/001.webp"` | 不变 |
| `fileSize` | HQ 大小 | 不变（不更新，保留原始值） | 不变 |

**说明**：`fileSize` 保留原始 HQ 大小作为历史记录，不改为 LQ 大小。

---

## 4. 架构设计

采用与 LQ 生成完全一致的 MQ 异步架构：

```
用户点击"删除 HQ"（前端二次确认弹窗）
  ↓
API: POST /api/comics/{id}/delete-hq 或 /api/chapters/{id}/delete-hq
  ↓
HqDeleteService 前置校验（仅 IMAGE 页的 lqStatus == READY？）
  ├─ 否 → 返回 409 CONFLICT + 未就绪页列表
  └─ 是 → 发布 DeleteHqRequestedEvent → MQ
       （整本删除拆分为每章一个事件）
  ↓
Worker HqDeleteHandler 接收事件
  ├─ 扫描 hq/{comicId}/{chapterNo}/ 下所有文件
  ├─ 按扩展名过滤：只删图片（.jpg/.jpeg/.png/.webp/.gif）
  ├─ 保留视频和其他文件
  ├─ 计算释放空间 freedBytes
  └─ 发布 HqDeletedEvent → MQ
  ↓
API HqDeletedHandler 接收事件
  └─ 更新 DB：IMAGE 页 hqStatus=DELETED, hqRoot=null, hqPath=null
  ↓
前端检测到 hqStatus='DELETED'
  └─ 自动降级 qualityMode（HQ_ONLY → AUTO），禁用 HQ_ONLY 选项
```

---

## 5. 详细数据流

### 5.1 前置校验逻辑

```java
// HqDeleteService 校验流程
List<Media> imagePages = mediaMapper.selectList(
    new LambdaQueryWrapper<Media>()
        .eq(Media::getChapterId, chapterId)
        .eq(Media::getMediaType, "IMAGE"));

List<Media> notReady = imagePages.stream()
    .filter(p -> !"READY".equals(p.getLqStatus()))
    .toList();

if (!notReady.isEmpty()) {
    // 返回 409，附未就绪页号
    throw new HqDeletePreconditionException(notReady);
}
```

**重要**：校验只检查 `mediaType = IMAGE`。VIDEO 页不参与校验，因为它们没有 LQ，HQ 也不会被删除。

### 5.2 Worker 删除逻辑

```java
// HqDeleteHandler
String hqDir = Path.of(config.getMangaRoot(), 
    pathBuilder.hqDir(comicId, chapterNo)).toString();

long freedBytes = 0;
try (var stream = Files.walk(Path.of(hqDir))) {
    for (Path file : stream.filter(Files::isRegularFile).toList()) {
        String name = file.getFileName().toString().toLowerCase();
        if (isImageFile(name)) {
            freedBytes += Files.size(file);
            Files.delete(file);
        }
    }
}
```

### 5.3 扩展名白名单

| 允许删除 | 保留（不删除） |
|----------|---------------|
| `.jpg`, `.jpeg`, `.png`, `.webp`, `.gif` | `.mp4`, `.mkv`, `.webm`, `.mov`, `.avi`（视频） |
| | `.txt`, `.json`, `.aria2`（元数据/临时文件） |

---

## 6. 错误处理

| 场景 | 行为 |
|------|------|
| 前置校验失败（LQ 未全部 READY） | API 返回 409 + 未就绪页列表，前端展示具体原因 |
| HQ 目录不存在 | Worker 直接发 `HqDeletedEvent`（freedBytes=0），API 正常更新 DB |
| 删除部分文件时 IO 错误 | Worker 记录错误日志，继续删除剩余文件，最后发 `HqDeletedEvent`（带实际 freedBytes） |
| MQ 事件丢失 | DLQ 捕获，API 侧无状态更新，前端展示"删除中..."超时后提示失败 |
| Worker 崩溃 | MQ 重试（manual ACK + DLQ），最多 3 次后进死信队列 |

---

## 7. 前端行为

### 7.1 触发入口

- **漫画详情页** (`DetailPage.vue`)：新增按钮"删除 HQ"（位于现有"生成 LQ"按钮附近）
- **章节列表**：每章节行末尾增加"删除 HQ"图标按钮

### 7.2 二次确认弹窗

```
┌─────────────────────────────────────┐
│ ⚠️ 删除 HQ 确认                      │
├─────────────────────────────────────┤
│ 此操作将删除 20 页 HQ 原图（约 50MB）│
│ 删除后无法恢复，LQ 画质仍可正常阅读。 │
│                                     │
│ [取消]              [确认删除]     │
└─────────────────────────────────────┘
```

### 7.3 阅读器自适应

当 `ProgressiveImage` 的 `hq` prop 为 null（`hqStatus=DELETED` 导致 `FileUrlResolver.resolve()` 返回 null）时：

```
applyInitialSrc() {
  HQ_ONLY: currentSrc = props.hq ?? props.lq  → 自动降级到 LQ
  AUTO:    lqAvailable && props.lq → LQ       → 不受影响
  LQ_ONLY: lqAvailable && props.lq → LQ       → 不受影响
}
```

**额外**：`ReaderSettingsDrawer` 检测到当前漫画/章节 `hqStatus='DELETED'` 时：
- `HQ_ONLY` 选项置灰，tooltip 提示"HQ 已删除"
- 如果当前模式是 `HQ_ONLY`，自动切换为 `AUTO`

---

## 8. MQ 事件定义

### 8.1 DeleteHqRequestedEvent

```java
package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record DeleteHqRequestedEvent(
    UUID eventId,
    Instant occurredAt,
    Long comicId,
    Long chapterId,
    String chapterNo,
    String scope        // "COMIC" 或 "CHAPTER"（便于 Worker 日志区分）
) implements ComicEvent {}
```

**routing key**: `comic.image` / `hq.delete.requested`  
**queue**: `hq.delete.queue`

### 8.2 HqDeletedEvent

```java
package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record HqDeletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long comicId,
    Long chapterId,
    Long freedBytes,    // 释放空间（字节）
    Integer deletedCount // 删除文件数
) implements ComicEvent {}
```

**routing key**: `comic.image` / `hq.delete.completed`  
**queue**: `hq.delete.result.queue`

---

## 9. API 接口

### 9.1 删除整本 HQ

```http
POST /api/comics/{comicId}/delete-hq
```

**前置校验**：所有 IMAGE 页 `lqStatus == 'READY'`  
**成功响应**：`202 Accepted`（任务已提交）  
**失败响应**：
- `409 CONFLICT`：部分 IMAGE 页 LQ 未就绪，body 含未就绪页列表
- `404 NOT FOUND`：漫画不存在

### 9.2 删除单章 HQ

```http
POST /api/chapters/{chapterId}/delete-hq
```

同上。

---

## 10. 新增/修改文件清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `api/common/enums/HqStatus.java` | 修改 | 新增 `DELETED` |
| `api/importer/controller/HqDeleteController.java` | 新建 | 两个 POST 端点 |
| `api/importer/service/HqDeleteService.java` | 新建 | 前置校验 + 发布事件 |
| `api/importer/service/impl/HqDeleteServiceImpl.java` | 新建 | 实现 |
| `api/importer/event/HqDeletedHandler.java` | 新建 | 消费 Worker 回传事件 |
| `api/config/RabbitMqConfig.java` | 修改 | 声明 hq.delete.queue / hq.delete.result.queue |
| `comic-common/event/DeleteHqRequestedEvent.java` | 新建 | MQ 事件 DTO |
| `comic-common/event/HqDeletedEvent.java` | 新建 | MQ 事件 DTO |
| `comic-common/event/ComicEvent.java` | 修改 | 新增两个事件到 sealed interface + @JsonSubTypes |
| `worker/event/HqDeleteHandler.java` | 新建 | MQ 消费者 + 文件删除 |
| `worker/config/WorkerConfig.java` | 修改 | 无需新增字段，复用现有 mangaRoot |
| `worker/common/FilePathBuilder.java` | 无需修改 | 复用 hqDir() |
| `frontend/services/api.ts` | 修改 | 新增 `hqApi.deleteComic(id)` / `deleteChapter(id)` |
| `frontend/views/reading/DetailPage.vue` | 修改 | 新增"删除 HQ"按钮 |
| `frontend/views/reading/reader/components/ReaderSettingsDrawer.vue` | 修改 | 禁用 HQ_ONLY 当 hqStatus=DELETED |
| `frontend/stores/reader-settings-store.ts` | 修改 | 自动降级 qualityMode |

---

## 11. 与现有功能的兼容性

| 功能 | 影响 |
|------|------|
| LQ 生成 | 无冲突。LQ 未 READY 时 HQ 删除会被前置校验阻止 |
| 阅读器 ProgressiveImage | `hq=null` 时自动降级到 LQ，行为已支持 |
| FileUrlResolver | `hqRoot=null` 时返回 null，已支持 |
| 导入流程 | 无影响 |
| 视频页 | 不删除视频 HQ，视频仍可正常播放 |
| 封面 | thumbs/ 独立目录，不受影响 |

---

## 12. 后续可扩展（Phase II）

- **释放空间统计**：前端展示"已释放 XX GB"
- **Comic.hqStatus 聚合**：简化前端判断（目前需遍历 chapters）
- **操作历史记录**：记录谁、何时、删了多少 HQ
- **批量删除**：支持多本漫画批量删除 HQ

---

## 附录：状态流转

```
导入完成
  ↓
page.hqStatus = READY, page.lqStatus = NOT_GENERATED
  ↓
用户触发 LQ 生成
  ↓
page.lqStatus = READY
  ↓
用户触发删除 HQ
  ↓
page.hqStatus = DELETED, page.hqRoot = null, page.hqPath = null
  （VIDEO 页状态不变）
```
