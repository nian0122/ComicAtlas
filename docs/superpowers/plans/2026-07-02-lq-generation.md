# LQ 生成功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现导入时标注是否生成 LQ + 导入后手动按漫画触发 LQ 生成，含缺失的 `lq.completed` 消费者

**Architecture:** `generateLq` 标记持久化到 `import_task` 表，经 MQ 传递至 Worker，导入完成后由 Worker 自行触发 LQ（不走 API ImportEventHandler）。新增 `LqCompletedHandler` 消费 `lq.result.queue` 更新 DB 状态。新增 API `POST /api/comics/{id}/generate-lq` 支持手动触发。

**Tech Stack:** Spring Boot 3 + MyBatis Plus + RabbitMQ + Redis + Lombok

---

### Task 1: DB Migration — import_task 加 generate_lq 列

**Files:**
- Modify: `api-service/src/main/resources/db/schema.sql:103-104`
- Create: `api-service/src/main/resources/db/migration/V3__lq_generation.sql`

- [ ] **Step 1: 更新 schema.sql — import_task 定义添加 generate_lq 列**

在 `schema.sql` 第 104 行 `source_path VARCHAR(1024) DEFAULT NULL,` 之后插入：

```sql
    generate_lq TINYINT(1) DEFAULT 0,
```

- [ ] **Step 2: 创建 migration 文件**

新建 `api-service/src/main/resources/db/migration/V3__lq_generation.sql`：

```sql
-- V3: LQ generation feature
ALTER TABLE import_task ADD COLUMN generate_lq TINYINT(1) DEFAULT 0 AFTER source_path;
```

- [ ] **Step 3: 执行 migration（本地开发环境）**

```bash
mysql -u root -p comic_atlas < api-service/src/main/resources/db/migration/V3__lq_generation.sql
```

验证: `DESCRIBE import_task;` 应看到 `generate_lq` 列

- [ ] **Step 4: Commit**

```bash
git add api-service/src/main/resources/db/schema.sql api-service/src/main/resources/db/migration/V3__lq_generation.sql
git commit -m "feat: import_task 表添加 generate_lq 列"
```

---

### Task 2: API — ImportTask 实体加 generateLq 字段

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/entity/ImportTask.java:29`

- [ ] **Step 1: 在 ImportTask 实体添加字段**

在 `// ... 现有字段 ...` 位置（`errorMessage` 字段上方或 `sourcePath` 字段下方）添加：

```java
private Boolean generateLq;
```

完整变更（在 `sourcePath` 之后）：

```java
    private String sourcePath;
    private Boolean generateLq;       // 🆕
    private String status;
```

- [ ] **Step 2: 验证编译**

```bash
cd api-service && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/importer/entity/ImportTask.java
git commit -m "feat: ImportTask 实体添加 generateLq 字段"
```

---

### Task 3: API — ImportRequest DTO 加 generateLq 字段

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/dto/ImportRequest.java:6-9`

- [ ] **Step 1: 添加字段**

在 `ImportRequest` 类的 `sourcePath` 之后添加：

```java
    private Boolean generateLq;       // 🆕 默认 false（null 时视为 false）
```

完整文件：

```java
package com.comicatlas.api.importer.dto;

import lombok.Data;

@Data
public class ImportRequest {
    private String sourceRef;    // EHENTAI: gallery URL
    private String sourceType;   // EHENTAI / ZIP / REGISTER
    private String sourcePath;   // ZIP: file path, REGISTER: dir path
    private Boolean generateLq;  // 🆕 导入时是否生成 LQ，默认 false
}
```

- [ ] **Step 2: 验证编译**

```bash
cd api-service && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/importer/dto/ImportRequest.java
git commit -m "feat: ImportRequest DTO 添加 generateLq 字段"
```

---

### Task 4: API — ImportEventPublisher 传递 generateLq

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/event/ImportEventPublisher.java:15-17`

