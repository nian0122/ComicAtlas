package com.comicatlas.worker.config;

import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.command.HqDeleteCommandHandler;
import com.comicatlas.worker.command.LqCommandHandler;
import com.comicatlas.worker.command.MediaUploadCommandHandler;
import com.comicatlas.worker.command.MetadataRefreshCommandHandler;
import com.comicatlas.worker.command.PurgeCommandHandler;
import com.comicatlas.worker.command.RestoreCommandHandler;
import com.comicatlas.worker.command.TranscodeCommandHandler;
import com.comicatlas.worker.command.TrashCommandHandler;
import com.comicatlas.worker.event.HqDeleteHandler;
import com.comicatlas.worker.event.ManagementCommandDispatcher;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.comicatlas.worker.storage.StorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * RabbitMQ 拓扑契约测试 — 旧完整删除（comic.delete）拓扑已移除，HQ_DELETE 与
 * ManagementCommandDispatcher 链路保持可用。
 */
@DisplayName("RabbitMqConfigTopologyTest — 旧完整删除拓扑移除契约")
class RabbitMqConfigTopologyTest {

    private static final String LEGACY_DELETE_HANDLER = "com.comicatlas.worker.event.DeleteHandler";
    private static final String LEGACY_DELETE_TASK_QUEUE = "delete.task.queue";
    private static final String LEGACY_DELETE_TASK_DLQ = "delete.task.dlq";

    @Test
    @DisplayName("配置不再声明旧 delete exchange/queue/DLQ/binding bean")
    void legacyDeleteTopologyBeansShouldBeAbsent() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(RabbitMqConfig.class)) {
            assertThat(ctx.containsBean("deleteExchange")).as("旧 delete exchange").isFalse();
            assertThat(ctx.containsBean("deleteDlxExchange")).as("旧 delete DLX").isFalse();
            assertThat(ctx.containsBean("deleteTaskQueue")).as("旧 delete task queue").isFalse();
            assertThat(ctx.containsBean("deleteTaskDlq")).as("旧 delete task DLQ").isFalse();
            assertThat(ctx.containsBean("deleteTaskBinding")).as("旧 delete binding").isFalse();
            assertThat(ctx.containsBean("deleteTaskDlqBinding")).as("旧 delete DLQ binding").isFalse();
        }
    }

    @Test
    @DisplayName("配置不再声明旧 video.metadata.fix queue/DLQ/binding bean（F6-10 下线）")
    void legacyVideoMetadataFixBeansShouldBeAbsent() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(RabbitMqConfig.class)) {
            assertThat(ctx.containsBean("videoMetadataFixQueue")).as("旧 video metadata fix queue").isFalse();
            assertThat(ctx.containsBean("videoMetadataFixDlq")).as("旧 video metadata fix DLQ").isFalse();
            assertThat(ctx.containsBean("videoMetadataFixBinding")).as("旧 video metadata fix binding").isFalse();
            assertThat(ctx.containsBean("videoMetadataFixDlqBinding")).as("旧 video metadata fix DLQ binding").isFalse();
        }
    }

    @Test
    @DisplayName("metadata refresh 拓扑 bean 仍存在（唯一用户维护链）")
    void metadataRefreshTopologyBeansShouldRemain() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(RabbitMqConfig.class)) {
            assertThat(ctx.containsBean("metadataRefreshQueue")).as("METADATA_REFRESH queue").isTrue();
            assertThat(ctx.containsBean("metadataRefreshDlq")).as("METADATA_REFRESH DLQ").isTrue();
            assertThat(ctx.containsBean("metadataRefreshBinding")).as("METADATA_REFRESH binding").isTrue();
            assertThat(ctx.containsBean("metadataRefreshDlqBinding")).as("METADATA_REFRESH DLQ binding").isTrue();
        }
    }

    @Test
    @DisplayName("不存在绑定到 delete.task.queue / delete.task.dlq 的 Binding")
    void noBindingShouldReferenceLegacyDeleteQueues() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(RabbitMqConfig.class)) {
            Map<String, Binding> bindings = ctx.getBeansOfType(Binding.class);
            assertThat(bindings).as("RabbitMqConfig 应仍声明 binding bean").isNotEmpty();
            assertThat(bindings.values())
                    .as("不应存在旧完整删除队列绑定")
                    .noneMatch(b -> LEGACY_DELETE_TASK_QUEUE.equals(b.getDestination()))
                    .noneMatch(b -> LEGACY_DELETE_TASK_DLQ.equals(b.getDestination()));
        }
    }

    @Test
    @DisplayName("HQ_DELETE 拓扑 bean 仍存在")
    void hqDeleteTopologyBeansShouldRemain() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(RabbitMqConfig.class)) {
            assertThat(ctx.containsBean("hqDeleteQueue")).as("HQ_DELETE queue").isTrue();
            assertThat(ctx.containsBean("hqDeleteDlq")).as("HQ_DELETE DLQ").isTrue();
            assertThat(ctx.containsBean("hqDeleteBinding")).as("HQ_DELETE binding").isTrue();
            assertThat(ctx.containsBean("hqDeleteDlqBinding")).as("HQ_DELETE DLQ binding").isTrue();
        }
    }

    @Test
    @DisplayName("DeleteHandler 类不再存在，HqDeleteHandler/ManagementCommandDispatcher 可装配")
    void legacyDeleteHandlerRemovedWhileActiveHandlersRemainWirable() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(LEGACY_DELETE_HANDLER),
                "旧 DeleteHandler 应已从 Worker 移除");

        try (AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(HandlerWiringTestConfig.class)) {
            assertThat(ctx.getBean(HqDeleteHandler.class)).isNotNull();
            assertThat(ctx.getBean(ManagementCommandDispatcher.class)).isNotNull();
        }
    }

    @Test
    @DisplayName("HqDeleteHandler 监听 HQ_DELETE，ManagementCommandDispatcher 监听 MANAGEMENT_COMMAND")
    void activeHandlersListenOnTheirQueues() {
        assertThat(listenQueues(HqDeleteHandler.class)).containsExactly("hq.delete.queue");
        assertThat(listenQueues(ManagementCommandDispatcher.class)).containsExactly("management.command.queue");
    }

    private static String[] listenQueues(Class<?> handlerClass) {
        for (Method m : handlerClass.getMethods()) {
            RabbitListener listener = m.getAnnotation(RabbitListener.class);
            if (listener != null) {
                return listener.queues();
            }
        }
        return new String[0];
    }

    /**
     * 装配验证配置：用 mock 依赖注入仍保留的消费者，验证其在 Spring 上下文中可正常装配。
     */
    @Configuration
    static class HandlerWiringTestConfig {

        @Bean
        HqDeleteHandler hqDeleteHandler() {
            return new HqDeleteHandler(
                    mock(StorageProperties.class), mock(ExportMediaMapper.class),
                    mock(RabbitTemplate.class), new MqConsumerSupport());
        }

        @Bean
        ManagementCommandDispatcher managementCommandDispatcher() {
            return new ManagementCommandDispatcher(
                    mock(LqCommandHandler.class), mock(HqDeleteCommandHandler.class),
                    mock(TranscodeCommandHandler.class), mock(TrashCommandHandler.class),
                    mock(RestoreCommandHandler.class), mock(PurgeCommandHandler.class),
                    mock(MediaUploadCommandHandler.class), mock(MetadataRefreshCommandHandler.class),
                    mock(ManagementCommandPublisher.class),
                    new MqConsumerSupport());
        }
    }
}
