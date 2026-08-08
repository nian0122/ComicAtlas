# 阿里 P1 阻断项整改实现计划

**状态**: 历史归档

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复阿里 Java 规范复审的三个 P1 阻断项——完整测试门禁、线程池与中断处理、状态枚举落地，使 `mvnw verify -DskipTests=false` 全绿。

**Architecture:** 三个独立工作流：(1) 测试门禁先跑全量失败基线再逐类修复；(2) `VideoNormalizer` 的裸 `Executors.newFixedThreadPool` 替换为 Spring 托管 `ThreadPoolTaskExecutor`，拆分中断处理；(3) 实体 `String` 状态字段迁移为已定义好的 Java 枚举（`EnumTypeHandlers` 已全局注册 TypeHandler，DB VARCHAR 存 `name()` 零迁移）。枚举迁移采用"实体字段改类型 → mvn compile 编译错误驱动修复 → 测试同步"的机械重构。

**Tech Stack:** Java 21、Spring Boot 3、MyBatis Plus、Testcontainers、JUnit 5、Mockito、Maven（`mvnw`）

## Global Constraints

- 认证授权不纳入本轮（降级为风险记录，见 spec）。
- P2（通配符 import、单行 if/for/while、语义命名、Checkstyle 增强、inSql、ImageOptimizer/MediaAnalyzer 裸线程）不纳入本轮。
- DB 保持 VARCHAR 存储枚举 `name()`，**不新增 Flyway 迁移**，现有数据零迁移。
- 前端契约不变：Jackson 默认输出枚举 `name()`，与现状字符串一致。
- 恢复任务终态字符串统一 `SUCCEEDED`；`import_task` 体系终态保持 `SUCCESS`（两体系独立）。
- 对话、注释、提交信息始终使用中文；提交用"动作 + 内容"格式。
- 最终验证门禁：API 模块 `mvnw verify -DskipTests=false` 退出码 0；Worker 测试 26 用例全绿；Checkstyle 0 违规。

---

## 文件结构映射

| 文件 | 职责 | 任务 |
|---|---|---|
| `api-service/src/test/.../DatabaseMigrationTest.java` | Flyway 迁移测试（动态断言） | 2 |
| `api-service/src/test/.../ImportServiceTest.java` | 导入服务单元测试（补 mock） | 3 |
| `api-service/src/test/.../RecoveryEventHandlerTest.java` | 恢复事件测试（终态字符串同步） | 4 |
| `worker-service/.../config/WorkerExecutorConfig.java` | 新建：Spring 托管线程池 bean | 6 |
| `worker-service/.../file/transcode/VideoNormalizer.java` | 转码器（受控线程池 + 中断 + 裸线程） | 6 |
| `api-service/.../comic/entity/Comic.java` | 漫画实体（枚举化） | 7 |
| `api-service/.../comic/entity/Media.java` | 媒体实体（枚举化） | 8 |
| `api-service/.../importer/entity/ImportTask.java` | 导入任务实体（枚举化） | 9 |
| 23 个生产文件 + 28 个 VO/DTO/实体 | 字符串状态赋值/比较迁移 | 7/8/9 |
| 16 个测试文件（79 处） | 测试状态字符串迁移 | 10 |

---

### Task 1: 建立完整测试门禁失败基线

**Files:**
- Test: 无（仅执行验证）

- [ ] **Step 1: 运行完整 API 模块测试**

```bash
.\mvnw -pl api-service -am verify -DskipTests=false -q 2>&1 | Select-String "Tests run|ERROR|FAIL"
```

Expected: 输出包含 268 tests 的汇总行，其中 6 failures、5 errors（以实际为准）。

- [ ] **Step 2: 记录全量失败清单**

将每个失败的测试方法（类名 `#方法名` + 失败原因首行）记录到会话中。评审只点名 3 个测试类，此步骤确认是否有遗漏类。此清单是后续 Task 2-4 的验证基线。

- [ ] **Step 3: 记录 Worker 测试基线**

```bash
.\mvnw -pl worker-service -am test -q 2>&1 | Select-String "Tests run"
```

Expected: 26 tests，全部通过（此基线确认 Worker 不受后续 API 改动影响）。

---

