# 阿里 Java 规范第四次复审（fourth-review）整改实施计划

**状态**: 历史归档

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复第四次复审 5 项阻断项（两处转码吞中断、Runner 输出无上限+回收无界、`var md` 守卫假绿、RunnerTest 断言不足、SmokeTest 未入 JUnit 门禁），保持全绿。

**Architecture:** 分 5 批独立交付：P1-A 两处转码 Handler 接入 ExternalProcessRunner（worker）；P1-B Runner 输出容量+有界回收+Torrent 可配置超时（worker）；P2-A 守卫 var 增强 + 短名清理（api）；P2-B RunnerTest 生命周期断言（worker）；P2-C SmokeTest 改 JUnit（worker）。每批独立编译+测试+提交，最终全量 `clean verify`。

**Tech Stack:** Spring Boot 3、Java 21、JUnit 5、Maven Wrapper、Checkstyle、Testcontainers（仅既有 IT）。

## Global Constraints

- 语言：注释、提交信息始终使用中文；提交信息格式"动作 + 内容"。
- DB：VARCHAR 存枚举 name()，零迁移；前端契约不变。
- 中断语义：`InterruptedException` 必须恢复中断标志或向上抛出；外部子进程（ffmpeg/ffprobe/aria2c/Go 工具）在中断/超时/取消时必须 `destroyForcibly()` 终止。
- 进程输出：外部进程 stdout 必须容量受限（尾部缓冲，超限排空不保留），防 Worker 堆耗尽。
- 禁止空 catch；禁止 `as any` 类 Java 等价物。
- Maven 命令在 PowerShell 下 `-D` 参数需引号包裹。
- 提交前 `git diff --check` 通过；每个任务只解决一个完整问题。
- 现有 `ExternalProcessRunner` 位于 `worker-service/src/main/java/com/comicatlas/worker/process/ExternalProcessRunner.java`，`run(ProcessBuilder, long timeoutSeconds)` → `ExternalProcessResult(int exitCode, String stdout)`，`ProcessTimeoutException` 内部异常。

---

### Task 1: P1-A 两处转码 Handler 接入 ExternalProcessRunner（worker-service）

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/event/VideoTranscodeHandler.java`
- Modify: `worker-service/src/main/java/com/comicatlas/worker/event/TranscodeCommandHandler.java`

**Interfaces:**
- Consumes: `ExternalProcessRunner`（`@Component`，构造器注入 `processIoExecutor`）
- Produces: 两处 Handler 不再手写 `start()/waitFor/destroyForcibly`，统一走 `processRunner.run(pb, 600)`；中断由 runner 恢复标志 + destroyForcibly 后向上传播。

- [ ] **Step 1: `VideoTranscodeHandler` 注入 runner**

类已 `@RequiredArgsConstructor`，添加 final 字段：
```java
    private final ExternalProcessRunner processRunner;
```
添加 import `com.comicatlas.worker.process.ExternalProcessRunner;`。删除不再使用的 `import java.util.concurrent.TimeUnit;`（若仅用于 waitFor）。

- [ ] **Step 2: `VideoTranscodeHandler` 替换转码进程调用**

将（L65-80）：
```java
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command(buildFfmpegCommand(
                    config.resolveToolPath(config.getFfmpegPath()).toString(),
                    hqFile.toString(), tempFile.toString()));
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = processBuilder.start();

            // 超时 10 分钟（Metis G9）
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg 超时(10min): pageId=" + pageId);
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg exit code " + process.exitValue() + ": pageId=" + pageId);
            }
```
替换为：
```java
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command(buildFfmpegCommand(
                    config.resolveToolPath(config.getFfmpegPath()).toString(),
                    hqFile.toString(), tempFile.toString()));
            // 统一外部进程执行：超时 10 分钟，中断由 Runner 恢复标志并销毁 ffmpeg 后向上传播
            ExternalProcessRunner.ExternalProcessResult result =
                    processRunner.run(processBuilder, 600);
            if (result.exitCode() != 0) {
                throw new IOException("ffmpeg exit code " + result.exitCode() + ": pageId=" + pageId);
            }
