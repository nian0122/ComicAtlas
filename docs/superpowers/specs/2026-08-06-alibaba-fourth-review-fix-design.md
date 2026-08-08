# 阿里 Java 规范第四次复审（fourth-review）整改设计

**状态**: 历史归档
- 日期：2026-08-06
- 依据：`.omo/evidence/alibaba-java-backend-fourth-review-2026-08-06.md`（综合结论 FAIL / REQUEST_CHANGES）
- 审查提交：`3fdabd771e66c29b1bc35fadef4c4f7b27cd658c`；对比基线 `a73f34b`
- 审查边界：本地个人应用不要求鉴权；业务 HTTP 仅 API；Worker 仅通过 MQ 承接业务任务，可读 MySQL、不可写 MySQL；状态使用 Java enum + DB VARCHAR

## 目标

修复第四次复审 5 项阻断项（两处转码吞中断、Runner 输出无上限+回收无界、`var md` 守卫假绿、RunnerTest 断言不足、SmokeTest 未入 JUnit 门禁），保持 `mvnw clean verify` 全绿、Checkstyle 0、DB VARCHAR 零迁移、前端契约不变。

## 已通过项（本轮不动）

- `mvnw verify` 退出码 0、Worker 33 + API 272 = 305 tests 全绿、Checkstyle 0、`git diff --check` 通过
- Worker 无业务 HTTP Controller、六 Mapper 只暴露 `@Select`、只读账号 + `hikari.read-only` + `${MYSQL_PASS}` 注入、权限测试 14/14
- Java enum + DB VARCHAR、未知枚举值告警、下载日志脱敏

## 阻断项与修复设计

### P1-A：两处视频转码处理器仍吞中断（worker-service）

**现状**（验证属实）：

| 位置 | 问题 |
|---|---|
| `VideoTranscodeHandler` L65-80 | `start()` + `waitFor(10, MINUTES)`，中断被外层 `catch (Exception)`（L122）当普通失败——未恢复标志、未终止 ffmpeg、未传播 |
| `TranscodeCommandHandler` L112-126 | 同上（L118-123），外层 `catch (Exception)`（L148）吞掉 |

两者均 `redirectOutput(ProcessBuilder.Redirect.DISCARD)`（不需要 stdout）。

**修复**：两处接入 `ExternalProcessRunner.run(pb, 600)`（10 分钟超时与现有语义一致）：
- `VideoTranscodeHandler`：`run` 返回后检查 `exitCode != 0` → 抛 `IOException`；`InterruptedException` 由 runner 内恢复标志 + destroyForcibly 后向上传播，不再被外层 `catch (Exception)` 当普通失败
- `TranscodeCommandHandler`：同上
- 移除手写 `start()/waitFor/destroyForcibly` 与 `redirectOutput(DISCARD)`（runner 内部统一 redirectErrorStream；无需 stdout 的调用方可忽略输出）
- 两个 Handler 注入 `ExternalProcessRunner`（构造器参数或 `@RequiredArgsConstructor` final 字段）

**涉及文件**：
- `worker-service/.../event/VideoTranscodeHandler.java`
- `worker-service/.../event/TranscodeCommandHandler.java`

**验证**：编译 + 相关测试回归 + `clean verify` 全绿。

### P1-B：ExternalProcessRunner 输出无上限 + 回收无界（worker-service）

**现状**（验证属实）：
- `ExternalProcessRunner.java` L65：`StringBuilder processOutput` 无上限累积外部进程完整输出 → Worker 堆耗尽风险（评审安全通道）
- L96-99：超时 `destroyForcibly()` 后未等待子进程确定终止、未等读取任务有界收尾
- L79-87：`timeoutSeconds <= 0` 分支（`TorrentDownloader` 用 `timeout=0`）允许 aria2c 无限运行

**修复（用户决策：Torrent 可配置超时，默认 120 分钟）**：

