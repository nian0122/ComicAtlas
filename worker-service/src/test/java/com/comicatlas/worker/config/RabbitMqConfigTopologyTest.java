package com.comicatlas.worker.config;

import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.media.hq.HqDeleteCommandHandler;
import com.comicatlas.worker.media.lq.LqCommandHandler;
import com.comicatlas.worker.media.upload.MediaUploadCommandHandler;
import com.comicatlas.worker.media.metadata.command.MetadataRefreshCommandHandler;
import com.comicatlas.worker.recovery.command.PurgeCommandHandler;
import com.comicatlas.worker.recovery.command.RestoreCommandHandler;
import com.comicatlas.worker.media.transcode.TranscodeCommandHandler;
import com.comicatlas.worker.recovery.command.TrashCommandHandler;
import com.comicatlas.worker.task.ManagementCommandDispatcher;
import com.comicatlas.worker.task.ManagementCommandPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * RabbitMQ 拓扑契约测试 — 旧完整删除（comic.delete）与旧 HQ 删除（comic.image.hq.delete）
 * 拓扑均已移除，ManagementCommandDispatcher 统一命令链路保持可用。
 */
@DisplayName("RabbitMqConfigTopologyTest — 遗留 MQ 拓扑移除契约")
class RabbitMqConfigTopologyTest {

    private static final String LEGACY_DELETE_HANDLER = "com.comicatlas.worker.event.DeleteHandler";
    private static final String LEGACY_HQ_DELETE_HANDLER = "com.comicatlas.worker.event.HqDeleteHandler";
    private static final String LEGACY_DELETE_TASK_QUEUE = "delete.task.queue";
    private static final String LEGACY_DELETE_TASK_DLQ = "delete.task.dlq";
    private static final String LEGACY_HQ_DELETE_QUEUE = "hq.delete.queue";
    private static final String LEGACY_HQ_DELETE_DLQ = "hq.delete.dlq";

    @Test
    @DisplayName("配置不再声明旧 delete / 旧 HQ 删除的 queue/DLQ/binding bean")
    void legacyTopologyBeansShouldBeAbsent() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(RabbitMqConfig.class)) {
            assertThat(ctx.containsBean("deleteExchange")).as("旧 delete exchange").isFalse();
            assertThat(ctx.containsBean("deleteDlxExchange")).as("旧 delete DLX").isFalse();
            assertThat(ctx.containsBean("deleteTaskQueue")).as("旧 delete task queue").isFalse();
            assertThat(ctx.containsBean("deleteTaskDlq")).as("旧 delete task DLQ").isFalse();
            assertThat(ctx.containsBean("deleteTaskBinding")).as("旧 delete binding").isFalse();
            assertThat(ctx.containsBean("deleteTaskDlqBinding")).as("旧 delete DLQ binding").isFalse();
            assertThat(ctx.containsBean("hqDeleteQueue")).as("旧 HQ 删除 queue").isFalse();
            assertThat(ctx.containsBean("hqDeleteDlq")).as("旧 HQ 删除 DLQ").isFalse();
            assertThat(ctx.containsBean("hqDeleteBinding")).as("旧 HQ 删除 binding").isFalse();
            assertThat(ctx.containsBean("hqDeleteDlqBinding")).as("旧 HQ 删除 DLQ binding").isFalse();
        }
    }

    @Test
    @DisplayName("不存在绑定到旧 delete / 旧 HQ 删除队列的 Binding")
    void noBindingShouldReferenceLegacyQueues() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(RabbitMqConfig.class)) {
            Map<String, Binding> bindings = ctx.getBeansOfType(Binding.class);
            assertThat(bindings).as("RabbitMqConfig 应仍声明 binding bean").isNotEmpty();
            assertThat(bindings.values())
                    .as("不应存在旧完整删除队列绑定")
                    .noneMatch(b -> LEGACY_DELETE_TASK_QUEUE.equals(b.getDestination()))
                    .noneMatch(b -> LEGACY_DELETE_TASK_DLQ.equals(b.getDestination()));
            assertThat(bindings.values())
                    .as("不应存在旧 HQ 删除队列绑定")
                    .noneMatch(b -> LEGACY_HQ_DELETE_QUEUE.equals(b.getDestination()))
                    .noneMatch(b -> LEGACY_HQ_DELETE_DLQ.equals(b.getDestination()));
        }
    }

    @Test
    @DisplayName("旧 DeleteHandler / HqDeleteHandler 类不再存在，ManagementCommandDispatcher 可装配")
    void legacyHandlersRemovedWhileCommandDispatcherRemainsWirable() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(LEGACY_DELETE_HANDLER),
                "旧 DeleteHandler 应已从 Worker 移除");
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(LEGACY_HQ_DELETE_HANDLER),
                "旧 HqDeleteHandler 应已从 Worker 移除");

        try (AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(HandlerWiringTestConfig.class)) {
            assertThat(ctx.getBean(ManagementCommandDispatcher.class)).isNotNull();
        }
    }

    @Test
    @DisplayName("ManagementCommandDispatcher 监听 MANAGEMENT_COMMAND")
    void commandDispatcherListensOnManagementQueue() {
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