- [ ] **Step 1: 修改 publishImportTaskCreated 方法签名和实现**

```java
public void publishImportTaskCreated(Long taskId, Long comicId, String sourceRef,
                                      String sourceType, String sourcePath, Boolean generateLq) {
    var msg = new java.util.LinkedHashMap<String, Object>();
    msg.put("messageId", UUID.randomUUID().toString());
    msg.put("taskId", taskId);
    msg.put("comicId", comicId);
    msg.put("sourceType", sourceType);
    if (sourceRef != null) msg.put("sourceRef", sourceRef);
    if (sourcePath != null) msg.put("sourcePath", sourcePath);
    if (generateLq != null) msg.put("generateLq", generateLq);      // 🆕
    rabbitTemplate.convertAndSend("comic.import", "task.created", msg);
}
```

- [ ] **Step 2: 验证编译**

```bash
cd api-service && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/importer/event/ImportEventPublisher.java
git commit -m "feat: ImportEventPublisher 传递 generateLq 到 MQ"
```

---

### Task 5: API — ImportServiceImpl 存储 generateLq 并传递

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/service/impl/ImportServiceImpl.java:95-104`

- [ ] **Step 1: 创建 ImportTask 时设置 generateLq**

在 `createImportTask` 方法中，`task.setSourcePath(sourcePath);` 之后、`task.setStatus("PENDING");` 之前插入：

```java
        task.setGenerateLq(request.getGenerateLq() != null && request.getGenerateLq());
```

- [ ] **Step 2: 调用 eventPublisher 时传递 generateLq**

修改 `createImportTask` 方法中第 104 行的调用：

```java
        // 3. 发 MQ
        eventPublisher.publishImportTaskCreated(task.getId(), comic.getId(), sourceRef,
            sourceType, sourcePath, task.getGenerateLq());  // 🆕 传递 generateLq
```

- [ ] **Step 3: retryTask 方法也传递 generateLq**

修改第 161 行的 `publishImportTaskCreated` 调用：

```java
        eventPublisher.publishImportTaskCreated(t.getId(), t.getComicId(), t.getSourceRef(),
            t.getSourceType(), t.getSourcePath(), t.getGenerateLq());  // 🆕
```

- [ ] **Step 4: 验证编译**

```bash
cd api-service && mvn compile -q
```

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/importer/service/impl/ImportServiceImpl.java
git commit -m "feat: ImportServiceImpl 存储并传递 generateLq"
```

---

### Task 6: API — LqCompletedHandler 消费 lq.result.queue（🆕）

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/importer/event/LqCompletedHandler.java`

- [ ] **Step 1: 创建 LqCompletedHandler**

```java
package com.comicatlas.api.importer.event;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Page;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.PageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
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
        log.info("LQ completed: comicId={}, chapterId={}, failedPages={}",
            comicId, chapterId, failedPages);

        // 2. 查 chapter 确认存在
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            log.warn("LQ completed: chapter 不存在 chapterId={}", chapterId);
            return;
        }

        // 3a. 成功页（除 failedPages 外的 PENDING 页）: PENDING → READY
        var successWrapper = new LambdaUpdateWrapper<Page>()
            .eq(Page::getChapterId, chapterId)
            .eq(Page::getLqStatus, "PENDING");
        if (failedPages != null && !failedPages.isEmpty()) {
            successWrapper.notIn(Page::getPageNumber, failedPages);
        }
        successWrapper.set(Page::getLqStatus, "READY");
        pageMapper.update(null, successWrapper);

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
            // 最终一致：如果已有章节失败，保持 PARTIAL
            String newStatus = hasFailed || "PARTIAL".equals(comic.getLqStatus())
                ? "PARTIAL" : "READY";
            comic.setLqStatus(newStatus);
            comicMapper.updateById(comic);
        }

        log.info("LQ completed 处理完成: comicId={}, chapterId={}, failedPages={}",
            comicId, chapterId, failedPages);
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd api-service && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/importer/event/LqCompletedHandler.java
git commit -m "feat: LqCompletedHandler 消费 lq.result.queue 更新 DB 状态"
```

---

### Task 7: API — ComicService 添加 triggerLqGeneration 接口

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/service/ComicService.java:10`
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicServiceImpl.java:182`

- [ ] **Step 1: ComicService 接口添加方法签名**

在 `ComicService.java` 的 `deleteComicAsync` 方法上方添加：

```java
    /**
     * 手动触发漫画 LQ 生成。遍历所有章节，逐章发送 lq.generate。
     * @param comicId 漫画 ID
     * @throws BusinessException 漫画不存在或为 EXTERNAL 存储策略
     */
    void triggerLqGeneration(Long comicId);
