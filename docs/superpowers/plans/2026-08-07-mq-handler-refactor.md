# MQ 消费编排统一与 ImportTaskHandler 重构实施计划

**状态**: 历史归档

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用统一的 `MqConsumerSupport` 消费模板替换全部 25 个 MQ handler 的手写 ack/reject 样板，并重构 `ImportTaskHandler.handle` 为纯编排。

**Architecture:** 在 comic-common 新增函数式消费编排组件（组合优于继承，阿里规范），`FailurePolicy` 三枚举表达消费失败语义；各 handler 注入模板后 `handle` 只剩业务逻辑；`ImportTaskHandler` 拆分为"协议编排 + 路由收敛"两层。

**Tech Stack:** Java 21, Spring Boot 3.3.0, RabbitMQ (spring-amqp), Lombok, Mockito

**Design spec:** `docs/superpowers/specs/2026-08-07-mq-handler-refactor-design.md`

## Global Constraints

- 所有修改必须以现有 catch 块行为为准，禁止臆造新的失败语义（映射见 spec §4.3 表格）。
- `MqConsumerSupport` 放 comic-common，包 `com.comicatlas.common.mq`，`@Component`，仅依赖 `com.rabbitmq.client.Channel`。
- 中断语义：`InterruptedException` 必须恢复中断标志、结束任务、**不 ack 不 reject 不执行 onFailure**（阿里规范）。
- 日志使用占位符 `{}`，失败日志必须保留异常对象作为最后参数。
- 每个 Task 结束时运行对应测试并提交，提交信息中文"动作 + 内容"。
- 禁止修改无关代码；禁止重命名 handler 类；`handle` 方法签名（`Channel`/`@Header` 参数）保持不变。

---

### Task 1: `MqConsumerSupport` 模板组件 + 单测

**Files:**
- Modify: `comic-common/pom.xml`
- Create: `comic-common/src/main/java/com/comicatlas/common/mq/MqConsumerSupport.java`
- Test: `comic-common/src/test/java/com/comicatlas/common/mq/MqConsumerSupportTest.java`

**Interfaces:**
- Produces: `MqConsumerSupport`（Spring bean）——下游 Task 2-5 的 handler 注入 `private final MqConsumerSupport mqConsumerSupport;`。
  - `void consume(Channel, long tag, String label, ConsumeAction action)`
  - `void consume(Channel, long tag, String label, ConsumeAction action, ConsumeAction onFailure)`
  - `void consume(Channel, long tag, String label, ConsumeAction action, ConsumeAction onFailure, FailurePolicy failurePolicy)`
  - `@FunctionalInterface ConsumeAction { void run() throws Exception; }`
  - `enum FailurePolicy { REJECT_TO_DLQ, REQUEUE, ACK_AFTER_CALLBACK }`

- [ ] **Step 1: comic-common/pom.xml 加依赖**（在 `<dependencies>` 内、jackson-annotations 之后）

```xml
<dependency>
    <groupId>com.rabbitmq</groupId>
    <artifactId>amqp-client</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-context</artifactId>
</dependency>
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <scope>test</scope>
</dependency>
```

（版本均由 spring-boot-starter-parent 3.3.0 BOM 管理，无需显式版本号）

- [ ] **Step 2: 写失败测试 `MqConsumerSupportTest`**