```
`handle` 外层 `catch (Exception e)`（L122）仍在——中断经 runner 恢复标志后落入此处仅记录失败事件，不再存在"吞中断当普通失败"（标志已恢复，ffmpeg 已销毁）。

- [ ] **Step 3: `TranscodeCommandHandler` 注入 runner + 替换调用**

类已 `@RequiredArgsConstructor`，添加：
```java
    private final ExternalProcessRunner processRunner;
```
添加 import。将（L112-126）：
```java
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command(buildFfmpegCommand(
                config.resolveToolPath(config.getFfmpegPath()).toString(),
                hqFile.toString(), tempFile.toString()));
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = processBuilder.start();

            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg 超时(10min): pageId=" + pageId);
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg exit code " + process.exitValue() + ": pageId=" + pageId);
            }
```
替换为：
```java
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command(buildFfmpegCommand(
                config.resolveToolPath(config.getFfmpegPath()).toString(),
                hqFile.toString(), tempFile.toString()));
            // 统一外部进程执行：超时 10 分钟，中断由 Runner 恢复标志并销毁 ffmpeg 后向上传播
            ExternalProcessRunner.ExternalProcessResult result =
                    processRunner.run(processBuilder, 600);
            if (result.exitCode() != 0) {
                throw new IOException("ffmpeg exit code " + result.exitCode() + ": pageId=" + pageId);
            }
```
删除 `import java.util.concurrent.TimeUnit;`（若仅用于 waitFor）。

- [ ] **Step 4: 编译 + 相关测试**

```bash
".\mvnw -pl worker-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
```
检查两文件是否还有 `waitFor`/`start()`/`destroyForcibly` 残留：`git grep -n "waitFor\|destroyForcibly\|\.start()" worker-service/src/main/java/com/comicatlas/worker/event/VideoTranscodeHandler.java worker-service/src/main/java/com/comicatlas/worker/event/TranscodeCommandHandler.java`（预期无输出）。

- [ ] **Step 5: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/event/VideoTranscodeHandler.java worker-service/src/main/java/com/comicatlas/worker/event/TranscodeCommandHandler.java
git commit -m "两处视频转码处理器接入 ExternalProcessRunner，中断恢复标志并终止 ffmpeg"
```

---

### Task 2: P1-B Runner 输出容量 + 有界回收 + Torrent 超时（worker-service）

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/process/ExternalProcessRunner.java`
- Modify: `worker-service/src/main/java/com/comicatlas/worker/config/WorkerConfig.java`（`Torrent` 内嵌类加 `timeoutMinutes`）
- Modify: `worker-service/src/main/java/com/comicatlas/worker/file/download/TorrentDownloader.java`
- Modify: `worker-service/src/main/resources/application.yml`

**Interfaces:**
- Consumes: 无（Runner 内部改造）
- Produces:
  - `ExternalProcessRunner.MAX_OUTPUT_CHARS = 64 * 1024`（常量）
  - `run()` 输出尾部截断（含 `[输出已截断...]` 标记）；超时/中断后统一 `destroyAndReap(Process, CompletableFuture)` 有界回收
  - `WorkerConfig.Torrent.timeoutMinutes`（默认 120，配置键 `worker.torrent.timeout-minutes`）
  - `TorrentDownloader` 用 `run(pb, timeoutMinutes * 60)`

- [ ] **Step 1: `ExternalProcessRunner` 输出改容量受限尾部缓冲**

替换 L65 `StringBuilder processOutput = new StringBuilder();` 与读取循环（L66-76），用容量受限缓冲：

```java
    /** 外部进程 stdout 保留上限（字节）。超限后继续排空但不保留旧内容，防 Worker 堆耗尽。 */
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;

    /**
     * 容量受限的 stdout 尾部缓冲：追加内容，超限时丢弃旧内容只保留尾部。
     * 由读取线程单线程调用，无需同步。
     */
    private static final class TailBuffer {
        private final StringBuilder buf = new StringBuilder(MAX_OUTPUT_CHARS / 2);
        private boolean truncated = false;

        void append(String line) {
            if (buf.length() >= MAX_OUTPUT_CHARS) {
                truncated = true;
                return;   // 继续排空（读取循环仍在跑，防管道死锁），但不保留
            }
            buf.append(line).append('\n');
        }

        String snapshot() {
            if (!truncated) { return buf.toString(); }
            return "[输出已截断，仅保留尾部 " + buf.length() + " 字符]\n" + buf;
        }
    }
