package com.comicatlas.api.config;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RabbitMqConfig {

    @Bean
    public MessageConverter messageConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(mapper);
    }

    /**
     * RabbitMQ Management HTTP API 客户端（枚举全部队列用于积压/死信统计）。
     * <p>
     * 凭据默认回退 spring.rabbitmq 配置；连接/读取超时避免管理插件不可用时拖垮管理接口。
     */
    @Bean
    public RestTemplate rabbitManagementRestTemplate(
            RestTemplateBuilder builder,
            @Value("${mq.management.username:${spring.rabbitmq.username:guest}}") String username,
            @Value("${mq.management.password:${spring.rabbitmq.password:guest}}") String password) {
        return builder
                .basicAuthentication(username, password)
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Bean
    public DirectExchange importExchange() {
        return new DirectExchange(MqExchanges.IMPORT);
    }

    @Bean
    public Queue importResultQueue() {
        return QueueBuilder.durable(MqQueues.IMPORT_RESULT)
                .deadLetterExchange(MqExchanges.IMPORT_DLX)
                .deadLetterRoutingKey(MqQueues.IMPORT_RESULT_DLQ)
                .build();
    }

    @Bean
    public Queue importResultDlq() {
        return QueueBuilder.durable(MqQueues.IMPORT_RESULT_DLQ).build();
    }

    @Bean
    public Binding importResultBinding() {
        return BindingBuilder.bind(importResultQueue())
                .to(importExchange()).with(MqRoutingKeys.TASK_COMPLETED);
    }

    @Bean
    public Queue importFailedQueue() {
        return QueueBuilder.durable(MqQueues.IMPORT_FAILED)
                .deadLetterExchange(MqExchanges.IMPORT_DLX)
                .deadLetterRoutingKey(MqQueues.IMPORT_FAILED_DLQ)
                .build();
    }

    @Bean
    public Queue importFailedDlq() {
        return QueueBuilder.durable(MqQueues.IMPORT_FAILED_DLQ).build();
    }

    @Bean
    public Binding importFailedBinding() {
        return BindingBuilder.bind(importFailedQueue())
                .to(importExchange()).with(MqRoutingKeys.TASK_FAILED);
    }

    @Bean
    public Binding importFailedDlqBinding() {
        return BindingBuilder.bind(importFailedDlq())
                .to(importDlxExchange()).with(MqQueues.IMPORT_FAILED_DLQ);
    }

    @Bean
    public Binding importResultDlqBinding() {
        return BindingBuilder.bind(importResultDlq())
                .to(importDlxExchange()).with(MqQueues.IMPORT_RESULT_DLQ);
    }

    @Bean
    public DirectExchange importDlxExchange() {
        return new DirectExchange(MqExchanges.IMPORT_DLX);
    }

    // ==================== MqExchanges.IMPORT 导入存储最终化结果 ====================

    @Bean
    public Queue importStorageFinalizeCompletedQueue() {
        return QueueBuilder.durable(MqQueues.IMPORT_STORAGE_FINALIZE_COMPLETED)
                .deadLetterExchange(MqExchanges.IMPORT_DLX)
                .deadLetterRoutingKey(MqQueues.IMPORT_STORAGE_FINALIZE_COMPLETED_DLQ)
                .build();
    }

    @Bean
    public Queue importStorageFinalizeCompletedDlq() {
        return QueueBuilder.durable(MqQueues.IMPORT_STORAGE_FINALIZE_COMPLETED_DLQ).build();
    }

    @Bean
    public Binding importStorageFinalizeCompletedBinding() {
        return BindingBuilder.bind(importStorageFinalizeCompletedQueue())
                .to(importExchange()).with(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED);
    }

    @Bean
    public Binding importStorageFinalizeCompletedDlqBinding() {
        return BindingBuilder.bind(importStorageFinalizeCompletedDlq())
                .to(importDlxExchange()).with(MqQueues.IMPORT_STORAGE_FINALIZE_COMPLETED_DLQ);
    }

    @Bean
    public Queue importStorageFinalizeFailedQueue() {
        return QueueBuilder.durable(MqQueues.IMPORT_STORAGE_FINALIZE_FAILED)
                .deadLetterExchange(MqExchanges.IMPORT_DLX)
                .deadLetterRoutingKey(MqQueues.IMPORT_STORAGE_FINALIZE_FAILED_DLQ)
                .build();
    }

    @Bean
    public Queue importStorageFinalizeFailedDlq() {
        return QueueBuilder.durable(MqQueues.IMPORT_STORAGE_FINALIZE_FAILED_DLQ).build();
    }

    @Bean
    public Binding importStorageFinalizeFailedBinding() {
        return BindingBuilder.bind(importStorageFinalizeFailedQueue())
                .to(importExchange()).with(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_FAILED);
    }

    @Bean
    public Binding importStorageFinalizeFailedDlqBinding() {
        return BindingBuilder.bind(importStorageFinalizeFailedDlq())
                .to(importDlxExchange()).with(MqQueues.IMPORT_STORAGE_FINALIZE_FAILED_DLQ);
    }

    @Bean
    public DirectExchange taskExchange() {
        return new DirectExchange(MqExchanges.TASK);
    }

    @Bean
    public Queue taskStatusQueue() {
        return QueueBuilder.durable(MqQueues.TASK_STATUS).build();
    }

    @Bean
    public Binding taskStatusBinding() {
        return BindingBuilder.bind(taskStatusQueue())
                .to(taskExchange()).with(MqRoutingKeys.STATUS_CHANGED);
    }

    @Bean
    public DirectExchange imageExchange() {
        return new DirectExchange(MqExchanges.IMAGE);
    }

    @Bean
    public Queue lqResultQueue() {
        return QueueBuilder.durable(MqQueues.LQ_RESULT)
                .deadLetterExchange(MqExchanges.IMAGE_DLX)
                .deadLetterRoutingKey(MqQueues.LQ_RESULT_DLQ)
                .build();
    }

    @Bean
    public Queue lqResultDlq() {
        return QueueBuilder.durable(MqQueues.LQ_RESULT_DLQ).build();
    }

    @Bean
    public Binding lqResultBinding() {
        return BindingBuilder.bind(lqResultQueue())
                .to(imageExchange()).with(MqRoutingKeys.LQ_COMPLETED);
    }

    @Bean
    public Binding lqResultDlqBinding() {
        return BindingBuilder.bind(lqResultDlq())
                .to(imageDlxExchange()).with(MqQueues.LQ_RESULT_DLQ);
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
    public Queue hqDeleteResultQueue() {
        return QueueBuilder.durable(MqQueues.HQ_DELETE_RESULT)
                .deadLetterExchange(MqExchanges.IMAGE_DLX)
                .deadLetterRoutingKey(MqQueues.HQ_DELETE_RESULT_DLQ)
                .build();
    }

    @Bean
    public Queue hqDeleteResultDlq() {
        return QueueBuilder.durable(MqQueues.HQ_DELETE_RESULT_DLQ).build();
    }

    @Bean
    public Binding hqDeleteResultBinding() {
        return BindingBuilder.bind(hqDeleteResultQueue())
                .to(imageExchange()).with(MqRoutingKeys.HQ_DELETE_COMPLETED);
    }

    @Bean
    public Binding hqDeleteResultDlqBinding() {
        return BindingBuilder.bind(hqDeleteResultDlq())
                .to(imageDlxExchange()).with(MqQueues.HQ_DELETE_RESULT_DLQ);
    }

    @Bean
    public Queue videoMetadataFixResultQueue() {
        return QueueBuilder.durable(MqQueues.VIDEO_METADATA_FIX_RESULT)
                .deadLetterExchange(MqExchanges.IMAGE_DLX)
                .deadLetterRoutingKey(MqQueues.VIDEO_METADATA_FIX_RESULT_DLQ)
                .build();
    }

    @Bean
    public Queue videoMetadataFixResultDlq() {
        return QueueBuilder.durable(MqQueues.VIDEO_METADATA_FIX_RESULT_DLQ).build();
    }

    @Bean
    public Binding videoMetadataFixCompletedBinding() {
        return BindingBuilder.bind(videoMetadataFixResultQueue())
                .to(imageExchange()).with(MqRoutingKeys.VIDEO_METADATA_FIX_COMPLETED);
    }

    @Bean
    public Binding videoMetadataFixResultDlqBinding() {
        return BindingBuilder.bind(videoMetadataFixResultDlq())
                .to(imageDlxExchange()).with(MqQueues.VIDEO_METADATA_FIX_RESULT_DLQ);
    }

    @Bean
    public DirectExchange imageDlxExchange() {
        return new DirectExchange(MqExchanges.IMAGE_DLX);
    }

    // ==================== MqExchanges.EXPORT ====================

    @Bean
    public DirectExchange exportExchange() {
        return new DirectExchange(MqExchanges.EXPORT);
    }

    @Bean
    public DirectExchange exportDlxExchange() {
        return new DirectExchange(MqExchanges.EXPORT_DLX);
    }

    @Bean
    public DirectExchange videoExchange() {
        return new DirectExchange(MqExchanges.VIDEO);
    }

    @Bean
    public DirectExchange videoDlxExchange() {
        return new DirectExchange(MqExchanges.VIDEO_DLX);
    }

    @Bean
    public Queue exportStartedResultQueue() {
        return QueueBuilder.durable(MqQueues.EXPORT_STARTED_RESULT)
                .deadLetterExchange(MqExchanges.EXPORT_DLX)
                .deadLetterRoutingKey(MqQueues.EXPORT_STARTED_RESULT_DLQ)
                .build();
    }

    @Bean
    public Queue exportCompletedResultQueue() {
        return QueueBuilder.durable(MqQueues.EXPORT_COMPLETED_RESULT)
                .deadLetterExchange(MqExchanges.EXPORT_DLX)
                .deadLetterRoutingKey(MqQueues.EXPORT_COMPLETED_RESULT_DLQ)
                .build();
    }

    @Bean
    public Queue exportFailedResultQueue() {
        return QueueBuilder.durable(MqQueues.EXPORT_FAILED_RESULT)
                .deadLetterExchange(MqExchanges.EXPORT_DLX)
                .deadLetterRoutingKey(MqQueues.EXPORT_FAILED_RESULT_DLQ)
                .build();
    }

    @Bean
    public Queue exportStartedResultDlq() {
        return QueueBuilder.durable(MqQueues.EXPORT_STARTED_RESULT_DLQ).build();
    }

    @Bean
    public Queue exportCompletedResultDlq() {
        return QueueBuilder.durable(MqQueues.EXPORT_COMPLETED_RESULT_DLQ).build();
    }

    @Bean
    public Queue exportFailedResultDlq() {
        return QueueBuilder.durable(MqQueues.EXPORT_FAILED_RESULT_DLQ).build();
    }

    @Bean
    public Binding exportStartedResultBinding() {
        return BindingBuilder.bind(exportStartedResultQueue())
                .to(exportExchange()).with(MqRoutingKeys.TASK_STARTED);
    }

    @Bean
    public Binding exportCompletedResultBinding() {
        return BindingBuilder.bind(exportCompletedResultQueue())
                .to(exportExchange()).with(MqRoutingKeys.TASK_COMPLETED);
    }

    @Bean
    public Binding exportFailedResultBinding() {
        return BindingBuilder.bind(exportFailedResultQueue())
                .to(exportExchange()).with(MqRoutingKeys.TASK_FAILED);
    }

    @Bean
    public Binding exportStartedResultDlqBinding() {
        return BindingBuilder.bind(exportStartedResultDlq())
                .to(exportDlxExchange()).with(MqQueues.EXPORT_STARTED_RESULT_DLQ);
    }

    @Bean
    public Binding exportCompletedResultDlqBinding() {
        return BindingBuilder.bind(exportCompletedResultDlq())
                .to(exportDlxExchange()).with(MqQueues.EXPORT_COMPLETED_RESULT_DLQ);
    }

    @Bean
    public Binding exportFailedResultDlqBinding() {
        return BindingBuilder.bind(exportFailedResultDlq())
                .to(exportDlxExchange()).with(MqQueues.EXPORT_FAILED_RESULT_DLQ);
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
    public Binding metadataRefreshBinding() {
        return BindingBuilder.bind(metadataRefreshQueue())
                .to(exportExchange()).with(MqRoutingKeys.METADATA_REFRESH_REQUESTED);
    }

    @Bean
    public Binding metadataRefreshDlqBinding() {
        return BindingBuilder.bind(metadataRefreshDlq())
                .to(exportDlxExchange()).with(MqQueues.METADATA_REFRESH_DLQ);
    }

    // ==================== MqExchanges.VIDEO 视频转码结果 ====================

    @Bean
    public Queue videoTranscodeCompletedQueue() {
        return QueueBuilder.durable(MqQueues.VIDEO_TRANSCODE_COMPLETED)
                .deadLetterExchange(MqExchanges.VIDEO_DLX)
                .deadLetterRoutingKey(MqQueues.VIDEO_TRANSCODE_COMPLETED_DLQ)
                .build();
    }

    @Bean
    public Queue videoTranscodeCompletedDlq() {
        return QueueBuilder.durable(MqQueues.VIDEO_TRANSCODE_COMPLETED_DLQ).build();
    }

    @Bean
    public Queue videoTranscodeFailedQueue() {
        return QueueBuilder.durable(MqQueues.VIDEO_TRANSCODE_FAILED)
                .deadLetterExchange(MqExchanges.VIDEO_DLX)
                .deadLetterRoutingKey(MqQueues.VIDEO_TRANSCODE_FAILED_DLQ)
                .build();
    }

    @Bean
    public Queue videoTranscodeFailedDlq() {
        return QueueBuilder.durable(MqQueues.VIDEO_TRANSCODE_FAILED_DLQ).build();
    }

    @Bean
    public Binding videoTranscodeCompletedBinding() {
        return BindingBuilder.bind(videoTranscodeCompletedQueue())
                .to(videoExchange()).with(MqRoutingKeys.VIDEO_TRANSCODE_COMPLETED);
    }

    @Bean
    public Binding videoTranscodeFailedBinding() {
        return BindingBuilder.bind(videoTranscodeFailedQueue())
                .to(videoExchange()).with(MqRoutingKeys.VIDEO_TRANSCODE_FAILED);
    }

    @Bean
    public Binding videoTranscodeCompletedDlqBinding() {
        return BindingBuilder.bind(videoTranscodeCompletedDlq())
                .to(videoDlxExchange()).with(MqQueues.VIDEO_TRANSCODE_COMPLETED_DLQ);
    }

    @Bean
    public Binding videoTranscodeFailedDlqBinding() {
        return BindingBuilder.bind(videoTranscodeFailedDlq())
                .to(videoDlxExchange()).with(MqQueues.VIDEO_TRANSCODE_FAILED_DLQ);
    }

    // ==================== MqExchanges.RECOVERY ====================

    @Bean
    public DirectExchange recoveryExchange() {
        return new DirectExchange(MqExchanges.RECOVERY);
    }

    @Bean
    public DirectExchange recoveryDlxExchange() {
        return new DirectExchange(MqExchanges.RECOVERY_DLX);
    }

    @Bean
    public Queue recoveryResultQueue() {
        return QueueBuilder.durable(MqQueues.RECOVERY_RESULT)
                .deadLetterExchange(MqExchanges.RECOVERY_DLX)
                .deadLetterRoutingKey(MqQueues.RECOVERY_RESULT_DLQ)
                .build();
    }

    @Bean
    public Queue recoveryResultDlq() {
        return QueueBuilder.durable(MqQueues.RECOVERY_RESULT_DLQ).build();
    }

    @Bean
    public Binding recoveryProgressBinding() {
        return BindingBuilder.bind(recoveryResultQueue())
                .to(recoveryExchange()).with(MqRoutingKeys.RECOVERY_PROGRESS);
    }

    @Bean
    public Binding recoveryCompletedBinding() {
        return BindingBuilder.bind(recoveryResultQueue())
                .to(recoveryExchange()).with(MqRoutingKeys.RECOVERY_COMPLETED);
    }

    @Bean
    public Binding recoveryFailedBinding() {
        return BindingBuilder.bind(recoveryResultQueue())
                .to(recoveryExchange()).with(MqRoutingKeys.RECOVERY_FAILED);
    }

    @Bean
    public Binding recoveryResultDlqBinding() {
        return BindingBuilder.bind(recoveryResultDlq())
                .to(recoveryDlxExchange()).with(MqQueues.RECOVERY_RESULT_DLQ);
    }

    // ==================== MqExchanges.SCAN 目录扫描 ====================

    @Bean
    public DirectExchange scanExchange() {
        return new DirectExchange(MqExchanges.SCAN);
    }

    @Bean
    public DirectExchange scanDlxExchange() {
        return new DirectExchange(MqExchanges.SCAN_DLX);
    }

    @Bean
    public Queue scanResultQueue() {
        return QueueBuilder.durable(MqQueues.SCAN_RESULT)
                .deadLetterExchange(MqExchanges.SCAN_DLX)
                .deadLetterRoutingKey(MqQueues.SCAN_RESULT_DLQ)
                .build();
    }

    @Bean
    public Queue scanResultDlq() {
        return QueueBuilder.durable(MqQueues.SCAN_RESULT_DLQ).build();
    }

    @Bean
    public Binding scanCompletedBinding() {
        return BindingBuilder.bind(scanResultQueue())
                .to(scanExchange()).with(MqRoutingKeys.SCAN_COMPLETED);
    }

    @Bean
    public Binding scanFailedBinding() {
        return BindingBuilder.bind(scanResultQueue())
                .to(scanExchange()).with(MqRoutingKeys.SCAN_FAILED);
    }

    @Bean
    public Binding scanResultDlqBinding() {
        return BindingBuilder.bind(scanResultDlq())
                .to(scanDlxExchange()).with(MqQueues.SCAN_RESULT_DLQ);
    }

    // ==================== MqExchanges.MANAGEMENT 管理命令结果 ====================

    @Bean
    public DirectExchange managementExchange() {
        return new DirectExchange(MqExchanges.MANAGEMENT);
    }

    @Bean
    public DirectExchange managementDlxExchange() {
        return new DirectExchange(MqExchanges.MANAGEMENT_DLX);
    }

    @Bean
    public Queue managementResultQueue() {
        return QueueBuilder.durable(MqQueues.MANAGEMENT_RESULT)
                .deadLetterExchange(MqExchanges.MANAGEMENT_DLX)
                .deadLetterRoutingKey(MqQueues.MANAGEMENT_RESULT_DLQ)
                .build();
    }

    @Bean
    public Queue managementResultDlq() {
        return QueueBuilder.durable(MqQueues.MANAGEMENT_RESULT_DLQ).build();
    }

    @Bean
    public Binding managementCompletedBinding() {
        return BindingBuilder.bind(managementResultQueue())
                .to(managementExchange()).with(MqRoutingKeys.COMMAND_COMPLETED);
    }

    @Bean
    public Binding managementFailedBinding() {
        return BindingBuilder.bind(managementResultQueue())
                .to(managementExchange()).with(MqRoutingKeys.COMMAND_FAILED);
    }

    @Bean
    public Binding managementProgressBinding() {
        return BindingBuilder.bind(managementResultQueue())
                .to(managementExchange()).with(MqRoutingKeys.COMMAND_PROGRESS);
    }

    @Bean
    public Binding managementResultDlqBinding() {
        return BindingBuilder.bind(managementResultDlq())
                .to(managementDlxExchange()).with(MqQueues.MANAGEMENT_RESULT_DLQ);
    }
}