```

- [ ] **Step 2: ComicServiceImpl 实现方法**

在 `ComicServiceImpl.java` 末尾（`deleteComicAsync` 方法之后，类的 `}` 之前）添加：

```java
    @Override
    public void triggerLqGeneration(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(404, "漫画不存在");
        }
        if ("EXTERNAL".equals(comic.getStoragePolicy())) {
            throw new BusinessException(400, "EXTERNAL 漫画不支持 LQ 生成");
        }

        var chapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, comicId));

        if (chapters.isEmpty()) {
            throw new BusinessException(400, "漫画无章节");
        }

        for (Chapter chapter : chapters) {
            Map<String, Object> lqMsg = new LinkedHashMap<>();
            lqMsg.put("messageId", UUID.randomUUID().toString());
            lqMsg.put("comicId", comicId);
            lqMsg.put("chapterId", chapter.getId());
            rabbitTemplate.convertAndSend("comic.image", "lq.generate", lqMsg);
        }

        log.info("手动触发 LQ 生成: comicId={}, chapterCount={}", comicId, chapters.size());
    }
```

同时需要在 `ComicServiceImpl` 类中添加 `RabbitTemplate` 依赖：

在已有 `private final` 字段区（约第 33 行 `RedisTemplate` 之后）添加：

```java
    private final RabbitTemplate rabbitTemplate;
```

以及需要在文件头部添加 import：

```java
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.util.LinkedHashMap;
import java.util.UUID;
```

- [ ] **Step 3: 验证编译**

```bash
cd api-service && mvn compile -q
```

- [ ] **Step 4: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/comic/service/ComicService.java api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicServiceImpl.java
git commit -m "feat: ComicService 添加 triggerLqGeneration 手动触发 LQ 生成"
```

---

### Task 8: API — ComicController 添加 POST /api/comics/{id}/generate-lq

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/controller/ComicController.java:38-39`

- [ ] **Step 1: 添加控制器方法**

在 `ComicController.java` 的 `deleteComic` 方法之后、类 `}` 之前添加：

```java
    @PostMapping("/comics/{id}/generate-lq")
    public Result<?> generateLq(@PathVariable Long id) {
        comicService.triggerLqGeneration(id);
        return Result.ok();
    }
```

- [ ] **Step 2: 验证编译**

```bash
cd api-service && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/comic/controller/ComicController.java
git commit -m "feat: ComicController 添加 POST /api/comics/{id}/generate-lq"
```

---

### Task 9: Worker — ImportTaskHandler 读取 generateLq 并触发 LQ

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/event/ImportTaskHandler.java:26-59`

- [ ] **Step 1: 从消息读取 generateLq，构建正确的 ImportContext**

修改 `handle` 方法，从消息中读取 `generateLq` 并在构建 `ImportContext` 时使用：