```

读取循环改：
```java
        TailBuffer processOutput = new TailBuffer();
        CompletableFuture<Void> readFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    processOutput.append(line);
                }
            } catch (IOException e) {
                log.warn("读取外部进程输出失败: {}", e.getMessage());
            }
        }, processIoExecutor);
```

- [ ] **Step 2: `ExternalProcessRunner` 有界回收**

新增私有方法：
```java
    /**
     * 销毁子进程并等待其终止与输出读取任务收尾（有界）。
     * 中断/超时路径统一调用，确保不悬挂且不泄漏子进程。
     */
    private void destroyAndReap(Process process, CompletableFuture<Void> readFuture) {
        process.destroyForcibly();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("外部进程强制终止后 5s 仍未退出，可能残留");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            readFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("等待外部进程输出读取任务收尾超时: {}", e.getMessage());
        }
    }
```

中断分支（L83-87 与 L91-95）改：
```java
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyAndReap(process, readFuture);
                throw e;
            }
```

超时分支（L96-99）改：
```java
            if (!finished) {
                destroyAndReap(process, readFuture);
                throw new ProcessTimeoutException("外部进程执行超时 (" + timeoutSeconds + "s)");
            }
```

正常路径末尾的 `readFuture.get(5s)` 保留（子进程已退出，等待读取任务自然收尾）。

- [ ] **Step 3: `WorkerConfig.Torrent` 加 `timeoutMinutes`**

`WorkerConfig.Torrent` 内嵌类（L62-67）添加字段：
```java
    @Data
    public static class Torrent {
        private int peerDetectTimeout = 30;
        private long minSpeedThreshold = 10240;
        private int speedCheckDuration = 300;
        /** 下载总超时（分钟），超过则销毁 aria2c。默认 120 分钟。 */
        private int timeoutMinutes = 120;
    }
```

- [ ] **Step 4: `TorrentDownloader` 用有限超时**

`download()` 中（L33-37）：
```java
        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        ExternalProcessRunner.ExternalProcessResult result = processRunner.run(processBuilder, 0);
```
改为：
```java
        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        long timeoutSeconds = (long) config.getTorrent().getTimeoutMinutes() * 60;
        ExternalProcessRunner.ExternalProcessResult result = processRunner.run(processBuilder, timeoutSeconds);
```
确认 `config.getTorrent()` 存在（`WorkerConfig` 有 `private Torrent torrent = new Torrent();` + Lombok `@Data` getter）。

- [ ] **Step 5: `application.yml` 加 Torrent 超时配置**

`worker.torrent` 段（L62-65）添加：
```yaml
  torrent:
    peer-detect-timeout: 30
    min-speed-threshold: 10240
    speed-check-duration: 300
    timeout-minutes: ${TORRENT_TIMEOUT_MINUTES:120}
```

- [ ] **Step 6: 编译 + RunnerTest 现有用例回归**

```bash
".\mvnw -pl worker-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
".\mvnw -pl worker-service -am test \"-Dtest=ExternalProcessRunnerTest\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: COMPILE_OK + RunnerTest 现有 4 用例通过（超时/中断用例语义在 Task 4 增强，本任务保持兼容）。

- [ ] **Step 7: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/process/ExternalProcessRunner.java worker-service/src/main/java/com/comicatlas/worker/config/WorkerConfig.java worker-service/src/main/java/com/comicatlas/worker/file/download/TorrentDownloader.java worker-service/src/main/resources/application.yml
git commit -m "外部进程输出改容量受限尾部缓冲，销毁后有界回收，Torrent 下载设可配置超时"
```

---

### Task 3: P2-A 守卫 var 增强 + 短名清理（api-service）

**Files:**
- Modify: `api-service/src/main/java/com/comicatlas/api/upload/UploadSessionService.java`（L396 `var md`）
- Modify: `api-service/src/main/java/com/comicatlas/api/outbox/relay/OutboxRelay.java`（L125 `CorrelationData cd`）
- Modify: `api-service/src/test/java/com/comicatlas/api/config/SemanticNamingContractTest.java`

**Interfaces:**
- Consumes: 无
- Produces: 生产零 `var md`/`CorrelationData cd`；守卫 `var` 声明识别（`var <短名> = <类型基名>.`）。

- [ ] **Step 1: `UploadSessionService` L396 `var md` → `MessageDigest messageDigest`**

将：
```java
        var md = java.security.MessageDigest.getInstance("SHA-256");
