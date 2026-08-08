# 阿里 Java 规范第五次复审（fifth-review）整改设计

**状态**: 历史归档
- 日期：2026-08-06
- 依据：`.omo/evidence/alibaba-java-backend-fifth-review-2026-08-06.md`（综合结论 FAIL / REQUEST_CHANGES）
- 审查提交：`9d9296db4cff60ebd967571ed5c0d802a22372ef`；对比基线 `3fdabd7`
- 明确边界：本地个人应用不要求鉴权；业务 HTTP 仅 API；Worker 仅通过 MQ 承接业务任务，可读 MySQL、不可写 MySQL；状态采用 Java enum + DB VARCHAR

## 目标

修复第五次复审 5 项阻断项（输出上限可突破、中断回收顺序失效+进程树未回收、CallerRunsPolicy 超时失效、中断分支缺失、测试未观测进程存活），保持 `mvnw clean verify` 全绿、Checkstyle 0、DB VARCHAR 零迁移、前端契约不变。

## 已通过项（本轮不动）

- `mvnw verify` 退出码 0、Worker 40 + API 273 = 313 tests 全绿、Checkstyle 0、`git diff --check` 通过
- 转码接入 Runner、Torrent 120 分钟超时、守卫 var 识别、SmokeTest 入 JUnit、Worker 只读、枚举告警、密码无默认值

## 阻断项与修复设计

### P1-A：输出上限可突破 —— 字符块读取 + 真正固定容量缓冲（worker-service）

**现状**（验证属实）：
- `ExternalProcessRunner.java` L57-63：`TailBuffer.append(String line)` 先整行 `append` 再检查——`BufferedReader.readLine()` 会先构造完整无换行长字符串，单行 70,000 字符直接突破 64KB 且无截断标记（QA 实测返回 70,001 字符）
- L93-98：`readLine()` 逐行读取是突破根因

**修复**：
1. **弃用 `readLine()`**：改用 `Reader.read(char[] chunk, 0, CHUNK_SIZE)` 定长块循环读取（如 CHUNK_SIZE = 8192），不再构造完整无换行字符串
2. **TailBuffer 真正固定容量**：`append(char[] chunk, int len)` 按剩余容量截断写入（写入前计算 `min(len, MAX - buf.length())`），超限置截断标记并继续排空（防管道死锁）
3. **上限不变式**：`result.stdout().length() <= MAX_OUTPUT_CHARS + 截断标记长度`，任何单行/块长均不可能突破
4. 保留 `MAX_OUTPUT_CHARS = 64 * 1024`（包可见，测试引用）

**涉及文件**：
- `worker-service/.../process/ExternalProcessRunner.java`

**验证**：RunnerTest 单条超长无换行输出用例（P1-E）断言长度 ≤ 上限 + 截断标记。

### P1-B：中断回收顺序失效 + 进程树未回收（worker-service）

**现状**（验证属实）：
- `ExternalProcessRunner.java` L109-120：中断路径先 `Thread.currentThread().interrupt()` 再调 `destroyAndReap`——内部 `waitFor/get` 立即再次收到中断，**等待实际失效**，无法确认进程/读取任务完成回收
- L144-159：`destroyAndReap` 只销毁直接进程，不处理 `ProcessHandle.descendants()`——QA 实测超时后 descendant（如 cmd 的 ping 子进程）仍存活

**修复**：
1. **先保存中断状态，清理阶段不中断**：
   ```java
   private void destroyAndReap(Process process, CompletableFuture<Void> readFuture) {
       boolean interrupted = Thread.interrupted();   // 先取出并清除中断标志
       try {
           // 终止进程树
           process.descendants().forEach(ProcessHandle::destroyForcibly);
           process.destroyForcibly();
           // 不中断状态下有界等待（此时 Thread.interrupted() 已清除，等待真正生效）
           if (!process.waitFor(5, TimeUnit.SECONDS)) {
               log.warn("外部进程强制终止后 5s 仍未退出，可能残留");
           }
           try {
               readFuture.get(5, TimeUnit.SECONDS);
           } catch (InterruptedException e) {
               log.warn("清理阶段读取任务等待被中断，忽略");   // 清理阶段不传播
           } catch (ExecutionException | TimeoutException e) {
               log.warn("等待外部进程输出读取任务收尾超时: {}", e.getMessage());
           }
       } finally {
           if (interrupted) { Thread.currentThread().interrupt(); }   // 恢复原中断标志
       }
   }
   ```
