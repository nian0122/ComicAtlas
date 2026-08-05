package com.comicatlas.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.QueueBuilder;

@Configuration
public class RabbitMqConfig {

    @Bean
    public MessageConverter messageConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean
    public DirectExchange importExchange() {
        return new DirectExchange("comic.import");
    }

    @Bean
    public Queue importResultQueue() {
        return QueueBuilder.durable("import.result.queue")
                .deadLetterExchange("comic.import.dlx")
                .deadLetterRoutingKey("import.result.dlq")
                .build();
    }

    @Bean
    public Queue importResultDlq() {
        return QueueBuilder.durable("import.result.dlq").build();
    }

    @Bean
    public Binding importResultBinding() {
        return BindingBuilder.bind(importResultQueue())
                .to(importExchange()).with("task.completed");
    }

    @Bean
    public Queue importFailedQueue() {
        return QueueBuilder.durable("import.failed.queue")
                .deadLetterExchange("comic.import.dlx")
                .deadLetterRoutingKey("import.failed.dlq")
                .build();
    }

    @Bean
    public Queue importFailedDlq() {
        return QueueBuilder.durable("import.failed.dlq").build();
    }

    @Bean
    public Binding importFailedBinding() {
        return BindingBuilder.bind(importFailedQueue())
                .to(importExchange()).with("task.failed");
    }

    @Bean
    public Binding importFailedDlqBinding() {
        return BindingBuilder.bind(importFailedDlq())
                .to(importDlxExchange()).with("import.failed.dlq");
    }

    @Bean
    public Binding importResultDlqBinding() {
        return BindingBuilder.bind(importResultDlq())
                .to(importDlxExchange()).with("import.result.dlq");
    }

    @Bean
    public DirectExchange importDlxExchange() {
        return new DirectExchange("comic.import.dlx");
    }

    @Bean
    public DirectExchange taskExchange() {
        return new DirectExchange("comic.task");
    }

    @Bean
    public Queue taskStatusQueue() {
        return QueueBuilder.durable("task.status.queue").build();
    }

    @Bean
    public Binding taskStatusBinding() {
        return BindingBuilder.bind(taskStatusQueue())
                .to(taskExchange()).with("status.changed");
    }

    @Bean
    public DirectExchange deleteExchange() {
        return new DirectExchange("comic.delete");
    }

    @Bean
    public Queue deleteResultQueue() {
        return QueueBuilder.durable("delete.result.queue")
                .deadLetterExchange("comic.delete.dlx")
                .deadLetterRoutingKey("delete.result.dlq")
                .build();
    }

    @Bean
    public Queue deleteResultDlq() {
        return QueueBuilder.durable("delete.result.dlq").build();
    }

    @Bean
    public Binding deleteResultBinding() {
        return BindingBuilder.bind(deleteResultQueue())
                .to(deleteExchange()).with("delete.completed");
    }

    @Bean
    public Binding deleteResultDlqBinding() {
        return BindingBuilder.bind(deleteResultDlq())
                .to(deleteDlxExchange()).with("delete.result.dlq");
    }

    @Bean
    public DirectExchange deleteDlxExchange() {
        return new DirectExchange("comic.delete.dlx");
    }

    @Bean
    public DirectExchange imageExchange() {
        return new DirectExchange("comic.image");
    }

    @Bean
    public Queue lqResultQueue() {
        return QueueBuilder.durable("lq.result.queue")
                .deadLetterExchange("comic.image.dlx")
                .deadLetterRoutingKey("lq.result.dlq")
                .build();
    }

    @Bean
    public Queue lqResultDlq() {
        return QueueBuilder.durable("lq.result.dlq").build();
    }

    @Bean
    public Binding lqResultBinding() {
        return BindingBuilder.bind(lqResultQueue())
                .to(imageExchange()).with("lq.completed");
    }

    @Bean
    public Binding lqResultDlqBinding() {
        return BindingBuilder.bind(lqResultDlq())
                .to(imageDlxExchange()).with("lq.result.dlq");
    }

