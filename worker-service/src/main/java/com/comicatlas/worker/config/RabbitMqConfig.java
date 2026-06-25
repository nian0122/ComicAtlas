package com.comicatlas.worker.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Bean
    public DirectExchange importDlxExchange() {
        return new DirectExchange("comic.import.dlx");
    }

    @Bean
    public Queue importTaskDlq() {
        return QueueBuilder.durable("import.task.dlq").build();
    }

    @Bean
    public Binding importTaskDlqBinding() {
        return BindingBuilder.bind(importTaskDlq())
                .to(importDlxExchange()).with("import.task.dlq");
    }

    @Bean
    public Queue importTaskQueue() {
        return QueueBuilder.durable("import.task.queue")
                .deadLetterExchange("comic.import.dlx")
                .deadLetterRoutingKey("import.task.dlq")
                .build();
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
}
