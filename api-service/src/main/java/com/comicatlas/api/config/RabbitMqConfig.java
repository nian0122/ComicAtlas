package com.comicatlas.api.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Bean
    public DirectExchange importExchange() {
        return new DirectExchange("comic.import");
    }

    @Bean
    public Queue importResultQueue() {
        return QueueBuilder.durable("import.result.queue")
                .deadLetterExchange("comic.import.dlx")
                .build();
    }

    @Bean
    public Binding importResultBinding() {
        return BindingBuilder.bind(importResultQueue())
                .to(importExchange()).with("task.completed");
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
    public DirectExchange imageExchange() {
        return new DirectExchange("comic.image");
    }

    @Bean
    public Queue lqResultQueue() {
        return QueueBuilder.durable("lq.result.queue").build();
    }

    @Bean
    public Binding lqResultBinding() {
        return BindingBuilder.bind(lqResultQueue())
                .to(imageExchange()).with("lq.completed");
    }

    @Bean
    public DirectExchange deleteExchange() {
        return new DirectExchange("comic.delete");
    }

    @Bean
    public Queue deleteResultQueue() {
        return QueueBuilder.durable("delete.result.queue").build();
    }

    @Bean
    public Binding deleteResultBinding() {
        return BindingBuilder.bind(deleteResultQueue())
                .to(deleteExchange()).with("delete.completed");
    }
}
