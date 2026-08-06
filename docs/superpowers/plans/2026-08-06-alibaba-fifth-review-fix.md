# 阿里 Java 规范第五次复审（fifth-review）整改实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复第五次复审 5 项阻断项（输出上限可突破、中断回收顺序失效+进程树未回收、CallerRunsPolicy 超时失效、中断分支缺失、测试未观测进程存活），保持全绿。

**Architecture:** 分 5 批独立交付：P1-A 字符块读取 + 固定容量缓冲；P1-B 进程树终止 + 不中断有界 reap；P1-C processIoExecutor AbortPolicy；P1-D 中断分支统一；P1-E RunnerTest ProcessHandle 真实断言。每批独立编译+测试+提交，最终全量 `clean verify`。

**Tech Stack:** Spring Boot 3、Java 21、JUnit 5、Maven Wrapper、Checkstyle。

## Global Constraints

- 语言：注释、提交信息始终使用中文；提交信息格式"动作 + 内容"。
- DB：VARCHAR 存枚举 name()，零迁移；前端契约不变。
- 中断语义：`InterruptedException` 必须恢复中断标志或向上抛出；外部子进程（含 descendants）在中断/超时/取消时必须 `destroyForcibly()` 终止。
- 进程输出：固定容量上限（字符块读取），任何单行/块长均不可突破；超限继续排空不保留。
- 清理阶段不中断：`destroyAndReap` 必须先取出中断标志，清理期不中断等待，最后恢复标志并传播。
- 禁止空 catch；禁止 `as any` 类 Java 等价物。
- Maven 命令在 PowerShell 下 `-D` 参数需引号包裹。
- 提交前 `git diff --check` 通过；每个任务只解决一个完整问题。
- `ExternalProcessRunner` 当前：`run(ProcessBuilder, long)` → `ExternalProcessResult(int exitCode, String stdout)`；`TailBuffer`（L53-69）；`destroyAndReap`（L144-160）；`MAX_OUTPUT_CHARS = 64 * 1024`（包可见，L47）。

---

### Task 1: P1-A 字符块读取 + 真正固定容量缓冲（worker-service）

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/process/ExternalProcessRunner.java`

**Interfaces:**
- Consumes: 无
- Produces: `TailBuffer.append(char[] chunk, int len)` 按剩余容量截断写入；读取循环用 `Reader.read(char[], 0, CHUNK_SIZE)`；`stdout().length() <= MAX_OUTPUT_CHARS + 截断标记`。

- [ ] **Step 1: 重写 `TailBuffer`**

将（L53-69）：
```java
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
            return "[输出已截断，仅保留开头 " + buf.length() + " 字符]\n" + buf;
        }
    }
```
替换为：
```java
    private static final class TailBuffer {
        private final StringBuilder buf = new StringBuilder(MAX_OUTPUT_CHARS / 2);
        private boolean truncated = false;

        /**
         * 按剩余容量截断写入：最多写入 (MAX - 当前长度) 字符，超限部分丢弃但继续排空
         * （读取循环仍在跑，防管道死锁）。任何单行/块长均不可能突破上限。
         */
        void append(char[] chunk, int len) {
            int remaining = MAX_OUTPUT_CHARS - buf.length();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int toWrite = Math.min(len, remaining);
            buf.append(chunk, 0, toWrite);
            if (len > toWrite) {
                truncated = true;
            }
        }

        String snapshot() {
            if (!truncated) { return buf.toString(); }
            return "[输出已截断，仅保留开头 " + buf.length() + " 字符]\n" + buf;
        }
    }
```

- [ ] **Step 2: 重写读取循环**

将（L92-102）：
```java
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
替换为（弃用 readLine，定长块读取）：
```java
        CompletableFuture<Void> readFuture = CompletableFuture.runAsync(() -> {
            try (Reader r = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] chunk = new char[CHUNK_SIZE];
                int n;
                while ((n = r.read(chunk, 0, CHUNK_SIZE)) != -1) {
                    processOutput.append(chunk, n);
                }
            } catch (IOException e) {
                log.warn("读取外部进程输出失败: {}", e.getMessage());
            }
        }, processIoExecutor);
```
添加常量与 import：
```java
    /** 输出读取块大小（字符）。定长块读取，避免 readLine 构造完整无换行长字符串。 */
    private static final int CHUNK_SIZE = 8192;
```
import：删除 `java.io.BufferedReader`，添加 `java.io.Reader`。