### Task 2: 修复 DatabaseMigrationTest（动态断言）

**Files:**
- Modify: `api-service/src/test/java/com/comicatlas/api/DatabaseMigrationTest.java:100-148,214-238`

**Interfaces:**
- Produces: 静态方法 `expectedMigrationVersions()` → `List<String>`（从 `classpath:db/flyway` 解析 `V(\d+)__` 文件名的版本号，按数值排序）

- [ ] **Step 1: 新增迁移版本提取方法**

在类中新增：

```java
static List<String> expectedMigrationVersions() {
    try {
        var resource = DatabaseMigrationTest.class.getClassLoader()
                .getResource("db/flyway");
        if (resource == null) {
            throw new IllegalStateException("db/flyway 目录不存在");
        }
        java.net.URI uri = resource.toURI();
        java.nio.file.Path dir = java.nio.file.Paths.get(uri);
        try (var stream = java.nio.file.Files.list(dir)) {
            return stream
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("V\\d+__.*\\.sql"))
                    .map(name -> name.replaceFirst("V(\\d+)__.*\\.sql", "$1"))
                    .sorted(Comparator.comparingLong(Long::parseLong))
                    .toList();
        }
    } catch (Exception e) {
        throw new RuntimeException("解析 Flyway 迁移目录失败", e);
    }
}
```

- [ ] **Step 2: 更新 freshDatabase_ShouldReachV2 断言**

替换第 116 行：

```java
assertThat(versions).containsExactly("1", "2");
```

为：

```java
assertThat(versions).containsExactlyElementsOf(expectedMigrationVersions());
```

同时将 `@DisplayName("空库迁移达到 V2")` 改为 `@DisplayName("空库迁移达到最新版本")`。

- [ ] **Step 3: 更新 repeatMigration_IsNoOp 断言**

替换第 237 行：

```java
assertThat(appliedMigrations(ds)).containsExactly("1", "2");
```

为：

```java
assertThat(appliedMigrations(ds)).containsExactlyElementsOf(expectedMigrationVersions());
```

- [ ] **Step 4: 确认 V2 漂移列断言在最新版本仍成立**

运行测试，若 `import_task.batch_id`、`comic.category`、`page.lq_status` VARCHAR(32)、`idx_batch_id` 任一断言失败（后续迁移改动过这些列），按实际 schema 同步更新断言内容。

- [ ] **Step 5: 运行测试确认通过**

```bash
.\mvnw -pl api-service -Dtest=DatabaseMigrationTest test -q 2>&1 | Select-String "Tests run"
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`（若 Docker 不可用则为跳过，需本机 Docker 环境验证）。

- [ ] **Step 6: 提交**

```bash
git add api-service/src/test/java/com/comicatlas/api/DatabaseMigrationTest.java
git commit -m "修复数据库迁移测试：断言改为动态读取 Flyway 版本"
```

---

### Task 3: 修复 ImportServiceTest（补 mock 依赖）

**Files:**
- Modify: `api-service/src/test/java/com/comicatlas/api/importer/service/impl/ImportServiceTest.java:44-54`

**Interfaces:**
- Consumes: `ImportServiceImpl` 构造器 11 参（新增 `OutboxService`、`ApiStorageProperties`）；`outboxService.enqueue(ComicEvent, String, String)` 为 void；`storageProperties.root(String)` → `Path`
- Produces: 补全的 mock 集合，供后续枚举 Task 9 测试同步时复用

- [ ] **Step 1: 新增两个 mock 字段**

在 `@Mock private ManagementTaskService managementTaskService;` 之后新增：

```java
@Mock private OutboxService outboxService;
@Mock private ApiStorageProperties storageProperties;
```

并补充 import：

```java
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.storage.ApiStorageProperties;
```

（`ApiStorageProperties` 实际包名以 `comic-common`/`api-service` 中为准，编译失败时用 IDE/`mvn compile` 提示修正 import。）

- [ ] **Step 2: setUp 中 stub storageProperties.root**

在 `setUp()` 方法末尾追加：

```java
Path metadataDir = Path.of("target/test-tmp/metadata");
lenient().when(storageProperties.root("METADATA")).thenReturn(metadataDir);
```

