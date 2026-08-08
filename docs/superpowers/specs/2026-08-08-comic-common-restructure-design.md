# comic-common 按阿里开发规范整理设计

**日期**: 2026-08-08
**状态**: 设计待审阅
**范围**: comic-common 模块包结构整理（纯结构变更，无业务逻辑变更）

## 背景与目标

`comic-common` 是 API 与 Worker 共享的基础模块（事件 DTO、MQ 契约、枚举、工具）。当前 7 个包 56 个 Java 文件，经对照《阿里巴巴 Java 开发手册》审查，存在 3 类组织问题，本设计按阿里规范修正包归属与命名。

**目标**：包按业务边界分包、类职责单一、命名符合 POJO/DTO 规范；**不改变任何业务逻辑与 MQ 序列化契约**。

## 现状分析

| 包 | 文件数 | 内容 | 消费方 |
|----|--------|------|--------|
| `event` | 38 | ComicEvent sealed 接口 + 35 事件 record + **2 个非事件数据载体** | api 47 处 + worker 45 处 |
| `enums` | 6 | 生命周期/任务/转码状态枚举 | **仅 api 63 处**（worker 0、common 内部 0） |
| `constant` | 3 | MQ 契约（Exchanges/Queues/RoutingKeys） | api 44 + worker 35 |
| `mq` | 1 | MqConsumerSupport | api 14 + worker 13 |
| `dto` | 5 | 跨模块传输 DTO，命名后缀混用 | api 9 + worker 8 |
| `metadata` | 2 | MetadataV3 + MetadataJsonBuilder | api 3 + worker 3 |
| `util` | 1 | ImageDimensionsReader | api 1 + worker 1 |

## 阿里规范违规点

1. **event 包混入非事件类**（违反"按业务边界分包"）：
   `TranscodeMediaInfo`、`VideoMetadataFixResult` 不实现 `ComicEvent`，仅作为 `ManagementCommandCompletedEvent.transcode()`、`VideoMetadataFixCompletedEvent.results()` 的数据载荷，却与事件同包混放，造成"37 个 record"口径含 2 个非事件（35 事件 + 2 载体）。

2. **enums 共享边界过大**（违反"高内聚低耦合"）：
   6 个枚举（ChapterLifecycleStatus / ManagementTaskStatus / MediaLifecycleStatus / TaskStage / TaskType / TranscodeStatus）仅 api-service 消费，worker 与 common 内部均无引用，放在共享模块属于 API 内部概念泄漏。

3. **dto 命名后缀混用**（违反"命名规范"）：
   `ScanItemVO`/`ScanResultVO`（VO 后缀）与 `OutboxStats`/`TrashManifest`/`TrashManifestActual`（无后缀）并存。这些均为跨进程/跨模块传输的数据对象，语义属 DTO 而非视图对象（VO）。

## 变更设计

### 变更 1：event 包回归纯净（新建 payload 子包）

- 新建 `com.comicatlas.common.event.payload` 子包：
  - `TranscodeMediaInfo`（转码完成回传数据，ffprobe 实测）
  - `VideoMetadataFixResult`（视频元数据修复逐页结果）
- `event` 根包仅保留 `ComicEvent` sealed 接口 + 35 个事件 record（36 文件）

**理由**：数据载体与事件同域但非事件本身，`payload` 子包语义准确；两载体被 api 与 worker 双模块消费，必须留在 common，不可下沉 api。

**MQ 契约影响**：已核实两载体未被 `@JsonSubTypes` 注册（`ComicEvent.java` 无引用），序列化走事件内嵌字段、不依赖类全限定名，**迁移零契约影响**。

**影响面**：
- `comic-common/event/ManagementCommandCompletedEvent.java`、`VideoMetadataFixCompletedEvent.java`（import + 字段类型）
- worker：`TranscodeCommandHandler`、`VideoMetadataFixHandler`、`ManagementCommandPublisher`
- api：`ManagementCommandResultHandler`、`VideoMetadataFixCompletedHandler`
- 测试：`TranscodeCommandHandlerTest`、`ManagementEventContractTest`、`VideoMetadataFixCompletedHandler` 相关

