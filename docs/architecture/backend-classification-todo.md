# 后端文件分类 TODO

检查日期：2026-09-16。依据当前工作区源码，按“运行模块 → 业务域 → 框架职责”梳理。本清单已完成 PACKAGE-01～12 的迁移、引用同步与验证记录。

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

- [x] **TODO PACKAGE-01：业务专属配置回归业务域。** `MetadataSyncSchedulerConfig` 已归 `metadata.config`，`ApiStorageProperties` 已归 `storage.config`；共享构建器注册入口 `MetadataJsonBuilderConfig` 经核对后保留在应用 `config`。Bean 名、`storage` 配置前缀、默认值和调度器销毁行为保持不变。
- [x] **TODO PACKAGE-02：MyBatis 类型实现与配置分开。** `ManagementEnumTypeHandlers` 已归 `shared.persistence.handler`，MyBatis 注册入口同步更新；嵌套 handler 的注册方式、`name()` 字符串映射和未知值安全解析语义保持不变。
- [x] **TODO PACKAGE-03：元数据刷新策略归 metadata。** `MetadataRefreshTaskPolicy` 已归 `metadata.policy` 并实现 task 定义的 `TaskLifecyclePolicy`，task 仅依赖通用策略接口；创建锁定、取消释放、重试准备保持原事务调用位置及 `attempt` 递增/重发语义。
- [x] **TODO PACKAGE-04：装配器和结果对象离开 service。** `task/assembler/TaskResponseAssembler.java` 负责任务响应装配；`exporter/model/ExportDirectoryOpenResult.java` 作为内部目录打开结果保留。HTTP 响应 DTO 仍归 dto，响应字段和状态码未改变。
- [x] **TODO PACKAGE-05：框架触发入口单独归类。** `upload/cleanup/UploadSessionCleanupTask.java` 保留原 `@Scheduled` 配置；`task/bootstrap/LegacyTaskBackfillRunner.java` 保留原启动触发、启用行为和顺序。清理与回填业务仍由 service 承担。
- [x] **TODO PACKAGE-06：基础设施实现从 service 中辨识出来。** `dlq/adapter/RabbitDlqBrokerClient.java`、`task/adapter/RabbitManagementClient.java`、`storage/adapter/StorageCapacityAdapter.java` 已归入 adapter；`DlqBrokerClient` 接口仍保留在 service，超时、错误处理和容量读取语义未变。
- [x] **TODO PACKAGE-07：结果业务路由与 MQ 接收区分。** `task/service/routing/ManagementResultRouter.java` 已与 `task/event/ManagementCommandResultHandler` 分离；`ManagementResultApplicationServiceImpl` 的 Inbox、事务、重复结果和迟到结果处理保持不变。

## Worker：消息入口、执行器与配置

以下路径相对 `worker-service/src/main/java/com/comicatlas/worker/`。

- [x] **TODO PACKAGE-08：统一 MQ 消费者归属。** `exporter/event/ExportTaskHandler.java` 已承接原导出 MQ 消费职责；队列、并发、ACK/重投策略保持不变。
- [x] **TODO PACKAGE-09：明确统一配置中的业务所有者。** 新增 `storage/config/StorageProperties` 作为存储业务配置模型，旧 `storage/StorageProperties` 保留为兼容 Bean 类型并继续绑定 `storage` 前缀。Worker 的统一 `worker.*` 配置模型、默认值及启动校验保持不变，避免重复注册属性 Bean 造成绑定歧义；其嵌套业务段继续按 `torrent/proxy/zip/cover/executor/transcode/image/media/download/lifecycle/ehentai` 明确归属，后续消费者可逐段替换为独立 Bean。

## 共享模块：契约与实现

- [x] **TODO PACKAGE-10：Web 异常映射从契约层分离。** `GlobalExceptionHandler` 已迁至 `com.comicatlas.web.exception`；业务异常类型仍保留 contract。API/Reading 显式扫描 Web 适配包，边界测试覆盖 contract 不依赖 Web 且适配器只注册一次，响应结构与异常优先级保持不变。
- [x] **TODO PACKAGE-11：common 工具按实际能力归类。** `MetadataFileWriter`、`MetadataSnapshotRevision` 已归 `common.metadata.file/revision`；`ImageDimensionsReader`、`VideoPlayability` 已归 `common.media.image/video`。已同步生产代码与测试引用，保留跨服务共享能力和原有行为。
- [x] **TODO PACKAGE-12：为共享契约建立业务索引，冻结事件名称。** 已在[共享模块边界](shared-module-boundaries.md)维护 importer/exporter/trash/metadata/media/task/recovery/MQ 索引，并由 `EventTypeNames` 集中冻结 `ComicEvent` 的 Jackson `eventType` 名称；事件类全限定名与消息载荷保持不变。

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

## 实施与验收记录

- [x] 已按 PACKAGE-01～12 分项复核依赖并完成对应迁移。
- [x] 已建立旧路径到新路径的映射，更新 import、组件扫描、测试和 XML；HTTP、JSON、数据库、MQ 与配置契约保持不变。
- [x] 已执行对应模块测试/编译，Checkstyle 与 `git diff --check` 通过；全量 verify 的测试阶段通过，最终 Reading 模块 verify 通过。
- [x] 每个实施项均有迁移产物和验证证据后才标记完成。

本次实施已完成 PACKAGE-01～12；最终整体验证与提交前审查由主任务统一执行。