    @Bean
    public Queue hqDeleteQueue() {
        return QueueBuilder.durable("hq.delete.queue")
                .deadLetterExchange("comic.image.dlx")
                .deadLetterRoutingKey("hq.delete.dlq")
                .build();
    }

    @Bean
    public Queue hqDeleteDlq() {
        return QueueBuilder.durable("hq.delete.dlq").build();
    }

    @Bean
    public Binding hqDeleteBinding() {
        return BindingBuilder.bind(hqDeleteQueue())
                .to(imageExchange()).with("hq.delete.requested");
    }

    @Bean
    public Binding hqDeleteDlqBinding() {
        return BindingBuilder.bind(hqDeleteDlq())
                .to(imageDlxExchange()).with("hq.delete.dlq");
    }

    @Bean
    public Queue hqDeleteResultQueue() {
        return QueueBuilder.durable("hq.delete.result.queue")
                .deadLetterExchange("comic.image.dlx")
                .deadLetterRoutingKey("hq.delete.result.dlq")
                .build();
    }

    @Bean
    public Queue hqDeleteResultDlq() {
        return QueueBuilder.durable("hq.delete.result.dlq").build();
    }

    @Bean
    public Binding hqDeleteResultBinding() {
        return BindingBuilder.bind(hqDeleteResultQueue())
                .to(imageExchange()).with("hq.delete.completed");
    }

    @Bean
    public Binding hqDeleteResultDlqBinding() {
        return BindingBuilder.bind(hqDeleteResultDlq())
                .to(imageDlxExchange()).with("hq.delete.result.dlq");
    }

    @Bean
    public Queue videoMetadataFixResultQueue() {
        return QueueBuilder.durable("video.metadata.fix.result.queue")
                .deadLetterExchange("comic.image.dlx")
                .deadLetterRoutingKey("video.metadata.fix.result.dlq")
                .build();
    }

    @Bean
    public Queue videoMetadataFixResultDlq() {
        return QueueBuilder.durable("video.metadata.fix.result.dlq").build();
    }

    @Bean
    public Binding videoMetadataFixCompletedBinding() {
        return BindingBuilder.bind(videoMetadataFixResultQueue())
                .to(imageExchange()).with("video.metadata.fix.completed");
    }

    @Bean
    public Binding videoMetadataFixResultDlqBinding() {
        return BindingBuilder.bind(videoMetadataFixResultDlq())
                .to(imageDlxExchange()).with("video.metadata.fix.result.dlq");
    }

    @Bean
    public DirectExchange imageDlxExchange() {
        return new DirectExchange("comic.image.dlx");
    }

    // ==================== comic.export ====================

    @Bean
    public DirectExchange exportExchange() {
        return new DirectExchange("comic.export");
    }

    @Bean
    public DirectExchange exportDlxExchange() {
        return new DirectExchange("comic.export.dlx");
    }

    @Bean
    public DirectExchange videoExchange() {
        return new DirectExchange("comic.video");
    }

    @Bean
    public DirectExchange videoDlxExchange() {
        return new DirectExchange("comic.video.dlx");
    }

    @Bean
    public Queue exportStartedResultQueue() {
        return QueueBuilder.durable("export.started.result.queue")
                .deadLetterExchange("comic.export.dlx")
                .deadLetterRoutingKey("export.started.result.dlq")
                .build();
    }

    @Bean
    public Queue exportCompletedResultQueue() {
        return QueueBuilder.durable("export.completed.result.queue")
                .deadLetterExchange("comic.export.dlx")
                .deadLetterRoutingKey("export.completed.result.dlq")
                .build();
    }

    @Bean
    public Queue exportFailedResultQueue() {
        return QueueBuilder.durable("export.failed.result.queue")
                .deadLetterExchange("comic.export.dlx")
                .deadLetterRoutingKey("export.failed.result.dlq")
                .build();
    }

    @Bean
    public Queue exportStartedResultDlq() {
        return QueueBuilder.durable("export.started.result.dlq").build();
    }

