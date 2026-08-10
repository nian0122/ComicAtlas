package com.comicatlas.worker.config;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Bean
    public MessageConverter messageConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(mapper);
    }

    // ===== Exchanges =====

    @Bean
    public DirectExchange importExchange() { return new DirectExchange(MqExchanges.IMPORT); }

    @Bean
    public DirectExchange importDlxExchange() { return new DirectExchange(MqExchanges.IMPORT_DLX); }

    @Bean
    public DirectExchange imageExchange() { return new DirectExchange(MqExchanges.IMAGE); }

    @Bean
    public DirectExchange imageDlxExchange() { return new DirectExchange(MqExchanges.IMAGE_DLX); }

    @Bean
    public DirectExchange taskExchange() { return new DirectExchange(MqExchanges.TASK); }

    @Bean
    public DirectExchange exportExchange() { return new DirectExchange(MqExchanges.EXPORT); }

    @Bean
    public DirectExchange exportDlxExchange() { return new DirectExchange(MqExchanges.EXPORT_DLX); }

    // ===== Queues =====

    @Bean
    public Queue importTaskQueue() {
        return QueueBuilder.durable(MqQueues.IMPORT_TASK)
                .deadLetterExchange(MqExchanges.IMPORT_DLX)
                .deadLetterRoutingKey(MqQueues.IMPORT_TASK_DLQ)
                .build();
    }

    @Bean
    public Queue importTaskDlq() {
        return QueueBuilder.durable(MqQueues.IMPORT_TASK_DLQ).build();
    }

    @Bean
    public Queue cancelTaskQueue() {
        return QueueBuilder.durable(MqQueues.CANCEL_TASK).build();
    }

    @Bean
    public Queue hqDeleteQueue() {
        return QueueBuilder.durable(MqQueues.HQ_DELETE)
                .deadLetterExchange(MqExchanges.IMAGE_DLX)
                .deadLetterRoutingKey(MqQueues.HQ_DELETE_DLQ)
                .build();
    }

    @Bean
    public Queue hqDeleteDlq() {
        return QueueBuilder.durable(MqQueues.HQ_DELETE_DLQ).build();
    }

    @Bean
    public Queue exportTaskQueue() {
        return QueueBuilder.durable(MqQueues.EXPORT_TASK)
                .deadLetterExchange(MqExchanges.EXPORT_DLX)
                .deadLetterRoutingKey(MqQueues.EXPORT_TASK_DLQ)
                .build();
    }

    @Bean
    public Queue exportTaskDlq() {
        return QueueBuilder.durable(MqQueues.EXPORT_TASK_DLQ).build();
    }

    @Bean
    public Queue metadataRefreshQueue() {
        return QueueBuilder.durable(MqQueues.METADATA_REFRESH)
                .deadLetterExchange(MqExchanges.EXPORT_DLX)
                .deadLetterRoutingKey(MqQueues.METADATA_REFRESH_DLQ)
                .build();
    }

    @Bean
    public Queue metadataRefreshDlq() {
        return QueueBuilder.durable(MqQueues.METADATA_REFRESH_DLQ).build();
    }

    @Bean
    public Queue videoMetadataFixQueue() {
        return QueueBuilder.durable(MqQueues.VIDEO_METADATA_FIX)
                .deadLetterExchange(MqExchanges.IMAGE_DLX)
                .deadLetterRoutingKey(MqQueues.VIDEO_METADATA_FIX_DLQ)
                .build();
    }

    @Bean
    public Queue videoMetadataFixDlq() {
        return QueueBuilder.durable(MqQueues.VIDEO_METADATA_FIX_DLQ).build();
    }

    // ===== Bindings =====

    @Bean
    public Binding importTaskBinding() {
        return BindingBuilder.bind(importTaskQueue())
                .to(importExchange()).with(MqRoutingKeys.TASK_CREATED);
    }

    @Bean
    public Binding importTaskDlqBinding() {
        return BindingBuilder.bind(importTaskDlq())
                .to(importDlxExchange()).with(MqQueues.IMPORT_TASK_DLQ);
    }

    // ==================== MqExchanges.IMPORT 导入存储最终化请求 ====================

    @Bean
    public Queue importStorageFinalizeRequestedQueue() {
        return QueueBuilder.durable(MqQueues.IMPORT_STORAGE_FINALIZE_REQUESTED)
                .deadLetterExchange(MqExchanges.IMPORT_DLX)
                .deadLetterRoutingKey(MqQueues.IMPORT_STORAGE_FINALIZE_REQUESTED_DLQ)
                .build();
    }

    @Bean
    public Queue importStorageFinalizeRequestedDlq() {
        return QueueBuilder.durable(MqQueues.IMPORT_STORAGE_FINALIZE_REQUESTED_DLQ).build();
    }

    @Bean
    public Binding importStorageFinalizeRequestedBinding() {
        return BindingBuilder.bind(importStorageFinalizeRequestedQueue())
                .to(importExchange()).with(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_REQUESTED);
    }

    @Bean
    public Binding importStorageFinalizeRequestedDlqBinding() {
        return BindingBuilder.bind(importStorageFinalizeRequestedDlq())
                .to(importDlxExchange()).with(MqQueues.IMPORT_STORAGE_FINALIZE_REQUESTED_DLQ);
    }

    @Bean
    public Binding cancelTaskBinding() {
        return BindingBuilder.bind(cancelTaskQueue())
                .to(taskExchange()).with(MqRoutingKeys.CANCEL_REQUESTED);
    }

    @Bean
    public Binding hqDeleteBinding() {
        return BindingBuilder.bind(hqDeleteQueue())
                .to(imageExchange()).with(MqRoutingKeys.HQ_DELETE_REQUESTED);
    }

    @Bean
    public Binding hqDeleteDlqBinding() {
        return BindingBuilder.bind(hqDeleteDlq())
                .to(imageDlxExchange()).with(MqQueues.HQ_DELETE_DLQ);
    }

    @Bean
    public Binding exportTaskBinding() {
        return BindingBuilder.bind(exportTaskQueue())
                .to(exportExchange()).with(MqRoutingKeys.TASK_CREATED);
    }

    @Bean
    public Binding exportTaskDlqBinding() {
        return BindingBuilder.bind(exportTaskDlq())
                .to(exportDlxExchange()).with(MqQueues.EXPORT_TASK_DLQ);
    }

    @Bean
    public Binding metadataRefreshBinding() {
        return BindingBuilder.bind(metadataRefreshQueue())
                .to(exportExchange()).with(MqRoutingKeys.METADATA_REFRESH_REQUESTED);
    }

    @Bean
    public Binding metadataRefreshDlqBinding() {
        return BindingBuilder.bind(metadataRefreshDlq())
                .to(exportDlxExchange()).with(MqQueues.METADATA_REFRESH_DLQ);
    }

    @Bean
    public Binding videoMetadataFixBinding() {
        return BindingBuilder.bind(videoMetadataFixQueue())
                .to(imageExchange()).with(MqRoutingKeys.VIDEO_METADATA_FIX_REQUESTED);
    }

    @Bean
    public Binding videoMetadataFixDlqBinding() {
        return BindingBuilder.bind(videoMetadataFixDlq())
                .to(imageDlxExchange()).with(MqQueues.VIDEO_METADATA_FIX_DLQ);
    }

    // ==================== recovery 恢复任务 ====================

    @Bean
    public DirectExchange recoveryExchange() { return new DirectExchange(MqExchanges.RECOVERY); }

    @Bean
    public DirectExchange recoveryDlxExchange() { return new DirectExchange(MqExchanges.RECOVERY_DLX); }

    @Bean
    public Queue recoveryTaskQueue() {
        return QueueBuilder.durable(MqQueues.RECOVERY_TASK)
                .deadLetterExchange(MqExchanges.RECOVERY_DLX)
                .deadLetterRoutingKey(MqQueues.RECOVERY_TASK_DLQ)
                .build();
    }

    @Bean
    public Queue recoveryTaskDlq() {
        return QueueBuilder.durable(MqQueues.RECOVERY_TASK_DLQ).build();
    }

    @Bean
    public Binding recoveryTaskBinding() {
        return BindingBuilder.bind(recoveryTaskQueue())
                .to(recoveryExchange()).with(MqRoutingKeys.RECOVERY_REQUESTED);
    }

    @Bean
    public Binding recoveryTaskDlqBinding() {
        return BindingBuilder.bind(recoveryTaskDlq())
                .to(recoveryDlxExchange()).with(MqQueues.RECOVERY_TASK_DLQ);
    }

    // ==================== scan 目录扫描 ====================

    @Bean
    public DirectExchange scanExchange() { return new DirectExchange(MqExchanges.SCAN); }

    @Bean
    public DirectExchange scanDlxExchange() { return new DirectExchange(MqExchanges.SCAN_DLX); }

    @Bean
    public Queue scanTaskQueue() {
        return QueueBuilder.durable(MqQueues.SCAN_TASK)
                .deadLetterExchange(MqExchanges.SCAN_DLX)
                .deadLetterRoutingKey(MqQueues.SCAN_TASK_DLQ)
                .build();
    }

    @Bean
    public Queue scanTaskDlq() {
        return QueueBuilder.durable(MqQueues.SCAN_TASK_DLQ).build();
    }

    @Bean
    public Binding scanTaskBinding() {
        return BindingBuilder.bind(scanTaskQueue())
                .to(scanExchange()).with(MqRoutingKeys.SCAN_REQUESTED);
    }

    @Bean
    public Binding scanTaskDlqBinding() {
        return BindingBuilder.bind(scanTaskDlq())
                .to(scanDlxExchange()).with(MqQueues.SCAN_TASK_DLQ);
    }

    // ==================== management 管理命令任务 ====================

    @Bean
    public DirectExchange managementExchange() { return new DirectExchange(MqExchanges.MANAGEMENT); }

    @Bean
    public DirectExchange managementDlxExchange() { return new DirectExchange(MqExchanges.MANAGEMENT_DLX); }

    @Bean
    public Queue managementCommandQueue() {
        return QueueBuilder.durable(MqQueues.MANAGEMENT_COMMAND)
                .deadLetterExchange(MqExchanges.MANAGEMENT_DLX)
                .deadLetterRoutingKey(MqQueues.MANAGEMENT_COMMAND_DLQ)
                .build();
    }

    @Bean
    public Queue managementCommandDlq() {
        return QueueBuilder.durable(MqQueues.MANAGEMENT_COMMAND_DLQ).build();
    }

    @Bean
    public Queue managementCancelQueue() {
        return QueueBuilder.durable(MqQueues.MANAGEMENT_CANCEL)
                .deadLetterExchange(MqExchanges.MANAGEMENT_DLX)
                .deadLetterRoutingKey(MqQueues.MANAGEMENT_CANCEL_DLQ)
                .build();
    }

    @Bean
    public Queue managementCancelDlq() {
        return QueueBuilder.durable(MqQueues.MANAGEMENT_CANCEL_DLQ).build();
    }

    @Bean
    public Binding managementCommandBinding() {
        return BindingBuilder.bind(managementCommandQueue())
                .to(managementExchange()).with(MqRoutingKeys.COMMAND_REQUESTED);
    }

    @Bean
    public Binding managementCancelBinding() {
        return BindingBuilder.bind(managementCancelQueue())
                .to(managementExchange()).with(MqRoutingKeys.COMMAND_CANCEL);
    }

    @Bean
    public Binding managementCommandDlqBinding() {
        return BindingBuilder.bind(managementCommandDlq())
                .to(managementDlxExchange()).with(MqQueues.MANAGEMENT_COMMAND_DLQ);
    }

    @Bean
    public Binding managementCancelDlqBinding() {
        return BindingBuilder.bind(managementCancelDlq())
                .to(managementDlxExchange()).with(MqQueues.MANAGEMENT_CANCEL_DLQ);
    }
}
