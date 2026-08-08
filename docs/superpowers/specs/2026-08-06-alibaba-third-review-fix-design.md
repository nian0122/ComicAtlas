# 阿里 Java 规范第三次复审（third-review）整改设计

**状态**: 历史归档
- 日期：2026-08-06
- 依据：`.omo/evidence/alibaba-java-backend-third-review-2026-08-06.md`（综合结论 FAIL / REQUEST_CHANGES）
- 审查提交：`a73f34baff1938ace6881e33bdcf88a27f9285ce`
- 产品边界：严格本地个人应用，无鉴权要求；业务 HTTP 仅 API；Worker 可读 MySQL、禁止写；Java enum + DB VARCHAR

## 目标

修复第三次复审 1 项 P1 阻断项（外部进程中断清理未完全覆盖）与 3 条非阻断建议（语义命名清理、safeValueOf 可观察化、Worker 密码去默认值），保持 `mvnw clean verify` 全绿、Checkstyle 0、DB VARCHAR 零迁移、前端契约不变。

## 已通过项（本轮不动）

- 300 tests 全绿（API 272 + Worker 28）、Checkstyle 0、命名守卫 5/5
- Worker 只读三层加固（生产账号 + Hikari read-only + Mapper 无 DML）、16 权限测试通过
- 状态枚举迁移（Chapter/UploadSession/Export/Recovery/DirectoryScan）与 Comic 双枚举合并完成
- 通配符 import、裸线程、`Executors`、空 catch、`System.out/err`、`printStackTrace` 均为 0
- Archive URL 与 magnet 日志已脱敏；`git diff --check f6ab390..HEAD` 通过

## 阻断项与修复设计

### P1-A：外部进程中断清理未完全覆盖（worker-service）

**现状**（全部验证属实）：

| # | 位置 | 问题 |
|---|------|------|
| 1 | `MediaAnalyzer.analyzeVideo()` L132 | `waitFor(...)` 中断落入外层 `catch (Exception)`（L152），未恢复中断、未终止 ffprobe |
| 2 | `VideoNormalizer.transcode()` L257 | 主 `process.waitFor()` 无保护，中断直抛但 ffmpeg 未销毁 |
| 3 | `TorrentDownloader.download()` L34 | 裸 `process.waitFor()`，中断时 aria2c 未终止 |
| 4 | `ImageOptimizer.generateCover()` L240 | `readFuture.get(5s)` 仍 `catch (Exception)`，中断被当普通失败 |
| 5 | `ImageOptimizer.generateCoverFromVideo()` L333 | 同上 |
| 6 | `DownloadContext.download()` L38 | `catch (Exception)` 吞 Archiver HTTP 下载中断 → 静默回退 Torrent |

**修复方案（用户决策：统一封装工具类）**：

新建 `worker-service/src/main/java/com/comicatlas/worker/file/ExternalProcessRunner.java`（或 `worker/process/` 包）：

```java
package com.comicatlas.worker.process;

/** 外部进程执行结果：退出码 + 已消费的 stdout 内容 */
public record ExternalProcessResult(int exitCode, String stdout) {}

/**
 * 统一外部进程执行工具：启动 → 异步消费 stdout → 带超时 waitFor。
 * 中断语义：InterruptedException 必须恢复中断标志 + destroyForcibly 终止子进程 + 向上传播，
 * 禁止宽泛 catch(Exception) 吞掉中断。超时同样 destroyForcibly。
 */
```

**核心方法设计**（`run(ProcessBuilder, long timeoutSeconds)` 返回 `ExternalProcessResult`，内部职责）：
1. `processBuilder.redirectErrorStream(true)` + 启动进程
2. 提交 stdout 消费任务到 `processIoExecutor`（`CompletableFuture.runAsync`）
3. `process.waitFor(timeout, TimeUnit.SECONDS)`：
   - `InterruptedException` → `Thread.currentThread().interrupt()` + `process.destroyForcibly()` + 重抛（或包装后传播）
   - 超时未完成 → `process.destroyForcibly()` + 抛超时异常