    @Bean
    public Queue exportCompletedResultDlq() {
        return QueueBuilder.durable("export.completed.result.dlq").build();
    }

    @Bean
    public Queue exportFailedResultDlq() {
        return QueueBuilder.durable("export.failed.result.dlq").build();
    }

    @Bean
    public Binding exportStartedResultBinding() {
        return BindingBuilder.bind(exportStartedResultQueue())
                .to(exportExchange()).with("task.started");
    }

    @Bean
    public Binding exportCompletedResultBinding() {
        return BindingBuilder.bind(exportCompletedResultQueue())
                .to(exportExchange()).with("task.completed");
    }

    @Bean
    public Binding exportFailedResultBinding() {
        return BindingBuilder.bind(exportFailedResultQueue())
                .to(exportExchange()).with("task.failed");
    }

    @Bean
    public Binding exportStartedResultDlqBinding() {
        return BindingBuilder.bind(exportStartedResultDlq())
                .to(exportDlxExchange()).with("export.started.result.dlq");
    }

    @Bean
    public Binding exportCompletedResultDlqBinding() {
        return BindingBuilder.bind(exportCompletedResultDlq())
                .to(exportDlxExchange()).with("export.completed.result.dlq");
    }

    @Bean
    public Binding exportFailedResultDlqBinding() {
        return BindingBuilder.bind(exportFailedResultDlq())
                .to(exportDlxExchange()).with("export.failed.result.dlq");
    }

    @Bean
    public Queue metadataRefreshQueue() {
        return QueueBuilder.durable("metadata.refresh.queue")
                .deadLetterExchange("comic.export.dlx")
                .deadLetterRoutingKey("metadata.refresh.dlq")
                .build();
    }

    @Bean
    public Queue metadataRefreshDlq() {
        return QueueBuilder.durable("metadata.refresh.dlq").build();
    }

    @Bean
    public Binding metadataRefreshBinding() {
        return BindingBuilder.bind(metadataRefreshQueue())
                .to(exportExchange()).with("metadata.refresh.requested");
    }

    @Bean
    public Binding metadataRefreshDlqBinding() {
        return BindingBuilder.bind(metadataRefreshDlq())
                .to(exportDlxExchange()).with("metadata.refresh.dlq");
    }

    // ==================== comic.video 视频转码结果 ====================

    @Bean
    public Queue videoTranscodeCompletedQueue() {
        return QueueBuilder.durable("video.transcode.completed.queue")
                .deadLetterExchange("comic.video.dlx")
                .deadLetterRoutingKey("video.transcode.completed.dlq")
                .build();
    }

    @Bean
    public Queue videoTranscodeCompletedDlq() {
        return QueueBuilder.durable("video.transcode.completed.dlq").build();
    }

    @Bean
    public Queue videoTranscodeFailedQueue() {
        return QueueBuilder.durable("video.transcode.failed.queue")
                .deadLetterExchange("comic.video.dlx")
                .deadLetterRoutingKey("video.transcode.failed.dlq")
                .build();
    }

    @Bean
    public Queue videoTranscodeFailedDlq() {
        return QueueBuilder.durable("video.transcode.failed.dlq").build();
    }

    @Bean
    public Binding videoTranscodeCompletedBinding() {
        return BindingBuilder.bind(videoTranscodeCompletedQueue())
                .to(videoExchange()).with("video.transcode.completed");
    }

    @Bean
    public Binding videoTranscodeFailedBinding() {
        return BindingBuilder.bind(videoTranscodeFailedQueue())
                .to(videoExchange()).with("video.transcode.failed");
    }

    @Bean
    public Binding videoTranscodeCompletedDlqBinding() {
        return BindingBuilder.bind(videoTranscodeCompletedDlq())
                .to(videoDlxExchange()).with("video.transcode.completed.dlq");
    }

    @Bean
    public Binding videoTranscodeFailedDlqBinding() {
        return BindingBuilder.bind(videoTranscodeFailedDlq())
                .to(videoDlxExchange()).with("video.transcode.failed.dlq");
    }

