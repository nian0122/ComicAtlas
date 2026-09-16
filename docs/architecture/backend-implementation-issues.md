# 后端实现问题标记

检查日期：2026-09-16。确认 7 项实现问题，在源码添加 8 处 `TODO(IMPL-xx)`；IMPL-02 涉及两个方法。**全部待修复，本轮仅添加注释和说明，不改变执行逻辑。**

以下依据源码控制流和更新条件进行静态分析。列出的触发场景是后续回归要求，尚未为本轮问题新增测试或进行故障复现。已有测试通过不代表这些未覆盖场景正确。

## P1：状态一致性与事务

### IMPL-01：导入终态仍可被其他终态覆盖

位置：[ImportEventHandler.persistTaskStatusChanged](../../api-service/src/main/java/com/comicatlas/api/importer/event/ImportEventHandler.java)。

- 证据：当前状态属于终态时，仅当新状态为空或不是终态才返回；若新状态也是终态，仍执行 `task.setStatus(mappedStatus)` 和 `updateById`。
- 触发：任务已经 SUCCESS 或 CANCELLED，收到迟到的 FAILED 状态事件，导入任务会被改为 FAILED。另一个 `handleImportTaskFailed` 入口对终态直接跳过，两条入口规则不一致。
- 影响：导入任务与漫画/统一任务项可能出现相互矛盾的终态；乱序消息会改写最终结果。
- 处理方向：明确同一执行尝试的终态转换规则，在条件更新中落实保护；保留合法重试入口，不能简单禁止所有重试后的状态变化。
- 回归：SUCCESS→迟到 FAILED、CANCELLED→迟到 FAILED、重复同终态，以及合法重试后的新事件。

### IMPL-02：任务状态和进度更新存在检查与写入之间的并发窗口

位置：[ManagementTaskService.updateItemStatus / updateItemProgress](../../api-service/src/main/java/com/comicatlas/api/task/service/ManagementTaskService.java)，两处标记。

- 证据：先 `selectById` 检查 attempt 和终态，后续 `LambdaUpdateWrapper` 只以 ID 为条件；状态和 attempt 未进入该 UPDATE 的条件，也未依据更新行数处理竞态。
- 触发：两个不同结果事件均读到 RUNNING，再依次写 SUCCEEDED/FAILED；或者进度消息检查通过后，另一事务先完成任务或启动重试。
- 影响：存在后到结果覆盖先到终态、旧尝试进度污染新尝试的窗口；若进度入口最初读到 QUEUED，它还会写 RUNNING。`@Transactional` 本身不能保证读后条件在写入时仍成立。
- 处理方向：使用包含预期 attempt 和允许状态的条件更新，检查受影响行数后决定返回与聚合；必要时采用一致的行锁方案。不能仅增加 Java 层判断。
- 回归：并发成功/失败结果、进度与完成竞态、重试与旧进度竞态。需用真实数据库验证，而非只模拟顺序调用。

### IMPL-05：上传完成校验在数据库事务内读取全部文件

位置：[UploadSessionService.complete](../../api-service/src/main/java/com/comicatlas/api/upload/service/UploadSessionService.java)。

- 证据：`@Transactional` 方法先查会话和文件，再调用 `verifyUploadedFiles`；该方法对每个暂存文件计算完整 SHA-256 并检测媒体类型，之后才写 STAGING 媒体、任务与 Outbox。
- 触发：上传文件较大、数量较多或磁盘读取慢。
- 影响：数据库事务与连接占用时间随文件读取时间增长，增加超时和并发处理压力，违反项目“事务内不执行长时间外部 IO”的约束。
- 处理方向：与 DECOUPLE-01 协同，将校验与短事务提交分离；必须同时解决校验后文件变化及会话并发状态复核，不能仅删除事务注解。
- 回归：慢文件校验、校验期间写分片/取消、校验后文件变化、重复完成和提交失败补偿。

## P2：计算、可观测性和时间字段

### IMPL-03：恢复进度使用了已处理数量作为分母

位置：[RecoveryEventHandler.processScanCompleted](../../api-service/src/main/java/com/comicatlas/api/recovery/event/RecoveryEventHandler.java)。

