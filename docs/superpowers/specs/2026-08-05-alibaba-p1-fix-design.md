# 阿里 Java 规范复审 P1 阻断项整改设计

- 日期：2026-08-05
- 分支：`develop`
- 依据：`.omo/evidence/alibaba-java-backend-review-2026-08-05.md`（结论 FAIL / REQUEST_CHANGES）
- 范围：三个可执行 P1——完整测试门禁、线程池与中断处理、状态枚举落地
- 状态：已获用户确认

## 背景与范围裁定

评审共列 4 个 P1 + 3 个 P2 + 项目边界加固。经与用户对齐：

- **认证授权（P1）**：项目定位为"可信本机部署"（README 明确管理端默认不鉴权是有意设计），**降级为风险记录**，不进入本轮整改；仅强化 README/AGENTS.md 中的部署边界警告。
- **本轮整改**：三个可执行的 P1——测试门禁、线程池、枚举落地。
- **P2 与 Worker 只读加固**：留待下一轮（通配符 import、单行 if/for/while、语义命名残留、Checkstyle 增强、inSql 拼接、Worker 裸线程、只读 Mapper/账号）。

## 第一节：完整测试门禁修复（P1-1）

### 目标

`mvnw verify -DskipTests=false` 在 API 模块全绿（当前 6 failures + 5 errors）。

### 已知失败点与修复

| 失败点 | 根因 | 修复 |
|---|---|---|
| `DatabaseMigrationTest`（3 用例） | 断言 `containsExactly("1","2")`，实际已有 V1/V2/V10-V16 共 9 个迁移文件 | **动态断言**：运行时扫描 `classpath:db/flyway`，解析迁移文件版本号集合，断言 `flyway_schema_history` 应用后版本集与文件版本集一致；保留核心表存在断言与 V2 漂移列断言（实现时实测 V16 后仍成立，若被后续迁移变更则同步更新） |
| `ImportServiceTest` | `ImportServiceImpl` 构造器 11 个依赖，测试仅 mock 9 个，缺 `OutboxService`、`ApiStorageProperties` → null 注入 | 补 `@Mock OutboxService`、`@Mock ApiStorageProperties`；批量导入相关断言按实际失败点调整 |
| `RecoveryEventHandlerTest` | 测试用旧状态字符串 `"SUCCESS"`，生产代码终态集合为 `SUCCEEDED/FAILED/CANCELLED` → 幂等跳过分支不触发 | 4 处 `"SUCCESS"` → `"SUCCEEDED"`（与 `TERMINAL_STATUSES` 一致） |

### 执行顺序

1. 先跑完整 `mvnw verify -DskipTests=false`，取得**全量**失败清单（评审"主要包括"暗示可能还有其他失败类）。
2. 逐一定位、修复、重跑，直到 API 模块全绿。
3. 同步跑 Worker 测试（26 用例）确认无回归。

### 附带生产一致性修复

恢复任务终态字符串存在三方不一致：

- `RecoveryEventHandler.TERMINAL_STATUSES`：`SUCCEEDED/FAILED/CANCELLED`
- 测试与旧代码路径：`SUCCESS`
- `LegacyTaskBackfillService`：同时兼容 `SUCCESS`/`SUCCEEDED`

本轮统一为 **`SUCCEEDED`**（以现有生产代码为准），消除后续混淆。不改变 `import_task` 体系（其终态为 `SUCCESS`，与恢复任务体系独立）。

## 第二节：线程池与中断处理（P1-2）

### 目标

`VideoNormalizer` 符合阿里并发强制项。

### 改动点（`worker-service/.../file/transcode/VideoNormalizer.java`）

1. **受控线程池**：`Executors.newFixedThreadPool(parallelism)` → 注入 Spring 托管的 `ThreadPoolTaskExecutor`。
   - Worker 侧新增统一 executor 配置 bean：核心/最大线程数、有界队列、命名 `ThreadFactory`（`video-normalizer-N`）、`CallerRunsPolicy` 拒绝策略、`awaitTermination` 优雅关闭。
   - `VideoNormalizer` 改为构造器注入，删除 `Runtime.getRuntime().availableProcessors()` 私有推导逻辑（交由配置控制）。
