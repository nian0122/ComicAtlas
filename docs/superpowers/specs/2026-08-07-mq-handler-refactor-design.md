# MQ 消费编排统一与 ImportTaskHandler 重构设计

**日期**: 2026-08-07
**状态**: 设计待审阅
**范围**: worker-service + api-service 全部 RabbitMQ 消费者

## 1. 背景与动机

项目现有 **27 个 `@RabbitListener` 消费者**（worker 12 + api 15，`ImportEventHandler` 独占 3 个），分布 25 个 handler 类。所有 `handle` 方法重复着相同的 MQ 协议样板：

```java
@RabbitListener(queues = MqQueues.XXX)
public void handle(XxxEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
    // 提取字段 + log
    try {
        ...业务...
        channel.basicAck(tag, false);
    } catch (Exception e) {
        log.error("...失败: ...", e);
        try { channel.basicReject(tag, false); } catch (Exception ex) { log.warn("消息 reject 失败: tag={}", tag, ex); }
    }
}
```

痛点：

1. **样板重复**：ack/reject/中断处理在 25 个 handler 中复制粘贴，变更协议语义需改 25 处。
2. **中断处理不一致（违反阿里规范）**：`LqCompletedHandler`、`DeleteEventHandler` 等用宽泛 `catch(Exception)` 把中断也 reject 进 DLQ；`VideoTranscodeHandler` 单独捕获中断恢复标志。阿里规范要求"不得把取消/中断误报为普通业务失败，中断必须恢复线程中断标志并结束任务流程"。
3. **`ImportTaskHandler.handle` 职责混杂**：45 行方法混入 MQ 协议、取消检查、路径映射、switch 路由、状态发布、按字符串分类的错误分类（`classifyFailure` 无消费者，仅日志用）。

## 2. 设计目标

- 统一 MQ 消费语义（ack/reject/中断），一处修改全局生效。
- `handle` 方法只剩业务逻辑与编排，可读性提升。
- 修复中断误报为业务失败的不一致。
- `ImportTaskHandler.handle` 拆分为"协议编排 + 业务路由"两个清晰层次。

## 3. 设计决策（已与用户确认）

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 痛点范围 | 两者都改（模板 + ImportTaskHandler） | 样板重复 + 方法过胖均存在 |
| 模板形态 | **函数式支持组件（组合）** | 阿里规范"组合优于继承" |
| 路由重构 | **switch 收敛 + 方法拆分** | 仅 3 种来源，策略模式过度设计（YAGNI） |
| 模板落点 | comic-common | 两模块共享，阿里规范"跨应用共享放二方库" |

## 4. 设计细节

### 4.1 `MqConsumerSupport`（新增，comic-common）

**位置**: `comic-common/src/main/java/com/comicatlas/common/mq/MqConsumerSupport.java`

**依赖**: comic-common pom 新增 `com.rabbitmq:amqp-client`（仅用 `Channel`，版本由 Spring Boot 3.3.0 BOM 管理，不引入 spring-amqp）。

**API**:

```java
@Slf4j
@Component
public class MqConsumerSupport {

    /** 标准消费：成功 ack；业务异常 reject 进 DLQ；中断恢复标志且不 reject。 */
    public void consume(Channel channel, long tag, String label, ConsumeAction action);

    /** 带失败回调：失败先执行 onFailure（发失败事件/更新状态），再 reject。 */
    public void consume(Channel channel, long tag, String label, ConsumeAction action, ConsumeAction onFailure);

    /** 完整变体：requeueOnFailure=true 时失败 requeue 而非进 DLQ（取消类消息用）。 */
    public void consume(Channel channel, long tag, String label, ConsumeAction action,
                        ConsumeAction onFailure, boolean requeueOnFailure);

    @FunctionalInterface
    public interface ConsumeAction {
        void run() throws Exception;
    }
}
```

**统一语义**:

| 场景 | 行为 |
|------|------|
| 业务成功 | `basicAck(tag, false)` + 完成日志（label） |
| 业务异常 | 失败日志（保留 cause 占位符，不拼接）→ `onFailure` 回调（异常单独捕获记录，不掩盖原始异常）→ `basicReject(tag, requeueOnFailure)` |
| 中断 | `Thread.currentThread().interrupt()` + 结束任务，**不 reject、不执行 onFailure** |
| reject 本身失败 | 单独捕获，warn 日志（同现状） |

### 4.2 `ImportTaskHandler.handle` 重构（switch 收敛 + 方法拆分）

```java
@RabbitListener(queues = MqQueues.IMPORT_TASK)
public void handle(ImportTaskCreatedEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
    Long taskId = event.taskId();
    if (cancelHandler.isCancelled(taskId)) {
        log.info("Task cancelled, skipping: taskId={}", taskId);
        try { channel.basicAck(tag, false); } catch (Exception ex) { log.warn("消息 ack 失败: tag={}", tag, ex); }
        return;
    }
    mqConsumerSupport.consume(channel, tag, "导入任务: taskId=" + taskId,
            () -> runImport(event, taskId),
            () -> publisher.publishStatus(taskId, "FAILED", 0, null, 0, 0));
}

/** 业务编排：状态发布 → 路径映射 → 路由 → 完成发布。 */
private void runImport(ImportTaskCreatedEvent event, Long taskId) throws Exception {
    Long comicId = event.comicId();
    String sourceType = event.sourceType() != null ? event.sourceType() : "ZIP";
    String sourcePath = event.sourcePath();
    Path mangaRoot = Path.of(config.getMangaRoot());

    publisher.publishStatus(taskId, "PARSING", 0, null, 0, 0);
    String normalizedPath = mapHostPathToContainer(sourcePath);
    if (!normalizedPath.equals(sourcePath)) {
        log.info("Source path normalized: {} -> {}", sourcePath, normalizedPath);
    }
    routeToHandler(sourceType, normalizedPath, taskId, comicId, mangaRoot);
    publisher.publishImported(taskId, comicId);
}

/** 路由收敛：仅构建 ImportContext 并委托对应 handler。 */
private void routeToHandler(String sourceType, String sourcePath, Long taskId, Long comicId, Path mangaRoot) throws Exception {
    switch (sourceType) {
        case "ZIP" -> zipHandler.importZip(
                new ImportContext("ZIP", Path.of(sourcePath), false, false), taskId, comicId, mangaRoot);
        case "REGISTER", "DIRECTORY" -> {
            if (sourcePath == null) { throw new IllegalArgumentException("DIRECTORY 需要 sourcePath"); }
            directoryHandler.handle(
                    new ImportContext("DIRECTORY", Path.of(sourcePath), false, false), taskId, comicId, mangaRoot);
        }
        case "EHENTAI" -> {
            Path sourceDir = ehentaiDownloadService.downloadToSourceDir(taskId, sourcePath);
            directoryHandler.handle(
                    new ImportContext("DIRECTORY", sourceDir, false, false), taskId, comicId, mangaRoot);
        }
        default -> throw new IllegalArgumentException("Unknown sourceType: " + sourceType);
    }
}
```