```java
package com.comicatlas.common.mq;

import com.rabbitmq.client.Channel;
import com.comicatlas.common.mq.MqConsumerSupport.FailurePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MqConsumerSupportTest {

    private final MqConsumerSupport support = new MqConsumerSupport();

    @Test
    void success_acksWithNoReject() throws Exception {
        Channel channel = mock(Channel.class);
        support.consume(channel, 1L, "label", () -> { });
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void failure_rejectToDlq_runsOnFailure() throws Exception {
        Channel channel = mock(Channel.class);
        support.consume(channel, 1L, "label",
                () -> { throw new IllegalStateException("boom"); },
                e -> { }, FailurePolicy.REJECT_TO_DLQ);
        verify(channel).basicReject(1L, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void failure_requeue_rejectsWithTrue() throws Exception {
        Channel channel = mock(Channel.class);
        support.consume(channel, 1L, "label",
                () -> { throw new IllegalStateException("boom"); },
                null, FailurePolicy.REQUEUE);
        verify(channel).basicReject(1L, true);
    }

    @Test
    void failure_ackAfterCallback_acksAndNoReject() throws Exception {
        Channel channel = mock(Channel.class);
        support.consume(channel, 1L, "label",
                () -> { throw new IllegalStateException("boom"); },
                e -> { }, FailurePolicy.ACK_AFTER_CALLBACK);
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void interrupted_restoresFlagAndNeverAcksOrRejects() throws Exception {
        Channel channel = mock(Channel.class);
        Thread.currentThread().interrupt();
        try {
            support.consume(channel, 1L, "label",
                    () -> { throw new InterruptedException("interrupt"); },
                    e -> { }, FailurePolicy.REJECT_TO_DLQ);
        } finally {
            assertTrue(Thread.interrupted(), "中断标志必须被恢复且被消费");
        }
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void onFailureThrows_doesNotMaskOriginalAndStillRejects() throws Exception {
        Channel channel = mock(Channel.class);
        support.consume(channel, 1L, "label",
                () -> { throw new IllegalStateException("original"); },
                e -> { throw new IllegalStateException("onFailure boom"); },
                FailurePolicy.REJECT_TO_DLQ);
        verify(channel).basicReject(1L, false);
    }

    @Test
    void ackThrows_logsAndDoesNotPropagate() throws Exception {
        Channel channel = mock(Channel.class);
        doThrow(new java.io.IOException("ack fail")).when(channel).basicAck(anyLong(), anyBoolean());
        assertDoesNotThrow(() -> support.consume(channel, 1L, "label", () -> { }));
    }

    @Test
    void interruptDoesNotRunOnFailure() throws Exception {
        Channel channel = mock(Channel.class);
        Thread.currentThread().interrupt();
        try {
            support.consume(channel, 1L, "label",
                    () -> { throw new InterruptedException(); },
                    e -> fail("onFailure 不应在中断时执行"),
                    FailurePolicy.ACK_AFTER_CALLBACK);
        } finally {
            Thread.interrupted();
        }
    }
}
```

- [ ] **Step 3: 运行测试确认失败（编译错误即预期）**

Run: `.\mvnw -pl comic-common test -Dtest=MqConsumerSupportTest`
Expected: 编译失败（MqConsumerSupport 不存在）

- [ ] **Step 4: 实现 `MqConsumerSupport`**

