# 阿里 Java 规范复审（rerun-final）整改设计

**状态**: 历史归档
- 日期：2026-08-05
- 依据：`.omo/evidence/alibaba-java-backend-rerun-final-2026-08-05.md`（综合结论 FAIL / REQUEST_CHANGES）
- 审查提交：`f6ab39095ada9a4bfd3ce94c3054eb398269c383`
- 产品边界：严格本地个人应用，不要求鉴权；Worker 可读 MySQL、禁止写；业务 HTTP 仅 API

## 目标

修复 rerun-final 复审全部 5 项阻断项（2×P1 + 3×P2），保持 `mvnw clean verify` 全绿、Checkstyle 0 违规、DB VARCHAR 零迁移、前端契约不变。

## 已通过项（本轮不动）

- 596 tests 全绿、Checkstyle 0、通配符 import 0、`Executors.*`/裸 `new Thread` 0
- 空 catch、`System.out/err`、`printStackTrace`、日志字符串拼接 0；`git diff --check` 通过
- 动态 SQL 参数占位、Comic/Media/ImportTask 主状态字段已枚举化、Worker 零业务 HTTP Controller、Worker 源码零 DML 调用

## 阻断项与修复设计

### P1-A：中断异常宽泛 catch 吞掉（worker-service）

**现状**（全部验证属实）：

| 位置 | 问题 |
|---|---|
| `VideoNormalizer.transcode()` L253-257 | `readFuture.get(5s)` 用 `catch (Exception)`，吞 `InterruptedException` |
| `VideoNormalizer.transcodeToTemp()` L150 | 兜底 `catch (Exception)`，吞全部异常（含中断） |
| `ImageOptimizer.runOptimizer()` L134-138 | `readFuture.get(5s)` 同款宽泛捕获 |
| `MediaAnalyzer.analyzeVideo()` L131-135 | `readFuture.get(1s)` 同款宽泛捕获 |

**修复原则**：中断必须恢复或向上抛出；子进程必须在取消/中断时 `destroyForcibly()` 终止。

对三处 `readFuture.get(...)` 统一改为：

```java
try {
    readFuture.get(TIMEOUT, TimeUnit.SECONDS);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    process.destroyForcibly();
    throw e;  // 或等价地让中断向上传播
} catch (ExecutionException | TimeoutException e) {
    log.warn("等待子进程输出读取超时: {}", e.getMessage());
}
```

对 `VideoNormalizer.transcodeToTemp()`：在方法级 catch 前增加中断分支，`InterruptedException` 恢复中断标志后不再吞掉（通过向上抛出或让 `future.get()` 感知）。需保证 `normalize()` 外层已有正确的 `InterruptedException` 处理（L100-107 已正确：恢复中断 + 取消剩余 + return 0）。

**涉及文件**：
- `worker-service/.../file/transcode/VideoNormalizer.java`
- `worker-service/.../image/ImageOptimizer.java`
- `worker-service/.../file/parse/MediaAnalyzer.java`

**验证**：编译 + `clean verify` 全绿；无新增测试（现有转码/LQ/分析链路集成测试回归覆盖）。

### P1-B：Worker 只读边界未在生产配置成立（worker-service）

**现状**（全部验证属实）：
- 生产 `application.yml` L36 `username: ${MYSQL_USER:root}`（默认 root 高权限），无 `hikari.read-only`
- 6 个 Worker Mapper 继承 MyBatis Plus `BaseMapper`：`ExportComicMapper`、`ExportChapterMapper`、`ExportCatalogMapper`、`ExportMediaMapper`、`ExportUploadSessionMapper`、`ExportUploadFileMapper`
- `WorkerDatabasePermissionIT` 用动态属性显式换成测试专用只读账号 + Hikari read-only，未验证生产默认配置
- Worker 生产源码零 DML 调用（已 grep 确认，仅 `Files.delete` 等文件操作）

**修复（三层加固）**：

1. **生产配置**（`worker-service/src/main/resources/application.yml`）：
   ```yaml
   spring:
     datasource:
       username: ${MYSQL_USER:comicatlas_ro}
       password: ${MYSQL_PASS:comicatlas_ro_pass}
       hikari:
         read-only: true
         maximum-pool-size: 2
   ```
   默认账号由 root 改为独立只读账号 `comicatlas_ro`（保留环境变量覆盖）；启用 HikariCP read-only 连接。`README.md` / `.env` 示例 / 运维文档同步说明 Worker 需 GRANT SELECT 权限。