1. **容量受限尾部缓冲**：`StringBuilder` 改容量受限的环形尾部缓冲（`RingBuffer` 或定长 `StringBuilder` 截断策略）：
   - 保留最近 `MAX_OUTPUT_CHARS`（常量，如 64KB）字符
   - 超限后**继续排空**（防管道死锁）但**不再保留**旧内容
   - 对外行为：`ExternalProcessResult.stdout()` 返回截断后的尾部内容（含超限提示标记，如首行 `"[输出已截断...]"`），错误信息诊断仍可用
2. **有界回收**：`destroyForcibly()` 后：
   - `process.waitFor(短超时, TimeUnit.SECONDS)`（如 5s）确保子进程确定终止
   - `readFuture.get(短超时, TimeUnit.SECONDS)` 确保读取任务收尾
   - 中断/超时两条路径统一走回收逻辑（提取私有 `destroyAndReap(Process, CompletableFuture)` 方法）
3. **Torrent 超时**：
   - `WorkerConfig.Torrent` 新增字段 `timeoutMinutes`（默认 120），配置键 `worker.torrent.timeout-minutes`
   - `TorrentDownloader.download()`：`processRunner.run(pb, timeoutMinutes * 60)` 替代 `timeout=0`
   - `application.yml` 增加 `timeout-minutes: ${TORRENT_TIMEOUT_MINUTES:120}`

**涉及文件**：
- `worker-service/.../process/ExternalProcessRunner.java`
- `worker-service/.../config/WorkerConfig.java`（Torrent.timeoutMinutes）
- `worker-service/.../file/download/TorrentDownloader.java`
- `worker-service/src/main/resources/application.yml`

**验证**：新增/增强 RunnerTest 用例（见 P2-B）+ `clean verify` 全绿。

### P2-A：命名守卫 var 漏洞 + cd 残留（api-service）

**现状**（验证属实）：
- `UploadSessionService` L396：`var md = java.security.MessageDigest.getInstance("SHA-256");` —— 守卫 `BannedPattern("MessageDigest", "md", ...)` 只匹配显式类型声明 `MessageDigest md`，`var` 声明绕过 → 守卫假绿（5/5 仍通过）
- `OutboxRelay` L125：`CorrelationData cd = new CorrelationData(...)` 残留

**修复（用户决策：守卫增强识别 var）**：

1. **生产清理**：
   - `UploadSessionService` L396：`var md` → `MessageDigest messageDigest`（显式类型 + 语义名）
   - `OutboxRelay` L125：`CorrelationData cd` → `correlationData`，方法体引用同步
2. **守卫增强（`SemanticNamingContractTest`）**：`BannedPattern.regex()` 增加 `var` 声明识别——匹配 `var <短名> = <类型基名>.` 初始化式：
   - 现有正则匹配显式类型声明：`\b<类型基名>\b... \b<短名>\b`
   - 新增 var 分支：`\bvar\s+\b<短名>\b\s*=\s*\b<类型基名>\b\s*\.`
   - 例如 `var md = MessageDigest.getInstance(...)` → 命中 MessageDigest/md 规则；`var correlationData = new CorrelationData(...)` → 不命中（变量名已合规）
   - `DETECTION_FIXTURES` 同步（两表逐项一致不变式）
   - 新增 fixture 测试：`var md = MessageDigest.getInstance(...)` 应被识别；`var messageDigest = MessageDigest.getInstance(...)` 不误报

**涉及文件**：
- `api-service/.../upload/UploadSessionService.java`
- `api-service/.../outbox/relay/OutboxRelay.java`
- `api-service/src/test/java/com/comicatlas/api/config/SemanticNamingContractTest.java`

**验证**：守卫新 fixture + 生产扫描通过 + `clean verify` 全绿。

### P2-B：ExternalProcessRunnerTest 生命周期断言增强（worker-service）

**现状**（验证属实）：
- `ExternalProcessRunnerTest` L50-77：超时/中断用例只验证异常或中断标志，未断言子进程实际退出
- 未覆盖输出容量上限截断、读取任务有界回收