```java
package com.comicatlas.common.mq;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MQ 消费编排支持：统一 ACK/Reject/中断语义（阿里规范）。
 * 组合优于继承——handler 注入本组件，handle 方法只保留业务逻辑与编排。
 */
@Component
public class MqConsumerSupport {

    private static final Logger log = LoggerFactory.getLogger(MqConsumerSupport.class);

    /** 消费失败策略（业务异常时）。 */
    public enum FailurePolicy {
        /** 默认：reject(requeue=false) → 进 DLQ，任务类消费失败 */
        REJECT_TO_DLQ,
        /** reject(requeue=true) → 原队列重试，取消类消息不能丢 */
        REQUEUE,
        /** 失败回调后 ack：失败事件即业务结果，不重试不进 DLQ */
        ACK_AFTER_CALLBACK
    }

    @FunctionalInterface
    public interface ConsumeAction {
        void run() throws Exception;
    }

    /** 失败回调：接收业务异常，用于发失败事件/更新状态（异常消息即失败事件内容）。 */
    @FunctionalInterface
    public interface ExceptionHandler {
        void accept(Exception e) throws Exception;
    }

    public void consume(Channel channel, long tag, String label, ConsumeAction action) {
        consume(channel, tag, label, action, null, FailurePolicy.REJECT_TO_DLQ);
    }

    public void consume(Channel channel, long tag, String label, ConsumeAction action, ExceptionHandler onFailure) {
        consume(channel, tag, label, action, onFailure, FailurePolicy.REJECT_TO_DLQ);
    }

    public void consume(Channel channel, long tag, String label, ConsumeAction action,
                        ExceptionHandler onFailure, FailurePolicy failurePolicy) {
        try {
            action.run();
            channel.basicAck(tag, false);
            log.info("MQ 消费完成: {}", label);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("MQ 消费被中断，结束任务: {}", label);
        } catch (Exception e) {
            log.error("MQ 消费失败: {}", label, e);
            runOnFailure(onFailure, e, label);
            try {
                if (failurePolicy == FailurePolicy.ACK_AFTER_CALLBACK) {
                    channel.basicAck(tag, false);
                } else {
                    channel.basicReject(tag, failurePolicy == FailurePolicy.REQUEUE);
                }
            } catch (Exception ex) {
                log.warn("消息 ack/reject 失败: tag={}, label={}", tag, label, ex);
            }
        }
    }

    private void runOnFailure(ExceptionHandler onFailure, Exception failure, String label) {
        if (onFailure == null) { return; }
        try {
            onFailure.accept(failure);
        } catch (Exception e) {
            log.error("MQ 失败回调执行异常: {}", label, e);
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `.\mvnw -pl comic-common test -Dtest=MqConsumerSupportTest`
Expected: 8 tests, 0 failures, BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add comic-common/pom.xml comic-common/src/main/java/com/comicatlas/common/mq/ comic-common/src/test/java/com/comicatlas/common/mq/
git commit -m "新增 MqConsumerSupport 统一 MQ 消费编排：三策略 FailurePolicy 覆盖 DLQ/重试/失败即结果"
```

---

