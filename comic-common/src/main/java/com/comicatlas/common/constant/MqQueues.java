package com.comicatlas.common.constant;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RabbitMQ 队列名常量（主队列 + 死信队列，契约与 AGENTS.md「RABBITMQ」表一致）。
 * <p>
 * 主队列统一配置 DLX + DLQ；拓扑声明与 @RabbitListener 消费必须引用同一常量。
 */
public final class MqQueues {
    private MqQueues() {
    }

    // ===== 主队列 =====
    public static final String IMPORT_TASK = "import.task.queue";
    public static final String IMPORT_RESULT = "import.result.queue";
    public static final String IMPORT_FAILED = "import.failed.queue";
    public static final String IMPORT_STORAGE_FINALIZE_REQUESTED = "import.storage.finalize.requested.queue";
    public static final String IMPORT_STORAGE_FINALIZE_COMPLETED = "import.storage.finalize.completed.queue";
    public static final String IMPORT_STORAGE_FINALIZE_FAILED = "import.storage.finalize.failed.queue";
    public static final String TASK_STATUS = "task.status.queue";
    public static final String CANCEL_TASK = "cancel.task.queue";
    public static final String VIDEO_METADATA_FIX = "video.metadata.fix.queue";
    public static final String VIDEO_METADATA_FIX_RESULT = "video.metadata.fix.result.queue";
    public static final String EXPORT_TASK = "export.task.queue";
    public static final String EXPORT_STARTED_RESULT = "export.started.result.queue";
    public static final String EXPORT_COMPLETED_RESULT = "export.completed.result.queue";
    public static final String EXPORT_FAILED_RESULT = "export.failed.result.queue";
    public static final String METADATA_REFRESH = "metadata.refresh.queue";
    public static final String VIDEO_TRANSCODE = "video.transcode.queue";
    public static final String VIDEO_TRANSCODE_COMPLETED = "video.transcode.completed.queue";
    public static final String VIDEO_TRANSCODE_FAILED = "video.transcode.failed.queue";
    public static final String RECOVERY_TASK = "recovery.task.queue";
    public static final String RECOVERY_RESULT = "recovery.result.queue";
    public static final String SCAN_TASK = "scan.task.queue";
    public static final String SCAN_RESULT = "scan.result.queue";
    public static final String MANAGEMENT_COMMAND = "management.command.queue";
    public static final String MANAGEMENT_CANCEL = "management.cancel.queue";
    public static final String MANAGEMENT_RESULT = "management.result.queue";

    // ===== 死信队列 =====
    public static final String IMPORT_TASK_DLQ = "import.task.dlq";
    public static final String IMPORT_RESULT_DLQ = "import.result.dlq";
    public static final String IMPORT_FAILED_DLQ = "import.failed.dlq";
    public static final String IMPORT_STORAGE_FINALIZE_REQUESTED_DLQ = "import.storage.finalize.requested.dlq";
    public static final String IMPORT_STORAGE_FINALIZE_COMPLETED_DLQ = "import.storage.finalize.completed.dlq";
    public static final String IMPORT_STORAGE_FINALIZE_FAILED_DLQ = "import.storage.finalize.failed.dlq";
    public static final String VIDEO_METADATA_FIX_DLQ = "video.metadata.fix.dlq";
    public static final String VIDEO_METADATA_FIX_RESULT_DLQ = "video.metadata.fix.result.dlq";
    public static final String EXPORT_TASK_DLQ = "export.task.dlq";
    public static final String EXPORT_STARTED_RESULT_DLQ = "export.started.result.dlq";
    public static final String EXPORT_COMPLETED_RESULT_DLQ = "export.completed.result.dlq";
    public static final String EXPORT_FAILED_RESULT_DLQ = "export.failed.result.dlq";
    public static final String METADATA_REFRESH_DLQ = "metadata.refresh.dlq";
    public static final String VIDEO_TRANSCODE_DLQ = "video.transcode.dlq";
    public static final String VIDEO_TRANSCODE_COMPLETED_DLQ = "video.transcode.completed.dlq";
    public static final String VIDEO_TRANSCODE_FAILED_DLQ = "video.transcode.failed.dlq";
    public static final String RECOVERY_TASK_DLQ = "recovery.task.dlq";
    public static final String RECOVERY_RESULT_DLQ = "recovery.result.dlq";
    public static final String SCAN_TASK_DLQ = "scan.task.dlq";
    public static final String SCAN_RESULT_DLQ = "scan.result.dlq";
    public static final String MANAGEMENT_COMMAND_DLQ = "management.command.dlq";
    public static final String MANAGEMENT_CANCEL_DLQ = "management.cancel.dlq";
    public static final String MANAGEMENT_RESULT_DLQ = "management.result.dlq";

    /**
     * 契约队列名全集（主队列 + 死信队列）。
     * <p>
     * 通过反射读取全部 String 常量，新增队列常量时自动纳入，无需手工维护清单。
     * 供 MQ 拓扑对账等监控逻辑判断 Broker 上是否存在契约外的僵尸队列。
     */
    public static Set<String> all() {
        return Arrays.stream(MqQueues.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()) && field.getType() == String.class)
                .map(field -> readValue(field))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String readValue(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读取队列常量失败: " + field.getName(), e);
        }
    }
}