并补 import：`import java.nio.file.Path;`

- [ ] **Step 3: 运行测试，按实际失败修正批量断言**

```bash
.\mvnw -pl api-service -Dtest=ImportServiceTest test -q 2>&1 | Select-String "Tests run|FAIL|AssertionFailedError"
```

Expected: `Tests run: 9, Failures: 0, Errors: 0`。

若仍有失败（评审提及"批量导入断言失败"），读取失败详情，检查 `createBatchImportTasks` 返回的 `BatchImportResultVO` 实际字段值（`getTotal()`/`getSucceeded()`/`getFailed()`）与断言是否一致，按实际修复断言。

- [ ] **Step 4: 提交**

```bash
git add api-service/src/test/java/com/comicatlas/api/importer/service/impl/ImportServiceTest.java
git commit -m "修复导入服务测试：补 OutboxService 与 ApiStorageProperties mock"
```

---

### Task 4: 修复 RecoveryEventHandlerTest 终态字符串 + 生产一致性

**Files:**
- Modify: `api-service/src/test/java/com/comicatlas/api/importer/event/RecoveryEventHandlerTest.java:110,133,224`
- Verify: `api-service/src/main/java/com/comicatlas/api/importer/event/RecoveryEventHandler.java:49`（无需改动，作为事实标准）

- [ ] **Step 1: 同步测试终态字符串**

将以下三处 `task.setStatus("SUCCESS")` 改为 `task.setStatus("SUCCEEDED")`：
- 第 110 行（`handleScanCompleted_shouldSkip_whenTaskAlreadySuccess`）
- 第 133 行（`handleScanCompleted_shouldSkip_whenTaskAlreadyFailed` 相邻的 skip 用例）
- 第 224 行（`handleFailed_shouldSkip_whenTaskAlreadySuccess`）

（注意：仅改这 3 处 mock 数据中的终态值，不断言处 `"SUCCEEDED"` 已是期望值。）

- [ ] **Step 2: 运行测试确认通过**

```bash
.\mvnw -pl api-service -Dtest=RecoveryEventHandlerTest test -q 2>&1 | Select-String "Tests run"
```

Expected: `Tests run: 7, Failures: 0, Errors: 0`。

- [ ] **Step 3: 确认生产一致性**

检查 `LegacyTaskBackfillService` 第 192 行 `case "SUCCESS", "SUCCEEDED" ->` 保持兼容分支不变（向后兼容旧数据，本轮统一目标仅针对新写入的终态值）。`RecoveryTaskServiceImpl` 无 `SUCCESS` 终态写入点（已确认仅 QUEUED/FAILED），无需改动。

- [ ] **Step 4: 提交**

```bash
git add api-service/src/test/java/com/comicatlas/api/importer/event/RecoveryEventHandlerTest.java
git commit -m "修复恢复事件测试：终态字符串与生产代码 SUCCEEDED 对齐"
```

---

### Task 5: API 模块完整测试门禁验证

**Files:**
- Test: 无（仅执行验证）

- [ ] **Step 1: 运行完整 API 模块测试**

```bash
.\mvnw -pl api-service -am verify -DskipTests=false -q 2>&1 | Select-String "Tests run:|BUILD"
```

Expected: `BUILD SUCCESS`，268 tests 0 failure 0 error。

- [ ] **Step 2: 若有残留失败，回归 Task 1 清单逐项修复**

对清单中尚未修复的失败项，定位根因、修复、重跑直到全绿。每修复一项提交一次（"修复 XXX 测试失败"）。

---

### Task 6: VideoNormalizer 线程池 Spring 托管改造

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/config/WorkerExecutorConfig.java`
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/transcode/VideoNormalizer.java`

**Interfaces:**
- Produces: `@Bean(name = "videoNormalizeExecutor") ThreadPoolTaskExecutor`（core=max=可配置、队列 64、前缀 `video-normalizer-`、`CallerRunsPolicy`、优雅关闭 30s）
- Consumes: `VideoNormalizer` 构造器改为 `(WorkerConfig config, @Qualifier("videoNormalizeExecutor") ThreadPoolTaskExecutor executor)`

- [ ] **Step 1: 新建 WorkerExecutorConfig**