```java
    @RabbitListener(queues = "import.task.queue")
    public void handle(java.util.Map<String, Object> msg) {
        Long taskId = Long.valueOf(msg.get("taskId").toString());
        Long comicId = Long.valueOf(msg.get("comicId").toString());
        String sourceType = (String) msg.getOrDefault("sourceType", "ZIP");
        String sourcePath = (String) msg.get("sourcePath");
        String sourceRef = (String) msg.get("sourceRef");
        boolean generateLq = Boolean.TRUE.equals(msg.get("generateLq"));  // 🆕
        log.info("ImportTaskHandler: taskId={}, comicId={}, sourceType={}, generateLq={}",
            taskId, comicId, sourceType, generateLq);

        try {
            publisher.publishStatus(taskId, "PARSING", 0, null, 0, 0);
            Path mangaRoot = Path.of(config.getMangaRoot());

            switch (sourceType) {
                case "ZIP" -> {
                    ImportContext ctx = new ImportContext("ZIP", "MANAGED",
                        Path.of(sourcePath), generateLq, false, null, null);  // 🆕 使用 generateLq
                    zipHandler.importZip(ctx, taskId, comicId, mangaRoot);
                }
                case "REGISTER" -> {
                    String path = sourcePath != null ? sourcePath : sourceRef;
                    String storagePolicy = "EXTERNAL";
                    ImportContext ctx = new ImportContext("REGISTER", storagePolicy,
                        Path.of(path), generateLq, false, "LOCAL", path);  // 🆕 使用 generateLq
                    directoryHandler.importExternal(ctx, taskId, mangaRoot);
                }
                case "EHENTAI" -> fileService.processImport(taskId, comicId, sourceRef, sourceType);
                default -> throw new IllegalArgumentException("Unknown sourceType: " + sourceType);
            }

            publisher.publishImported(taskId, comicId);
        } catch (Exception e) {
            log.error("Import failed: taskId={}", taskId, e);
            publisher.publishStatus(taskId, "FAILED", 0, null, 0, 0);
        }
    }
```

- [ ] **Step 2: 验证编译**

```bash
cd worker-service && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/event/ImportTaskHandler.java
git commit -m "feat: ImportTaskHandler 读取 generateLq 传入 ImportContext"
```

---

### Task 10: Worker — LqGenerateHandler 修复 totalPages

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/event/LqGenerateHandler.java:31`

- [ ] **Step 1: 将 totalPages 从写死 0 改为实际页数**

修改第 31 行，在 `handle` 方法中 `failedPages` 获取后、构建 `result` 前：

```java
    @RabbitListener(queues = "lq.generate.queue")
    public void handle(Map<String, Object> msg) {
        Long comicId = Long.valueOf(msg.get("comicId").toString());
        Long chapterId = Long.valueOf(msg.get("chapterId").toString());
        log.info("LQ generation: comicId={}, chapterId={}", comicId, chapterId);

        try {
            List<Integer> failedPages = optimizer.generateLq(comicId, chapterId, "1");

            // 🆕 统计实际页数
            String hqDir = Path.of(config.getMangaRoot(),
                pathBuilder.hqDir(comicId, "1")).toString();
            int totalPages = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(hqDir))) {
                for (Path ignored : stream) totalPages++;
            } catch (IOException e) {
                log.warn("无法统计 hq 目录: {}", hqDir, e);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("messageId", UUID.randomUUID().toString());
            result.put("comicId", comicId);
            result.put("chapterId", chapterId);
            result.put("totalPages", totalPages);   // 🆕 不再写死 0
            result.put("failedPages", failedPages);
            rabbitTemplate.convertAndSend("comic.image", "lq.completed", result);
        } catch (Exception e) {
            log.error("LQ generation failed: comicId={}", comicId, e);
        }
    }
```

同时需要在文件头部添加新的 import：

```java
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
```

以及注入 `WorkerConfig` 和 `FilePathBuilder` 依赖（如果尚未注入）。检查当前构造函数：

```java
    private final ImageOptimizer optimizer;
    private final RabbitTemplate rabbitTemplate;
    private final WorkerConfig config;         // 🆕
    private final FilePathBuilder pathBuilder;  // 🆕