### Task 2: `ImportTaskHandler.handle` 重构

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/event/ImportTaskHandler.java`

**Interfaces:**
- Consumes: `MqConsumerSupport`（Task 1）；现有 `CancelHandler`、`TaskStatusPublisher`、`ZipImportHandler`、`DirectoryImportHandler`、`EhentaiDownloadService`、`WorkerConfig`
- Produces: 无新接口（内部私有方法拆分）

- [ ] **Step 1: 替换整个 `ImportTaskHandler.java`**

```java
package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.EhentaiDownloadService;
import com.comicatlas.worker.file.handler.DirectoryImportHandler;
import com.comicatlas.worker.file.handler.ZipImportHandler;
import com.comicatlas.worker.file.parse.ImportContext;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportTaskHandler {
    private final EhentaiDownloadService ehentaiDownloadService;
    private final DirectoryImportHandler directoryHandler;
    private final ZipImportHandler zipHandler;
    private final WorkerConfig config;
    private final TaskStatusPublisher publisher;
    private final CancelHandler cancelHandler;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.IMPORT_TASK)
    public void handle(ImportTaskCreatedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        if (cancelHandler.isCancelled(taskId)) {
            log.info("Task cancelled, skipping: taskId={}", taskId);
            try { channel.basicAck(tag, false); } catch (Exception ex) { log.warn("消息 ack 失败: tag={}", tag, ex); }
            return;
        }
        mqConsumerSupport.consume(channel, tag, "导入任务: taskId=" + taskId,
                () -> runImport(event, taskId),
                e -> publisher.publishStatus(taskId, "FAILED", 0, null, 0, 0));
    }

    private void runImport(ImportTaskCreatedEvent event, Long taskId) throws Exception {
        Long comicId = event.comicId();
        String sourceType = event.sourceType() != null ? event.sourceType() : "ZIP";
        String sourcePath = event.sourcePath();
        Path mangaRoot = Path.of(config.getMangaRoot());

        publisher.publishStatus(taskId, "PARSING", 0, null, 0, 0);
        String normalizedPath = mapHostPathToContainer(sourcePath);
        if (!normalizedPath.equals(sourcePath)) {
            log.info("Source path normalized: {} -> {}", sourcePath, normalizedPath);
        }
        routeToHandler(sourceType, normalizedPath, taskId, comicId, mangaRoot);
        publisher.publishImported(taskId, comicId);
    }

    private void routeToHandler(String sourceType, String sourcePath, Long taskId, Long comicId, Path mangaRoot) throws Exception {
        switch (sourceType) {
            case "ZIP" -> zipHandler.importZip(
                    new ImportContext("ZIP", Path.of(sourcePath), false, false), taskId, comicId, mangaRoot);
            case "REGISTER", "DIRECTORY" -> {
                if (sourcePath == null) { throw new IllegalArgumentException("DIRECTORY 需要 sourcePath"); }
                directoryHandler.handle(
                        new ImportContext("DIRECTORY", Path.of(sourcePath), false, false), taskId, comicId, mangaRoot);
            }
            case "EHENTAI" -> {
                Path sourceDir = ehentaiDownloadService.downloadToSourceDir(taskId, sourcePath);
                directoryHandler.handle(
                        new ImportContext("DIRECTORY", sourceDir, false, false), taskId, comicId, mangaRoot);
            }
            default -> throw new IllegalArgumentException("Unknown sourceType: " + sourceType);
        }
    }

    private String mapHostPathToContainer(String sourcePath) {
        if (sourcePath == null || config.getHostMangaRoot() == null || config.getHostMangaRoot().isBlank()) {
            return sourcePath;
        }
        String hostRoot = config.getHostMangaRoot().replace('\\', '/');
        String containerRoot = config.getContainerMangaRoot() != null
                ? config.getContainerMangaRoot().replace('\\', '/')
                : "/storage";
        String normalized = sourcePath.replace('\\', '/');
        if (normalized.regionMatches(true, 0, hostRoot, 0, hostRoot.length())) {
            String suffix = normalized.substring(hostRoot.length());
            return containerRoot + suffix;
        }
        return sourcePath;
    }
}
```

注意：**删除 `classifyFailure` 私有方法**（无消费者、违反"不得用异常控制业务分支"）；`mapHostPathToContainer` 原样保留。

- [ ] **Step 2: 编译**

Run: `.\mvnw -pl worker-service -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: 回归测试**

Run: `.\mvnw -pl worker-service -am test -Dtest=DirectoryImportResumeTest -DfailIfNoTests=false`
Expected: 5 tests, 0 failures

