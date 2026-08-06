# 阿里 Java 规范第三次复审（third-review）整改实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复第三次复审 1 项 P1（外部进程中断清理未覆盖）与 3 条非阻断建议（命名清理、枚举可观察化、密码去默认值），保持全绿。

**Architecture:** 分 4 批独立交付：P1-A 新建统一外部进程工具类并接入 6 处（worker）；P2-A 语义命名清理 + 守卫（api）；P2-B safeValueOf 可观察化（api）；P2-C Worker 密码去默认值（worker+文档）。每批独立编译+测试+提交，最终全量 `clean verify`。

**Tech Stack:** Spring Boot 3、Java 21、JUnit 5、Maven Wrapper、Checkstyle、Testcontainers（仅既有 IT）。

## Global Constraints

- 语言：注释、提交信息始终使用中文；提交信息格式"动作 + 内容"。
- DB：VARCHAR 存枚举 name()，零迁移；前端契约不变（VO status/lifecycle 输出 String/枚举名）。
- 中断语义：`InterruptedException` 必须恢复中断标志或向上抛出；外部子进程（ffmpeg/ffprobe/aria2c/Go 工具）在中断/超时/取消时必须 `destroyForcibly()` 终止。禁止宽泛 `catch (Exception)` 吞掉中断。
- 禁止空 catch；禁止 `as any` 类 Java 等价物。
- Maven 命令在 PowerShell 下 `-D` 参数需引号包裹。
- 提交前 `git diff --check` 通过；每个任务只解决一个完整问题。
- `ExternalProcessRunner` 命名：`worker-service/src/main/java/com/comicatlas/worker/process/ExternalProcessRunner.java`（包 `com.comicatlas.worker.process`）。

---

### Task 1: P1-A 统一外部进程执行工具 + 6 处接入（worker-service）

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/process/ExternalProcessRunner.java`
- Create: `worker-service/src/test/java/com/comicatlas/worker/process/ExternalProcessRunnerTest.java`
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/parse/MediaAnalyzer.java`
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/transcode/VideoNormalizer.java`
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/download/TorrentDownloader.java`
- Modify: `worker-service/src/main/java/com/comicatlas/worker/image/ImageOptimizer.java`
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/download/DownloadContext.java`

**Interfaces:**
- Consumes: `processIoExecutor`（`@Qualifier("processIoExecutor")`，已存在于 `WorkerExecutorConfig`）；无其他外部依赖
- Produces:
  - `ExternalProcessRunner.run(ProcessBuilder, long timeoutSeconds)` → `ExternalProcessResult`（record：`int exitCode` + `String stdout`）
  - `ExternalProcessRunner` 为 `@Component`，构造器注入 `processIoExecutor`
  - 语义：正常返回退出码与已消费 stdout；超时抛 `ProcessTimeoutException`（内部 RuntimeException 子类）；中断抛 `InterruptedException`（恢复标志 + destroyForcibly 后向上传播）

- [ ] **Step 1: 创建 `ExternalProcessRunner`**

创建 `worker-service/src/main/java/com/comicatlas/worker/process/ExternalProcessRunner.java`：

```java
package com.comicatlas.worker.process;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 统一外部进程执行工具：启动 → 异步消费 stdout（防管道死锁）→ 带超时 waitFor。
 * <p>
 * 中断/超时语义（阿里规范）：
 * - InterruptedException 必须恢复中断标志 + destroyForcibly 终止子进程 + 向上传播；
 * - 超时未完成必须 destroyForcibly 终止子进程并抛明确异常；
 * - 禁止用宽泛 catch(Exception) 把中断当普通失败。
 */
@Slf4j
@Component
public class ExternalProcessRunner {

    private final ThreadPoolTaskExecutor processIoExecutor;

    public ExternalProcessRunner(@Qualifier("processIoExecutor") ThreadPoolTaskExecutor processIoExecutor) {
        this.processIoExecutor = processIoExecutor;
    }

    /** 外部进程执行结果：退出码 + 已消费的 stdout 内容。 */
    public record ExternalProcessResult(int exitCode, String stdout) {}

