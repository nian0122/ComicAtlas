# 后端三层架构检查

检查日期：2026-09-16。当前确认 **7 类问题、13 处源码标记**，均为待处理。检索 `TODO(LAYER-xx)` 可定位；本次只添加注释与文档，不修改业务执行逻辑。

## 判断标准与检查范围

检查 API、Reading 的 Controller、API 的 MQ 消费入口，以及 Service、Mapper、Entity、DTO 的依赖方向。采用本项目的三层边界：

- 接口适配层：HTTP Controller 和 MQ 消费入口负责协议转换、校验及响应/ACK/DLQ，调用业务服务。
- 业务层：Service 负责业务规则、状态流转、跨操作编排和事务边界。
- 数据访问层：Mapper/存储适配器封装数据库和文件访问；不反向调用 Controller 或业务编排。

Service 调用 Mapper 是正常分层；Mapper 返回查询投影 DTO、Controller 返回 Resource/ResponseEntity、接收请求流，以及 MQ 入口使用消费支持组件，均不因这些类型本身而判定违规。Worker 是后台执行模块，不机械套用 HTTP 三层模型。

LAYER-01～06 是接口适配层承担文件访问或业务持久化的问题；LAYER-07 是持久化框架类型泄漏，单独列为 P2，不等同于 Controller 直接访问数据库。本清单是当前确认项，不是全项目完全合规证明。

## P1：接口层越界

### LAYER-01：回收封面接口解释存储清单并访问磁盘

位置：[TrashLifecycleController.cover](../../api-service/src/main/java/com/comicatlas/api/trash/controller/TrashLifecycleController.java)。

- 证据：筛选 manifest 的 THUMBS 条目，拼接 `trashRelativePath/cover.webp`，调用 `Files.isRegularFile`。Controller 已掌握回收清单与物理布局。
- 目标：`trash.service` 中的封面查询服务负责选择规则，存储适配器负责路径解析和文件存在检查；Controller 仅输出资源或 404。
- 约束：规范化后仍应验证路径位于受管根内；保留 WebP 类型和清单缺失时的响应。此处记录缺少局部边界校验，不据此断言存在可利用漏洞。
- 验证：封面存在、无清单、文件缺失、多个条目及异常相对路径。

### LAYER-02：导出接口直接调用宿主机能力

位置：[StorageOperationController.openExportDir](../../api-service/src/main/java/com/comicatlas/api/media/controller/StorageOperationController.java)。

- 证据：Controller 使用 `Path.of`、`Files.exists` 与 `Desktop.getDesktop().open`，同时处理操作系统能力检测和异常回退。
- 目标：导出服务根据任务定位产物，独立本机目录打开适配器封装 Desktop；Controller 将服务结果映射为现有 HTTP 响应。
- 约束：保持已有 200/404/501 与响应内容契约；不能为了拆分增加对远端客户端自动打开文件的行为。
- 验证：无产物、目录不存在、无桌面环境、打开失败及正常打开；单元测试替身不得真的唤起桌面。

### LAYER-03：三个导出消费者直接写业务表

位置：

- [ExportStartedHandler.handle](../../api-service/src/main/java/com/comicatlas/api/exporter/event/ExportStartedHandler.java)
- [ExportCompletedHandler.handle](../../api-service/src/main/java/com/comicatlas/api/exporter/event/ExportCompletedHandler.java)
- [ExportFailedHandler.handle](../../api-service/src/main/java/com/comicatlas/api/exporter/event/ExportFailedHandler.java)

证据：三个监听方法均直接通过 `ExportTaskMapper` 读取/修改导出任务，在本地事务中联动 `ManagementTaskService`；终态判断和结果应用散落于消息入口。

目标：建立导出结果应用服务，提供启动、完成、失败三个业务入口，统一状态规则和事务。消费者只负责消息适配和消费策略。

约束：保留导出表与管理任务项同事务，不让重复或乱序事件重开终态任务；拆分时核对事件与对应任务项的关联，不扩大“当前活动项”的匹配范围。

验证：重复启动、完成后收到失败、失败后收到启动、任务不存在、联动写入失败回滚。

### LAYER-04：视频修复消费者直接更新媒体

位置：[VideoMetadataFixCompletedHandler.handle](../../api-service/src/main/java/com/comicatlas/api/media/event/VideoMetadataFixCompletedHandler.java)。