创建 `worker-service/src/main/java/com/comicatlas/worker/config/WorkerExecutorConfig.java`：

```java
package com.comicatlas.worker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Worker 统一线程池配置 — 外部进程输出读取与转码并行。
 */
@Configuration
public class WorkerExecutorConfig {

    @Bean(name = "videoNormalizeExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor videoNormalizeExecutor(
            @Value("${worker.executor.video-normalize-threads:2}") int threads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("video-normalizer-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 2: 改造 VideoNormalizer 构造器与字段**

替换字段区与构造器：

```java
private final WorkerConfig config;
private final ThreadPoolTaskExecutor executor;
private final int ffmpegThreads;

public VideoNormalizer(WorkerConfig config,
                       @Qualifier("videoNormalizeExecutor") ThreadPoolTaskExecutor executor) {
    this.config = config;
    this.executor = executor;
    this.ffmpegThreads = 2; // ffmpeg 转码线程固定 2，并行度由托管线程池控制
}
```

删除 `private final int parallelism;` 字段及其在构造器中的推导；删除 `Executors` 与 `ExecutorService` import，新增：

```java
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Future;
```

（`java.util.concurrent.Executors`/`ExecutorService` import 一并移除。）

- [ ] **Step 3: 替换 normalize() 中的线程池创建与异常处理**

替换第 89 行 `ExecutorService executor = Executors.newFixedThreadPool(parallelism);` 为删除（不再创建，使用注入的 `executor`）。

替换第 99-106 行的异常处理：

```java
for (Future<?> f : futures) {
    try {
        f.get();
    } catch (ExecutionException e) {
        log.error("转码任务异常: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        failed.incrementAndGet();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("转码被中断，取消剩余任务");
        for (Future<?> remaining : futures) {
            remaining.cancel(true);
        }
        return 0;
    }
}
```

删除原 `finally { executor.shutdown(); }` 块（托管 executor 由容器管理，不可手动 shutdown；`shutdownNow` 仅在中断路径使用）。

同步更新日志行中的 `parallelism` 引用为 `executor.getCorePoolSize()`。

- [ ] **Step 4: 修复 ffmpeg 输出读取裸线程与空 catch**

替换 `transcode()` 中第 240-251 行的裸线程读取：

```java
StringBuilder processOutput = new StringBuilder();
CompletableFuture<Void> readFuture = CompletableFuture.runAsync(() -> {
    try (var br = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) {
            processOutput.append(line).append('\n');
        }
    } catch (IOException e) {
        log.warn("读取 ffmpeg 输出失败: {}", e.getMessage());
    }
}, executor);
```

（同时删除原裸线程的 `Thread reader = new Thread(...)`、`reader.setDaemon(true)`、`reader.start()` 三行。）

并将 `process.waitFor()` 之后：

```java
reader.join(5000);
```

替换为：

```java
try {
    readFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
} catch (Exception e) {
    log.warn("等待 ffmpeg 输出读取超时: {}", e.getMessage());
}
```

新增 import：`import java.util.concurrent.CompletableFuture;`

- [ ] **Step 5: 编译 Worker 模块**

```bash
.\mvnw -pl worker-service -am compile -q
```

Expected: BUILD SUCCESS，无编译错误。

- [ ] **Step 6: 运行 Worker 测试**

```bash
.\mvnw -pl worker-service -am test -q 2>&1 | Select-String "Tests run"
```

Expected: 26 tests 全绿（与 Task 1 基线一致）。

- [ ] **Step 7: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/config/WorkerExecutorConfig.java
git add worker-service/src/main/java/com/comicatlas/worker/file/transcode/VideoNormalizer.java
git commit -m "重构视频标准化：线程池改 Spring 托管并修复中断处理与裸线程"
```

---