- [ ] **Step 4: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/event/ImportTaskHandler.java
git commit -m "重构 ImportTaskHandler：handle 拆为协议编排+路由收敛，接入 MqConsumerSupport，移除无消费者的 classifyFailure"
```

---

### Task 3: worker 侧标准模式迁移（6 个 handler）

**Files (Modify):**
- `worker-service/src/main/java/com/comicatlas/worker/event/LqGenerateHandler.java`
- `worker-service/src/main/java/com/comicatlas/worker/event/HqDeleteHandler.java`
- `worker-service/src/main/java/com/comicatlas/worker/event/DeleteHandler.java`
- `worker-service/src/main/java/com/comicatlas/worker/event/MetadataRefreshHandler.java`
- `worker-service/src/main/java/com/comicatlas/worker/event/ManagementCommandDispatcher.java`
- `worker-service/src/main/java/com/comicatlas/worker/event/VideoMetadataFixHandler.java`

**Interfaces:**
- Consumes: `MqConsumerSupport`（Task 1）。标准模式：无 onFailure，默认 `REJECT_TO_DLQ`。

**迁移模式（6 个 handler 完全一致）：**

1. 字段区添加 `private final MqConsumerSupport mqConsumerSupport;` + import `com.comicatlas.common.mq.MqConsumerSupport`。
2. handle 方法体替换为 `mqConsumerSupport.consume(channel, tag, "<label>", () -> { /* 原 try 块业务代码原样搬入 */ });`
   - 删除原 `channel.basicAck(tag, false);` 与 `catch (Exception e) { log...; basicReject... }` 整块（模板承载）。
   - 原方法开头的局部变量声明（`Long comicId = event.comicId();` 等）保留在 handle 开头（lambda 引用需 effectively final）。
   - 原方法内的**提前 ack 分支**（如 `pages.isEmpty()` 时 `basicAck; return;`）改写为 lambda 内 `return;`（lambda 中 `return` 合法）。
   - `handle` 方法签名（Event 类型、Channel、@Header tag）不变；`import Channel` 保留。
3. 每个 handler 迁移后编译 + 跑对应测试（无测试的跑编译 + 该文件相关测试）。

**各 handler label 与业务边界（精确）:**

| Handler | handle 事件类型 | label | 提前分支处理 |
|---------|---------------|-------|------------|
| LqGenerateHandler | `LqGenerateEvent` | `"LQ生成: comicId=" + comicId` | `pages.isEmpty()` 提前 ack → lambda 内 `return;` |
| HqDeleteHandler | `DeleteHqRequestedEvent` | `"HQ删除: chapterId=" + chapterId` | 如有提前 ack 分支 → `return;` |
| DeleteHandler | `DeleteRequestedEvent` | `"完整删除: comicId=" + comicId` | 无 |
| MetadataRefreshHandler | `MetadataRefreshEvent` | `"元数据刷新: comicId=" + comicId` | 无 |
| ManagementCommandDispatcher | `ManagementCommandRequestedEvent` | `"管理命令: taskId=" + cmd.taskId()` | 无 |
| VideoMetadataFixHandler | `VideoMetadataFixRequestedEvent` | `"视频元数据修复: comicId=" + comicId` | 无（原 catch 仅 log+reject） |

- [ ] **Step 1: 迁移 6 个 handler 的 handle 壳**（按上表逐 handler 执行迁移模式）
- [ ] **Step 2: 编译**

Run: `.\mvnw -pl worker-service -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: 回归测试（涉及 handler 的现有测试）**

Run: `.\mvnw -pl worker-service -am test -DfailIfNoTests=false`
Expected: 48 tests, 0 failures（数量可能因新增测试微增）