2. **Mapper 能力层**：6 个 `Export*Mapper` 移除 `extends BaseMapper`，改为显式只读查询接口。接口层不再暴露 `insert/update/delete`。

   具体做法：每个 Mapper 保留自定义 `@Select` 查询方法，并为调用点需要的 BaseMapper 通用方法补充显式 `@Select` 声明：
   - `ExportComicMapper`：`selectById`（`ExportCollector` 使用）
   - `ExportChapterMapper`：`selectByComicIdOrderByGlobalOrder`（已有）
   - `ExportCatalogMapper`：`selectList(wrapper)` → 显式 `@Select` 方法（`ExportCollector` 使用）
   - `ExportMediaMapper`：`selectByComicId`/`selectByChapterId`（已有）+ `selectById`（`MediaUploadCommandHandler`、`TranscodeCommandHandler` 使用）
   - `ExportUploadSessionMapper`：`selectById`（`MediaUploadCommandHandler` 使用）
   - `ExportUploadFileMapper`：`selectBySessionId`（已有）

   已确认全部调用点仅使用：`selectById`、`selectByComicId`、`selectByChapterId`、`selectBySessionId`、`selectByComicIdOrderByGlobalOrder`、`selectList`。无任何 DML 调用。

3. **测试补洞**：新增生产配置契约测试（无容器、纯配置加载）：
   - 加载 `src/main/resources/application.yml`
   - 断言 `spring.datasource.hikari.read-only` 为 `true`
   - 断言 `spring.datasource.username` 默认值非 `root`
   - 防止生产默认配置回退为高权限可写
   - `WorkerDatabasePermissionIT` 保持不动（仍验证 MySQL GRANT + Hikari 双层）

**涉及文件**：
- `worker-service/src/main/resources/application.yml`
- 6 个 `worker-service/.../mapper/Export*Mapper.java`
- 新增配置契约测试（建议放 `worker-service/.../config/` 或 `worker-service/.../integration/`）
- `README.md`、`.env.example`、`docs/operations/management.md`（只读账号说明）

**验证**：编译 + 新增测试通过 + `clean verify` 全绿。

### P2-C：语义命名残留 + 守卫假绿（api-service）

**现状**（全部验证属实）：
- `RecoveryEngine` L291/L310：`Map<String, Object> cd`
- `ImportEventHandler` L203/L223：`cd`；L274：`md`
- `SemanticNamingContractTest.BANNED` 只有 `cm/pm/chm`，缺 `cd/md` → 生产违规仍 5/5 通过（守卫假绿）

**修复**：
1. `RecoveryEngine` 两处 `cd` → `catalogData`；`ImportEventHandler` 两处 `cd` → `catalogData`、一处 `md` → `mediaData`
2. `SemanticNamingContractTest` 的 `BANNED` 与 `DETECTION_FIXTURES` 同步新增：
   ```java
   new BannedPattern("Map<String, Object>", "cd", "catalogData"),
   new BannedPattern("Map<String, Object>", "md", "mediaData"),
   ```
   （保持两表逐项一致的不变式）

**涉及文件**：
- `api-service/.../admin/recovery/RecoveryEngine.java`
- `api-service/.../importer/event/ImportEventHandler.java`
- `api-service/.../config/SemanticNamingContractTest.java`

**验证**：守卫测试新增变体检测通过；`clean verify` 全绿。

### P2-D：状态枚举迁移不完整（api-service）

**现状**（全部验证属实）：

| 位置 | 现状 |
|---|---|
| `Chapter.status` | 字段为 `String`，注释声明对应 `ChapterLifecycleStatus` |
| `UploadSession.status` | 字段为 `String`（`UploadSessionStatus` 枚举已存在，实体仍用 `name()` 字符串） |
| `ExportTask.status` | `String`，无枚举（注释 PENDING/RUNNING/SUCCESS/FAILED） |
| `RecoveryTask.status` | `String`，无枚举（QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED） |
| `DirectoryScanTask.status` | `String`，无枚举（PENDING/SUCCESS/FAILED） |
| `ComicStatus` vs `ComicLifecycleStatus` | 双套高度重叠枚举并存（ComicStatus 84 引用、ComicLifecycleStatus 9 引用） |

**修复（完整收敛）**：

