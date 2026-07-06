package com.comicatlas.api.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
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
}
