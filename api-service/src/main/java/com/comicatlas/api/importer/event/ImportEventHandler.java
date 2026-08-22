package com.comicatlas.api.importer.event;

import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.api.importer.enums.ImportTaskStatus;
import com.comicatlas.api.management.enums.ManagementTaskStatus;
import com.comicatlas.api.management.enums.TaskType;
import com.comicatlas.api.storage.ApiStorageProperties;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportPersistenceService;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.state.ManagementStateMachine;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.ImportTaskFailedEvent;
import com.comicatlas.common.event.TaskStatusChangedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入任务事件处理器（API 侧消费）。
 * <p>
 * 只做协议适配：MQ 消费 → 幂等/终态判断 → 委托 {@link ImportPersistenceService} 完成两阶段落库。
 * catalog/chapter/media 持久化与最终化编排已拆至 Service，本类不再触碰文件系统。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportEventHandler {

    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ComicMapper comicMapper;
    private final ImportTaskMapper taskMapper;
    private final TransactionTemplate transactionTemplate;
    private final ManagementTaskService managementTaskService;
    private final ApiStorageProperties storageProperties;
    private final MqConsumerSupport mqConsumerSupport;
    private final ImportPersistenceService importPersistenceService;

    /** 终态集合：到达这些状态后不可回退到非终态（含 CANCELLED 真正终态）。 */
    private static final Set<ImportTaskStatus> TERMINAL_STATUSES =
            EnumSet.of(ImportTaskStatus.SUCCESS, ImportTaskStatus.FAILED, ImportTaskStatus.CANCELLED);

    @RabbitListener(queues = MqQueues.IMPORT_RESULT)
    @SuppressWarnings("unchecked")
    public void handleComicImported(ImportTaskCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        String idempKey = "mq:event:" + event.eventId();
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        log.info("ComicImported: taskId={}, comicId={}", taskId, comicId);

        mqConsumerSupport.consume(channel, tag, "导入完成: taskId=" + taskId, () -> {
            if (isEventProcessed(idempKey) || isImportTaskTerminal(taskId)) {
                log.info("事件已处理或任务已处终态，确认消息: eventId={}", event.eventId());
                markEventProcessed(idempKey);
                return;
            }

            // metadata 在事务外读取（短事务内不做文件 IO），交由 Service 校验并落库
            Map<String, Object> metadata = objectMapper.readValue(
                storageProperties.root("METADATA").resolve(taskId + ".json").toFile(),
                new TypeReference<Map<String, Object>>() {});

            List<ImportPersistenceService.FinalizeRequest> requests =
                    importPersistenceService.persistCompleted(event, metadata);
            markEventProcessed(idempKey);

            log.info("ComicImported 完成: comicId={}, finalizeRequests={}", comicId, requests.size());
        });
    }

    @RabbitListener(queues = MqQueues.TASK_STATUS)
    public void handleTaskStatusChanged(TaskStatusChangedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        mqConsumerSupport.consume(channel, tag, "任务状态变更: taskId=" + event.taskId(), () -> {
            transactionTemplate.executeWithoutResult(status -> persistTaskStatusChanged(event));
        });
    }

    private void persistTaskStatusChanged(TaskStatusChangedEvent event) {
        Long taskId = event.taskId();
        String newStatus = event.status();
        ImportTask task = taskMapper.selectById(taskId);
        if (task == null) { return; }

        ImportTaskStatus currentStatus = task.getStatus();
        ImportTaskStatus mappedStatus = parseStatus(newStatus);
        if (TERMINAL_STATUSES.contains(currentStatus)
                && (mappedStatus == null || !mappedStatus.isTerminal())) {
            log.warn("状态机拒绝非终态写入: taskId={}, current={}, attempted={}", taskId, currentStatus, newStatus);
            return;
        }

        if (mappedStatus != null) {
            task.setStatus(mappedStatus);
        }
        if ("DOWNLOADING".equals(newStatus) && task.getStartTime() == null) {
            task.setStartTime(LocalDateTime.now());
        }
        task.setProgress(event.progress());
        if (event.speedBytesPerSec() > 0) { task.setDownloadSpeed(event.speedBytesPerSec()); }
        if (event.etaSeconds() > 0) { task.setEtaSeconds(event.etaSeconds()); }
        if (event.downloadMethod() != null) { task.setDownloadMethod(event.downloadMethod()); }
        // 失败原因透传：Worker 失败事件携带 errorMessage（下载/解压/解析/搬移环节），
        // 写入任务供前端展示与重试决策（阿里规范：失败信息须包含可定位的业务上下文）
        if ("FAILED".equals(newStatus) && event.errorMessage() != null && !event.errorMessage().isBlank()) {
            task.setErrorMessage(event.errorMessage());
            task.setEndTime(LocalDateTime.now());
        }
        taskMapper.updateById(task);

        // 阶段状态（DOWNLOADING/EXTRACTING/PARSING）同步到统一任务 stage 列（TaskStage 枚举）
        if (task.getManagementTaskId() != null) {
            com.comicatlas.api.management.enums.TaskStage stage =
                    com.comicatlas.api.management.enums.TaskStage.fromStatus(newStatus);
            if (stage != null) {
                managementTaskService.updateStage(task.getManagementTaskId(), stage, event.progress());
            }
        }

        // QA 修复注记（task-21）：Worker 导入失败只发 TaskStatusChangedEvent(FAILED)，
        // 不发 ImportTaskFailedEvent，导致统一管理任务 item 滞留 RUNNING（导入任务已
        // FAILED 但 management_task 仍 RUNNING）→ retryTask 校验非终态抛异常并把外层
        // 事务标记 rollback-only → 重试 500。此处把 FAILED/CANCELLED 联动到管理任务 item。
        if (task.getManagementTaskId() != null
                && ("FAILED".equals(newStatus) || "CANCELLED".equals(newStatus))) {
            ManagementTaskItem mgmtItem = managementTaskService.findActiveItem(
                    "COMIC", task.getComicId(), TaskType.IMPORT);
            if (mgmtItem != null) {
                ManagementTaskStatus mgmtStatus = "CANCELLED".equals(newStatus)
                        ? ManagementTaskStatus.CANCELLED
                        : ManagementTaskStatus.FAILED;
                managementTaskService.updateItemStatus(
                        mgmtItem.getId(), mgmtStatus, task.getErrorMessage(), "IMPORT_TASK", task.getId());
            }
        }

        // Worker 导入失败只发 TaskStatusChangedEvent(FAILED)（同 task-21 注记），
        // 若不联动 comic → IMPORT_FAILED，漫画将永久卡 IMPORTING（如 taskId=207/comicId=239）。
        if ("FAILED".equals(newStatus)) {
            markComicImportFailed(task);
        }
    }

    @RabbitListener(queues = MqQueues.IMPORT_FAILED)
    public void handleImportTaskFailed(ImportTaskFailedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        log.warn("ImportTaskFailed: taskId={}, errorCode={}, message={}",
                taskId, event.errorCode(), event.errorMessage());

        mqConsumerSupport.consume(channel, tag, "导入失败: taskId=" + taskId, () -> {
            transactionTemplate.executeWithoutResult(status -> {
                ImportTask task = taskMapper.selectById(taskId);
                if (task == null || TERMINAL_STATUSES.contains(task.getStatus())) {
                    return;
                }
                task.setStatus(ImportTaskStatus.FAILED);
                task.setEndTime(LocalDateTime.now());
                if (event.errorCode() != null) {
                    task.setErrorMessage(event.errorCode() + ": " + event.errorMessage());
                } else if (event.errorMessage() != null) {
                    task.setErrorMessage(event.errorMessage());
                }
                taskMapper.updateById(task);
                markImportFailed(task);
            });
        });
    }

    /**
     * 导入失败：comic → IMPORT_FAILED（可重试），并标记管理任务项失败。
     */
    private void markImportFailed(ImportTask task) {
        Comic comic = markComicImportFailed(task);
        if (comic == null) {
            return;
        }
        ManagementTaskItem mgmtItem = managementTaskService.findActiveItem(
                "COMIC", comic.getId(), TaskType.IMPORT);
        if (mgmtItem != null) {
            managementTaskService.updateItemStatus(
                    mgmtItem.getId(), ManagementTaskStatus.FAILED,
                    task.getErrorMessage(), "IMPORT_TASK", task.getId());
        }
    }

    /**
     * comic → IMPORT_FAILED（仅当处于 IMPORTING）。
     * 供 ImportTaskFailedEvent 与 TaskStatusChangedEvent(FAILED) 两条失败路径共用。
     *
     * @return 加载到的 comic；不存在时返回 null
     */
    private Comic markComicImportFailed(ImportTask task) {
        Comic comic = comicMapper.selectById(task.getComicId());
        if (comic == null || comic.getStatus() != ComicStatus.IMPORTING) {
            return comic;
        }
        ManagementStateMachine.validateComicTransition(comic.getStatus().name(), "IMPORT_FAILED");
        comic.setStatus(ComicStatus.IMPORT_FAILED);
        comicMapper.updateById(comic);
        return comic;
    }

    private boolean isEventProcessed(String idempKey) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(idempKey));
        } catch (Exception e) {
            log.warn("幂等标记读取失败，降级使用 DB 状态判断: key={}", idempKey, e);
            return false;
        }
    }

    private boolean isImportTaskTerminal(Long taskId) {
        ImportTask task = taskMapper.selectById(taskId);
        return task != null && TERMINAL_STATUSES.contains(task.getStatus());
    }

    /** 将事件状态字符串映射为 ImportTaskStatus；阶段值（DOWNLOADING/EXTRACTING）返回 null。 */
    private static ImportTaskStatus parseStatus(String status) {
        if (status == null) { return null; }
        try {
            return ImportTaskStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void markEventProcessed(String idempKey) {
        try {
            redisTemplate.opsForValue().set(idempKey, "1", Duration.ofDays(1));
        } catch (Exception e) {
            log.warn("幂等标记写入失败: key={}", idempKey, e);
        }
    }
}