1. **`Chapter.status` → `ChapterLifecycleStatus`**（TypeHandler 已存在于 `EnumTypeHandlers`）
   - `Chapter` 实体字段类型改枚举
   - 赋值点改枚举：`TrashLifecycleService`（5 处：TRASHING/RESTORING/PURGING/TRASHED/READY）、`ManagementCommandResultHandler`（5 处：TRASHED/READY/DELETED/TRASHED/READY）、`ChapterManagementServiceImpl` L73（READY）
   - 读取/比较点适配（`"TRASHING".equals(chapter.getStatus())` 等字符串比较改为枚举 `==`/`!=`）：
     - `ComicServiceImpl` L192、`ReaderServiceImpl` L41：`ChapterLifecycleStatus.READY.name().equals(...)` → `chapter.getStatus() == ChapterLifecycleStatus.READY`
     - `TrashLifecycleService`、`ManagementCommandResultHandler` 中多处 `"XXX".equals(chapter.getStatus())` → 枚举比较
     - `ManagementStateMachine.validateChapterTransition/canTransitionChapter` 接收 `String` 参数 → 调用点传 `chapter.getStatus().name()`（方法签名保持 String，内部状态机字符串映射不变）
     - `OperationPolicyService.forChapter(String)` → 调用点（`TrashLifecycleService` L109/169/220）传 `chapter.getStatus().name()`
     - `TrashLifecycleService.resolveDbStatus()`（返回 `String`）L407 `yield chapter.getStatus()` → `chapter.getStatus() == null ? null : chapter.getStatus().name()`
     - `ChapterVO.status` 为 `String`，`vo.setStatus(chapter.getStatus())` → 改为 `chapter.getStatus() == null ? null : chapter.getStatus().name()`（**前端契约不变**，VO 仍输出枚举名字符串）
     - `LegacyTaskBackfillService`（ImportTask 已枚举化，L65 已有 `.name()` 先例）→ `recoveryTask.getStatus()`、`task.getStatus()` 传参处同样加 `.name()`

2. **`UploadSession.status` → `UploadSessionStatus`**
   - `UploadSession` 实体字段类型改枚举
   - `UploadSessionService` 全部 `setStatus(UploadSessionStatus.X.name())` → `setStatus(UploadSessionStatus.X)`（约 8 处），`getStatus()` 比较同步调整
   - `UploadSessionStatus` 注册 TypeHandler（新增 `UploadSessionStatusHandler`）

3. **新建 3 个任务状态枚举**（放 `api-service/.../common/enums/`）：
   - `ExportTaskStatus`：PENDING/RUNNING/SUCCESS/FAILED
   - `RecoveryTaskStatus`：QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED
   - `DirectoryScanTaskStatus`：PENDING/SUCCESS/FAILED
   - 对应实体 `ExportTask`/`RecoveryTask`/`DirectoryScanTask` 字段类型改枚举
   - 赋值与比较点改枚举（`ExportServiceImpl`、`ExportStartedHandler`、`ExportFailedHandler`、`ExportCompletedHandler`、`RecoveryTaskServiceImpl`、`RecoveryEventHandler`、`DirectoryScanTaskServiceImpl` 等）
   - `RecoveryEventHandler.TERMINAL_STATUSES`：`Set<String>` → `EnumSet<RecoveryTaskStatus>`（值 SUCCEEDED/FAILED/CANCELLED）
   - 三个新枚举注册 TypeHandler
   - `ExportTask` 的 `isPending()/isRunning()/isSuccess()/isFailed()` 辅助方法改为枚举比较（保留方法签名，前端/调用方不受影响）
   - `RecoveryTaskServiceImpl` L139/L172：实体枚举化后，VO（`String`）与实体互转处需 `name()`/`valueOf` 适配（`vo.setStatus(...)` 加 `.name()`、`setStatus(vo.getStatus())` 加 `valueOf`，注意 VO 可能为 null）
   - `LegacyTaskBackfillService`：`recoveryTask.getStatus()`、`task.getStatus()`（ExportTask）传入 `baseTask`/`baseItem` 处加 `.name()`（参考 L65 ImportTask 既有先例）
   - `DirectoryScanTaskServiceImpl` L114：`"SUCCESS".equals(task.getStatus())` → 枚举比较；`ManagementTaskStatus` 转换保持

