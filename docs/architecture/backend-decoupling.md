# 后端待解耦清单

更新日期：2026-09-16。本文件记录后端职责拆分项及整改结果；DECOUPLE-01～13 均已完成，具体职责边界以当前代码为准。

P1 表示优先处理的事务/跨业务边界问题，P2 表示可随对应功能演进处理的职责拆分。此优先级是重构顺序，不表示已经发生生产故障。以下建议的新类和接口尚未创建。

## P1：事务与业务边界

### DECOUPLE-01：上传校验与写事务（已完成）

文件：[UploadSessionService.java](../../api-service/src/main/java/com/comicatlas/api/upload/service/UploadSessionService.java)，`complete`、`verifyUploadedFiles`，以及 create/cancel/expire 的文件操作。

- 证据：`complete` 的 `@Transactional` 包含逐文件完整性、摘要和类型检测，随后创建 STAGING 媒体、管理任务和 Outbox 消息；大文件读取会拉长事务。
- 拆分：上传校验器在事务外产生校验结果；独立事务服务负责状态复核、媒体/任务/Outbox 原子提交。创建目录和清理文件也应明确事务外执行与失败补偿。
- 约束：不能简单把读取前移；必须防止校验后仍可上传分片造成文件变化，并保留重复 complete 的幂等语义。
- 验证：`MediaUploadManagementIT`，补充校验期间取消、并发写分片、重复完成、文件校验失败及事务回滚。

### DECOUPLE-02：导入落库与最终化状态机（已完成）

文件：[ImportPersistenceServiceImpl.java](../../api-service/src/main/java/com/comicatlas/api/importer/service/impl/ImportPersistenceServiceImpl.java)，`persistCompletedInTxn`、`prepareChapter`、`applyFinalizeCompletedInTxn`、`applyFinalizeFailedInTxn`。

- 证据：同一类解析 Map 元数据、转换目录与媒体、处理标签、写业务表、处理最终化成功/失败并收尾任务。
- 拆分：元数据解码器与实体装配器生成校验后的导入计划；持久化服务提交计划；最终化服务承担章节和漫画终态转换。
- 约束：保留章节 ID 路径最终化、重复事件处理、所有章节完成才 READY，以及业务数据与 Outbox 同事务。
- 验证：`ImportPersistenceServiceTest`；补充乱序、重复、部分章节失败以及任务重试的回归覆盖。

### DECOUPLE-03：恢复计划与持久化执行（已完成）

文件：[RecoveryEngine.java](../../api-service/src/main/java/com/comicatlas/api/recovery/engine/RecoveryEngine.java)，`restoreComicInternal` 及其装配辅助方法。

- 证据：恢复引擎同时解释兼容元数据、映射目录索引、生成实体并写入多张表；还协调缓存和元数据同步。
- 拆分：恢复计划构建器处理版本兼容和类型转换；恢复写入服务执行强类型计划；引擎保留流程协调。
- 约束：已有 `RecoveryMediaResolver` 的文件扫描继续在事务外，缓存失效和元数据同步保持提交后的顺序；不得把缺失文件标为 READY。
- 验证：`RecoveryEngineTest`，覆盖旧元数据、越界目录索引、缺失 HQ/LQ、重复恢复和事务失败。

### DECOUPLE-04：回收生命周期与磁盘对账（已完成）

文件：[TrashLifecycleService.java](../../api-service/src/main/java/com/comicatlas/api/trash/service/TrashLifecycleService.java)，`reconcile`、`reconcileAndRepair`。

- 证据：生命周期命令编排同时读取 manifest/actual、检查磁盘、解释数据库状态并执行修复。
- 拆分：只读对账服务生成报告，修复服务在复核任务版本和状态后提交；生命周期服务专注回收/恢复/清理命令。
- 约束：修复不能依据过期磁盘报告覆盖新状态；保留 TRASHED 生命周期、7 天保留期和 purge token 校验。
- 验证：`TrashLifecycleIT`，覆盖实际文件移动未完成、数据库状态变化、actual 缺失和补偿成功等场景。

### DECOUPLE-05：通用任务与元数据业务（已完成）

文件：[ManagementTaskService.java](../../api-service/src/main/java/com/comicatlas/api/task/service/ManagementTaskService.java)，`releaseCancelledMetadataRefresh`、`lockMetadataRefreshComics` 及刷新重试处理。

- 证据：通用任务服务直接识别 METADATA_REFRESH，并通过漫画 Mapper 修改 REFRESHING/READY 状态。
- 拆分：task 提供取消/重试业务策略接口，metadata 提供实现；通用任务服务只协调状态机、attempt 和策略调用。
- 约束：使用依赖注入注册策略，避免 task 反向依赖具体实现；业务锁释放和任务状态变更保持同事务，迟到结果仍按 attempt 拦截。
- 验证：`ManagementTaskServiceTest`、`ManagementTaskServiceIT` 及元数据刷新真实链路测试。

## P2：流程编排与数据装配

### DECOUPLE-06：Worker 导入编排（已完成）

文件：[DirectoryImportHandler.java](../../worker-service/src/main/java/com/comicatlas/worker/importer/handler/DirectoryImportHandler.java)，`buildMetadataMap`、`writeMetadataNode`、`generateCoverFromNode`。

- 证据：已委托解析器与清单组件，但该 handler 仍构造完整元数据 Map、写出文件、展开媒体并生成封面。
- 拆分：提取导入元数据写出服务和封面服务，handler 保留各步骤顺序和清单恢复协调。
- 约束：保留取消检查、断点清单、失败产物清理及两阶段最终化的路径约定。
- 验证：`DirectoryImportResumeTest`、`DirectoryImportHandlerSmokeTest`，补充封面失败和元数据写出失败。