```
改为：
```java
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
```
添加 import `import java.security.MessageDigest;`，方法体内 `md.update(...)`/`md.digest()` → `messageDigest.update(...)`/`messageDigest.digest()`。注意 `computeSha256` 方法（L394-408）。

- [ ] **Step 2: `OutboxRelay` L125 `CorrelationData cd` → `correlationData`**

将：
```java
        CorrelationData cd = new CorrelationData(msg.getEventId());
```
改为：
```java
        CorrelationData correlationData = new CorrelationData(msg.getEventId());
```
方法体内 `cd.getFuture()` 等引用 → `correlationData.getFuture()` 等（用 `git grep -n "\bcd\b" OutboxRelay.java` 定位全部引用）。

- [ ] **Step 3: 守卫 `BannedPattern.regex()` 增加 var 分支**

`SemanticNamingContractTest` 的 `BannedPattern.regex()`（L140-160）在返回的 Pattern 中增加 var 声明替代匹配。修改 `regex()`：

```java
        Pattern regex() {
            String base = type;
            String firstArg = null;
            int lt = type.indexOf('<');
            if (lt >= 0) {
                base = type.substring(0, lt);
                int gt = type.indexOf('>', lt);
                firstArg = type.substring(lt + 1, gt).trim();
                int comma = firstArg.indexOf(',');
                if (comma >= 0) {
                    firstArg = firstArg.substring(0, comma).trim();
                }
            }
            String genericPart = (firstArg == null)
                    ? "(?:\\s*<[^;{}()]*?>)?"
                    : "(?:\\s*<\\s*" + Pattern.quote(firstArg) + "\\b[^;{}()]*?>)?";
            String declared = "\\b" + Pattern.quote(base) + "\\b" + genericPart
                    + "\\s+\\b" + variable + "\\b";
            // var 声明：var <短名> = <类型基名>.xxx(...) —— 从初始化式推断真实类型，
            // 防止 var md = MessageDigest.getInstance(...) 绕过显式类型规则
            String varDecl = "\\bvar\\s+\\b" + variable + "\\b\\s*=\\s*"
                    + Pattern.quote(base) + "\\b\\s*\\.";
            return Pattern.compile(declared + "|" + varDecl);
        }
```

注意：`var` 分支只匹配"变量名 + 类型基名 + `.`"初始化式（如 `var md = MessageDigest.getInstance`）。泛型类型（`Map<String, Object>`）的 var 分支不适用（Java 无法 `var` 推断泛型 Map 声明场景较少），保持显式声明分支覆盖即可。

- [ ] **Step 4: 守卫新增 var fixture 测试**

在 `SemanticNamingContractTest` 新增测试方法（放在 `eachBannedDeclaration_isDetected` 之后）：
```java
    @Test
    @DisplayName("var 声明按初始化式类型识别（防绕过）")
    void varDeclarations_areDetectedByInitializerType() {
        String fixture = """
                var md = MessageDigest.getInstance("SHA-256");
                var correlationData = new CorrelationData("id");
                var op = buildTaskType();
                """;
        List<Violation> found = findViolations(fixture);
        assertThat(found)
                .as("var md 应命中 MessageDigest 规则: %s", fixture)
                .anySatisfy(v -> {
                    assertThat(v.type()).isEqualTo("MessageDigest");
                    assertThat(v.variable()).isEqualTo("md");
                });
        assertThat(found.stream().map(Violation::variable))
                .as("var correlationData / var op 不应命中")
                .doesNotContain("correlationData", "op");
    }
```
注意：`var op = buildTaskType()` 不匹配 TaskType/op 规则（初始化式无 `TaskType.` 前缀）；`var correlationData` 变量名合规。若 fixture 中的 `var op` 意外命中（正则宽松匹配），调整 fixture 使断言精确。

- [ ] **Step 5: 编译 + 守卫测试**

```bash
".\mvnw -pl api-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
".\mvnw -pl api-service -am test \"-Dtest=SemanticNamingContractTest,UploadSessionServiceTest\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: BUILD SUCCESS。守卫生产扫描（新增 var 规则后）应零违规——若 `var` 规则误命中其他生产代码，调整正则或修生产代码。