**变更点**:
- `handle` 从 45 行 → 约 15 行纯编排。
- 移除 `classifyFailure`（按 message 字符串分类、无消费者、违反"不得用异常控制业务分支"）。
- 失败回调只发 `FAILED` 状态（与现状一致，`basicReject` 由模板负责）。
- 中断（InterruptedException）由模板统一处理，不再被 `classifyFailure` 误判。

### 4.3 迁移范围（25 个 handler 类 / 27 个消费者）

全部迁移到 `mqConsumerSupport.consume(...)`。按现有 catch 行为映射模板参数：

**worker 侧（12 类）**:

| Handler | onFailure | requeue | 备注 |
|---------|-----------|---------|------|
| ImportTaskHandler | publishStatus(FAILED) | false | 前置取消检查保留在 handle |
| CancelHandler | 无 | **true** | 取消意图不能丢：Redis 故障应 requeue 重试而非进 DLQ |
| LqGenerateHandler | 无 | false | 标准 |
| HqDeleteHandler | 无 | false | 标准 |
| DeleteHandler | 无 | false | 标准 |
| RecoveryTaskHandler | 发 recovery.failed | false | 按现状 catch 逻辑映射 |
| VideoTranscodeHandler | 发 VIDEO_TRANSCODE_FAILED | false | 临时文件清理留在 action 内 try-finally |
| VideoMetadataFixHandler | 发 fix.completed(带错误) | false | 实施时按现状 catch 确认 |
| MetadataRefreshHandler | 无 | false | 标准 |
| ExportTaskHandler | 发 task.failed | false | 按现状 catch 确认 |
| DirectoryScanHandler | 发 scan.failed | false | 按现状 catch 确认 |
| ManagementCommandDispatcher | 无 | false | 标准 |

**api 侧（13 类 / 15 消费者）**: `ImportEventHandler`（3 个消费者）、`LqCompletedHandler`、`HqDeletedHandler`、`DeleteEventHandler`、`RecoveryEventHandler`、`DirectoryScanEventHandler`、`ManagementCommandResultHandler`、`VideoMetadataFixCompletedHandler`、`ExportStartedHandler`、`ExportCompletedHandler`、`ExportFailedHandler`、`TranscodeCompletedHandler`、`TranscodeFailedHandler` — 全部为标准模式（无 onFailure，requeue=false）。

> 实施说明：每个 handler 的 onFailure/requeue 参数**以现有 catch 块行为为准**逐一手工映射，禁止臆造新行为；`VideoTranscodeHandler` 的 finally 清理移入 action 内部 try-finally。

### 4.4 依赖变更

`comic-common/pom.xml` 新增：

```xml
<dependency>
    <groupId>com.rabbitmq</groupId>
    <artifactId>amqp-client</artifactId>
</dependency>
```

（版本由 spring-boot-starter-parent 3.3.0 BOM 管理，无需显式版本号）

## 5. 测试策略

1. **新增 `MqConsumerSupportTest`**（comic-common 或 worker-service 单测）：
   - 业务成功 → ack 调用，无 reject
   - 业务异常 → onFailure 执行 + reject
   - 中断 → 恢复标志、不 reject、不执行 onFailure
   - `requeueOnFailure=true` → reject 带 requeue=true
   - onFailure 自身抛异常 → 不影响原始异常与 reject
2. **回归**：`VideoTranscodeHandlerTest`、`TranscodeCommandHandlerTest`、`ImportServiceTest` 等现有测试全部通过。
3. **全链路**：`api-service` + `worker-service` 编译 + worker 全量测试（48）+ api 契约测试（RabbitTopologyIT 等）。

## 6. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 迁移遗漏 handler 的特定 catch 行为 | 每个 handler 迁移时对照原 catch 块，onFailure/requeue 手工映射 |
| 中断语义变化（原来 reject 的现在不 reject） | 这是**修复**而非回归：符合阿里规范；受影响 handler（LqCompletedHandler 等）测试覆盖 |
| CancelHandler requeue 行为与现状差异 | 现状 set 失败即抛异常由容器 requeue，模板 requeue=true 语义等价 |
| comic-common 新增依赖 | 仅 amqp-client（轻量，BOM 管理版本），不引入 spring-amqp |

## 7. 排除项（YAGNI）

- 不做策略模式（来源类型仅 3 种，switch 收敛足够）。
- 不引入 Spring 容器级错误处理（改动面大、风险高）。
- 不重命名现有 handler 类（仅方法内部结构变化）。