    // ==================== comic.recovery ====================

    @Bean
    public DirectExchange recoveryExchange() {
        return new DirectExchange("comic.recovery");
    }

    @Bean
    public DirectExchange recoveryDlxExchange() {
        return new DirectExchange("comic.recovery.dlx");
    }

    @Bean
    public Queue recoveryResultQueue() {
        return QueueBuilder.durable("recovery.result.queue")
                .deadLetterExchange("comic.recovery.dlx")
                .deadLetterRoutingKey("recovery.result.dlq")
                .build();
    }

    @Bean
    public Queue recoveryResultDlq() {
        return QueueBuilder.durable("recovery.result.dlq").build();
    }

    @Bean
    public Binding recoveryProgressBinding() {
        return BindingBuilder.bind(recoveryResultQueue())
                .to(recoveryExchange()).with("recovery.progress");
    }

    @Bean
    public Binding recoveryCompletedBinding() {
        return BindingBuilder.bind(recoveryResultQueue())
                .to(recoveryExchange()).with("recovery.completed");
    }

    @Bean
    public Binding recoveryFailedBinding() {
        return BindingBuilder.bind(recoveryResultQueue())
                .to(recoveryExchange()).with("recovery.failed");
    }

    @Bean
    public Binding recoveryResultDlqBinding() {
        return BindingBuilder.bind(recoveryResultDlq())
                .to(recoveryDlxExchange()).with("recovery.result.dlq");
    }

    // ==================== comic.scan 目录扫描 ====================

    @Bean
    public DirectExchange scanExchange() {
        return new DirectExchange("comic.scan");
    }

    @Bean
    public DirectExchange scanDlxExchange() {
        return new DirectExchange("comic.scan.dlx");
    }

    @Bean
    public Queue scanResultQueue() {
        return QueueBuilder.durable("scan.result.queue")
                .deadLetterExchange("comic.scan.dlx")
                .deadLetterRoutingKey("scan.result.dlq")
                .build();
    }

    @Bean
    public Queue scanResultDlq() {
        return QueueBuilder.durable("scan.result.dlq").build();
    }

    @Bean
    public Binding scanCompletedBinding() {
        return BindingBuilder.bind(scanResultQueue())
                .to(scanExchange()).with("scan.completed");
    }

    @Bean
    public Binding scanFailedBinding() {
        return BindingBuilder.bind(scanResultQueue())
                .to(scanExchange()).with("scan.failed");
    }

    @Bean
    public Binding scanResultDlqBinding() {
        return BindingBuilder.bind(scanResultDlq())
                .to(scanDlxExchange()).with("scan.result.dlq");
    }

    // ==================== comic.management 管理命令结果 ====================

    @Bean
    public DirectExchange managementExchange() {
        return new DirectExchange("comic.management");
    }

    @Bean
    public DirectExchange managementDlxExchange() {
        return new DirectExchange("comic.management.dlx");
    }

    @Bean
    public Queue managementResultQueue() {
        return QueueBuilder.durable("management.result.queue")
                .deadLetterExchange("comic.management.dlx")
                .deadLetterRoutingKey("management.result.dlq")
                .build();
    }

    @Bean
    public Queue managementResultDlq() {
        return QueueBuilder.durable("management.result.dlq").build();
    }

    @Bean
    public Binding managementCompletedBinding() {
        return BindingBuilder.bind(managementResultQueue())
                .to(managementExchange()).with("command.completed");
    }

    @Bean
    public Binding managementFailedBinding() {
        return BindingBuilder.bind(managementResultQueue())
                .to(managementExchange()).with("command.failed");
    }

    @Bean
    public Binding managementProgressBinding() {
        return BindingBuilder.bind(managementResultQueue())
                .to(managementExchange()).with("command.progress");
    }

    @Bean
    public Binding managementResultDlqBinding() {
        return BindingBuilder.bind(managementResultDlq())
                .to(managementDlxExchange()).with("management.result.dlq");
    }
}