- [ ] **Step 4: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/event/
git commit -m "迁移 worker 侧 6 个标准模式 handler 到 MqConsumerSupport：消除手写 ack/reject 样板"
```

---

### Task 4: worker 侧变体模式迁移（5 个 handler）

**Files (Modify):**
- `worker-service/src/main/java/com/comicatlas/worker/event/CancelHandler.java`（REQUEUE）
- `worker-service/src/main/java/com/comicatlas/worker/event/RecoveryTaskHandler.java`（ACK_AFTER_CALLBACK）
- `worker-service/src/main/java/com/comicatlas/worker/event/DirectoryScanHandler.java`（ACK_AFTER_CALLBACK）
- `worker-service/src/main/java/com/comicatlas/worker/event/VideoTranscodeHandler.java`（onFailure + REJECT_TO_DLQ）
- `worker-service/src/main/java/com/comicatlas/worker/event/ExportTaskHandler.java`（onFailure + REJECT_TO_DLQ）

**Interfaces:**
- Consumes: `MqConsumerSupport`（Task 1）

**各 handler 精确替换（handle 方法体）：**

**CancelHandler**（REQUEUE，无 onFailure）:
```java
@RabbitListener(queues = MqQueues.CANCEL_TASK)
public void handle(CancelTaskEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
    mqConsumerSupport.consume(channel, tag, "取消标记: taskId=" + event.taskId(),
            () -> redisTemplate.opsForValue().set(KEY_PREFIX + event.taskId(), "1", TTL),
            null, MqConsumerSupport.FailurePolicy.REQUEUE);
}
```
（原 handle 的 set + ack 逻辑被 lambda + 模板替换；`redisTemplate`/`KEY_PREFIX`/`TTL` 为现有字段）

**RecoveryTaskHandler**（ACK_AFTER_CALLBACK，onFailure = publishFailed）:
```java
@RabbitListener(queues = MqQueues.RECOVERY_TASK)
public void handle(RecoveryRequestedEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
    Long taskId = event.taskId();
    log.info("RecoveryTaskHandler: 接收恢复请求, taskId={}", taskId);
    mqConsumerSupport.consume(channel, tag, "存储恢复: taskId=" + taskId,
            () -> scanAndPublish(taskId),
            e -> publishFailed(taskId, e.getMessage()),
            MqConsumerSupport.FailurePolicy.ACK_AFTER_CALLBACK);
}
```
- 抽出私有方法 `scanAndPublish(Long taskId) throws Exception`：内容 = 原 handle 中 `try { ... } catch (Exception e) { publishFailed; ack; return; }` 的 try 块（HQ 根检查 + 目录扫描 + 发布 progress），原提前失败分支（HQ 根不可读 → publishFailed + ack + return）改为**抛出异常**让模板走 onFailure（onFailure 即 publishFailed）：
  ```java
  private void scanAndPublish(Long taskId) throws Exception {
      Path hqRoot = Path.of(config.getMangaRoot(), "hq");
      if (!Files.isDirectory(hqRoot) || !Files.isReadable(hqRoot)) {
          throw new IllegalStateException("HQ 根目录不可读: " + hqRoot.toAbsolutePath());
      }
      List<Long> comicIds = new ArrayList<>();
      try (var stream = Files.newDirectoryStream(hqRoot)) {
          for (Path dir : stream) {
              if (!Files.isDirectory(dir)) { continue; }
              String dirName = dir.getFileName().toString();
              try {
                  long comicId = Long.parseLong(dirName);
                  if (comicId > 0) { comicIds.add(comicId); }
              } catch (NumberFormatException ignored) {
                  log.debug("RecoveryTaskHandler: 跳过非数字目录: {}", dirName);
              }
          }
      }
      comicIds.sort(Comparator.naturalOrder());
      log.info("RecoveryTaskHandler: 扫描完成, taskId={}, 发现 {} 个漫画目录", taskId, comicIds.size());
      rabbitTemplate.convertAndSend(MqExchanges.RECOVERY, MqRoutingKeys.RECOVERY_PROGRESS,
              new RecoveryScanCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, comicIds));
  }
  ```
- **删除**：私有 `ack(Channel, long)` 方法（模板承载）、`publishFailed` 中多余的日志保留（onFailure 复用）。`publishFailed(taskId, msg)` 保留为 onFailure 回调。

**DirectoryScanHandler**（ACK_AFTER_CALLBACK，onFailure = publishFailed）:
```java
@RabbitListener(queues = MqQueues.SCAN_TASK)
public void handle(DirectoryScanRequestedEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
    Long taskId = event.taskId();
    String dirPath = event.directoryPath();
    log.info("DirectoryScanHandler: 接收扫描请求, taskId={}, directoryPath={}", taskId, dirPath);
    mqConsumerSupport.consume(channel, tag, "目录扫描: taskId=" + taskId,
            () -> scanAndPublish(taskId, dirPath),
            e -> publishFailed(taskId, e.getMessage()),
            MqConsumerSupport.FailurePolicy.ACK_AFTER_CALLBACK);
}