2. **进程树终止**：`process.descendants().forEach(ProcessHandle::destroyForcibly)` + `process.destroyForcibly()`（直接进程 + 全部后代）
3. **调用点语义**：中断路径 `destroyAndReap` 后恢复标志 + `throw e`；超时路径 `destroyAndReap` 后抛 `ProcessTimeoutException`——原调用点（L109-125）保持结构，仅 `destroyAndReap` 内部重构
4. **正常路径 L128-137**：`readFuture.get(5s)` 的 `InterruptedException` 改为传播（见 P1-D）

**涉及文件**：
- `worker-service/.../process/ExternalProcessRunner.java`

**验证**：RunnerTest 进程树终止用例（P1-E）——启动派生子进程的命令，超时后断言 descendants 全终止。

### P1-C：processIoExecutor 拒绝策略（worker-service）

**现状**（验证属实）：
- `WorkerExecutorConfig.java` L31-39：`processIoExecutor` 用 `CallerRunsPolicy`——池和队列饱和时，读取循环在业务调用线程同步执行，可能在进入 `waitFor(timeout)` 前阻塞到 EOF，使声明的超时失效

**修复（用户决策）**：
- `processIoExecutor` 的 `RejectedExecutionHandler` 从 `CallerRunsPolicy` 改为 `AbortPolicy`（拒绝即抛 `RejectedExecutionException`，由读取任务的 `runAsync` 抛给调用方；调用方 waitFor 不会因读取阻塞而失效）
- `videoNormalizeExecutor` 保留 `CallerRunsPolicy`（转码任务语义不同，`CallerRunsPolicy` 是转码并行度的有意设计）

**涉及文件**：
- `worker-service/.../config/WorkerExecutorConfig.java`

**验证**：编译 + 相关测试回归 + `clean verify` 全绿。

### P1-D：中断分支统一（worker-service，Runner + 4 调用方）

**现状**（验证属实）：
- `ExternalProcessRunner.java` L128-137：正常路径 `readFuture.get` 捕获 `InterruptedException` 后 `Thread.currentThread().interrupt()` 并**正常返回成功**——违反方法 Javadoc"中断向上传播"
- `VideoTranscodeHandler.java` L122：外层 `catch (Exception)` 把中断当普通业务失败
- `TranscodeCommandHandler.java` L148：同上
- `ImageOptimizer.java` L173（generateCover 外层 catch）、L231（generateCoverFromVideo 外层 catch）：把中断当普通失败（`runOptimizer` L91-94 已有正确中断分支，为参照模式）

**修复**：
1. **Runner 正常路径**：`readFuture.get(5s)` 的 `InterruptedException` 改为恢复标志 + `throw e`（向上传播，不正常返回）
2. **VideoTranscodeHandler**（L117）：外层 `catch (Exception)` 前增加专门分支：
   ```java
   } catch (InterruptedException e) {
       Thread.currentThread().interrupt();
       log.warn("视频转码被中断: pageId={}", pageId);
       // 不发送 failed 事件（非业务失败），由监听器容器处理中断
   } catch (Exception e) { ... }
   ```
   （`@RabbitListener` 方法，中断后直接返回让容器感知；若需显式终止可重抛——采用恢复标志 + 返回，由 Spring AMQP 容器按中断状态处理）
3. **TranscodeCommandHandler**（L148）：`transcode()` 外层 `catch (Exception)` 前同样增加 `catch (InterruptedException e)`（恢复标志 + 返回 error message 或重抛；该方法返回 String 错误信息——恢复标志 + 返回 null 或按既有契约处理，需在实现时定夺，原则是中断不被当普通业务失败）
4. **ImageOptimizer.generateCover**（L173）：`catch (Exception)` 前增加 `catch (InterruptedException e)`（恢复标志 + 包装重抛，参照 `runOptimizer` L91-94 模式）
5. **ImageOptimizer.generateCoverFromVideo**（L229-232）：已有 `catch (RuntimeException) throw e` + `catch (Exception)`——在 `catch (RuntimeException)` 之前增加 `catch (InterruptedException e)`（恢复标志 + 重抛）