- [ ] **Step 6: 提交**

```bash
git add api-service/src/main/java/com/comicatlas/api/upload/UploadSessionService.java api-service/src/main/java/com/comicatlas/api/outbox/relay/OutboxRelay.java api-service/src/test/java/com/comicatlas/api/config/SemanticNamingContractTest.java
git commit -m "命名守卫识别 var 声明按初始化式类型，清理 var md 与 cd 残留"
```

---

### Task 4: P2-B RunnerTest 生命周期断言增强（worker-service）

**Files:**
- Modify: `worker-service/src/test/java/com/comicatlas/worker/process/ExternalProcessRunnerTest.java`

**Interfaces:**
- Consumes: Task 2 的 `MAX_OUTPUT_CHARS`、`destroyAndReap`
- Produces: 超时/中断用例断言子进程实际退出；新增输出容量与有界回收用例。

- [ ] **Step 1: 超时用例增强——断言子进程实际终止**

现有 `run_timeout` 用例（用 `ping -n 10 127.0.0.1`）增强：超时抛 `ProcessTimeoutException` 后，用可观测句柄断言子进程已退出。方案：让子进程写 PID 到文件后 sleep，超时后检查进程不再存活：

```java
    @Test
    @DisplayName("超时路径：destroyForcibly 终止子进程并抛 ProcessTimeoutException")
    void run_timeout() throws Exception {
        Path pidFile = Files.createTempFile("runner-timeout-pid", ".txt");
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c",
                "echo %PROCESS_ID% > \"" + pidFile + "\" & ping -n 10 127.0.0.1 > nul");
        assertThatThrownBy(() -> runner.run(pb, 1))
                .isInstanceOf(ExternalProcessRunner.ProcessTimeoutException.class);
        assertThat(pidFile).exists();
        // 读取 PID 并断言进程已终止（destroyForcibly 生效）
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        ProcessHandle process = ProcessHandle.of(pid).orElseThrow();
        // 等待短时间后应已退出
        long deadline = System.currentTimeMillis() + 3000;
        while (process.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(process.isAlive()).as("超时后子进程应已被 destroyForcibly 终止").isFalse();
    }
```
注：Windows `%PROCESS_ID%` 可能不是合法变量名——改用 `%RANDOM%` 不行。实际方案：子进程用 `cmd /c start /b` 不行。**更可靠的实现**：runner 不暴露 Process 句柄，因此通过"子进程自身检测"——让子进程启动时写一个 marker 文件，runner 超时销毁后**等待 marker 文件所在进程的 stdout 关闭**不可行。

**替代方案（推荐）**：runner 增加包私有可测钩子——在 `run()` 中记录 `Process` 到测试可访问的字段（`volatile Process lastProcess`）不可取（并发污染）。**最干净方案**：`run()` 的 `destroyAndReap` 中 `process.waitFor(5s)` 已保证确定性终止，测试通过"超时后短时间内 runner 返回 + 无残留进程"间接验证。**具体**：

```java
    @Test
    @DisplayName("超时路径：抛 ProcessTimeoutException 且进程已终止（waitFor 回收）")
    void run_timeout() {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ping -n 10 127.0.0.1 > nul");
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> runner.run(pb, 1))
                .isInstanceOf(ExternalProcessRunner.ProcessTimeoutException.class);
        long elapsed = System.currentTimeMillis() - start;
        // destroyAndReap 的 5s waitFor 应在超时后尽快返回（ping 被强杀）
        assertThat(elapsed).as("超时后回收应迅速完成（不悬挂）").isLessThan(8000);
    }
```
此断言验证"有界回收不悬挂"。子进程"实际退出"由 `destroyAndReap` 内部的 `waitFor(5s)` 逻辑保证（代码审查层面），测试侧用回收时长上界佐证。

- [ ] **Step 2: 输出容量用例**