    /** 进程超时异常（内部异常，由调用方决定如何呈现）。 */
    public static class ProcessTimeoutException extends RuntimeException {
        public ProcessTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * 执行外部进程并等待完成。
     *
     * @param command        完整命令行（含可执行文件路径）
     * @param timeoutSeconds 超时秒数；<=0 表示不超时（不推荐，调用方应尽量给超时）
     * @return 进程退出码与 stdout 内容
     * @throws InterruptedException    执行被中断（中断标志已恢复，子进程已销毁）
     * @throws ProcessTimeoutException 超过 timeoutSeconds 未完成（子进程已销毁）
     * @throws RuntimeException        启动失败或 IO 异常
     */
    public ExternalProcessResult run(ProcessBuilder processBuilder, long timeoutSeconds)
            throws InterruptedException {
        processBuilder.redirectErrorStream(true);
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new RuntimeException("启动外部进程失败: " + e.getMessage(), e);
        }

        StringBuilder processOutput = new StringBuilder();
        CompletableFuture<Void> readFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    processOutput.append(line).append('\n');
                }
            } catch (IOException e) {
                log.warn("读取外部进程输出失败: {}", e.getMessage());
            }
        }, processIoExecutor);

        boolean finished;
        if (timeoutSeconds <= 0) {
            try {
                process.waitFor();
                finished = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw e;
            }
        } else {
            try {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw e;
            }
            if (!finished) {
                process.destroyForcibly();
                throw new ProcessTimeoutException("外部进程执行超时 (" + timeoutSeconds + "s)");
            }
        }

        // 等待 stdout 消费完成；中断时恢复标志（子进程已退出，无需再销毁）
        try {
            readFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("等待外部进程输出读取超时: {}", e.getMessage());
        }

        return new ExternalProcessResult(process.exitValue(), processOutput.toString());
    }
}
```

- [ ] **Step 2: 创建 `ExternalProcessRunnerTest`**

创建 `worker-service/src/test/java/com/comicatlas/worker/process/ExternalProcessRunnerTest.java`：

```java
package com.comicatlas.worker.process;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外部进程执行工具单元测试：正常/超时/中断三路径（无 Docker，纯 JVM 子进程）。
 */
@DisplayName("ExternalProcessRunnerTest — 外部进程中断/超时清理")
class ExternalProcessRunnerTest {

    private ExternalProcessRunner runner;

    @BeforeEach
    void setUp() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("test-process-io-");
        executor.initialize();
        runner = new ExternalProcessRunner(executor);
    }

    @Test
    @DisplayName("正常路径：进程成功退出并返回 stdout")
    void run_success() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "echo hello");
        ExternalProcessRunner.ExternalProcessResult result = runner.run(pb, 10);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello");
    }

    @Test
    @DisplayName("非零退出码正常返回（不抛异常）")
    void run_nonZeroExit() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "exit 3");
        ExternalProcessRunner.ExternalProcessResult result = runner.run(pb, 10);
        assertThat(result.exitCode()).isEqualTo(3);
    }

    @Test
    @DisplayName("超时路径：destroyForcibly 终止子进程并抛 ProcessTimeoutException")
    void run_timeout() {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ping -n 10 127.0.0.1 > nul");
        assertThatThrownBy(() -> runner.run(pb, 1))
                .isInstanceOf(ExternalProcessRunner.ProcessTimeoutException.class);
    }

    @Test
    @DisplayName("中断路径：恢复中断标志并抛 InterruptedException")
    void run_interrupt() {
        AtomicBoolean interruptRestored = new AtomicBoolean(false);
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ping -n 10 127.0.0.1 > nul");
        Thread t = new Thread(() -> {
            try {
                runner.run(pb, 10);
            } catch (InterruptedException e) {
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Exception ignored) {
                // 其他异常不应出现
            }
        });
        t.start();
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        t.interrupt();
        try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(interruptRestored).as("中断标志应恢复").isTrue();
    }
}
```

- [ ] **Step 3: 接入 `MediaAnalyzer.analyzeVideo()`**

将（L108-151 的 ProcessBuilder 构建 + L118-150 的手写 waitFor/readFuture）重构为：
- 保留 ProcessBuilder 构建（ffprobe 命令，不 redirectErrorStream——runner 内部已做）
- 删除 `redirectErrorStream(true)`（runner 内部处理）、删除手写 readFuture/waitFor 块
- 替换为：

```java
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    ffprobe,
                    "-v", "error",
                    "-show_format", "-show_streams",
                    "-of", "json",
                    file.toAbsolutePath().toString());
            ExternalProcessRunner.ExternalProcessResult result =
                    processRunner.run(processBuilder, FFPROBE_TIMEOUT_SECONDS);
            if (result.exitCode() != 0) {
                log.warn("ffprobe exit={} for {}", result.exitCode(), file);
                return videoFallback(name, ext, size, "exit-" + result.exitCode());
            }
            return parseFfprobeJson(name, ext, size, result.stdout());
        } catch (InterruptedException e) {
            // 中断已恢复标志，进程已销毁
            return videoFallback(name, ext, size, "interrupted");
        } catch (ExternalProcessRunner.ProcessTimeoutException e) {
            log.warn("ffprobe 读取 {} 超时 ({}s)", file, FFPROBE_TIMEOUT_SECONDS);
            return videoFallback(name, ext, size, "timeout");
        } catch (Exception e) {
            log.warn("ffprobe 读取 {} 失败", file, e);
            return videoFallback(name, ext, size, "exception");
        }