private void scanAndPublish(Long taskId, String dirPath) throws Exception {
    ScanResultVO result = scanDirectory(dirPath);
    rabbitTemplate.convertAndSend(MqExchanges.SCAN, MqRoutingKeys.SCAN_COMPLETED,
            new DirectoryScanCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, result));
    log.info("DirectoryScanHandler: 扫描完成, taskId={}, total={}", taskId, result.total());
}
```
- **删除**：私有 `ack(Channel, long)` 方法。`scanDirectory`、`publishFailed`、`IMAGE_EXT`、`extensionOf` 等保留。

**VideoTranscodeHandler**（onFailure 发失败事件 + REJECT_TO_DLQ，临时文件清理移入 action 内 try-finally）:
```java
@RabbitListener(queues = MqQueues.VIDEO_TRANSCODE)
public void handle(VideoTranscodeRequestedEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
    Long pageId = event.pageId();
    Long comicId = event.comicId();
    log.info("视频转码开始: pageId={}, comicId={}, container={}", pageId, comicId, event.container());
    mqConsumerSupport.consume(channel, tag, "视频转码: pageId=" + pageId,
            () -> transcodeAndPublish(event),
            e -> publishFailed(event, e),
            MqConsumerSupport.FailurePolicy.REJECT_TO_DLQ);
}
```
- 抽出 `transcodeAndPublish(VideoTranscodeRequestedEvent event) throws Exception`：原 handle 的 try 块业务（解析 HQ → 转码 → 验证 → 原子替换 → 发完成事件），**含原 finally 的 tempFile 清理**（`try { ... } finally { Files.deleteIfExists(tempFile); }`）。
- 抽出 `publishFailed(VideoTranscodeRequestedEvent event, Exception failure)`：原 catch 中发 `VIDEO_TRANSCODE_FAILED` 事件的逻辑，错误消息取 `failure.getMessage()`（`ExceptionHandler` 已携带异常，无需实例字段）：
  ```java
  private void publishFailed(VideoTranscodeRequestedEvent event, Exception failure) {
      rabbitTemplate.convertAndSend(MqExchanges.VIDEO, MqRoutingKeys.VIDEO_TRANSCODE_FAILED,
              new VideoTranscodeFailedEvent(UUID.randomUUID(), Instant.now(),
                      event.pageId(), event.comicId(),
                      failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName()));
  }
  ```
- **注意**：`buildFfmpegCommand`、`FFMPEG_ARGS`、`probe` 等私有方法保留不动。

**ExportTaskHandler**（onFailure 发 TASK_FAILED + REJECT_TO_DLQ）:
```java
@RabbitListener(queues = MqQueues.EXPORT_TASK)
public void handle(ExportTaskCreatedEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
    Long taskId = event.taskId();
    Long comicId = event.comicId();
    log.info("导出任务开始: taskId={}, comicId={}", taskId, comicId);
    mqConsumerSupport.consume(channel, tag, "导出任务: taskId=" + taskId,
            () -> exportAndPublish(event),
            e -> publishExportFailed(event, e),
            MqConsumerSupport.FailurePolicy.REJECT_TO_DLQ);
}
```
- 抽出 `exportAndPublish(ExportTaskCreatedEvent event) throws Exception`：原 handle 的 try 块业务（collect → buildManifest → zipBuilder.build → 发 TASK_COMPLETED）。原 `classifyExportError` 与 `buildOutputFileName` 保留。
- 抽出 `publishExportFailed(ExportTaskCreatedEvent event, Exception failure)`：原 catch 中发 `TASK_FAILED` 事件的逻辑（`errorCode = classifyExportError(failure)`，消息取 `failure.getMessage()`）——`ExceptionHandler` 已携带异常，无需实例字段。

- [ ] **Step 1: 迁移 5 个 handler**（按上述精确代码；每个 handler 迁移后立即编译确认）
- [ ] **Step 2: 编译**

Run: `.\mvnw -pl worker-service -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: 回归测试（VideoTranscodeHandlerTest、TranscodeCommandHandlerTest、DirectoryScan 相关）**

Run: `.\mvnw -pl worker-service -am test -DfailIfNoTests=false`
Expected: 全部通过