- 证据：监听回调逐项 `selectById`，选择性覆盖宽高/时长/编码，再 `updateById`。
- 目标：媒体结果应用服务处理更新规则；入口保留 MQ 消费支持与日志摘要。
- 约束：明确单事件批次是否原子、重复投递如何处理以及媒体是否属于事件漫画；不要只将循环换一个类名而保留不明确的业务边界。
- 验证：媒体不存在、部分字段为 null、重复结果、跨漫画媒体 ID、批次中途更新失败。

### LAYER-05：导入消费者仍包含状态持久化与文件读取

位置：[ImportEventHandler](../../api-service/src/main/java/com/comicatlas/api/importer/event/ImportEventHandler.java)，标记在 `persistTaskStatusChanged`；关联 `handleImportTaskFailed`、`markComicImportFailed`、`handleComicImported`。

- 证据：直接调用 ImportTaskMapper/ComicMapper，联动任务阶段、FAILED/CANCELLED 和漫画 IMPORT_FAILED；导入成功分支用 ObjectMapper 从文件读取元数据。原类注释“不再触碰文件系统”与代码不符，本次已更正。
- 目标：导入结果服务承担状态应用和失败联动，元数据读取由服务委托存储适配器；消费者只做协议分派和 ACK/DLQ。
- 约束：区分 Redis 幂等标记与数据库提交边界，保留终态保护、取消语义及文件读取在事务外。与 DECOUPLE-02 的落库服务拆分协同推进，避免新建重复状态机。
- 验证：导入失败两种事件入口、取消、Redis 不可用、元数据缺失、数据库提交失败、重复/乱序事件。

### LAYER-06：恢复消费者承担批次恢复业务

位置：[RecoveryEventHandler.processScanCompleted](../../api-service/src/main/java/com/comicatlas/api/recovery/event/RecoveryEventHandler.java)，关联失败处理和任务项同步。

- 证据：监听处理器直接更新 RecoveryTaskMapper，控制逐本恢复循环、累计计数、更新进度与终态，并同步管理任务。
- 目标：恢复批次服务承担循环与任务生命周期，复用 RecoveryEngine；入口保留消息分派与消费失败处理。
- 约束：不能为整个恢复批次增加长事务；保留单本失败继续、可见进度和 DLQ 策略，明确定义重投后的已完成项处理方式。
- 验证：重复扫描结果、任务已终态、部分漫画恢复失败、进度保存失败和消费异常。

## P2：框架类型泄漏

### LAYER-07：五个分页 HTTP 入口暴露 IPage

位置：

- [ManagementComicQueryController.list](../../api-service/src/main/java/com/comicatlas/api/library/controller/ManagementComicQueryController.java)
- [ImportController.listTasks](../../api-service/src/main/java/com/comicatlas/api/importer/controller/ImportController.java)
- [RecoveryTaskController.listTasks](../../api-service/src/main/java/com/comicatlas/api/recovery/controller/RecoveryTaskController.java)
- [ManagementTaskController.listTasks](../../api-service/src/main/java/com/comicatlas/api/task/controller/ManagementTaskController.java)
- [ReadingComicController.listComics](../../reading-service/src/main/java/com/comicatlas/reading/library/controller/ReadingComicController.java)

证据：公开响应签名均为 `Result<IPage<...>>`，相应查询服务也暴露 MyBatis-Plus 的分页接口。数据库分页实现因此参与 HTTP 序列化契约。

目标：采用框架无关分页 DTO，服务内部保留 IPage/Page 查询；在服务边界完成转换。

约束：先记录现有实际 JSON 字段、类型和空页行为，再兼容迁移；不要假定前端仅使用 records/total，也不要在本次标记阶段调整返回值。

验证：上述五条接口的 JSON 契约，空页、末页、页码/页大小及前端分页。允许内部查询实现继续依赖 MyBatis。

## 与解耦清单的关系

[后端待解耦清单](backend-decoupling.md) 的 DECOUPLE-09～12 是本轮新增职责拆分项。特别是 DECOUPLE-09 没有直接 Mapper 依赖，应按事务协调与消息适配的耦合处理；不能与 LAYER-03～06 的直接业务写入混为一谈。

检索方式：

```powershell
rg -n 'TODO\((LAYER|DECOUPLE)-' api-service/src/main/java reading-service/src/main/java worker-service/src/main/java
```

同一编号可覆盖同一问题的多个文件。处理完毕后同步删除源码标记和更新文档状态。现有 `ApiPackageBoundaryTest` 主要检查包归属与 Controller→Mapper 依赖，不能因其通过便宣称本清单问题已消失。