```
- 类添加 `private final ExternalProcessRunner processRunner;` 字段 + 构造器参数（L47-53 构造器注入）

- [ ] **Step 4: 接入 `VideoNormalizer.transcode()`**

将（L234-264 的 ProcessBuilder 构建 + waitFor/readFuture/exitCode 判断）重构为：
- 保留命令构建（L220-232）
- 删除 `redirectErrorStream(true)`（runner 内部做）与手写块
- 替换为：

```java
        ExternalProcessRunner.ExternalProcessResult result =
                processRunner.run(processBuilder, 0);  // 转码无固定超时，中断由调用方取消管理
        if (result.exitCode() != 0) {
            String tail = result.stdout().length() > 500
                    ? result.stdout().substring(result.stdout().length() - 500)
                    : result.stdout();
            throw new RuntimeException("ffmpeg exit " + result.exitCode() + ": " + tail.trim());
        }
        if (!isNonEmpty(output)) {
            throw new RuntimeException("转码输出文件为空: " + output.getFileName());
        }
```
- 注意：`transcode()` 已声明 `throws Exception`，`InterruptedException` 从 runner 直抛后由 `transcodeToTemp()` 的 `catch (InterruptedException)`（L151）处理——该处已正确恢复标志并包装传播
- 类添加 `processRunner` 字段 + 构造器参数（L48-55）

- [ ] **Step 5: 接入 `TorrentDownloader.download()`**

将（L31-37）：
```java
        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        processBuilder.inheritIO();
        Process process = processBuilder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0 && exitCode != 143) { // 143 = SIGTERM
            throw new RuntimeException("aria2c exit: " + exitCode);
        }
```
替换为（runner 不支持 inheritIO——改为捕获 stdout，语义等价；`--stop-with-process` 仍保证进程生命周期绑定）：
```java
        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        ExternalProcessRunner.ExternalProcessResult result = processRunner.run(processBuilder, 0);
        int exitCode = result.exitCode();
        if (exitCode != 0 && exitCode != 143) { // 143 = SIGTERM
            throw new RuntimeException("aria2c exit: " + exitCode);
        }
