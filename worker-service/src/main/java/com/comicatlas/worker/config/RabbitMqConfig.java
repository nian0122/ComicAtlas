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
}