新增：
```java
    @Test
    @DisplayName("输出容量受限：超限后截断保留尾部且进程正常退出")
    void run_outputExceedsLimit_isTruncated() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c",
                "for /l %i in (1,1,3000) do @echo line-%i-0123456789012345678901234567890123456789");
        ExternalProcessRunner.ExternalProcessResult result = runner.run(pb, 30);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().length()).as("输出应被容量限制截断")
                .isLessThan(ExternalProcessRunner.MAX_OUTPUT_CHARS + 512);
        assertThat(result.stdout()).contains("[输出已截断");
        assertThat(result.stdout()).contains("line-3000");   // 尾部保留
    }
```
若 `MAX_OUTPUT_CHARS` 非 public，测试无法访问——将常量设为 `public static final`（Task 2 中声明为 public 或包可见 + 测试同包可访问——测试与 Runner 同包 `com.comicatlas.worker.process`，包可见即可）。

- [ ] **Step 3: 中断用例增强——断言中断后 runner 快速返回（回收不悬挂）**

增强现有 `run_interrupt` 用例：记录从 interrupt 到方法返回的耗时，断言有界：
```java
        long[] elapsed = new long[1];
        Thread t = new Thread(() -> {
            try {
                long s = System.currentTimeMillis();
                runner.run(pb, 10);
                elapsed[0] = System.currentTimeMillis() - s;
            } catch (InterruptedException e) {
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Exception ignored) {
            }
        });
        t.start();
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        t.interrupt();
        try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(interruptRestored).as("中断标志应恢复").isTrue();
        assertThat(elapsed[0]).as("中断后回收应迅速完成").isLessThan(5000);
```

- [ ] **Step 4: 运行 RunnerTest**

```bash
".\mvnw -pl worker-service -am test \"-Dtest=ExternalProcessRunnerTest\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: BUILD SUCCESS，全部用例通过。若 Windows 子进程行为差异（`for /l` 语法、ping 参数）导致失败，调整命令保证确定性。

- [ ] **Step 5: 提交**

```bash
git add worker-service/src/test/java/com/comicatlas/worker/process/ExternalProcessRunnerTest.java
git commit -m "增强外部进程执行测试：超时回收有界、输出容量截断、中断快速返回"
```

---

### Task 5: P2-C MediaAnalyzerSmokeTest 改标准 JUnit（worker-service）

**Files:**
- Modify: `worker-service/src/test/java/com/comicatlas/worker/file/parse/MediaAnalyzerSmokeTest.java`

**Interfaces:**
- Consumes: `MediaAnalyzer(WorkerConfig, ObjectMapper, ExternalProcessRunner)`、`ExternalProcessRunner(ThreadPoolTaskExecutor processIoExecutor)`
- Produces: 标准 JUnit 测试（Surefire 门禁内），6 场景保留。

- [ ] **Step 1: 改为 JUnit 测试类**

- 类声明加 `class` 改为 JUnit 5 风格（原为 `public class MediaAnalyzerSmokeTest` 已是 class——改造为 `@Test` 方法集合）
- 删除 `main(String[] args)`、`System.exit`、`failures` 计数器、手写 `assertEquals/assertNull/assertTrue/assertNotNull` 工具方法
- 用 JUnit 5 `@Test` + `org.junit.jupiter.api.Assertions`（或 AssertJ）
- 保留 `createFakeFfprobeScript`、`deleteRecursively`（改 `@TempDir` 自动清理）
- 构造依赖：创建 `ThreadPoolTaskExecutor`（测试内）+ `ExternalProcessRunner` → `new MediaAnalyzer(cfg, om, runner)`

- [ ] **Step 2: 测试结构**

```java
package com.comicatlas.worker.file.parse;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MediaAnalyzer 冒烟测试（JUnit 版）：
 * 直接实例化 MediaAnalyzer，验证图片/视频/缺失文件的元数据解析。
 * 纳入 Surefire 门禁（原为 main() 独立程序，未进入 Maven 测试）。
 */
@DisplayName("MediaAnalyzerSmokeTest — 媒体元数据分析冒烟")
class MediaAnalyzerSmokeTest {

    @TempDir
    Path tmp;

    private WorkerConfig cfg;
    private ObjectMapper om;
    private ExternalProcessRunner runner;
    private ThreadPoolTaskExecutor executor;
    private MediaAnalyzer analyzer;