```
- `download` 签名已 `throws Exception`，中断直抛向上传播
- 类添加 `processRunner` 字段（`@RequiredArgsConstructor` 自动注入 final 字段）

- [ ] **Step 6: 接入 `ImageOptimizer.runOptimizer()` / `generateCover()` / `generateCoverFromVideo()`**

三个方法的手写 waitFor/readFuture 块统一替换为 `processRunner.run(...)`：
- `runOptimizer()`（L93-159）：`run(processBuilder, LQ_TIMEOUT_SECONDS)` → 移除 L119-138 手写块，用 `result.exitCode()`/`result.stdout()`；exitCode==2 与 JSON 解析逻辑保留（stdout 改从 result 取）
- `generateCover()`（L201-249）：`run(processBuilder, LQ_TIMEOUT_SECONDS)` → 移除 L225-243 手写块
- `generateCoverFromVideo()`（L311-341）：`run(processBuilder, 120)` → 移除 L315-336 手写块；`InterruptedException` 由方法外层 `catch (Exception)`（L347）捕获但**必须先恢复标志**——注意：runner 内已恢复标志，外层 catch 只是兜底记录，不再吞中断语义（标志已恢复，子进程已销毁）
- 类添加 `processRunner` 字段 + 构造器参数（L36-44）

- [ ] **Step 7: 修复 `DownloadContext.download()` 中断吞掉**

将（L29-40）：
```java
        if (metadata != null && metadata.get("archiverKey") != null) {
            try {
                ...
                long bytes = archiveDownloader.download(gid, token, archiverKey, zipFile);
                log.info("Archive downloaded: {} bytes", bytes);
                return new DownloadResult(bytes, "ARCHIVER", metadata);
            } catch (Exception e) {
                log.warn("Archiver failed, fallback to torrent: {}", e.getMessage());
            }
        }
```
替换为：
```java
        if (metadata != null && metadata.get("archiverKey") != null) {
            try {
                ...
                long bytes = archiveDownloader.download(gid, token, archiverKey, zipFile);
                log.info("Archive downloaded: {} bytes", bytes);
                return new DownloadResult(bytes, "ARCHIVER", metadata);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;   // 中断不静默回退，向上传播
            } catch (Exception e) {
                log.warn("Archiver failed, fallback to torrent: {}", e.getMessage());
            }
        }
```
- `download` 签名已 `throws Exception`，直抛合法

- [ ] **Step 8: 编译 + 跑 Worker 测试**

```bash
".\mvnw -pl worker-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
".\mvnw -pl worker-service -am test \"-Dtest=ExternalProcessRunnerTest\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: COMPILE_OK + ExternalProcessRunnerTest 4/4 通过。若 Windows 子进程行为差异导致超时/中断测试不稳定，调整 ping 参数（`ping -n 10` → `ping -n 20`）保证子进程存活时间 > 超时。

- [ ] **Step 9: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/process worker-service/src/test/java/com/comicatlas/worker/process worker-service/src/main/java/com/comicatlas/worker/file/parse/MediaAnalyzer.java worker-service/src/main/java/com/comicatlas/worker/file/transcode/VideoNormalizer.java worker-service/src/main/java/com/comicatlas/worker/file/download/TorrentDownloader.java worker-service/src/main/java/com/comicatlas/worker/image/ImageOptimizer.java worker-service/src/main/java/com/comicatlas/worker/file/download/DownloadContext.java
git commit -m "统一外部进程中断/超时清理：新增 ExternalProcessRunner 并接入 6 处"
```

---

### Task 2: P2-A 语义命名清理 + 守卫补规则（api-service）

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/upload/UploadStorageService.java`（L157 `md`→`messageDigest`）
- Modify: `api-service/src/main/java/com/comicatlas/api/management/service/ManagementTaskService.java`（L746 `md`→`messageDigest`、L365 `op`→`operation`）
- Modify: `api-service/src/main/java/com/comicatlas/api/importer/service/impl/ImportServiceImpl.java`（L434 `md`→`messageDigest`）
- Modify: `api-service/src/main/java/com/comicatlas/api/upload/UploadSessionService.java`（L309/L371 `op`→`operation`）
- Modify: `api-service/src/main/java/com/comicatlas/api/management/trash/TrashLifecycleService.java`（L256/447/462/480/494 `op`→`operation`、L486 `t`→`target`）
- Modify: `api-service/src/main/java/com/comicatlas/api/management/operation/MediaOperationCommandService.java`（L63/94/326/337/347 `op`→`operation`、L348 `t`→`target`）
- Modify: `api-service/src/main/java/com/comicatlas/api/management/service/LegacyTaskBackfillService.java`（L164 `op`→`operation`）
- Modify: `api-service/src/main/java/com/comicatlas/api/management/batch/service/BatchOperationService.java`（L203 `t`→`target`、L213/224 `op`→`operation`）
- Modify: `api-service/src/main/java/com/comicatlas/api/management/batch/service/BatchEligibilityChecker.java`（L74/85 `op`→`operation`）
- Modify: `api-service/src/main/java/com/comicatlas/api/reader/service/impl/HistoryServiceImpl.java`（L84 `rh`→`history`）
- Modify: `api-service/src/test/java/com/comicatlas/api/config/SemanticNamingContractTest.java`（BANNED + DETECTION_FIXTURES）