```

如果当前 `LqGenerateHandler` 只有 `optimizer` 和 `rabbitTemplate`，则需添加 `config` 和 `pathBuilder`。

- [ ] **Step 2: 检查当前 LqGenerateHandler 的依赖注入**

查看文件头部确认已注入的字段。如需添加 `config` 和 `pathBuilder`，补充：

```java
private final WorkerConfig config;
private final FilePathBuilder pathBuilder;
```

同时在 `ImageOptimizer` 的 import 中已存在这些依赖（`ImageOptimizer` 本身就注入了 `config` 和 `pathBuilder`），确保 import 正确：

```java
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.common.FilePathBuilder;
```

- [ ] **Step 3: 验证编译**

```bash
cd worker-service && mvn compile -q
```

- [ ] **Step 4: Commit**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/event/LqGenerateHandler.java
git commit -m "fix: LqGenerateHandler totalPages 从写死 0 改为实际页数统计"
```

---

### Task 11: Frontend — types 和 api.ts 添加 generateLq

**Files:**
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/services/api.ts:20`
- Modify: `frontend/src/stores/import-store.ts:9-12`

- [ ] **Step 1: types/index.ts 添加 generateLq 到 ImportRequest**

查找 `ImportRequest` 接口定义（或检查 `importApi.create` 的类型签名），如果不存在显式类型则新建：

```typescript
// 在 types/index.ts 中添加（如果不存在 ImportRequest 接口）
export interface ImportRequest {
  sourceRef: string
  sourceType?: string
  sourcePath?: string
  generateLq?: boolean
}
```

如果类型文件中已有类似的接口/类型定义，则在对应位置添加 `generateLq?: boolean` 字段。

- [ ] **Step 2: api.ts 修改 create 方法支持 generateLq**

```typescript
export const importApi = {
  create: (data: { sourceRef: string; generateLq?: boolean }) =>
    api.post('/tasks/import', data),
  // ... 其余不变
}
```

- [ ] **Step 3: import-store.ts 修改 create 方法传参**

```typescript
async function create(sourceRef: string, generateLq?: boolean): Promise<ImportTaskVO> {
    const res: any = await importApi.create({ sourceRef, generateLq })
    // ... 其余不变
}
```

- [ ] **Step 4: 验证 TypeScript 编译**

```bash
cd frontend && npx vue-tsc --noEmit
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/index.ts frontend/src/services/api.ts frontend/src/stores/import-store.ts
git commit -m "feat: frontend 类型和 API 添加 generateLq 字段"
```

---

### Task 12: 集成验证

- [ ] **Step 1: 启动全部服务**

```bash
docker-compose up -d mysql redis rabbitmq
cd api-service && mvn spring-boot:run &
cd worker-service && mvn spring-boot:run &
cd frontend && npm run dev &
```

- [ ] **Step 2: 测试导入时 generateLq=true**

```bash
curl -X POST http://localhost:8010/api/tasks/import \
  -H "Content-Type: application/json" \
  -d '{"sourceType":"ZIP","sourcePath":"D:/test-comic.zip","generateLq":true}'
```

验证：
- `import_task.generate_lq` = 1
- 导入完成后 Worker 日志出现 "LQ generation"
- `lq.result.queue` 消息被消费
- `page.lq_status` 和 `comic.lq_status` 更新

- [ ] **Step 3: 测试手动触发 LQ**

```bash
curl -X POST http://localhost:8010/api/comics/{comicId}/generate-lq
```

验证：Worker 逐章生成 LQ，DB 状态正确更新

- [ ] **Step 4: 测试 EXTERNAL 漫画拒绝**

```bash
# 对一个 EXTERNAL 漫画调用手动触发
curl -X POST http://localhost:8010/api/comics/{externalComicId}/generate-lq
# 预期: 400 "EXTERNAL 漫画不支持 LQ 生成"
```

- [ ] **Step 5: 测试幂等**

发送相同 `messageId` 的 `lq.completed` 消息两次，验证第二次日志显示"跳过"
