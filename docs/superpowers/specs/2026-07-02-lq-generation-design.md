# LQ 生成功能设计文档

**日期**: 2026-07-12
**版本**: 1.1
**状态**: Deferred — Phase II
**范围**: 后续的手动与批量 LQ 生成；不属于当前导入稳定性迭代

> **v1.1 决策**：当前只建设 ZIP/DIRECTORY 的可靠 MANAGED 导入，不实现 LQ 代码。LQ 的正式入口为手动生成和批量生成，不在导入完成时自动触发。图片变化检查是独立的未来能力：先定义文件指纹与“LQ 已过期”状态，再决定是否重新生成，不能以当前的路径推测代替。
>
> 本文后续的“导入时自动生成 LQ”、EHENTAI 和 EXTERNAL 叙述保留为历史草案，若与以上决策冲突，均以上述决策为准。

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [数据流](#2-数据流)
3. [数据库变更](#3-数据库变更)
4. [API 设计](#4-api-设计)
5. [MQ 消息设计](#5-mq-消息设计)
6. [Worker 端改动](#6-worker-端改动)
7. [错误处理与边界](#7-错误处理与边界)

---

## 1. 背景与目标

### 1.1 当前状态

LQ 生成基础设施已部分存在：

- **Worker**: `LqGenerateHandler` 消费 `lq.generate`，`ImageOptimizer.generateLq()` 生成 LQ 文件（当前为文件复制占位），发布 `lq.completed` 到 exchange `comic.image`
- **API**: `lq.result.queue` 已声明并绑定到 `comic.image` / `lq.completed`，但**没有消费者**——消息堆积永不被处理
- **ImportContext**: `generateLq` 字段存在但无法从前端传入，`ImportEventHandler` 中的自动触发已注释掉
- **DB**: `comic.lq_status` / `comic.lq_size` / `page.lq_status` 字段存在但仅有 `PENDING` 初始值，从未被更新

### 1.2 目标

| 场景 | 行为 |
|------|------|
| 导入时勾选"生成 LQ" | 导入完成后自动触发 LQ 生成，DB 状态正确更新 |
| 导入时未勾选 | 仅导入，不生成 LQ（默认行为） |
| 导入后手动触发 | API 按漫画触发全部章节 LQ 生成 |
| EXTERNAL 漫画 | 不支持 LQ 生成（导入时自动跳过，手动触发返回错误） |

### 1.3 约束

- 仅 MANAGED 存储策略支持 LQ（EXTERNAL 文件在原位置，不应覆写）
- 生成 LQ 不阻塞导入——导入完成时发 MQ，异步处理
- 逐章粒度处理：Worker 每次只处理一章
- 幂等：同一 `messageId` 不重复更新 DB
- 默认值：`generateLq` 默认 `false`

---

## 2. 数据流

### 2.1 导入时 LQ 触发

```
Frontend (ImportRequest.generateLq)
  │
  ▼ POST /api/tasks/import
ImportServiceImpl.createImportTask()
  │  存储 import_task.generate_lq = generateLq
  ▼
ImportEventPublisher.publishImportTaskCreated()
  │  MQ 消息携带 generateLq
  ▼  comic.import / task.created
Worker ImportTaskHandler
  │  从消息读取 generateLq → ImportContext.generateLq
  │  执行导入（ZIP/REGISTER/EHENTAI）
  │
  ▼ 导入完成（metadata.json 写毕）
  if (generateLq && storagePolicy == "MANAGED")
  │  逐章发送 lq.generate
  ▼  comic.image / lq.generate
LqGenerateHandler (已有，小修复)
  │  生成 LQ 文件（文件复制）
  ▼  comic.image / lq.completed
LqCompletedHandler (🆕)
  │  Redis SETNX 幂等
  │  BATCH UPDATE page.lq_status
  │  UPDATE comic.lq_status / comic.lq_size
  └── 完成
```

### 2.2 手动触发 LQ

```
Frontend / 外部调用
  │
  ▼ POST /api/comics/{comicId}/generate-lq
ComicController.generateLq()
  │  ComicService.triggerLqGeneration(comicId)
  │  ├─ 校验: comic 存在 && storagePolicy != EXTERNAL
  │  ├─ 查询所有 chapter
  │  └─ 逐章发送 lq.generate
  ▼
  ... 后续与导入时触发相同 ...
```

---

## 3. 数据库变更

### 3.1 import_task 表

```sql
ALTER TABLE import_task ADD COLUMN generate_lq TINYINT(1) DEFAULT 0 AFTER source_path;
```

### 3.2 ImportTask 实体

```java
@TableName("import_task")
public class ImportTask {
    // ... 现有字段 ...
    private Boolean generateLq;  // 🆕
}
```

---

## 4. API 设计

### 4.1 ImportRequest 扩展

```java
@Data
public class ImportRequest {
    private String sourceRef;
    private String sourceType;
    private String sourcePath;
    private Boolean generateLq;       // 🆕 默认 false
}
```

### 4.2 ImportServiceImpl 改动

`createImportTask()` 方法中，创建 `ImportTask` 时存储 `generateLq`：

```java
task.setGenerateLq(request.getGenerateLq() != null && request.getGenerateLq());
```

### 4.3 ImportEventPublisher 改动

`publishImportTaskCreated()` 方法签名新增 `generateLq` 参数：

```java
public void publishImportTaskCreated(
    Long taskId, Long comicId, String sourceRef,
    String sourceType, String sourcePath, Boolean generateLq  // 🆕
)
```

### 4.4 POST /api/comics/{comicId}/generate-lq（新增）

**请求**: `POST /api/comics/{comicId}/generate-lq`

**响应**: `200 OK`

**逻辑**:
1. 查询 comic，验证存在且 `status = 'READY'`
2. 验证 `storagePolicy != 'EXTERNAL'`，否则返回 400 "EXTERNAL 漫画不支持 LQ 生成"
3. 查询该 comic 所有 chapter
4. 逐章发送 `lq.generate` MQ 消息
5. 返回 `{ "triggered": true, "chapterCount": N }`

### 4.5 LqCompletedHandler（新增）

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class LqCompletedHandler {

    private final PageMapper pageMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Transactional
    @RabbitListener(queues = "lq.result.queue")
    @SuppressWarnings("unchecked")
    public void handleLqCompleted(Map<String, Object> msg) {
        String messageId = (String) msg.get("messageId");
        // 1. 幂等检查
        String idempKey = "mq:msg:" + messageId;
        if (Boolean.FALSE.equals(redisTemplate.opsForValue()
                .setIfAbsent(idempKey, "1", Duration.ofDays(1)))) {
            log.info("LQ 消息已处理，跳过: messageId={}", messageId);
            return;
        }

        Long comicId = Long.valueOf(msg.get("comicId").toString());
        Long chapterId = Long.valueOf(msg.get("chapterId").toString());
        List<Integer> failedPages = (List<Integer>) msg.get("failedPages");

        // 2. 查 chapter 确认存在
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            log.warn("LQ completed: chapter 不存在 chapterId={}", chapterId);
            return;
        }

        // 3. 批量更新 page.lq_status
        // 3a. 成功页（除 failedPages 外的所有页面）: PENDING → READY
        var updateWrapper = new LambdaUpdateWrapper<Page>()
            .eq(Page::getChapterId, chapterId)
            .eq(Page::getLqStatus, "PENDING");
        if (failedPages != null && !failedPages.isEmpty()) {
            updateWrapper.notIn(Page::getPageNumber, failedPages);
        }
        updateWrapper.set(Page::getLqStatus, "READY");
        pageMapper.update(null, updateWrapper);

        // 3b. 失败页: PENDING → FAILED
        if (failedPages != null && !failedPages.isEmpty()) {
            var failWrapper = new LambdaUpdateWrapper<Page>()
                .eq(Page::getChapterId, chapterId)
                .in(Page::getPageNumber, failedPages)
                .eq(Page::getLqStatus, "PENDING");
            failWrapper.set(Page::getLqStatus, "FAILED");
            pageMapper.update(null, failWrapper);
        }

        // 4. 更新 comic.lq_status
        Comic comic = comicMapper.selectById(comicId);
        if (comic != null) {
            boolean hasFailed = failedPages != null && !failedPages.isEmpty();
            comic.setLqStatus(hasFailed ? "PARTIAL" : "READY");
            comicMapper.updateById(comic);
        }

        log.info("LQ completed 处理完成: comicId={}, chapterId={}, failedPages={}",
            comicId, chapterId, failedPages);
    }
}
```

---

## 5. MQ 消息设计

### 5.1 现有消息（无变更）

| Exchange | RoutingKey | Queue | 消费者 | 流向 |
|----------|-----------|-------|--------|------|
| `comic.image` | `lq.generate` | `lq.generate.queue` | `LqGenerateHandler` (Worker) | API → Worker |
| `comic.image` | `lq.completed` | `lq.result.queue` | `LqCompletedHandler` 🆕 | Worker → API |

### 5.2 lq.generate 消息体

```json
{
  "messageId": "uuid",
  "comicId": 32,
  "chapterId": 2
}
```

### 5.3 lq.completed 消息体（修复后）

```json
{
  "messageId": "uuid",
  "comicId": 32,
  "chapterId": 2,
  "totalPages": 24,
  "failedPages": []
}
```

**修复点**: `totalPages` 从当前写死的 `0` 改为实际页数（`LqGenerateHandler` 中从 hq 目录统计）。

---

## 6. Worker 端改动

### 6.1 ImportTaskHandler

从 MQ 消息中读取 `generateLq` 字段，传入 `ImportContext`（不再传 `false` 硬编码）。

导入完成后（metadata.json 写毕），检查 `generateLq` 且 `storagePolicy == MANAGED`：

```java
if (ctx.generateLq() && "MANAGED".equals(metadata.storagePolicy())) {
    for (var chapter : metadata.chapters()) {
        Map<String, Object> lqMsg = new LinkedHashMap<>();
        lqMsg.put("messageId", UUID.randomUUID().toString());
        lqMsg.put("comicId", comicId);
        lqMsg.put("chapterId", chapterId);
        rabbitTemplate.convertAndSend("comic.image", "lq.generate", lqMsg);
    }
}
```

### 6.2 LqGenerateHandler（修复）

- `totalPages` 从硬编码 `0` → 实际统计 `Files.list(Path.of(hqDir)).count()`
- 其余逻辑不变

---

## 7. 错误处理与边界

### 7.1 EXTERNAL 漫画

- **导入时**: `generateLq=true` 但 `storagePolicy=EXTERNAL` → Worker 导入完成后检查 `storagePolicy`，跳过 LQ 生成
- **手动触发**: API 层校验，`storagePolicy=EXTERNAL` 返回 `400 "EXTERNAL 漫画不支持 LQ 生成"`

### 7.2 LQ 生成失败

- `ImageOptimizer.generateLq()` 返回 `failedPages` 列表
- `LqCompletedHandler` 将 failedPages 的 `lq_status` 设为 `FAILED`
- `comic.lq_status` 设为 `PARTIAL`（有失败）或 `READY`（全部成功）

### 7.3 幂等性

- Redis key: `mq:msg:{messageId}`，TTL 1 天
- 与 `ImportEventHandler` 保持一致

### 7.4 并发

- Worker 逐章发送 `lq.generate`，`LqGenerateHandler` 单线程逐章消费
- 同一 comic 的多个章节可能并发处理 → `LqCompletedHandler` 使用 `@Transactional` 保证单章内一致性
- comic.lq_status 最终一致：最后完成的章节写入的状态覆盖之前的

### 7.5 EHENTAI 导入

- EHENTAI 导入流程与其他类型相同：Worker 完成导入后检查 `generateLq`
- `storagePolicy` 由 Worker 最终确定（通常为 `MANAGED`）
- 如 EHENTAI 下载的文件位于外部目录（`EXTERNAL`），同样跳过 LQ 生成

---

## 变更文件清单

| 服务 | 文件 | 变更类型 |
|------|------|---------|
| DB | `schema.sql` / migration | 加列 `import_task.generate_lq` |
| API | `ImportTask.java` | 加字段 `generateLq` |
| API | `ImportRequest.java` | 加字段 `generateLq` |
| API | `ImportServiceImpl.java` | 存储 `generateLq`，传参到 MQ |
| API | `ImportEventPublisher.java` | 方法签名加 `generateLq` |
| API | `LqCompletedHandler.java` | 🆕 消费 `lq.result.queue` |
| API | `ComicController.java` | 🆕 `POST /api/comics/{id}/generate-lq` |
| API | `ComicService.java` / `ComicServiceImpl.java` | 🆕 `triggerLqGeneration(comicId)` |
| Worker | `ImportTaskHandler.java` | 读 `generateLq`，导入完成后发 `lq.generate` |
| Worker | `LqGenerateHandler.java` | `totalPages` 修复为实际页数 |
| Frontend | `ImportRequest` 类型 | 加 `generateLq` 字段 |
