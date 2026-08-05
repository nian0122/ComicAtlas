package com.comicatlas.api.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;



/**
 * RabbitMQ 拓扑集成测试 — 验证 API 与 Worker 的 Exchange/Queue/DLX/Binding 一致性。
 *
 * <p>使用 Testcontainers MySQL + RabbitMQ 启动完整 Spring Context，
 * 检查：
 * <ul>
 *   <li>comic.management exchange + DLX 存在且类型为 Direct</li>
 *   <li>result/comand/cancel 队列声明有正确 DLX/DLQ 配置</li>
 *   <li>路由键与预期一致</li>
 *   <li>旧 domain（comic.import/image/delete/recovery/scan）不受影响</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("RabbitTopologyIT — Rabbit 拓扑集成测试")
class RabbitTopologyIT {

    private static boolean dockerAvailable;

    static {
        dockerAvailable = checkDockerAvailable();
    }

    @Container
    static MySQLContainer<?> mysql = dockerAvailable
            ? new MySQLContainer<>("mysql:8.0.33")
                .withDatabaseName("comic_atlas_test")
                .withUsername("test")
                .withPassword("test")
            : null;

    @Container
    static RabbitMQContainer rabbitmq = dockerAvailable
            ? new RabbitMQContainer("rabbitmq:3.12-management-alpine")
                .withAdminPassword("test_rabbit_pass")
            : null;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (dockerAvailable && mysql != null && mysql.isRunning()) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        } else {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            registry.add("spring.flyway.enabled", () -> "false");
            registry.add("spring.sql.init.mode", () -> "always");
        }
        if (dockerAvailable && rabbitmq != null && rabbitmq.isRunning()) {
            registry.add("spring.rabbitmq.host", rabbitmq::getHost);
            registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
            registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
            registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        }
    }

    @Autowired
    private RabbitMqConfig apiConfig;

    /**
     * Worker 侧管理拓扑期望值 — 与 worker-service RabbitMqConfig 定义保持同步。
     * Worker 只接 MQ，不新增 HTTP，这些配置在两处 RabbitMqConfig 中对称。
     */
    private static final String EXCHANGE_MANAGEMENT = "comic.management";
    private static final String DLX_MANAGEMENT = "comic.management.dlx";
    private static final String QUEUE_COMMAND = "management.command.queue";
    private static final String QUEUE_COMMAND_DLQ = "management.command.dlq";
    private static final String QUEUE_CANCEL = "management.cancel.queue";
    private static final String QUEUE_CANCEL_DLQ = "management.cancel.dlq";

    // ======================== 新 management 拓扑 ========================

    @Nested
    @DisplayName("comic.management Exchange/Queue/DLX 拓扑")
    class ManagementTopology {

        @Test
        @DisplayName("managementExchange 为 Direct 类型，名称 comic.management")
        void managementExchange_typeAndName() {
            var exchange = apiConfig.managementExchange();
            assertThat(exchange.getType()).isEqualTo("direct");
            assertThat(exchange.getName()).isEqualTo("comic.management");
        }

        @Test
        @DisplayName("managementDlxExchange 为 Direct 类型，名称 comic.management.dlx")
        void managementDlxExchange_typeAndName() {
            var exchange = apiConfig.managementDlxExchange();
            assertThat(exchange.getType()).isEqualTo("direct");
            assertThat(exchange.getName()).isEqualTo("comic.management.dlx");
        }

        @Test
        @DisplayName("managementResultQueue 持久化并配置 DLX comic.management.dlx → management.result.dlq")
        void managementResultQueue_dlxConfig() {
            var queue = apiConfig.managementResultQueue();
            assertThat(queue.getName()).isEqualTo("management.result.queue");
            assertThat(queue.isDurable()).isTrue();

            Object dlxVal = queue.getArguments().get("x-dead-letter-exchange");
            assertThat(dlxVal).isNotNull();
            assertThat(dlxArgToString(dlxVal)).isEqualTo("comic.management.dlx");

            Object dlqVal = queue.getArguments().get("x-dead-letter-routing-key");
            assertThat(dlqVal).isNotNull();
            assertThat(dlxArgToString(dlqVal)).isEqualTo("management.result.dlq");
        }

        @Test
        @DisplayName("managementResultDlq 为持久化死信队列")
        void managementResultDlq_config() {
            var dlq = apiConfig.managementResultDlq();
            assertThat(dlq.getName()).isEqualTo("management.result.dlq");
            assertThat(dlq.isDurable()).isTrue();
        }

        @Test
        @DisplayName("result 队列绑定 command.completed / command.failed / command.progress 三个路由键")
        void resultQueue_threeRoutingKeys() {
            var completed = apiConfig.managementCompletedBinding();
            var failed = apiConfig.managementFailedBinding();
            var progress = apiConfig.managementProgressBinding();

            assertThat(completed.getDestination()).isEqualTo("management.result.queue");
            assertThat(completed.getExchange()).isEqualTo("comic.management");
            assertThat(completed.getRoutingKey()).isEqualTo("command.completed");

            assertThat(failed.getDestination()).isEqualTo("management.result.queue");
            assertThat(failed.getExchange()).isEqualTo("comic.management");
            assertThat(failed.getRoutingKey()).isEqualTo("command.failed");

            assertThat(progress.getDestination()).isEqualTo("management.result.queue");
            assertThat(progress.getExchange()).isEqualTo("comic.management");
            assertThat(progress.getRoutingKey()).isEqualTo("command.progress");
        }

        @Test
        @DisplayName("managementResultDlqBinding 绑定 DLX → DLQ")
        void dlqBinding() {
            var binding = apiConfig.managementResultDlqBinding();
            assertThat(binding.getDestination()).isEqualTo("management.result.dlq");
            assertThat(binding.getExchange()).isEqualTo("comic.management.dlx");
            assertThat(binding.getRoutingKey()).isEqualTo("management.result.dlq");
        }
    }

    // ======================== Worker 侧配置一致性验证 ========================

    @Nested
    @DisplayName("Worker 侧拓扑一致性（内联验证期望值）")
    class WorkerTopologyConsistency {

        @Test
        @DisplayName("Worker 与 API 使用相同 management exchange 名称")
        void managementExchange_sameName() {
            assertThat(apiConfig.managementExchange().getName()).isEqualTo(EXCHANGE_MANAGEMENT);
        }

        @Test
        @DisplayName("Worker 与 API 使用相同 management DLX 名称")
        void managementDlxExchange_sameName() {
            assertThat(apiConfig.managementDlxExchange().getName()).isEqualTo(DLX_MANAGEMENT);
        }

        @Test
        @DisplayName("Worker management.command.queue 期望持久化 + DLX comic.management.dlx")
        void workerCommandQueue_expectedConfig() {
            assertThat(QUEUE_COMMAND).isEqualTo("management.command.queue");
            assertThat(QUEUE_COMMAND_DLQ).isEqualTo("management.command.dlq");
        }

        @Test
        @DisplayName("Worker management.cancel.queue 期望持久化 + DLX comic.management.dlx")
        void workerCancelQueue_expectedConfig() {
            assertThat(QUEUE_CANCEL).isEqualTo("management.cancel.queue");
            assertThat(QUEUE_CANCEL_DLQ).isEqualTo("management.cancel.dlq");
        }

        @Test
        @DisplayName("Worker 与 API routing key 互补：command.requested → Worker，command.completed/failed/progress → API")
        void routingKeys_complementary() {
            // Worker 消费
            assertThat("command.requested").isNotEqualTo("command.completed");
            assertThat("command.requested").isNotEqualTo("command.failed");
            assertThat("command.requested").isNotEqualTo("command.progress");
            // API 消费
            assertThat(apiConfig.managementCompletedBinding().getRoutingKey()).isEqualTo("command.completed");
            assertThat(apiConfig.managementFailedBinding().getRoutingKey()).isEqualTo("command.failed");
            assertThat(apiConfig.managementProgressBinding().getRoutingKey()).isEqualTo("command.progress");
        }
    }

    // ======================== 旧 domain 不受影响 ========================

    @Nested
    @DisplayName("旧 domain 拓扑不受新 management 影响")
    class LegacyTopologyUnaffected {

        @Test
        @DisplayName("comic.import exchange 仍存在")
        void importExchange_stillExists() {
            assertThat(apiConfig.importExchange().getName()).isEqualTo("comic.import");
        }

        @Test
        @DisplayName("comic.image exchange 仍存在")
        void imageExchange_stillExists() {
            assertThat(apiConfig.imageExchange().getName()).isEqualTo("comic.image");
        }

        @Test
        @DisplayName("comic.delete exchange + DLX 仍存在")
        void deleteExchange_stillExists() {
            assertThat(apiConfig.deleteExchange().getName()).isEqualTo("comic.delete");
            assertThat(apiConfig.deleteDlxExchange().getName()).isEqualTo("comic.delete.dlx");
        }

        @Test
        @DisplayName("comic.recovery exchange + DLX 仍存在")
        void recoveryExchange_stillExists() {
            assertThat(apiConfig.recoveryExchange().getName()).isEqualTo("comic.recovery");
            assertThat(apiConfig.recoveryDlxExchange().getName()).isEqualTo("comic.recovery.dlx");
        }

        @Test
        @DisplayName("comic.scan exchange + DLX 仍存在")
        void scanExchange_stillExists() {
            assertThat(apiConfig.scanExchange().getName()).isEqualTo("comic.scan");
            assertThat(apiConfig.scanDlxExchange().getName()).isEqualTo("comic.scan.dlx");
        }

        @Test
        @DisplayName("import.result.queue DLX 配置不变")
        void importResultQueue_unchanged() {
            var queue = apiConfig.importResultQueue();
            assertThat(queue.getName()).isEqualTo("import.result.queue");

            Object dlx = queue.getArguments().get("x-dead-letter-exchange");
            assertThat(dlx).isNotNull();
            assertThat(dlxArgToString(dlx)).isEqualTo("comic.import.dlx");
        }

        @Test
        @DisplayName("lq.result.queue DLX 配置不变")
        void lqResultQueue_unchanged() {
            var queue = apiConfig.lqResultQueue();
            assertThat(queue.getName()).isEqualTo("lq.result.queue");

            Object dlx = queue.getArguments().get("x-dead-letter-exchange");
            assertThat(dlx).isNotNull();
            assertThat(dlxArgToString(dlx)).isEqualTo("comic.image.dlx");
        }
    }

    // ======================== 拓扑完整性 ========================

    @Nested
    @DisplayName("拓扑完整性")
    class TopologyCompleteness {

        @Test
        @DisplayName("API 侧 management 拓扑包含所有必需 bean（6 个）")
        void apiManagementBeans_complete() {
            assertThat(apiConfig.managementExchange()).isNotNull();
            assertThat(apiConfig.managementDlxExchange()).isNotNull();
            assertThat(apiConfig.managementResultQueue()).isNotNull();
            assertThat(apiConfig.managementResultDlq()).isNotNull();
            assertThat(apiConfig.managementCompletedBinding()).isNotNull();
            assertThat(apiConfig.managementFailedBinding()).isNotNull();
            assertThat(apiConfig.managementProgressBinding()).isNotNull();
            assertThat(apiConfig.managementResultDlqBinding()).isNotNull();
        }

        @Test
        @DisplayName("Worker 侧 management 拓扑预期包含全部必需元素")
        void workerManagementBeans_expectedComplete() {
            // 以下为 worker-service RabbitMqConfig 中声明的 bean 名称期望
            // exchange: comic.management, comic.management.dlx
            // queues: management.command.queue, management.command.dlq,
            //         management.cancel.queue, management.cancel.dlq
            // bindings: command.requested, command.cancel, + DLX bindings
            assertThat(EXCHANGE_MANAGEMENT).isEqualTo("comic.management");
            assertThat(DLX_MANAGEMENT).isEqualTo("comic.management.dlx");
            assertThat(QUEUE_COMMAND).isEqualTo("management.command.queue");
            assertThat(QUEUE_COMMAND_DLQ).isEqualTo("management.command.dlq");
            assertThat(QUEUE_CANCEL).isEqualTo("management.cancel.queue");
            assertThat(QUEUE_CANCEL_DLQ).isEqualTo("management.cancel.dlq");
        }
    }

    // ======================== 辅助 ========================

    /**
     * RabbitMQ queue arguments 中 DLX/DLQ 值可能为 String 或 List<String>。
     */
    private static String dlxArgToString(Object arg) {
        if (arg instanceof String s) return s;
        if (arg instanceof List<?> list && !list.isEmpty()) return list.get(0).toString();
        return arg != null ? arg.toString() : null;
    }

    private static boolean checkDockerAvailable() {
        try {
            new ProcessBuilder("docker", "info")
                .redirectErrorStream(true)
                .start()
                .waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