### Task 7: Comic 实体枚举化

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/entity/Comic.java:25,30`
- Modify（编译错误驱动，参考清单）: `ComicServiceImpl.java`、`ComicListQuery.java`、`ComicDetailVO.java`、`ImportServiceImpl.java`、`ImportEventHandler.java`、`RecoveryEngine.java`、`AdminServiceImpl.java`、`TrashLifecycleService.java`、`ManagementCommandResultHandler.java`、`DeleteEventHandler.java` 等涉及 `Comic.status/sourceType` 的文件

**Interfaces:**
- Produces: `Comic.getStatus() → ComicStatus`、`Comic.getSourceType() → SourceType`（getter 类型变化，所有调用方编译错误驱动修复）

- [ ] **Step 1: 修改实体字段类型**

```java
private SourceType sourceType;
private ComicStatus status;
```

同步更新类 Javadoc 中的枚举引用。补 import：`import com.comicatlas.api.common.enums.ComicStatus;`、`import com.comicatlas.api.common.enums.SourceType;`

- [ ] **Step 2: 编译定位全部不兼容点**

```bash
.\mvnw -pl api-service -am compile -q
```

Expected: 编译错误列出所有字符串↔枚举不兼容点。逐个修复，模式如下：

| 现状（String 时代） | 修复后（枚举） |
|---|---|
| `comic.setStatus("READY")` | `comic.setStatus(ComicStatus.READY)` |
| `"READY".equals(comic.getStatus())` | `comic.getStatus() == ComicStatus.READY` |
| `comic.setSourceType("ZIP")` | `comic.setSourceType(SourceType.ZIP)` |
| `.eq(Comic::getStatus, "READY")` | `.eq(Comic::getStatus, ComicStatus.READY)` |
| `vo.setSourceType(comic.getSourceType())`（VO 为 String） | `vo.setSourceType(comic.getSourceType() != null ? comic.getSourceType().name() : null)` |
| `query.getStatus()`（查询入参 String → 枚举比较） | `ComicStatus.valueOf(query.getStatus())` 包 try/catch 或空值判空 |
| `status.startsWith("DELETE")` 之类字符串运算 | 改为枚举常量集合判断（如 `Set.of(ComicStatus.DELETING, ...)`） |

- [ ] **Step 3: 同步受影响的测试类**

实体字段枚举化后，引用 `Comic.status/sourceType` 的测试编译失败。运行 `.\mvnw -pl api-service -am test-compile -q` 定位，按 Task 10 Step 2 的修复模式同步（如 `comic.setStatus("READY")` → `comic.setStatus(ComicStatus.READY)`）。仅修与本任务实体（Comic）相关的测试，其余实体相关测试留给对应任务。

- [ ] **Step 4: 运行相关测试**

```bash
.\mvnw -pl api-service -Dtest='ComicManagementCrudIT,TrashLifecycleIT,ImportServiceTest' test -q 2>&1 | Select-String "Tests run"
```

Expected: 相关测试通过（Testcontainers 需要 Docker）。

- [ ] **Step 4: 提交**

```bash
git add api-service/src/main/java/com/comicatlas/api/comic/entity/Comic.java
git add api-service/src/main/java  # 仅包含本次枚举迁移改动的文件
git commit -m "漫画状态枚举化：Comic.status/sourceType 迁移到 ComicStatus/SourceType"
```

（提交前用 `git status` + `git diff --cached` 核对只包含枚举迁移相关文件。）

---

### Task 8: Media 实体枚举化

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/comic/entity/Media.java:30-35`
- Modify（编译错误驱动，参考清单）: `LqCompletedHandler.java`、`HqDeletedHandler.java`、`DeleteEventHandler.java`、`TrashLifecycleService.java`、`ManagementCommandResultHandler.java`、`UploadSessionService.java`、`ReaderServiceImpl.java`、`MediaOperationCommandService.java`、`ReaderDTO.java`、`MediaItemInfo.java`、`ChapterStorageDTO.java`、`ComicStorageDTO.java`、`ComicStorageQuery.java` 等涉及 `Media` 四状态字段的文件

**Interfaces:**
- Produces: `Media.getHqStatus() → HqStatus`、`getLqStatus() → LqStatus`、`getStatus() → MediaLifecycleStatus`、`getTranscodeStatus() → TranscodeStatus`

- [ ] **Step 1: 修改实体字段类型**

```java
private HqStatus hqStatus;
private LqStatus lqStatus;
private TranscodeStatus transcodeStatus;
private MediaLifecycleStatus status;
```