4. 等待输出 Future 完成（短超时），若中断同样恢复标志
5. 返回 `ExternalProcessResult(exitCode, stdout)`

**6 处接入**：

| 位置 | 改造 |
|------|------|
| `MediaAnalyzer.analyzeVideo()` | 用 `ExternalProcessRunner.run(ffprobeBuilder, FFPROBE_TIMEOUT_SECONDS)` 替换手写 waitFor/readFuture 块；中断/超时/非零退出走 fallback |
| `VideoNormalizer.transcode()` | 用 runner 替换手写 waitFor/readFuture；中断 → destroyForcibly + 重抛（保留现 L261-263 已正确的中断分支语义） |
| `TorrentDownloader.download()` | 用 runner 替换裸 waitFor；退出码 143/0 语义保留 |
| `ImageOptimizer.runOptimizer()` | 用 runner 替换 L121-138 waitFor/readFuture |
| `ImageOptimizer.generateCover()` | 用 runner 替换 L226-243 |
| `ImageOptimizer.generateCoverFromVideo()` | 用 runner 替换 L327-334 |
| `DownloadContext.download()` | 不再用宽泛 `catch (Exception)` 包 Archiver 下载——中断单独捕获（恢复标志 + 记录，不静默回退）；其余异常才回退 Torrent |

**回归测试**（评审要求"补可控子进程回归测试"）：
- 新增 `ExternalProcessRunnerTest`（无 Docker、快速）：启动可快速退出的进程（如 `cmd /c exit 0`、`cmd /c echo xxx`）验证正常路径；构造超时场景（如 `cmd /c ping -n 5 127.0.0.1` 配短超时）验证 destroyForcibly 被调用且抛超时异常；中断场景验证中断标志恢复
- 若可直接注入 runner 的 timeout，测试可完全确定性

**涉及文件**：
- 新建 `worker-service/.../process/ExternalProcessRunner.java`
- Modify：`MediaAnalyzer`、`VideoNormalizer`、`TorrentDownloader`、`ImageOptimizer`、`DownloadContext`
- 新建 `worker-service/.../process/ExternalProcessRunnerTest.java`（或 image/parse 包下对应测试）

**验证**：新测试通过 + worker 既有测试回归 + `clean verify` 全绿。

### P2-A：语义命名清理 + 守卫（api-service）

**现状**（验证属实，均不在守卫 BANNED 表）：
- `MessageDigest md`：`UploadStorageService` L157、`ManagementTaskService` L746、`ImportServiceImpl` L434（3 处）
- `TaskType op`：共 18 处声明——局部变量 4 处（`UploadSessionService` L309、`MediaOperationCommandService` L63/L94、`ManagementTaskService` L365）+ 方法参数 14 处（`TrashLifecycleService` L256/447/462/480/494、`UploadSessionService` L371、`MediaOperationCommandService` L326/337/347、`LegacyTaskBackfillService` L164、`BatchOperationService` L213/224、`BatchEligibilityChecker` L74/85）。全部为 private 方法参数或局部变量，改名不影响公共契约
- `TaskTarget t`：`TrashLifecycleService` L486、`MediaOperationCommandService` L348、`BatchOperationService` L203（3 处局部变量）
- `ReadingHistory rh`：`HistoryServiceImpl` L84（1 处局部变量）

**修复**：
1. 改名（仅局部变量/参数，不动方法签名与 public 契约）：
   - `md` → `messageDigest`（3 处）
   - `op` → `operation`（18 处声明：局部变量 4 + private 方法参数 14）
   - `t` → `target`（3 处 `CreateManagementTaskRequest.TaskTarget t`）
   - `rh` → `history`（1 处）
2. 守卫 `BANNED`/`DETECTION_FIXTURES` 同步新增（保持两表逐项一致）：
   ```java
   new BannedPattern("MessageDigest", "md", "messageDigest"),
   new BannedPattern("TaskType", "op", "operation"),
   new BannedPattern("TaskTarget", "t", "target"),
   new BannedPattern("ReadingHistory", "rh", "history"),
   ```

