# 后端文件分类 TODO

检查日期：2026-09-16。依据当前工作区源码，按“运行模块 → 业务域 → 框架职责”梳理。**本轮只记录待办，不修改 Java、配置、SQL、测试或文件位置。所有复选框均表示尚未实施。** 工作区已有其他未提交改动，以下路径以检查时版本为准。

本清单补充[后端代码分类](backend-package-organization.md)，使用独立编号 `PACKAGE-xx`，不把已有 DECOUPLE、LAYER、IMPL 项重新标记为完成。此前文档中的迁移及测试记录属于历史工作，不是本轮执行结果。

## 分类规则

先确定业务所有者，再看类实际执行的职责，不能只凭 `Service`、`Handler` 后缀或 Spring 注解归类。

| 维度 | 分类规则 |
|---|---|
| 运行边界 | API 管理写入与编排；Reading 阅读查询及进度保存；Worker 文件执行和只读数据库；Gateway 路由 |
| 业务边界 | library 查询、metadata 属性与元数据、catalog 目录章节、importer 导入扫描、exporter 导出、recovery 磁盘恢复、trash 回收、media 媒体处理、storage 存储、task 通用任务 |
| HTTP | `controller` 接收请求；`dto` 放请求和响应；异常映射属于 Web 适配职责 |
| 业务执行 | `service` 编排、事务与业务服务；`policy` 业务规则；`assembler` 数据装配；`model` 内部结果和值对象 |
| 数据访问 | `persistence.entity / mapper / handler` 分别放实体、SQL 访问、MyBatis 类型适配 |
| MQ | `event` 接收消息；`publisher` 发布事件；Worker 的 `command` 放被命令分发器调用的执行器 |
| 基础设施 | `config` 装配与属性；`adapter` 外部系统或文件系统适配；`cleanup` 定期清理；`bootstrap` 启动触发 |
| 共享能力 | 只有实际跨业务复用才进入服务内 `shared`；跨服务共享仍在现有 comic-shared / comic-common 内，不额外拆 Maven 模块 |

以上为后续整理目标；不要求每个业务创建全部子目录。`engine`、`parser`、`archive` 等已有内聚包可以保留。

## API：业务归属与框架角色

以下路径相对 `api-service/src/main/java/com/comicatlas/api/`。

- [ ] **TODO PACKAGE-01：业务专属配置回归业务域。** `config/MetadataSyncSchedulerConfig.java` 专供元数据更新合并窗口，建议归 `metadata.config`；`storage/ApiStorageProperties.java` 建议归 `storage.config`。`config/MetadataJsonBuilderConfig.java` 是共享构建器的注册入口，先核对所有注入方，再决定保留应用装配或归 `metadata.config`，不能仅凭名称迁移。保持 Bean 名、配置前缀、默认值和销毁行为。
- [ ] **TODO PACKAGE-02：MyBatis 类型实现与配置分开。** `config/ManagementEnumTypeHandlers.java` 实现 `BaseTypeHandler`，不是 Spring 配置类。建议整体归 `shared.persistence.handler`，保持管理端专用范围；如果后续按业务拆分，则分别进入对应业务的 `persistence.handler`。同步核对类型处理器扫描、嵌套类全限定名、XML/注解引用及枚举字符串兼容性。
- [ ] **TODO PACKAGE-03：元数据刷新策略归 metadata。** `task/service/MetadataRefreshTaskPolicy.java` 直接负责漫画刷新锁定、取消释放和重试准备，建议归 `metadata.policy`。先让 `task` 定义通用生命周期策略接口，由 metadata 实现，避免移动后 task 继续依赖具体业务类。属于边界整理，不能靠改包名完成；参照 DECOUPLE-05，复核当前实现与历史完成记录的差异，保留同事务及 attempt 语义。
- [ ] **TODO PACKAGE-04：装配器和结果对象离开 service。** `task/service/TaskResponseAssembler.java` 只把任务实体转换为响应，建议归 `task.assembler`；`exporter/service/ExportDirectoryOpenResult.java` 是目录打开结果 record，建议归 `exporter.model`。HTTP 响应 DTO 仍归 dto，内部结果不强制变成 HTTP 契约；不能因此改变响应字段或状态码。
- [ ] **TODO PACKAGE-05：框架触发入口单独归类。** `upload/service/UploadSessionCleanupTask.java` 使用 `@Scheduled`，建议归 `upload.cleanup`，与现有 `outbox.cleanup` 一致；`task/service/LegacyTaskBackfillRunner.java` 是启动触发器，建议归 `task.bootstrap`。清理和回填业务继续由 service 承担，保持调度表达式、启用条件与启动顺序。
- [ ] **TODO PACKAGE-06：基础设施实现从 service 中辨识出来。** `dlq/service/RabbitDlqBrokerClient.java` 建议归 `dlq.adapter`，`DlqBrokerClient` 作为服务依赖接口保留；`task/service/RabbitManagementClient.java` 建议归 `task.adapter`；`storage/service/StorageCapacityAdapter.java` 建议归 `storage.adapter`。业务 Service 继续组合结果，不把网络和磁盘访问迁入 Controller；保留超时、错误处理和容量缓存语义。
- [ ] **TODO PACKAGE-07：结果业务路由与 MQ 接收区分。** `task/event/ManagementResultRouter.java` 调用媒体、回收、上传和元数据 completion 服务，并不承担 Rabbit 消费入口，建议归 `task.service.routing`；`ManagementCommandResultHandler` 保留 `task.event`。归类前核对 `ManagementResultApplicationServiceImpl` 调用，保持 Inbox、事务、重复结果和迟到结果处理，不顺带重做结果分发机制。