### DECOUPLE-07：LQ 命令与章节优化（已完成）

文件：[LqCommandHandler.java](../../worker-service/src/main/java/com/comicatlas/worker/media/lq/LqCommandHandler.java)，`processChapter` 和优化器结果匹配方法。

- 证据：同一命令处理器承担只读查询、路径定位、优化器执行、媒体 ID 匹配以及 MQ 结果发布。
- 拆分：章节优化服务返回成功媒体大小与失败明细；command 层适配消息并发布进度/终态。
- 约束：Worker 只读 MySQL；强制重建语义、部分失败和 lqSize 回传保持一致，取消不转成普通失败。
- 验证：`LqCommandHandlerTest` 及媒体操作管线集成测试。

### DECOUPLE-08：阅读查询与响应装配（已完成）

文件：[ReaderServiceImpl.java](../../reading-service/src/main/java/com/comicatlas/reading/reader/service/impl/ReaderServiceImpl.java)，`getChapter` 中的媒体 DTO 映射。

- 证据：章节查询方法内嵌视频 LQ 占位、文件名回退、URL 生成和媒体字段映射。
- 拆分：提取 `reader.assembler.ReaderAssembler`，查询服务保留可读状态筛选、章节/媒体查询和前后章定位。
- 约束：URL 继续经 FileUrlResolver；HQ 已删除时保留 LQ 文件名回退，顺序仍由 global_order 决定。
- 验证：阅读服务已有测试；拆分时增加图片/视频混排、HQ 删除保留 LQ 和不可读章节场景。

## 本轮补充：DECOUPLE-09～12

### DECOUPLE-09：管理结果消费与事务协调（已完成）

文件：[ManagementCommandResultHandler.java](../../api-service/src/main/java/com/comicatlas/api/task/event/ManagementCommandResultHandler.java)，`process`、`handleCompleted`、`handleFailed` 及专用快照分支。

- 证据：无直接 Mapper 依赖，但同一类协调 Rabbit 消费失败处理、Inbox 去重、事务、任务项状态及业务结果路由。
- 拆分：结果应用服务负责 Inbox 与业务状态原子提交，消息适配层负责事件识别及 ACK/DLQ。
- 约束：保留 attempt 检查、重复消息处理和快照读取在事务外；不要将全部事件无差别包进单一事务。
- 验证：`ManagementCommandResultHandlerTest` 及媒体操作/元数据刷新管线集成场景。

### DECOUPLE-10：重试发布器与业务恢复准备（已完成）

文件：[TaskRetryPublisher.java](../../api-service/src/main/java/com/comicatlas/api/task/service/TaskRetryPublisher.java)，`publishExportCommand`、`publishImportCommand`。

- 证据：名为发布器，实际直接重置导出专表、读取导入专表、调用导入重试协调器，再构造并写入 Outbox。
- 拆分：业务域提供重试策略，分别承担恢复准备和消息构造；task 层选择策略并协调重试事务。与 DECOUPLE-05 共用策略设计，避免两套扩展机制。
- 约束：专表重置与 Outbox 同事务，保留 attempt、原任务关联、导入重试前置条件和不重复发布语义。
- 验证：管理任务重试与导入重试测试，补充专表缺失、准备失败、Outbox 写入失败和重试竞态。

### DECOUPLE-11：存储聚合查询与同步磁盘扫描（已完成）

文件：[StorageQueryServiceImpl.java](../../api-service/src/main/java/com/comicatlas/api/storage/service/impl/StorageQueryServiceImpl.java)，`getStorageStats`、`directorySize`。

- 证据：数据库统计请求同步递归遍历缩略图目录；读取异常直接按 0 计入结果，与空目录无法区分。
- 拆分：容量统计适配器负责文件访问，查询服务组合统计结果；明确容量缓存刷新频率，必要时由后台采集提供快照。
- 约束：不得改变已有大小字段含义；错误应有可定位记录，并决定使用上次成功值还是明确失败，避免缓存“假零”。
- 验证：空目录、部分文件不可读、扫描中删除文件、大目录及缓存失效。

### DECOUPLE-12：媒体入口聚合多种业务（已完成）

文件：[StorageOperationController.java](../../api-service/src/main/java/com/comicatlas/api/media/controller/StorageOperationController.java)，类级标记。

- 证据：同一 Controller 承载 LQ/HQ/转码、元数据刷新、导出提交、导出产物查询和打开目录。
- 拆分：将导出端点交由 exporter 的 Controller，媒体端点保留 media，元数据刷新按业务归属分配。
- 约束：仅分配类职责，不改 URL、JSON 或状态码；Spring 映射不得重复。目录打开实现越界另见 LAYER-02。
- 验证：现有 StorageOperationControllerTest 与各端点映射测试。

### DECOUPLE-13：HQ 媒体登记职责混合（已完成）

文件：[HqMediaRegistrationService.java](../../api-service/src/main/java/com/comicatlas/api/media/service/HqMediaRegistrationService.java)。

- 证据：同一方法同时执行快照版本校验、章节和媒体查询、媒体匹配、实体装配以及批量写入。
- 拆分：登记规则负责快照与数据库状态匹配，媒体装配器负责构建待写入实体，持久化服务负责事务内批量提交。
- 约束：保留版本漂移校验、重复媒体跳过、无效快照统计、页码连续性和单批 500 条写入限制。
- 验证：覆盖重复候选、章节/媒体版本变化、无效媒体、空快照和批量写入失败。

## 跟踪方式

```powershell
rg -n 'DECOUPLE-' api-service/src/main/java worker-service/src/main/java reading-service/src/main/java
```

每完成一项，应更新本清单状态并记录测试证据。
