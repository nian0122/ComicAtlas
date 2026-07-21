package com.comicatlas.api.config;

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
    public DirectExchange imageDlxExchange() {
        return new DirectExchange("comic.image.dlx");
    }
}