**Interfaces:**
- Consumes: 无
- Produces: 生产代码零 `md`/`op`/`t`/`rh` 短名（上述类型场景）；守卫新增 4 条规则，两表同步。

- [ ] **Step 1: 改名 `MessageDigest md` → `messageDigest`（3 处）**

- `UploadStorageService` L157：`MessageDigest md = ...` → `messageDigest`，方法体内 `md.update(...)`/`md.digest()` 引用同步
- `ManagementTaskService` L746、`ImportServiceImpl` L434：同上

- [ ] **Step 2: 改名 `TaskType op` → `operation`（18 处声明）**

全部为局部变量或 private 方法参数，改名不改变公共契约。逐文件执行（用 `git grep "TaskType op\b"` 定位，每处声明 + 方法体引用同步）：
- `UploadSessionService`：L309（局部）、L371（参数）
- `TrashLifecycleService`：L256/447/462/480/494（参数）
- `MediaOperationCommandService`：L63/L94（局部）、L326/337/347（参数）
- `ManagementTaskService`：L365（局部）
- `LegacyTaskBackfillService`：L164（参数）
- `BatchOperationService`：L213/224（参数）
- `BatchEligibilityChecker`：L74/85（参数）

注意：`ManagementTaskService` L111 的 `TaskType opType` 不是 `op`，不改。

- [ ] **Step 3: 改名 `TaskTarget t` → `target`（3 处）**

- `TrashLifecycleService` L486、`MediaOperationCommandService` L348、`BatchOperationService` L203：`CreateManagementTaskRequest.TaskTarget t = ...` → `target`，方法体内 `t.setXxx(...)` 引用同步

- [ ] **Step 4: 改名 `ReadingHistory rh` → `history`（1 处）**

- `HistoryServiceImpl` L84：`ReadingHistory rh = new ReadingHistory();` → `history`，方法体引用同步

- [ ] **Step 5: 守卫表新增 4 条规则**

`SemanticNamingContractTest` 的 `BANNED` 与 `DETECTION_FIXTURES` 两表同一位置（末尾）各追加：
```java
            new BannedPattern("MessageDigest", "md", "messageDigest"),
            new BannedPattern("TaskType", "op", "operation"),
            new BannedPattern("TaskTarget", "t", "target"),
            new BannedPattern("ReadingHistory", "rh", "history"),
```
两表逐项一致（含顺序），`containsExactlyElementsOf` 断言强制。

- [ ] **Step 6: 编译 + 跑守卫与相关测试**