## Worker：消息入口、执行器与配置

以下路径相对 `worker-service/src/main/java/com/comicatlas/worker/`。

- [ ] **TODO PACKAGE-08：统一 MQ 消费者归属。** `exporter/command/ExportTaskHandler.java` 有 `@RabbitListener`，消费 `ExportTaskCreatedEvent` 后委托 ExportService，建议归 `exporter.event`，与 `importer.event`、`recovery.event` 对齐。`media.lq`、`media.hq` 等功能子域可保留；只在确有必要时于其下区分 command/service，不为了对称批量拆目录。保持原队列、并发、ACK/重投策略。
- [ ] **TODO PACKAGE-09：明确统一配置中的业务所有者。** `config/WorkerConfig.java` 同时包含下载、ZIP、封面、转码、图片、媒体分析和生命周期配置。先建立属性归属表，再按需提取到 `importer.config`、`media.config`、`task.config`；导入导出共用 ZIP 配置归 `shared.archive.config`，应用共用线程池仍归根 config。`storage/StorageProperties.java` 可归 `storage.config`。保留全部 `worker.*` / 存储配置键、默认值和初始化校验，不直接搬走整个 WorkerConfig。

## 共享模块：契约与实现

- [ ] **TODO PACKAGE-10：Web 异常映射从契约层分离。** `comic-shared/src/main/java/com/comicatlas/contract/common/exception/GlobalExceptionHandler.java` 包含 `@RestControllerAdvice`、Spring Web/DAO 异常处理，属于框架适配实现。建议在同一模块新增 `com.comicatlas.web.exception` 归属，异常类型本身仍保留 contract。该目标是对现有 contract/persistence 两类包的明确补充，实施时同步更新[共享模块边界](shared-module-boundaries.md)、组件扫描和边界测试；保持 API/Reading 都能发现且只注册一次，响应结构与异常优先级不变。
- [ ] **TODO PACKAGE-11：common 工具按实际能力归类。** `comic-common/src/main/java/com/comicatlas/common/util/MetadataFileWriter.java`、`MetadataSnapshotRevision.java` 建议归 `metadata` 下的文件写入/版本职责包；同目录的 `ImageDimensionsReader.java`、`VideoPlayability.java` 建议分别归 `media.image`、`media.video`。跨服务确实复用的能力继续留在 comic-common；先查引用和包内可见性，再决定子包粒度，不新建含义模糊的 tools/helper 包。
- [ ] **TODO PACKAGE-12：为共享契约建立业务索引，冻结事件名称。** `comic-common` 的 `event`、`dto`、`constant` 混合导入扫描、导出、回收、媒体、任务和 MQ 基础设施。优先在分类文档维护业务索引：`DirectoryScan*`/`Scan*` → importer，`TrashManifest*` → trash，`MetadataRefresh*` → metadata，`ManagementCommand*` → task，`Mq*` → MQ 基础设施。后续评估普通 DTO/常量是否值得分包；`ComicEvent` 及各事件实现的现有全限定名先保留，任何事件迁移必须先验证 Jackson 多态与 Rabbit 类型头、历史消息和死信重放兼容性。

## 本轮保留的结构

| 范围 | 判断与理由 |
|---|---|
| API 已有 controller / dto / service / persistence | 主体结构已符合业务优先原则；不再按技术类型聚合成全局 controller/service/mapper |
| Reading 的 library / catalog / reader / history | 业务与分层较清晰；`reader.assembler`、`library.support` 有明确职责，未发现本轮需要强行搬迁的依据 |
| Worker 的 persistence.mapper / record | 按项目约定集中只读访问，不迁成 API 式写实体，也不因业务分包引入写权限 |
| API recovery.engine 与相关包内 record | 保留内聚和包级可见性；recovery 与 trash 的业务语义继续分开 |
| storage 路径抽象 | `StorageLayout`、`StorageRoot`、`StorageRef` 等内聚类型允许留在 storage 根包；配置类再按 PACKAGE-01/09 整理 |
| comic-shared 的 persistence.comic.assembler | 按当前共享边界允许实体到视图装配，不仅因为带 Assembler 后缀就迁入 contract |
| comic-shared 的 contract.comic.cache | `ComicReferenceCache` 是缓存名常量，不是 Redis 实现，无需按框架实现迁移 |
| Gateway | 路由、启动与发现属于框架职责，无需虚构漫画业务层 |
| 测试与资源 | 本轮不移动；未来 Java 归类需同步检查测试包、Mapper XML namespace、扫描配置和文档链接 |

## 后续实施与验收 TODO

- [ ] 按低耦合归类（PACKAGE-01/04/05/06/07/08/11）与边界调整（PACKAGE-02/03/09/10/12）分别评估依赖、拆分提交；每项先复核届时源码。
- [ ] 每个实施项建立“旧路径 → 新路径”映射，并同步测试、import、扫描配置及 XML；不改变 HTTP、JSON、数据库、MQ 或配置契约。
- [ ] 执行对应模块测试、命名审查、Checkstyle 和 `git diff --check`；合并前按项目脚本注入环境后执行 `clean verify`，避免旧 class 干扰扫描。涉及 MQ/配置/Web 适配的项目补相应兼容性验证。
- [ ] 有迁移产物及验证证据后才勾选对应项；仅添加 TODO 或移动文件不能证明业务解耦已完成。

本轮仅核对源码角色、路径和文档变更，不运行后端测试或宣称完成上述迁移。