- [ ] **Step 4: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/event/
git commit -m "迁移 worker 侧 5 个变体模式 handler 到 MqConsumerSupport：REQUEUE/ACK_AFTER_CALLBACK/失败事件回调"
```

---

### Task 5: api 侧迁移（13 类 / 15 消费者）

**Files (Modify):** 全部为标准模式（无 onFailure，默认 REJECT_TO_DLQ）：
- `api-service/.../importer/event/ImportEventHandler.java`（3 个消费者：handleComicImported / handleTaskStatusChanged / handleImportTaskFailed）
- `api-service/.../importer/event/LqCompletedHandler.java`
- `api-service/.../importer/event/HqDeletedHandler.java`
- `api-service/.../importer/event/DeleteEventHandler.java`
- `api-service/.../importer/event/RecoveryEventHandler.java`
- `api-service/.../importer/event/DirectoryScanEventHandler.java`
- `api-service/.../management/event/ManagementCommandResultHandler.java`
- `api-service/.../admin/event/VideoMetadataFixCompletedHandler.java`
- `api-service/.../export/event/ExportStartedHandler.java`
- `api-service/.../export/event/ExportCompletedHandler.java`
- `api-service/.../export/event/ExportFailedHandler.java`
- `api-service/.../export/event/TranscodeCompletedHandler.java`
- `api-service/.../export/event/TranscodeFailedHandler.java`

**Interfaces:**
- Consumes: `MqConsumerSupport`（Task 1）

**迁移模式（与 Task 3 一致，标准模式）：**

1. 字段区添加 `private final MqConsumerSupport mqConsumerSupport;` + import。
2. handle 方法体替换为 `mqConsumerSupport.consume(channel, tag, "<label>", () -> { /* 原 try 块业务原样搬入 */ });`
   - 删除原 ack 与 catch/reject 块；提前 ack 分支 → lambda 内 `return;`。
3. `VideoMetadataFixCompletedHandler` 原 catch 用 `basicNack(tag, false, false)`——语义等价 REJECT_TO_DLQ，直接由模板替换（无特殊处理）。
4. 多个消费者的 handler（ImportEventHandler）逐方法迁移；三个方法共用注入的 `mqConsumerSupport` 字段。

**label 约定:** `<业务名>: <主ID>`（如 `"LQ完成: comicId=" + comicId`、`"删除完成: comicId=" + comicId`、`"管理命令结果: " + ...`、`"导出开始: taskId=" + taskId` 等，以各自 handle 提取的主 ID 为准）。

- [ ] **Step 1: 迁移 13 个 handler 的 handle 壳**（逐文件执行迁移模式）
- [ ] **Step 2: 编译**

Run: `.\mvnw -pl api-service -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: 回归测试**

Run: `.\mvnw -pl api-service test -DfailIfNoTests=false`
Expected: 全部通过（含 ImportServiceTest、DlqServiceTest 等）

- [ ] **Step 4: 提交**

```bash
git add api-service/src/main/java/com/comicatlas/api/
git commit -m "迁移 api 侧 13 个 MQ 消费者到 MqConsumerSupport：统一 ack/reject 语义"
```

---

### Task 6: 全链路验证与收尾

- [ ] **Step 1: 全量编译**

Run: `.\mvnw -pl api-service,worker-service -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 2: 契约测试（拓扑不变性）**

Run: `.\mvnw -pl api-service test "-Dtest=RabbitTopologyIT,RabbitMqConfigTest" -DfailIfNoTests=false`
Expected: 全部通过

- [ ] **Step 3: worker 全量测试**

Run: `.\mvnw -pl worker-service -am test -DfailIfNoTests=false`
Expected: 全部通过

- [ ] **Step 4: 确认无遗留手写 ack/reject 样板**

Run: 在 api-service/worker-service 的 src/main 下 grep `basicAck|basicReject|basicNack`
Expected: 仅 `MqConsumerSupport.java` 与 `ImportTaskHandler.java`（取消检查的 ack）命中；`ImportTaskHandler` 的取消分支 ack 保留属预期

- [ ] **Step 5: 提交收尾（若有遗漏调整）**

```bash
git add -A
git status   # 确认仅预期文件
git commit -m "MQ 消费编排统一收尾：清理遗漏与验证"
```