```bash
".\mvnw -pl api-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
".\mvnw -pl api-service -am test \"-Dtest=SemanticNamingContractTest,UploadSessionServiceTest,ManagementTaskServiceIT,BatchOperationIT\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: BUILD SUCCESS。若某测试类不存在，从命令中移除（以实际存在为准）。

- [ ] **Step 7: 提交**

```bash
git add api-service/src/main/java/com/comicatlas/api/upload/UploadStorageService.java api-service/src/main/java/com/comicatlas/api/management/service/ManagementTaskService.java api-service/src/main/java/com/comicatlas/api/importer/service/impl/ImportServiceImpl.java api-service/src/main/java/com/comicatlas/api/upload/UploadSessionService.java api-service/src/main/java/com/comicatlas/api/management/trash/TrashLifecycleService.java api-service/src/main/java/com/comicatlas/api/management/operation/MediaOperationCommandService.java api-service/src/main/java/com/comicatlas/api/management/service/LegacyTaskBackfillService.java api-service/src/main/java/com/comicatlas/api/management/batch/service/BatchOperationService.java api-service/src/main/java/com/comicatlas/api/management/batch/service/BatchEligibilityChecker.java api-service/src/main/java/com/comicatlas/api/reader/service/impl/HistoryServiceImpl.java api-service/src/test/java/com/comicatlas/api/config/SemanticNamingContractTest.java
git commit -m "清理 md/op/t/rh 语义短名残留，命名守卫补充 4 条规则"
```

---

### Task 3: P2-B safeValueOf 未知枚举值可观察化（api-service）

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/common/handler/EnumTypeHandlers.java`

**Interfaces:**
- Consumes: 无（14 个 Handler 共用 `safeValueOf`）
- Produces: 未知枚举值返回 null 前记录 `log.warn`（含类型名与原始值），保持读取链路不破坏。

- [ ] **Step 1: 添加 `@Slf4j` 与 import**

类头添加 `import lombok.extern.slf4j.Slf4j;`，`public class EnumTypeHandlers` 前添加 `@Slf4j` 注解。

- [ ] **Step 2: 修改 `safeValueOf`**

将（L150-153）：
```java
    private static <T extends Enum<T>> T safeValueOf(Class<T> clazz, String value) {
        if (value == null) { return null; }
        try { return Enum.valueOf(clazz, value); } catch (IllegalArgumentException e) { return null; }
    }
```
替换为：
```java
    private static <T extends Enum<T>> T safeValueOf(Class<T> clazz, String value) {
        if (value == null) { return null; }
        try {
            return Enum.valueOf(clazz, value);
        } catch (IllegalArgumentException e) {
            log.warn("数据库存在未知枚举值: type={}, value={}（已按 null 处理，建议核查脏数据）",
                    clazz.getSimpleName(), value);
            return null;
        }
    }
```
`@Slf4j` 生成的静态 `log` 在 static 方法中可用。

- [ ] **Step 3: 编译 + 相关测试**

```bash
".\mvnw -pl api-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
".\mvnw -pl api-service -am test \"-Dtest=SemanticNamingContractTest,DatabaseMigrationTest\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

```bash
git add api-service/src/main/java/com/comicatlas/api/common/handler/EnumTypeHandlers.java
git commit -m "未知枚举值从静默 null 改为可观察告警，便于发现脏数据"
```

---

### Task 4: P2-C Worker 密码去默认值 + 文档（worker-service）

**Files:**
- Modify: `worker-service/src/main/resources/application.yml`
- Modify: `worker-service/src/test/java/com/comicatlas/worker/config/WorkerDataSourceProductionConfigTest.java`
- Modify: `README.md`、`docs/operations/management.md`（若 `.env.example` 存在则一并）

**Interfaces:**
- Consumes: Task 2（第二次复审）的配置契约测试
- Produces: `spring.datasource.password` 无默认值（`${MYSQL_PASS}`），未配置时启动报错强制提供；契约测试断言不含固定默认密码。

- [ ] **Step 1: 修改 `application.yml`**

将（L35-38）：
```yaml
  datasource:
    # Worker 只读：默认使用独立只读账号（仅 GRANT SELECT），应用层强制 HikariCP read-only
    url: jdbc:mysql://${MYSQL_HOST:localhost}:3306/comic_atlas?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USER:comicatlas_ro}
    password: ${MYSQL_PASS:comicatlas_ro_pass}
    driver-class-name: com.mysql.cj.jdbc.Driver