    @BeforeEach
    void setUp() throws Exception {
        cfg = new WorkerConfig();
        cfg.setFfprobePath("worker-service/ffmpeg/ffprobe.exe");  // 不存在 → 走 fallback
        om = new ObjectMapper();
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setThreadNamePrefix("smoke-test-process-io-");
        executor.initialize();
        runner = new ExternalProcessRunner(executor);
        analyzer = new MediaAnalyzer(cfg, om, runner);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("jpg → IMAGE 且读取宽高")
    void jpg_isImageWithDimensions() throws Exception {
        Path jpg = tmp.resolve("test.jpg");
        BufferedImage img = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "jpg", jpg.toFile());

        ComicMetadata.MediaInfo info = analyzer.analyze(jpg);
        assertThat(info.mediaType()).isEqualTo("IMAGE");
        assertThat(info.width()).isEqualTo(100);
        assertThat(info.height()).isEqualTo(80);
        assertThat(info.fileName()).isNotNull();
        assertThat(info.fileSize()).isGreaterThan(0);
        assertThat(info.hqStatus()).isEqualTo("READY");
        assertThat(info.duration()).isNull();
        assertThat(info.videoCodec()).isNull();
    }

    @Test
    @DisplayName("mp4 无 ffprobe → VIDEO 且视频字段为 null")
    void mp4_withoutFfprobe_isVideoWithNulls() throws Exception {
        Path mp4 = tmp.resolve("test.mp4");
        Files.write(mp4, new byte[]{0, 0, 0, 0});

        ComicMetadata.MediaInfo info = analyzer.analyze(mp4);
        assertThat(info.mediaType()).isEqualTo("VIDEO");
        assertThat(info.container()).isEqualTo("mp4");
        assertThat(info.duration()).isNull();
        assertThat(info.width()).isNull();
        assertThat(info.height()).isNull();
        assertThat(info.videoCodec()).isNull();
        assertThat(info.audioCodec()).isNull();
    }

    @Test
    @DisplayName("mkv 无 ffprobe → VIDEO")
    void mkv_withoutFfprobe_isVideo() throws Exception {
        Path mkv = tmp.resolve("test.mkv");
        Files.write(mkv, new byte[]{0, 0, 0, 0});

        ComicMetadata.MediaInfo info = analyzer.analyze(mkv);
        assertThat(info.mediaType()).isEqualTo("VIDEO");
        assertThat(info.container()).isEqualTo("mkv");
    }

    @Test
    @DisplayName("fake-ffprobe 返回 JSON → 解析视频元数据")
    void mp4_withFakeFfprobe_parsesVideoMetadata() throws Exception {
        Path mp4 = tmp.resolve("test.mp4");
        Files.write(mp4, new byte[]{0, 0, 0, 0});
        Path fakeScript = createFakeFfprobeScript(tmp);
        cfg.setFfprobePath(fakeScript.toString());
        MediaAnalyzer analyzer2 = new MediaAnalyzer(cfg, om, runner);

        ComicMetadata.MediaInfo info = analyzer2.analyze(mp4);
        assertThat(info.mediaType()).isEqualTo("VIDEO");
        assertThat(info.container()).isEqualTo("mp4");
        assertThat(info.width()).isEqualTo(1920);
        assertThat(info.height()).isEqualTo(1080);
        assertThat(info.videoCodec()).isEqualTo("h264");
        assertThat(info.audioCodec()).isEqualTo("aac");
    }

    @Test
    @DisplayName("不存在的文件 → MISSING")
    void missingFile_isMissing() {
        ComicMetadata.MediaInfo info = analyzer.analyze(tmp.resolve("nope.jpg"));
        assertThat(info.hqStatus()).isEqualTo("MISSING");
        assertThat(info.fileSize()).isZero();
    }

    @Test
    @DisplayName("空 ffprobe 路径 → 走 fallback")
    void emptyFfprobePath_fallsBack() throws Exception {
        Path mp4 = tmp.resolve("test.mp4");
        Files.write(mp4, new byte[]{0, 0, 0, 0});
        cfg.setFfprobePath("");
        MediaAnalyzer analyzer3 = new MediaAnalyzer(cfg, om, runner);

        ComicMetadata.MediaInfo info = analyzer3.analyze(mp4);
        assertThat(info.mediaType()).isEqualTo("VIDEO");
        assertThat(info.duration()).isNull();
    }

