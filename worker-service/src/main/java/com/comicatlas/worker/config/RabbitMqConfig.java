package com.comicatlas.worker.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    // ===== Exchange 声明（幂等，已存在则复用）=====

    @Bean
    public DirectExchange importExchange() {
        return new DirectExchange("comic.import");
    }

    @Bean
    public DirectExchange importDlxExchange() {
        return new DirectExchange("comic.import.dlx");
    }

    @Bean
    public DirectExchange imageExchange() {
        return new DirectExchange("comic.image");
    }

    @Bean
    public DirectExchange deleteExchange() {
        return new DirectExchange("comic.delete");
    }

    // ===== Queue 声明 =====

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
    public Queue importProcessedQueue() {
        return QueueBuilder.durable("import.processed.queue").build();
    }

    @Bean
    public Queue lqGenerateQueue() {
        return QueueBuilder.durable("lq.generate.queue")
                .deadLetterExchange("comic.image.dlx")
                .build();
    }

    @Bean
    public Queue deleteTaskQueue() {
        return QueueBuilder.durable("delete.task.queue").build();
    }

    // ===== Binding =====

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
    public Binding importProcessedBinding() {
        return BindingBuilder.bind(importProcessedQueue())
                .to(importExchange()).with("task.processed");
    }

    @Bean
    public Binding lqGenerateBinding() {
        return BindingBuilder.bind(lqGenerateQueue())
                .to(imageExchange()).with("lq.generate");
    }

    @Bean
    public Binding deleteTaskBinding() {
        return BindingBuilder.bind(deleteTaskQueue())
                .to(deleteExchange()).with("delete.requested");
    }
}