4. **`ComicStatus` / `ComicLifecycleStatus` 合并**
   - 以 `ComicStatus` 为主（84 处引用，含管理后台内部 `REFRESHING`）
   - 删除 `comic-common/.../enums/ComicLifecycleStatus.java`
   - `EnumTypeHandlers` 移除 `ComicLifecycleStatusHandler`
   - `ComicServiceImpl.comicStatusName()`（L493）、`ComicListQueryServiceImpl`（L164）的 `ComicLifecycleStatus.valueOf(...)` 改为 `ComicStatus.valueOf(...)`
   - DB VARCHAR 值不变（两套枚举值完全同名），零迁移

**涉及文件**（预计）：
- 实体：`Chapter`、`UploadSession`、`ExportTask`、`RecoveryTask`、`DirectoryScanTask`
- 枚举新增：`ExportTaskStatus`、`RecoveryTaskStatus`、`DirectoryScanTaskStatus`（`common/enums/`）
- 枚举删除：`comic-common/.../ComicLifecycleStatus.java`
- Service/Handler：`TrashLifecycleService`、`ManagementCommandResultHandler`、`UploadSessionService`、`ExportServiceImpl`、`ExportStartedHandler`、`ExportFailedHandler`、`ExportCompletedHandler`、`RecoveryTaskServiceImpl`、`RecoveryEventHandler`、`DirectoryScanTaskServiceImpl`、`ComicServiceImpl`、`ComicListQueryServiceImpl`
- `EnumTypeHandlers`（新增 4 个 Handler、移除 1 个）
- 涉及 `Chapter.getStatus()` 读取的其余调用点（`ComicServiceImpl`、`ReaderServiceImpl`、`ChapterManagementServiceImpl`、`TrashLifecycleService`、`ManagementCommandResultHandler`、`ChapterVO`、`OperationPolicyService` 调用处、`LegacyTaskBackfillService`）同步适配（`.name()` 或枚举比较）

**验证**：`clean verify` 全绿（API 272 + 新增）；相关 IT（`ReadingLifecycleCompatibilityIT`、`UnifiedTaskCompatibilityIT`、`MediaUploadManagementIT`、导出/恢复/DirectoryScan IT）回归。

### P2-E：敏感下载凭据写入日志（worker-service）

**现状**（全部验证属实）：
- `ArchiveDownloader` L41：`log.info("Archive download: {}", url)` 记录含 gallery token 与 archiver key 的完整 URL
- `TorrentDownloader` L21：`log.info("Torrent: magnet={}, dest={}", magnetUrl, destDir)` 记录完整 magnet URI

**修复**：
1. `ArchiveDownloader`：仅记录 `gid` + 目标路径：
   ```java
   log.info("Archive download: gid={}, dest={}", gid, destFile);
   ```
2. `TorrentDownloader`：仅记录 magnet 摘要（提取 `btih` 哈希截断）+ 目标路径：
   ```java
   log.info("Torrent: btih={}..., dest={}", summarizeMagnet(magnetUrl), destDir);
   ```
   `summarizeMagnet` 提取 `xt=urn:btih:<hash>` 后取前 32 位，缺失时降级为 `"magnet?<len>chars"`（不打印完整 URI）。

**涉及文件**：
- `worker-service/.../file/download/ArchiveDownloader.java`
- `worker-service/.../file/download/TorrentDownloader.java`

**验证**：编译 + `clean verify` 全绿；无行为变更（仅日志内容）。

## 非目标（明确不做）

- 认证授权（用户已决策：坚持可信本机设计，降级为风险记录）
- 前端任何改动（枚举 `name()` 与 DB VARCHAR 字符串一致，前端契约不变）
- DB Schema 迁移（全部枚举化保持 VARCHAR 存 `name()`）
- 恢复任务/批量操作等既有功能行为变更

## 验证标准

1. `.\mvnw clean verify -DskipTests=false` → BUILD SUCCESS，五模块 Checkstyle 0
2. 新增测试全部通过：
   - Worker 生产配置契约测试（read-only + 非 root 默认账号）
   - 语义命名守卫新增 `cd`/`md` 变体检测
3. 既有测试回归：API 272 + Worker 26（+ 新增），0 failures/errors
4. `git diff --check` 通过

## 实施顺序建议

按评审修复顺序：
1. P1-A 中断拆分（独立、低风险）
2. P1-B Worker 只读（配置 + Mapper + 测试，中等）
3. P2-C 命名 + 守卫（简单）
4. P2-D 状态枚举收敛（最大，含双枚举合并）
5. P2-E 日志脱敏（简单）
6. 最终全量验证 + 提交