### 变更 2：enums 下沉 api-service

6 个枚举全部移至 api 已有包 `com.comicatlas.api.common.enums`：
- `ChapterLifecycleStatus` / `ManagementTaskStatus` / `MediaLifecycleStatus` / `TaskStage` / `TaskType` / `TranscodeStatus`

**理由**：阿里规范"高内聚低耦合"，仅消费方持有的概念不应污染共享模块；api 已有 `common/enums` 包，归属自然。事件 DTO 均不引用这些枚举（已核实），MQ 契约零影响。

**影响面**：api-service 63 处 import 更新（含测试）；`AGENTS.md` 及架构文档中"枚举位于 comic-common"的描述同步更新。

### 变更 3：dto 命名统一为 *DTO

5 个文件统一为 `*DTO` 后缀（符合阿里 POJO 命名规范，明确传输对象语义）：
- `OutboxStats` → `OutboxStatsDTO`
- `TrashManifest` → `TrashManifestDTO`
- `TrashManifestActual` → `TrashManifestItemDTO`（清单明细项，语义更准确）
- `ScanItemVO` → `ScanItemDTO`
- `ScanResultVO` → `ScanResultDTO`

**理由**：这些 DTO 通过 MQ/API 跨模块、跨进程传输，语义属数据传输对象（DTO），`VO`（视图对象）后缀名不副实；`TrashManifestActual` 是清单中的明细项，命名 `TrashManifestItemDTO` 更贴切。

**影响面**（已逐文件核对）：
- `ScanItemDTO`：worker `DirectoryScanHandler`（4 处）+ `ScanResultDTO` 内部引用
- `ScanResultDTO`：api `DirectoryScanTaskVO`/`DirectoryScanTaskService`/`DirectoryScanTaskServiceImpl` + worker `DirectoryScanHandler` + common `DirectoryScanCompletedEvent`
- `OutboxStatsDTO`：api `OutboxStatsController` + 测试 `OutboxInboxRelayIT`
- `TrashManifestDTO`/`TrashManifestItemDTO`：api `ManagementCommandResultHandler`、`TrashLifecycleService`、`TrashManifestService` + worker `PurgeCommandHandler`/`RestoreCommandHandler`/`TrashCommandHandler`/`TrashManifestStore`/`WorkerConfig` + 测试 `TrashLifecycleIT`/`MediaUploadManagementIT`

## 不做的事（YAGNI）

- 不重建包结构（如按领域重分 `api/`、`worker/` 子包）——56 文件规模收益不划算，且 `@JsonSubTypes` 序列化注册依赖全限定名，风险高
- 不动 `constant`/`mq`/`metadata`/`util` 四个合规包
- 不修改任何业务逻辑、枚举值、事件字段
- 不新增依赖

## 验证策略

1. **编译门禁**：`.\mvnw -q -pl comic-common -am compile` → `api-service`、`worker-service` 全量 compile
2. **测试门禁**：`.\mvnw -pl api-service -am test` 与 `.\mvnw -pl worker-service -am test`（重点：`ManagementEventContractTest` 校验 MQ 序列化契约、`TranscodeCommandHandlerTest`、`ExternalProcessRunnerTest`）
3. **残留检查**：grep 确认旧名 `ScanItemVO|ScanResultVO|OutboxStats\b|TrashManifest\b` 与旧包 `common.enums.` 零残留
4. **文档同步**：`AGENTS.md`（WHERE TO LOOK 枚举行、dto 行）与 `docs/architecture/` 相关引用
5. **契约验证**：`ManagementEventContractTest` 全绿即证明 Jackson 多态序列化未受影响

## 提交规划

按"一个提交一个完整问题"拆分：
1. `整理 comic-common：event 数据载体下沉 payload 子包`
2. `整理 comic-common：枚举下沉 api-service（高内聚低耦合）`
3. `整理 comic-common：dto 统一 *DTO 命名`
4. `同步文档：comic-common 包结构描述更新`