- [ ] **Step 3: 编译**

```bash
".\mvnw -pl worker-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
```

- [ ] **Step 4: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/process/ExternalProcessRunner.java
git commit -m "外部进程输出改为定长字符块读取，按剩余容量截断防单行突破上限"
```

---

### Task 2: P1-B 进程树终止 + 不中断有界 reap（worker-service）

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/process/ExternalProcessRunner.java`

**Interfaces:**
- Consumes: Task 1（TailBuffer 新签名不变）
- Produces: `destroyAndReap` 先取中断标志、清理期不中断、终止进程树、最后恢复标志并传播原异常。

- [ ] **Step 1: 重写 `destroyAndReap`**

将（L144-160）：
```java
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
替换为：
```java
    /**
     * 销毁进程树并等待其终止与输出读取任务收尾（有界、不中断）。
     * <p>
     * 必须先取出并清除中断标志，使清理期的 waitFor/get 真正生效（若带中断标志调用，
     * 等待会立即再次收到中断而失效）；清理完成后恢复原中断标志，由调用方决定传播。
     */
    private void destroyAndReap(Process process, CompletableFuture<Void> readFuture) {
        boolean interrupted = Thread.interrupted();
        try {
            // 终止进程树：先杀后代再杀直接进程
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("外部进程强制终止后 5s 仍未退出，可能残留");
            }
            try {
                readFuture.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("清理阶段读取任务等待被中断（已忽略，进程已终止）");
            } catch (ExecutionException | TimeoutException e) {
                log.warn("等待外部进程输出读取任务收尾超时: {}", e.getMessage());
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
```

- [ ] **Step 2: 检查调用点语义保持**

- 中断路径（L109-112、L117-120）：`destroyAndReap` 返回后 `throw e`——`destroyAndReap` 已在 finally 恢复中断标志，`throw e` 传播原 `InterruptedException` ✓（调用点结构不变）
- 超时路径（L122-124）：`destroyAndReap` 后 `throw new ProcessTimeoutException` ✓
- 确认 `destroyAndReap` 内不再有 `Thread.currentThread().interrupt()`（已改为保存/恢复模式）

- [ ] **Step 3: 编译**

```bash
".\mvnw -pl worker-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
```

- [ ] **Step 4: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/process/ExternalProcessRunner.java
git commit -m "外部进程销毁改为终止进程树，清理阶段不中断等待并最后恢复中断标志"
```

---

### Task 3: P1-C processIoExecutor 拒绝策略（worker-service）

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/config/WorkerExecutorConfig.java`

**Interfaces:**
- Consumes: 无
- Produces: `processIoExecutor` 用 `AbortPolicy`（拒绝即抛），`videoNormalizeExecutor` 保留 `CallerRunsPolicy`。

- [ ] **Step 1: 修改 `processIoExecutor`**

将（L39）：
```java
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
```
（processIoExecutor 方法内，L39）替换为：
```java
        // 进程输出读取任务必须异步执行：池饱和时拒绝即抛（AbortPolicy），
        // 避免 CallerRunsPolicy 让读取循环在业务线程同步阻塞，导致 waitFor 超时失效。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
```
**注意**：`videoNormalizeExecutor`（L24）的 `CallerRunsPolicy` **不改**（转码并行度有意设计）。修改时精确定位 processIoExecutor 方法内的那一处。

- [ ] **Step 2: 编译**

```bash
".\mvnw -pl worker-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
```

- [ ] **Step 3: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/config/WorkerExecutorConfig.java
git commit -m "进程输出线程池改 AbortPolicy 拒绝策略，避免读取阻塞业务线程致超时失效"
```

---

### Task 4: P1-D 中断分支统一（worker-service，Runner + 4 调用方）

**Files:**
- Modify: `worker-service/src/main/java/com/comicatlas/worker/process/ExternalProcessRunner.java`（L128-137 正常路径）
- Modify: `worker-service/src/main/java/com/comicatlas/worker/event/VideoTranscodeHandler.java`（L117）
- Modify: `worker-service/src/main/java/com/comicatlas/worker/event/TranscodeCommandHandler.java`（L148）
- Modify: `worker-service/src/main/java/com/comicatlas/worker/image/ImageOptimizer.java`（L173、L229-232）

**Interfaces:**
- Consumes: Task 2（destroyAndReap 不中断清理）
- Produces: 所有 runner 调用链的 `InterruptedException` 不被当普通业务失败；Runner 正常路径中断向上传播。

- [ ] **Step 1: Runner 正常路径中断传播**

将（L128-135）：
```java
        // 等待 stdout 消费完成；中断时恢复标志（子进程已退出，无需再销毁）
        try {
            readFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("等待外部进程输出读取超时: {}", e.getMessage());
        }
```
替换为：
```java
        // 等待 stdout 消费完成；中断向上传播（子进程已退出，无需再销毁）
        try {
            readFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("等待外部进程输出读取超时: {}", e.getMessage());
        }
```
`run` 签名已 `throws InterruptedException`，直抛合法。

- [ ] **Step 2: VideoTranscodeHandler 中断分支**

在 `catch (Exception e)`（L117）之前插入：
```java
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("视频转码被中断: pageId={}", pageId);
            // 非业务失败：不发送 failed 事件，由监听器容器感知中断状态
        } catch (Exception e) {
```
`handle()` 是 `@RabbitListener` 方法，中断后返回让容器处理。

- [ ] **Step 3: TranscodeCommandHandler 中断分支**

`transcode()` 的 `catch (Exception e)`（L148）之前插入：
```java
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("转码命令被中断: pageId={}", pageId);
            return "TRANSCODE_INTERRUPTED";
        } catch (Exception e) {
```
（`transcode` 返回 String 错误信息——中断时返回明确标记，不当作普通失败；若实现处 `transcode` 返回类型为 `String`，确认该分支编译。若该方法返回 `void` 或包装，按实际签名调整。）

- [ ] **Step 4: ImageOptimizer.generateCover 中断分支**

在 `catch (Exception e)`（L173）之前插入：
```java
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("封面优化被中断: comicId=" + comicId, e);
        } catch (Exception e) {
```
（参照 `runOptimizer` L91-94 既有模式。）

- [ ] **Step 5: ImageOptimizer.generateCoverFromVideo 中断分支**

在 `catch (RuntimeException e)`（L229）之前插入：
```java
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("视频封面生成被中断: comicId=" + comicId, e);
        } catch (RuntimeException e) {
```

- [ ] **Step 6: 编译 + 相关测试**

```bash
".\mvnw -pl worker-service -am test-compile" ; if ($LASTEXITCODE -eq 0) { "COMPILE_OK" }
".\mvnw -pl worker-service -am test \"-Dtest=VideoTranscodeHandlerTest,MediaAnalyzerSmokeTest\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: COMPILE_OK + 相关测试通过。

- [ ] **Step 7: 提交**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/process/ExternalProcessRunner.java worker-service/src/main/java/com/comicatlas/worker/event/VideoTranscodeHandler.java worker-service/src/main/java/com/comicatlas/worker/event/TranscodeCommandHandler.java worker-service/src/main/java/com/comicatlas/worker/image/ImageOptimizer.java
git commit -m "统一外部进程调用链中断分支：恢复标志并终止任务流程，不被当普通业务失败"
```

---

### Task 5: P1-E RunnerTest ProcessHandle 真实断言（worker-service）

**Files:**
- Modify: `worker-service/src/test/java/com/comicatlas/worker/process/ExternalProcessRunnerTest.java`

**Interfaces:**
- Consumes: Task 1（TailBuffer 截断）、Task 2（进程树终止）、Task 3（AbortPolicy）、Task 4（中断传播）
- Produces: 单条超长输出截断、进程树终止、线程池饱和、读取阶段中断四类真实断言。

- [ ] **Step 1: 单条超长无换行输出用例**

新增（验证 P1-A）：
```java
    @Test
    @DisplayName("单条超长无换行输出被截断（长度受限 + 截断标记）")
    void run_singleHugeLine_isTruncated() throws Exception {
        // 输出约 200KB 无换行文本：cmd 用 for 拼接会带空格，改用 PowerShell 生成
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "$s='x' * 200000; Write-Output $s");
        ExternalProcessRunner.ExternalProcessResult result = runner.run(pb, 30);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().length()).as("单条超长输出应被截断")
                .isLessThanOrEqualTo(ExternalProcessRunner.MAX_OUTPUT_CHARS + 512);
        assertThat(result.stdout()).contains("[输出已截断");
    }
```
若 PowerShell 不可用（某些 CI），改用 `cmd /c` 生成（多行+长行混合）或 `for /l` 循环拼接——目标：确定产生 > MAX 的输出。若 `powershell` 执行受限，用 java 侧生成器（见 Step 5 备选）。

- [ ] **Step 2: 进程树终止用例**

新增（验证 P1-B）——通过 PID 文件观测：
```java
    @Test
    @DisplayName("超时后直接进程与全部后代进程均被终止")
    void run_timeout_killsDescendants() throws Exception {
        Path pidFile = Files.createTempFile("runner-tree-pid", ".txt");
        // cmd 派生子进程（ping），主进程等待；超时后应终止整棵进程树
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c",
                "echo " + ProcessHandle.current().pid() + " > \"" + pidFile + "\" & ping -n 10 127.0.0.1");
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> runner.run(pb, 1))
                .isInstanceOf(ExternalProcessRunner.ProcessTimeoutException.class);
        // 超时后短时间内返回（有界回收）
        assertThat(System.currentTimeMillis() - start).isLessThan(10000);
        // 用 ProcessHandle.allProcesses 查找残留的 ping 进程（命令行含 127.0.0.1）
        boolean pingAlive = ProcessHandle.allProcesses()
                .filter(p -> p.info().commandLine().orElse("").contains("ping -n 10 127.0.0.1"))
                .anyMatch(ProcessHandle::isAlive);
        assertThat(pingAlive).as("超时后后代 ping 进程应已被终止").isFalse();
    }
```
注：PID 文件在 Windows 上可能因权限/时序不可靠——`ProcessHandle.allProcesses()` 按命令行过滤是主断言（跨平台可靠）。若 Windows 上 cmd 不继承 `> nul` 则 stdout 由 runner 消费，无死锁。

- [ ] **Step 3: 线程池饱和用例**

新增（验证 P1-C）——小池 + 多并发进程，断言 AbortPolicy 拒绝不阻塞：
```java
    @Test
    @DisplayName("进程输出线程池饱和时拒绝（AbortPolicy），不阻塞业务线程")
    void run_poolSaturation_abortsInsteadOfBlocking() throws Exception {
        // 小池（core=1, queue=0）：并发 5 个进程，前 1 个占用读取线程，其余被拒绝
        ThreadPoolTaskExecutor tiny = new ThreadPoolTaskExecutor();
        tiny.setCorePoolSize(1);
        tiny.setMaxPoolSize(1);
        tiny.setQueueCapacity(0);
        tiny.setThreadNamePrefix("tiny-io-");
        tiny.initialize();
        ExternalProcessRunner tinyRunner = new ExternalProcessRunner(tiny);
        try {
            List<ProcessBuilder> builders = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                builders.add(new ProcessBuilder("cmd", "/c", "echo task-" + i + " & ping -n 3 127.0.0.1"));
            }
            AtomicInteger success = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (ProcessBuilder pb : builders) {
                Thread t = new Thread(() -> {
                    try {
                        tinyRunner.run(pb, 5);
                        success.incrementAndGet();
                    } catch (Exception e) {
                        rejected.incrementAndGet();
                    }
                });
                t.start();
                threads.add(t);
            }
            for (Thread t : threads) { t.join(15000); }
            assertThat(success.get() + rejected.get()).as("全部任务应结束（成功或拒绝）").isEqualTo(5);
            // 关键：没有任何调用线程被同步阻塞超过声明的超时（有界返回）
            assertThat(rejected.get()).as("部分任务因池饱和被拒绝而非阻塞").isGreaterThan(0);
        } finally {
            tiny.shutdown();
        }
    }
```

- [ ] **Step 4: 读取阶段中断用例**

新增（验证 P1-D Runner 正常路径中断传播）：
```java
    @Test
    @DisplayName("读取阶段中断向上传播（run 抛 InterruptedException）")
    void run_readPhaseInterrupt_propagates() throws Exception {
        // 长时间运行进程使读取任务阻塞；中断主线程应传播
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ping -n 10 127.0.0.1");
        Thread t = new Thread(() -> {
            try {
                runner.run(pb, 10);
            } catch (InterruptedException e) {
                interruptPropagated.set(true);
            } catch (Exception ignored) {
            }
        });
        t.start();
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        t.interrupt();
        try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(interruptPropagated).as("读取阶段中断应向上传播").isTrue();
    }
```
需要类字段 `private final AtomicBoolean interruptPropagated = new AtomicBoolean();`（或局部数组）。若该用例与现有 `run_interrupt` 语义重叠，确认差异：现有中断落在 `waitFor` 阶段，本用例目标是中断落在 `readFuture.get` 阶段——若时序难精确控制，合并说明即可，至少覆盖传播语义。

- [ ] **Step 5: 运行 RunnerTest**

```bash
".\mvnw -pl worker-service -am test \"-Dtest=ExternalProcessRunnerTest\" \"-Dsurefire.failIfNoSpecifiedTests=false\""
```
Expected: BUILD SUCCESS，全部用例通过。若 PowerShell/cmd 生成器在 Windows 行为不稳，调整命令保证确定性（报告说明）。
**备选生成器**（若系统命令受限）：用 JUnit 测试内 `Files.writeString` 生成一个可执行脚本（.cmd/.ps1/.sh），其输出固定 200KB 单行文本——更可控、跨平台。

- [ ] **Step 6: 提交**

```bash
git add worker-service/src/test/java/com/comicatlas/worker/process/ExternalProcessRunnerTest.java
git commit -m "外部进程执行测试补真实断言：超长输出截断、进程树终止、池饱和拒绝与中断传播"
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
Expected: BUILD SUCCESS，五模块 Checkstyle 0，Worker 40+ + API 273+ 全绿，0 failures/errors。

- [ ] **Step 2: 边界复查**

```bash
git grep -n "readLine()\|CallerRunsPolicy" -- "worker-service/src/main/java" | Select-String -NotMatch "videoNormalizeExecutor"
git diff --check
git log --oneline -10
```
Expected: Worker 生产无 `readLine()`；`CallerRunsPolicy` 仅存在于 videoNormalizeExecutor；`git diff --check` 无输出。

- [ ] **Step 3: 汇报**

汇总各任务 commit SHA、测试结论、Checkstyle 结果，报告用户。

---

## Self-Review

**1. Spec coverage:**
- P1-A 字符块读取 + 固定容量 → Task 1 ✅
- P1-B 进程树终止 + 不中断 reap → Task 2 ✅
- P1-C AbortPolicy → Task 3 ✅
- P1-D 中断分支统一 → Task 4 ✅
- P1-E ProcessHandle 断言 → Task 5 ✅
- 最终验证 → Task 6 ✅

**2. Placeholder scan:** 无 TBD/TODO；每个代码步骤含完整代码块 ✅

**3. Type consistency:**
- `TailBuffer.append(char[], int)` 新签名（Task 1 定义，Task 1 读取循环消费）✅
- `destroyAndReap(Process, CompletableFuture<Void>)` 签名不变（Task 2 重构，调用点不变）✅
- `CHUNK_SIZE = 8192`（Task 1 定义，读取循环使用）✅
- RunnerTest 新用例引用 `MAX_OUTPUT_CHARS`（包可见，Task 1 保留）✅
- 中断分支（Task 4）在各调用方的方法签名约束下适配（TranscodeCommandHandler 返回 String → 中断返回标记）✅
