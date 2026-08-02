package com.comicatlas.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.*;
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
    public DirectExchange importExchange() { return new DirectExchange("comic.import"); }

    @Bean
    public DirectExchange importDlxExchange() { return new DirectExchange("comic.import.dlx"); }

    @Bean
    public DirectExchange imageExchange() { return new DirectExchange("comic.image"); }

    @Bean
    public DirectExchange imageDlxExchange() { return new DirectExchange("comic.image.dlx"); }

    @Bean
    public DirectExchange taskExchange() { return new DirectExchange("comic.task"); }

    @Bean
    public DirectExchange deleteExchange() { return new DirectExchange("comic.delete"); }

    @Bean
    public DirectExchange deleteDlxExchange() { return new DirectExchange("comic.delete.dlx"); }

    @Bean
    public DirectExchange exportExchange() { return new DirectExchange("comic.export"); }

    @Bean
    public DirectExchange exportDlxExchange() { return new DirectExchange("comic.export.dlx"); }

    @Bean
    public DirectExchange videoExchange() { return new DirectExchange("comic.video"); }

    @Bean
    public DirectExchange videoDlxExchange() { return new DirectExchange("comic.video.dlx"); }

    // ===== Queues =====

    @Bean
    public Queue importTaskQueue() {
        return QueueBuilder.durable("import.task.queue")
                .deadLetterExchange("comic.import.dlx")
                .deadLetterRoutingKey("import.task.dlq")
                .build();
    }

    @Bean
    public Queue importTaskDlq() {
        return QueueBuilder.durable("import.task.dlq").build();
    }

    @Bean
    public Queue cancelTaskQueue() {
        return QueueBuilder.durable("cancel.task.queue").build();
    }

    @Bean
    public Queue lqGenerateQueue() {
        return QueueBuilder.durable("lq.generate.queue")
                .deadLetterExchange("comic.image.dlx")
                .deadLetterRoutingKey("lq.generate.dlq")
                .build();
    }

    @Bean
    public Queue lqGenerateDlq() {
        return QueueBuilder.durable("lq.generate.dlq").build();
    }

    @Bean
    public Queue deleteTaskQueue() {
        return QueueBuilder.durable("delete.task.queue")
                .deadLetterExchange("comic.delete.dlx")
                .deadLetterRoutingKey("delete.task.dlq")
                .build();
    }

    @Bean
    public Queue deleteTaskDlq() {
        return QueueBuilder.durable("delete.task.dlq").build();
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
    public Queue exportTaskQueue() {
        return QueueBuilder.durable("export.task.queue")
                .deadLetterExchange("comic.export.dlx")
                .deadLetterRoutingKey("export.task.dlq")
                .build();
    }

    @Bean
    public Queue exportTaskDlq() {
        return QueueBuilder.durable("export.task.dlq").build();
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
    public Queue videoMetadataFixQueue() {
        return QueueBuilder.durable("video.metadata.fix.queue")
                .deadLetterExchange("comic.image.dlx")
                .deadLetterRoutingKey("video.metadata.fix.dlq")
                .build();
    }

    @Bean
    public Queue videoMetadataFixDlq() {
        return QueueBuilder.durable("video.metadata.fix.dlq").build();
    }

    @Bean
    public Queue videoTranscodeQueue() {
        return QueueBuilder.durable("video.transcode.queue")
                .deadLetterExchange("comic.video.dlx")
                .deadLetterRoutingKey("video.transcode.dlq")
                .build();
    }

    @Bean
    public Queue videoTranscodeDlq() {
        return QueueBuilder.durable("video.transcode.dlq").build();
    }

    // ===== Bindings =====

    @Bean
    public Binding importTaskBinding() {
        return BindingBuilder.bind(importTaskQueue())
                .to(importExchange()).with("task.created");
    }

    @Bean
    public Binding importTaskDlqBinding() {
        return BindingBuilder.bind(importTaskDlq())
                .to(importDlxExchange()).with("import.task.dlq");
    }

    @Bean
    public Binding cancelTaskBinding() {
        return BindingBuilder.bind(cancelTaskQueue())
                .to(taskExchange()).with("cancel.requested");
    }

    @Bean
    public Binding lqGenerateBinding() {
        return BindingBuilder.bind(lqGenerateQueue())
                .to(imageExchange()).with("lq.generate");
    }

    @Bean
    public Binding lqGenerateDlqBinding() {
        return BindingBuilder.bind(lqGenerateDlq())
                .to(imageDlxExchange()).with("lq.generate.dlq");
    }

    @Bean
    public Binding deleteTaskBinding() {
        return BindingBuilder.bind(deleteTaskQueue())
                .to(deleteExchange()).with("delete.requested");
    }

    @Bean
    public Binding deleteTaskDlqBinding() {
        return BindingBuilder.bind(deleteTaskDlq())
                .to(deleteDlxExchange()).with("delete.task.dlq");
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
    public Binding exportTaskBinding() {
        return BindingBuilder.bind(exportTaskQueue())
                .to(exportExchange()).with("task.created");
    }

    @Bean
    public Binding exportTaskDlqBinding() {
        return BindingBuilder.bind(exportTaskDlq())
                .to(exportDlxExchange()).with("export.task.dlq");
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

    @Bean
    public Binding videoMetadataFixBinding() {
        return BindingBuilder.bind(videoMetadataFixQueue())
                .to(imageExchange()).with("video.metadata.fix.requested");
    }

    @Bean
    public Binding videoMetadataFixDlqBinding() {
        return BindingBuilder.bind(videoMetadataFixDlq())
                .to(imageDlxExchange()).with("video.metadata.fix.dlq");
    }

    @Bean
    public Binding videoTranscodeBinding() {
        return BindingBuilder.bind(videoTranscodeQueue())
                .to(videoExchange()).with("video.transcode.requested");
    }

    @Bean
    public Binding videoTranscodeDlqBinding() {
        return BindingBuilder.bind(videoTranscodeDlq())
                .to(videoDlxExchange()).with("video.transcode.dlq");
    }

    // ==================== comic.recovery ====================

    @Bean
    public DirectExchange recoveryExchange() { return new DirectExchange("comic.recovery"); }

    @Bean
    public DirectExchange recoveryDlxExchange() { return new DirectExchange("comic.recovery.dlx"); }

    @Bean
    public Queue recoveryTaskQueue() {
        return QueueBuilder.durable("recovery.task.queue")
                .deadLetterExchange("comic.recovery.dlx")
                .deadLetterRoutingKey("recovery.task.dlq")
                .build();
    }

    @Bean
    public Queue recoveryTaskDlq() {
        return QueueBuilder.durable("recovery.task.dlq").build();
    }

    @Bean
    public Binding recoveryTaskBinding() {
        return BindingBuilder.bind(recoveryTaskQueue())
                .to(recoveryExchange()).with("recovery.requested");
    }

    @Bean
    public Binding recoveryTaskDlqBinding() {
        return BindingBuilder.bind(recoveryTaskDlq())
                .to(recoveryDlxExchange()).with("recovery.task.dlq");
    }

    // ==================== comic.scan 目录扫描 ====================

    @Bean
    public DirectExchange scanExchange() { return new DirectExchange("comic.scan"); }

    @Bean
    public DirectExchange scanDlxExchange() { return new DirectExchange("comic.scan.dlx"); }

    @Bean
    public Queue scanTaskQueue() {
        return QueueBuilder.durable("scan.task.queue")
                .deadLetterExchange("comic.scan.dlx")
                .deadLetterRoutingKey("scan.task.dlq")
                .build();
    }

    @Bean
    public Queue scanTaskDlq() {
        return QueueBuilder.durable("scan.task.dlq").build();
    }

    @Bean
    public Binding scanTaskBinding() {
        return BindingBuilder.bind(scanTaskQueue())
                .to(scanExchange()).with("scan.requested");
    }

    @Bean
    public Binding scanTaskDlqBinding() {
        return BindingBuilder.bind(scanTaskDlq())
                .to(scanDlxExchange()).with("scan.task.dlq");
    }
}