**修复**（增强现有测试类）：
1. **超时用例**：断言子进程实际终止——用可观测子进程（如 `cmd /c` 写 PID 文件或通过 `Process.onExit()` 句柄），超时后 `assertThat(process.isAlive()).isFalse()` 或等待 `onExit` 完成
2. **中断用例**：中断后断言子进程被销毁（同上可观测手段）
3. **输出容量用例**：构造大量输出的子进程（如 `cmd /c for /l ... echo` 或重复 echo），断言 `result.stdout()` 长度受限（≤ MAX_OUTPUT_CHARS + 截断标记）且子进程正常退出、读取任务完成
4. **有界回收用例**：超时后短时间内 Runner 返回（回收逻辑不悬挂），进程与读取任务均收尾

**涉及文件**：
- `worker-service/src/test/java/com/comicatlas/worker/process/ExternalProcessRunnerTest.java`

**验证**：RunnerTest 全用例通过（含新断言）。

### P2-C：MediaAnalyzerSmokeTest 改标准 JUnit（worker-service）

**现状**（验证属实）：
- `MediaAnalyzerSmokeTest.java` 是 `main()` 方法独立程序（非 JUnit），未进入 Surefire 门禁
- L53-63：用 `ThreadPoolTaskExecutor` 传给现在要求 `ExternalProcessRunner` 的 `MediaAnalyzer` 构造器 → 显式运行报错（构造器已改为 `(WorkerConfig, ObjectMapper, ExternalProcessRunner)`）

**修复**：改为标准 JUnit 测试（纳入 Surefire 门禁）：
- `main()` → `@Test` 方法（或拆分多 `@Test`），`@DisplayName` + JUnit 5 `assertAll/assertEquals` 替代手写断言
- 构造依赖：`ExternalProcessRunner`（真实实例，注入 `processIoExecutor`）
- 保留 6 个场景（jpg/mp4/mkv/fake-ffprobe/missing/空 ffprobe 路径）与 fake-ffprobe 脚本生成逻辑
- 删除 `main` 方法、`System.exit`、手写断言工具方法（改用 JUnit）
- 包名/文件名不变（已是 `*Test.java`，Surefire 会拾取）

**涉及文件**：
- `worker-service/src/test/java/com/comicatlas/worker/file/parse/MediaAnalyzerSmokeTest.java`

**验证**：Surefire 门禁内运行通过 + `clean verify` 全绿。

## 非目标（明确不做）

- 认证授权（延续可信本机设计，风险记录）
- AST/自定义 Checkstyle 语义门禁重构（守卫 var 增强已覆盖本轮漏洞，评审建议的"AST 检查"记为后续项）
- DB Schema 迁移、前端任何改动
- 其他未点名的 catch(Exception) 清理（各 MQ Handler 的 ack/reject 兜底 catch 属既有模式，非外部进程中断路径）

## 验证标准

1. `.\mvnw clean verify -DskipTests=false` → BUILD SUCCESS，五模块 Checkstyle 0
2. 新增/增强测试全部通过：
   - `ExternalProcessRunnerTest`：超时/中断断言子进程实际退出 + 输出容量截断 + 有界回收
   - `MediaAnalyzerSmokeTest`：Surefire 门禁内 JUnit 通过（Worker 33+ → 34+）
   - `SemanticNamingContractTest`：var fixture 新增（var md 命中、var messageDigest 不误报）
3. 既有测试回归：Worker 33 + API 272 + 新增，0 failures/errors
4. `git diff --check` 通过；生产代码零 `var md`/`CorrelationData cd` 残留；Worker 生产零手写 `waitFor`

## 实施顺序建议

按评审推荐顺序：
1. P1-A 两处转码 Handler 接入（Task 1）
2. P1-B Runner 输出容量 + 有界回收 + Torrent 超时（Task 2，依赖 RunnerTest 增强验证）
3. P2-A var md/cd 清理 + 守卫增强（Task 3）
4. P2-B RunnerTest 生命周期断言（Task 4）
5. P2-C SmokeTest 改 JUnit（Task 5）
6. 最终全量验证 + 提交（Task 6）