- 证据：进度公式为 `(recovered + skipped + placeholder + errors) * 100 / totalSoFar`。RecoveryEngine 每处理一本返回 `totalSoFar + 1`，各分类计数之和通常也等于已处理数量。
- 触发：100 本漫画的批次处理完第 1 本时，公式得到 `1 * 100 / 1 = 100`。
- 影响：提交给管理任务项的进度过早达到 100%，不能反映批次实际完成比例。
- 处理方向：以批次总漫画数为分母，并明确定义空批次行为；与错误计数、重投后的计数恢复保持一致。
- 回归：多本批次第一本、处理中间、最后一本，含失败与跳过的混合批次、空批次。

### IMPL-04：磁盘统计异常被静默转为零容量

位置：[StorageQueryServiceImpl.directorySize](../../api-service/src/main/java/com/comicatlas/api/storage/service/impl/StorageQueryServiceImpl.java)。

- 证据：单文件 `Files.size` 和外层遍历的 IOException 分支均直接返回 0，没有记录异常；上层 `getStorageStats` 使用缓存。
- 触发：文件不可读、目录访问失败，或扫描期间文件被移动/删除。
- 影响：统计结果少算或归零，用户无法区分“确实没有文件”与“未能读取”，错误结果还可能被缓存。
- 处理方向：明确失败与部分成功的统计语义，保留异常上下文，可按约定降级到上次成功统计；日志避免高频逐文件输出。与 DECOUPLE-11 一起处理。
- 回归：空目录、部分文件读取失败、根目录不可读、缓存已有成功结果后的失败。

### IMPL-06：摘要计算失败时丢失原始异常链

位置：[UploadSessionService.computeSha256](../../api-service/src/main/java/com/comicatlas/api/upload/service/UploadSessionService.java)。

- 证据：捕获 IOException 后仅把 `ex.getMessage()` 拼入新的 BusinessException，未传递 cause。
- 触发：打开或读取暂存文件失败。
- 影响：上层日志缺少原始异常类型与出错堆栈；原异常 message 还可能带宿主机路径，不适合直接作为对外错误详情。
- 处理方向：保留 cause，面向调用方返回稳定业务错误，内部日志记录可定位且脱敏的上下文；遵守项目统一异常契约。
- 回归：不可读/不存在的暂存文件，检查异常 cause 和 HTTP 错误信息。

### IMPL-07：首次 RUNNING 的开始时间未进入数据库更新

位置：[ManagementTaskService.updateItemStatus](../../api-service/src/main/java/com/comicatlas/api/task/service/ManagementTaskService.java)。

- 证据：当新状态为 RUNNING 且开始时间为空时，先调用 `item.setStartedAt(now)`；构造更新语句后，再次以相同的 `item.getStartedAt() == null` 条件决定是否写 `started_at`。此时该条件已不成立。
- 触发：任务项没有开始时间，通过 `updateItemStatus(..., RUNNING, ...)` 首次进入 RUNNING。
- 影响：更新语句写入状态却遗漏开始时间，影响耗时展示；如果后续进度入口读到 RUNNING，其 QUEUED 专用补时间分支也不会执行。
- 处理方向：在修改对象前保存是否首次启动的判断，或统一在条件更新中设置开始时间；已存在的开始时间不得覆盖。
- 回归：首次 RUNNING 写入时间、重复 RUNNING 保留原值，及后续进度更新。

## 范围说明

恢复处理中即使有单本错误也将批次标为 SUCCEEDED，现有测试明确接受此行为；需要先确认批次成功的业务定义，因此本轮不直接认定它是缺陷。路径安全、媒体归属检查等问题已在分层清单列出约束，未在缺少完整调用链证据时断言存在可利用漏洞。

检索：

```powershell
rg -n 'TODO\(IMPL-' api-service/src/main/java
```

解耦设计见 [后端待解耦清单](backend-decoupling.md)，职责归属见 [后端三层架构检查](backend-layer-audit.md)。修复时按编号补充回归测试，再同步更新状态；当前标记不代表已经修复。