```
替换为：
```yaml
  datasource:
    # Worker 只读：默认使用独立只读账号（仅 GRANT SELECT），应用层强制 HikariCP read-only；
    # 密码必须由环境变量 MYSQL_PASS 提供（禁止固定默认密码）
    url: jdbc:mysql://${MYSQL_HOST:localhost}:3306/comic_atlas?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USER:comicatlas_ro}
    password: ${MYSQL_PASS}
    driver-class-name: com.mysql.cj.jdbc.Driver
```

- [ ] **Step 2: 契约测试补充密码断言**

`WorkerDataSourceProductionConfigTest` 新增测试：
```java
    @Test
    @DisplayName("生产默认密码不应为固定默认值")
    void productionDefaultPasswordHasNoFixedDefault() throws IOException {
        String password = resolve("spring.datasource.password");
        assertThat(password).as("Worker 密码必须由环境变量提供，禁止固定默认密码").isNotNull();
        assertThat(password.toLowerCase()).doesNotContain("comicatlas_ro_pass");
    }
```
注：`resolve("spring.datasource.password")` 返回原始 YAML 文本 `${MYSQL_PASS}`——断言其不含 `comicatlas_ro_pass` 即验证无固定默认密码。

- [ ] **Step 3: 运行契约测试**

```bash
".\mvnw -pl worker-service -am test \"-Dtest=WorkerDataSourceProductionConfigTest\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: BUILD SUCCESS，3 测试通过。

- [ ] **Step 4: 同步文档**

- `README.md` `.env` 示例：若列出 `REMOTE_MYSQL_PASSWORD` 或 Worker 相关变量，注明 Worker 密码必须显式设置
- `docs/operations/management.md`"数据库账号"小节：注明 Worker 只读账号密码必须由 `MYSQL_PASS` 环境变量提供，无默认值

- [ ] **Step 5: 提交**

```bash
git add worker-service/src/main/resources/application.yml worker-service/src/test/java/com/comicatlas/worker/config/WorkerDataSourceProductionConfigTest.java README.md docs/operations/management.md .env.example 2>/dev/null
git commit -m "Worker 数据库密码去除固定默认值，改为环境变量必填"
```
若 `.env.example` 不存在，从 git add 移除该路径。

---

### Task 5: 最终全量验证

**Files:**
- 无代码改动（仅验证）

**Interfaces:**
- Consumes: Task 1-4 全部
- Produces: 全绿验证结论

- [ ] **Step 1: 全量 `clean verify`**

```bash
".\mvnw clean verify -DskipTests=false" ; echo "EXIT=$LASTEXITCODE"
```
Expected: BUILD SUCCESS，五模块 Checkstyle 0，API 272+ + Worker 28+ 全绿，0 failures/errors。

- [ ] **Step 2: 边界复查**

```bash
git grep -n "MessageDigest md\b\|TaskType op\b\|TaskTarget t\b\|ReadingHistory rh\b" -- "*.java" | Select-String -NotMatch "test"
git diff --check
git log --oneline -8
```
Expected: 生产代码无上述短名；`git diff --check` 无输出。

- [ ] **Step 3: 汇报**

汇总各任务 commit SHA、测试结论、Checkstyle 结果，报告用户。

---

## Self-Review

**1. Spec coverage:**
- P1-A 统一工具类 + 6 处接入 → Task 1（含回归测试）✅
- P2-A 命名清理 + 守卫 → Task 2 ✅
- P2-B safeValueOf 可观察化 → Task 3 ✅
- P2-C 密码去默认值 → Task 4 ✅
- 最终验证 → Task 5 ✅

**2. Placeholder scan:** 无 TBD/TODO；每个代码步骤含完整代码块 ✅

**3. Type consistency:**
- `ExternalProcessResult` record（exitCode/stdout）与 `ExternalProcessRunner.ProcessTimeoutException` 在 Task 1 定义并被 5 处接入消费 ✅
- `processRunner` 字段在 MediaAnalyzer/VideoNormalizer/TorrentDownloader/ImageOptimizer 均注入同名类型 ✅
- 守卫新增 4 条 `BannedPattern` 与生产改名一一对应 ✅
- `safeValueOf` 修改一处，14 个 Handler 全部生效 ✅