**涉及文件**：
- `worker-service/.../process/ExternalProcessRunner.java`
- `worker-service/.../event/VideoTranscodeHandler.java`
- `worker-service/.../event/TranscodeCommandHandler.java`
- `worker-service/.../image/ImageOptimizer.java`

**验证**：编译 + 相关测试回归 + `clean verify` 全绿。RunnerTest 读取阶段中断用例（P1-E）。

### P1-E：RunnerTest 用 ProcessHandle 真实断言（worker-service）

**现状**（验证属实）：
- `ExternalProcessRunnerTest.java` L50-101：主要断言耗时和中断标志，未通过 `ProcessHandle.isAlive/onExit` 观测直接进程和 descendants
- 输出测试只覆盖多条短行，未覆盖单条超长无换行输出

**修复**（新增/增强用例）：
1. **单条超长无换行输出**：子进程输出一条 200KB 无换行文本（如 `cmd /c` 用 `for` 拼接或生成器脚本）→ 断言 `stdout().length() <= MAX_OUTPUT_CHARS + 截断标记长度` + 含截断标记
2. **进程树终止**：启动会派生子进程的命令（如 `cmd /c ping -n 10 127.0.0.1`，cmd 派生子 ping 进程）→ 超时后断言 `process.toHandle().descendants()` 全部 `!isAlive()`（通过可观测手段获取 process 句柄——runner 不暴露 Process，需用外部可观测（PID 文件）或反射/包可见钩子）
3. **线程池饱和**：小池（core=1, queue=0）+ 并发多进程 → 断言拒绝策略生效（部分进程启动抛异常或等待不悬挂），读取任务不阻塞业务线程
4. **读取阶段中断**：中断到达 `readFuture.get` 阶段 → 断言中断向上传播（run 抛 InterruptedException）
5. 保留现有耗时上界断言（回归保护）

**涉及文件**：
- `worker-service/src/test/java/com/comicatlas/worker/process/ExternalProcessRunnerTest.java`
- 若需进程句柄观测：`ExternalProcessRunner` 增加包可见测试钩子（如 `Process lastProcess` 字段不合适——改为测试通过 PID 文件/`ProcessHandle.allProcesses()` 定位，或 runner 提供包可见 `startProcess` 拆分便于测试）

**验证**：RunnerTest 全用例通过（含新 ProcessHandle 断言）。

## 非目标（明确不做）

- 认证授权（延续可信本机设计，风险记录）
- AST/自定义 Checkstyle 语义门禁重构（守卫 var 增强已覆盖，后续项）
- DB Schema 迁移、前端任何改动
- `videoNormalizeExecutor` 的 `CallerRunsPolicy`（转码并行度有意设计，不改）
- 未点名的其他 `catch (Exception)`（各 MQ Handler 的 ack/reject 兜底 catch 属既有模式）

## 验证标准

1. `.\mvnw clean verify -DskipTests=false` → BUILD SUCCESS，五模块 Checkstyle 0
2. 新增/增强测试全部通过：
   - RunnerTest：单条超长输出截断（长度 ≤ 上限 + 截断标记）、进程树终止（descendants 全终止）、线程池饱和（AbortPolicy 不阻塞）、读取阶段中断（向上传播）
   - 既有 313 tests 回归，0 failures/errors
3. `git diff --check` 通过

## 实施顺序建议

按评审推荐顺序：
1. P1-A 字符块读取 + 固定容量缓冲（Task 1）
2. P1-B 进程树终止 + 不中断有界 reap（Task 2）
3. P1-C processIoExecutor AbortPolicy（Task 3）
4. P1-D 中断分支统一（Task 4）
5. P1-E RunnerTest ProcessHandle 断言（Task 5，依赖 Task 1-4 产物）
6. 最终全量验证 + 提交（Task 6）