补 import：`com.comicatlas.api.common.enums.HqStatus`、`com.comicatlas.api.common.enums.LqStatus`、`com.comicatlas.common.enums.MediaLifecycleStatus`、`com.comicatlas.common.enums.TranscodeStatus`。更新类 Javadoc 中四个状态列的枚举引用。

- [ ] **Step 2: 编译定位全部不兼容点并修复**

```bash
.\mvnw -pl api-service -am compile -q
```

修复模式同 Task 7 Step 2。特别注意：
- `MediaOperationCommandService`/`ReaderService` 中的 `"READY"`/`"NOT_GENERATED"` 字面量 → 对应枚举。
- 聚合 DTO（`ChapterStorageDTO`/`ComicStorageDTO` 的 `hqStatus/lqStatus`）保持 String，从枚举 `.name()` 转换。
- `ReaderDTO.lqStatus`、`MediaItemInfo.lqStatus` 保持 String 契约，转换点加 `.name()`。

- [ ] **Step 3: 同步受影响的测试类**

运行 `.\mvnw -pl api-service -am test-compile -q` 定位引用 `Media` 四状态字段的测试，按 Task 10 Step 2 的修复模式同步（如 `media.setLqStatus("READY")` → `media.setLqStatus(LqStatus.READY)`）。仅修与本任务实体（Media）相关的测试。

- [ ] **Step 4: 运行相关测试**

```bash
.\mvnw -pl api-service -Dtest='MediaUploadManagementIT,TrashLifecycleIT,ReadingLifecycleCompatibilityIT' test -q 2>&1 | Select-String "Tests run"
```

Expected: 相关测试通过。

- [ ] **Step 4: 提交**

```bash
git add api-service/src/main/java/com/comicatlas/api/comic/entity/Media.java
git add api-service/src/main/java  # 仅包含本次枚举迁移改动的文件
git commit -m "媒体状态枚举化：Media 四状态字段迁移到 HqStatus/LqStatus/MediaLifecycleStatus/TranscodeStatus"
```

---

### Task 9: ImportTask 实体枚举化

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/entity/ImportTask.java:16,19`
- Modify（编译错误驱动，参考清单）: `ImportServiceImpl.java`、`ImportEventHandler.java`、`DirectoryScanTaskServiceImpl.java`、`OutboxRelay.java`、`ImportTaskVO.java`、`RecoveryTaskServiceImpl.java`、`LegacyTaskBackfillService.java` 等涉及 `ImportTask.status/sourceType` 的文件

**Interfaces:**
- Produces: `ImportTask.getStatus() → ImportTaskStatus`、`getSourceType() → SourceType`

- [ ] **Step 1: 修改实体字段类型并补 CANCELLED 枚举**

`ImportTaskStatus` 当前缺 `CANCELLED`，但生产代码实际写 `t.setStatus("CANCELLED")`（`ImportServiceImpl:296`）。先为枚举补充常量：

```java
public enum ImportTaskStatus {
    PENDING,
    PARSING,
    IMPORTING,
    SUCCESS,
    FAILED,
    CANCELLED;