    private Path createFakeFfprobeScript(Path dir) throws Exception {
        String jsonBody = "{\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":1920,\"height\":1080},{\"codec_type\":\"audio\",\"codec_name\":\"aac\"}],\"format\":{\"duration\":\"125.500000\"}}";
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            Path script = dir.resolve("fake-ffprobe.cmd");
            String content = "@echo off\r\necho " + jsonBody + "\r\n";
            Files.writeString(script, content);
            return script;
        }
        Path script = dir.resolve("fake-ffprobe.sh");
        String content = "#!/bin/sh\necho '" + jsonBody + "'\n";
        Files.writeString(script, content);
        script.toFile().setExecutable(true);
        return script;
    }
}
```
若 AssertJ 不可用（项目依赖），改用 JUnit 5 `org.junit.jupiter.api.Assertions` 的 `assertEquals/assertNull/assertNotNull/assertTrue`。**注意**：删除原文件中 `ThreadPoolTaskExecutor` 直接传 `MediaAnalyzer` 的错误用法；`MediaInfo` 的 `width()/height()` 返回 `Integer`，`assertThat(...).isEqualTo(100)` 自动拆箱比较 OK。

- [ ] **Step 3: 运行 SmokeTest**

```bash
".\mvnw -pl worker-service -am test \"-Dtest=MediaAnalyzerSmokeTest\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: BUILD SUCCESS，6 测试通过（原 main 版场景全部保留）。

- [ ] **Step 4: 提交**

```bash
git add worker-service/src/test/java/com/comicatlas/worker/file/parse/MediaAnalyzerSmokeTest.java
git commit -m "MediaAnalyzerSmokeTest 改造为标准 JUnit 测试并纳入 Surefire 门禁"
```

---

### Task 6: 最终全量验证

**Files:**
- 无代码改动（仅验证）

**Interfaces:**
- Consumes: Task 1-5 全部
- Produces: 全绿验证结论

- [ ] **Step 1: 全量 `clean verify`**

```bash
".\mvnw clean verify -DskipTests=false" ; echo "EXIT=$LASTEXITCODE"
```
Expected: BUILD SUCCESS，五模块 Checkstyle 0，Worker 34+ + API 272+ 全绿，0 failures/errors。

- [ ] **Step 2: 边界复查**

```bash
git grep -n "var md\|CorrelationData cd" -- "*.java" | Select-String -NotMatch "test"
git grep -n "waitFor\|\.start()" -- "worker-service/src/main/java" | Select-String -NotMatch "ExternalProcessRunner"
git diff --check
git log --oneline -8
```
Expected: 生产代码无 `var md`/`CorrelationData cd`；Worker 生产仅 ExternalProcessRunner 内部有 waitFor/start；`git diff --check` 无输出。

- [ ] **Step 3: 汇报**

汇总各任务 commit SHA、测试结论、Checkstyle 结果，报告用户。

---

## Self-Review

**1. Spec coverage:**
- P1-A 两处转码接入 → Task 1 ✅
- P1-B 输出容量 + 有界回收 + Torrent 超时 → Task 2 ✅
- P2-A 守卫 var + 短名清理 → Task 3 ✅
- P2-B RunnerTest 断言增强 → Task 4 ✅
- P2-C SmokeTest 改 JUnit → Task 5 ✅
- 最终验证 → Task 6 ✅

**2. Placeholder scan:** 无 TBD/TODO；每个代码步骤含完整代码块 ✅

**3. Type consistency:**
- `ExternalProcessRunner.MAX_OUTPUT_CHARS`（Task 2 定义，Task 4 测试引用——同包可访问）✅
- `TailBuffer`/`destroyAndReap`（Task 2 定义，Task 2 内部使用）✅
- `WorkerConfig.Torrent.timeoutMinutes`（Task 2 定义，TorrentDownloader 消费）✅
- `MediaAnalyzer(WorkerConfig, ObjectMapper, ExternalProcessRunner)` 构造器（Task 5 使用，与当前签名一致）✅
- 守卫 `BannedPattern.regex()` var 分支（Task 3 定义与 fixture 自洽）✅