**注意**：评审提到"生产代码仍可见"这些短名——守卫是"固定类型+变量名映射"模式，加入上述规则后这些具体残留即被覆盖。评审建议"扩展门禁或改用 AST/自定义 Checkstyle 规则"——本轮采用**扩展守卫表**（低风险、立即可覆盖已知残留），不引入 AST 重构（超出本轮范围，记为后续项）。

**涉及文件**：
- 7 个 api-service 生产文件（上述清单）
- `SemanticNamingContractTest.java`

**验证**：守卫测试 + 相关单测 + `clean verify` 全绿。

### P2-B：safeValueOf 未知枚举值可观察化（api-service）

**现状**：`EnumTypeHandlers.safeValueOf`（L150）对未知 DB 枚举值静默返回 null，可能隐藏脏数据。

**修复**（用户决策：可观察化，不抛异常）：
```java
private static <T extends Enum<T>> T safeValueOf(Class<T> clazz, String value) {
    if (value == null) { return null; }
    try {
        return Enum.valueOf(clazz, value);
    } catch (IllegalArgumentException e) {
        log.warn("数据库存在未知枚举值: type={}, value={}（已按 null 处理，建议核查脏数据）", clazz.getSimpleName(), value);
        return null;
    }
}
```
- 类添加 `@Slf4j`（若未加）
- 14 个 Handler 共用此方法，一处修改全部生效
- 保持返回 null（旧数据仍可读入，不破坏读取链路）；仅记录可观察警告

**涉及文件**：`api-service/src/main/java/com/comicatlas/api/common/handler/EnumTypeHandlers.java`

**验证**：编译 + 相关单测 + `clean verify` 全绿。可考虑新增一个针对 safeValueOf 的单元测试（Mockito 不便测 static——可提取为包私有静态方法直接测，或依赖编译验证）。

### P2-C：Worker 密码去默认值（worker-service）

**现状**：`application.yml` L37 `password: ${MYSQL_PASS:comicatlas_ro_pass}`——固定默认密码。

**修复**（用户决策：去默认值）：
```yaml
  datasource:
    # Worker 只读账号密码必须由环境变量提供（禁止固定默认密码）
    username: ${MYSQL_USER:comicatlas_ro}
    password: ${MYSQL_PASS}
    ...
```
- 无默认值：未配置 `MYSQL_PASS` 时 Spring 启动报错（`Could not resolve placeholder`），强制部署方提供
- `WorkerDataSourceProductionConfigTest` 契约测试补充断言：`spring.datasource.password` 原始文本不含 `comicatlas_ro_pass`（防固定默认密码回归）
- 同步文档：README/.env 示例/`docs/operations/management.md` 注明 Worker 密码必须由环境提供

**涉及文件**：
- `worker-service/src/main/resources/application.yml`
- `WorkerDataSourceProductionConfigTest.java`
- `README.md`、`.env.example`、`docs/operations/management.md`

**验证**：契约测试通过 + worker 测试回归 + `clean verify` 全绿。

## 非目标（明确不做）

- 认证授权（延续可信本机设计，风险记录）
- AST/自定义 Checkstyle 语义门禁重构（守卫表扩展已覆盖本轮已知残留，记为后续项）
- DB Schema 迁移、前端任何改动
- 未知枚举值抛异常（保持 null 兼容旧数据，仅 warn 可观察）

## 验证标准

1. `.\mvnw clean verify -DskipTests=false` → BUILD SUCCESS，五模块 Checkstyle 0
2. 新增测试全部通过：
   - `ExternalProcessRunnerTest`（正常/超时/中断三路径）
   - Worker 配置契约测试补充密码断言
3. 既有测试回归：API 272 + Worker 28（+ 新增），0 failures/errors
4. `git diff --check` 通过；守卫测试 5+4 条规则全通过

## 实施顺序建议

1. P1-A 统一工具类 + 6 处接入 + 回归测试（最大，独立）
2. P2-A 语义命名清理 + 守卫（独立）
3. P2-B safeValueOf 可观察化（小，独立）
4. P2-C Worker 密码去默认值 + 文档（小，独立）
5. 最终全量验证 + 提交