    public boolean isTerminal() { return this == SUCCESS || this == FAILED || this == CANCELLED; }
}
```

再修改实体字段类型：

```java
private SourceType sourceType;
private ImportTaskStatus status;
```

补 import：`com.comicatlas.api.common.enums.ImportTaskStatus`、`com.comicatlas.api.common.enums.SourceType`。

- [ ] **Step 2: 编译定位全部不兼容点并修复**

```bash
.\mvnw -pl api-service -am compile -q
```

修复模式同 Task 7 Step 2。特别注意：
- `ImportServiceImpl` 的 `TERMINAL_STATUSES`（第 293 行 `"SUCCESS"/"FAILED"/"CANCELLED"`）→ `t.getStatus().isTerminal()` 或枚举 Set：`Set.of(ImportTaskStatus.SUCCESS, ImportTaskStatus.FAILED, ImportTaskStatus.CANCELLED)`。
- `ImportEventHandler.TERMINAL_STATUSES`（第 59 行）同理。
- `ImportTaskVO.status/sourceType` 保持 String，转换点加 `.name()`。
- `DirectoryScanTaskServiceImpl` 第 114 行 `"SUCCESS".equals(task.getStatus())` → `task.getStatus() == ImportTaskStatus.SUCCESS`。

- [ ] **Step 3: 同步受影响的测试类**

运行 `.\mvnw -pl api-service -am test-compile -q` 定位引用 `ImportTask.status/sourceType` 的测试，按 Task 10 Step 2 的修复模式同步（如 `task.setStatus("PENDING")` → `task.setStatus(ImportTaskStatus.PENDING)`）。仅修与本任务实体（ImportTask）相关的测试。

- [ ] **Step 4: 运行相关测试**

```bash
.\mvnw -pl api-service -Dtest='ImportServiceTest,UnifiedTaskCompatibilityIT,ImportEventHandlerCacheTest' test -q 2>&1 | Select-String "Tests run"
```

Expected: 相关测试通过。

- [ ] **Step 4: 提交**

```bash
git add api-service/src/main/java/com/comicatlas/api/importer/entity/ImportTask.java
git add api-service/src/main/java  # 仅包含本次枚举迁移改动的文件
git commit -m "导入任务状态枚举化：ImportTask.status/sourceType 迁移到 ImportTaskStatus/SourceType"
```

---

### Task 10: 全量验证与测试兜底清理

**Files:**
- Modify（兜底）: 16 个测试文件（79 处 `setStatus("...")`/`setSourceType("...")`/`setHqStatus("...")`/`setLqStatus("...")`/`setTranscodeStatus("...")` 中 Task 7-9 遗漏的），清单见 Task 1 探索结果（`ImportServiceTest`、`RecoveryEventHandlerTest`、`MediaOperationPipelineIT`、`UnifiedTaskCompatibilityIT`、`StorageLayoutContractIT`、`TrashLifecycleIT`、`RecoveryTaskServiceTest`、`TranscodeCompletedHandlerTest`、`MetadataExporterTest`、`TranscodeFailedHandlerTest`、`ReadingLifecycleCompatibilityIT`、`OutboxInboxRelayIT`、`CatalogChapterManagementIT`、`ComicReferenceCacheTest`、`ImportEventHandlerCacheTest`、`CatalogCacheTest`）

- [ ] **Step 1: 兜底编译扫描**

```bash
.\mvnw -pl api-service -am test-compile -q
```

Expected: 编译通过。若仍有 String→枚举不兼容点（Task 7-9 遗漏），按模式修复：

```text
task.setStatus("PENDING") → task.setStatus(ImportTaskStatus.PENDING)
comic.setStatus("READY") → comic.setStatus(ComicStatus.READY)
media.setLqStatus("READY") → media.setLqStatus(LqStatus.READY)
assertEquals("READY", vo.getStatus())（VO 仍 String）→ 不变
assertEquals("READY", entity.getStatus())（实体已枚举）→ assertEquals(ComicStatus.READY, entity.getStatus())
```

- [ ] **Step 3: 运行完整 API 模块测试**

```bash
.\mvnw -pl api-service -am verify -DskipTests=false -q 2>&1 | Select-String "Tests run:|BUILD"
```

Expected: `BUILD SUCCESS`，268 tests 0 failure 0 error。

- [ ] **Step 4: 运行 Worker 测试确认无回归**

```bash
.\mvnw -pl worker-service -am test -q 2>&1 | Select-String "Tests run"
```

Expected: 26 tests 全绿。

- [ ] **Step 5: 运行 Checkstyle 验证**

```bash
.\mvnw -DskipTests verify -q
```

Expected: 退出码 0，Checkstyle 0 违规。

- [ ] **Step 6: 提交**

```bash
git add api-service/src/test/java
git commit -m "测试同步枚举化并验证完整测试门禁全绿"
```

---

## 明确不做（本轮）

- API 认证授权（降级风险记录）。
- P2 全部：通配符 import、单行 if/for/while、语义命名残留、Checkstyle 增强、`VideoMetadataFixHandler` inSql、`ImageOptimizer`/`MediaAnalyzer` 裸线程。
- Worker 只读加固（只读 Mapper、专用只读数据库账号）。
- `RecoveryTask.status`、`Chapter.status`、`ExportTask.status`、`OutboxMessage.status`、`UploadSession.status` 等其余 String 状态字段的枚举化（超出评审点名范围，列入后续批次）。