2. **中断处理拆分**：
   - `ExecutionException` → 记录根因（`e.getCause()`），计入失败数。
   - `InterruptedException` → `Thread.currentThread().interrupt()` **恢复中断位**，`shutdownNow()` 取消未完成任务，记录日志后返回（不再继续搬文件）。
3. **同文件裸线程**：ffmpeg 输出读取（`new Thread(...)`）→ 受控读取（同步读取或复用托管线程池）；删除空 `catch (IOException ignored)`。
4. **范围边界**：`ImageOptimizer`（3 处）与 `MediaAnalyzer`（1 处）裸线程归入 P2 下一轮，本轮不动。

## 第三节：状态枚举落地（P1-3）

### 目标

实体使用 Java 枚举类型，DB 保持 VARCHAR，已有 `EnumTypeHandlers` 全局生效。

### 实体字段迁移（3 实体 8 字段）

| 实体 | 字段 | 现类型 | 目标枚举 |
|---|---|---|---|
| `Comic` | `status` | `String` | `ComicStatus`（api.common.enums） |
| `Comic` | `sourceType` | `String` | `SourceType` |
| `Media` | `hqStatus` | `String` | `HqStatus` |
| `Media` | `lqStatus` | `String` | `LqStatus` |
| `Media` | `status` | `String` | `MediaLifecycleStatus`（comic-common） |
| `Media` | `transcodeStatus` | `String` | `TranscodeStatus`（comic-common） |
| `ImportTask` | `status` | `String` | `ImportTaskStatus` |
| `ImportTask` | `sourceType` | `String` | `SourceType` |

### 波及面

- 生产代码 23 文件、75 处字符串赋值/比较：
  - `setStatus("READY")` → `setStatus(ComicStatus.READY)`
  - `"SUCCESS".equals(x.getStatus())` → `x.getStatus() == ImportTaskStatus.SUCCESS`
  - `TERMINAL_STATUSES` 字符串集合 → 枚举 `Set` 或直接枚举比较
  - MyBatis Plus 查询条件 `.eq/.in(..., "STR")` → 枚举值
- VO/DTO 转换：VO 字段保持 `String` 的转换点加 `.name()`；VO 已是字符串传输契约，不改前端契约。
- 测试代码：所有 `setStatus("PENDING")` 等同步改枚举（含 `ImportServiceTest`、`RecoveryEventHandlerTest` 及全量搜索定位的其它测试）。
- JSON 序列化：Jackson 默认输出枚举 `name()`，与现状字符串值一致，前端无感知。
- DB 兼容：VARCHAR 存 `name()`，现有数据零迁移；`EnumTypeHandlers` 的 `@MappedTypes` 已匹配枚举类型，字段类型变更后自动生效。

### 验证

- 每节完成后跑对应测试类。
- 全部完成后：`mvnw verify -DskipTests=false`（API）+ Worker 测试全绿。
- 抽查读链路（Reader/Catalog）与写链路（导入/回收站）契约测试不回归。

## 验证门禁（完成标准）

- [ ] API 模块 `mvnw verify -DskipTests=false` 退出码 0
- [ ] Worker 测试 26 用例全绿
- [ ] 指定 API 契约测试 44 用例全绿
- [ ] 现有 Checkstyle 0 违规（`mvnw -DskipTests verify`）
- [ ] 前端契约不变（枚举 name() 与现状字符串一致）

## 明确不做（本轮）

- API 认证授权（降级风险记录，仅文档警告强化）
- P2：通配符 import、单行 if/for/while、语义命名残留、Checkstyle 规则增强、inSql 拼接、`ImageOptimizer`/`MediaAnalyzer` 裸线程
- Worker 只读加固（只读 Mapper、专用只读数据库账号）
- 恢复任务状态迁移为枚举（本轮仅统一字符串值，`RecoveryTask.status` 字段迁移列入后续枚举批次）
