package com.comicatlas.api.outbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.outbox.entity.InboxReceipt;
import com.comicatlas.api.outbox.entity.OutboxMessage;
import com.comicatlas.api.outbox.mapper.InboxReceiptMapper;
import com.comicatlas.api.outbox.mapper.OutboxMessageMapper;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.dto.OutboxStats;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("OutboxInboxRelay 集成测试")
class OutboxInboxRelayIT {

    private static boolean dockerAvailable;
    static { dockerAvailable = checkDockerAvailable(); }

    @Container static MySQLContainer<?> mysql = dockerAvailable
        ? new MySQLContainer<>("mysql:8.0.33").withDatabaseName("comic_atlas_test").withUsername("test").withPassword("test") : null;
    @Container static RabbitMQContainer rabbitmq = dockerAvailable
        ? new RabbitMQContainer("rabbitmq:3.12-management-alpine").withAdminPassword("test_rabbit_pass") : null;

    @DynamicPropertySource static void configureProperties(DynamicPropertyRegistry registry) {
        if (dockerAvailable && mysql != null && mysql.isRunning()) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        } else {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL");
            registry.add("spring.datasource.username", () -> "sa"); registry.add("spring.datasource.password", () -> "");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            registry.add("spring.flyway.enabled", () -> "false"); registry.add("spring.sql.init.mode", () -> "always");
        }
        if (dockerAvailable && rabbitmq != null && rabbitmq.isRunning()) {
            registry.add("spring.rabbitmq.host", rabbitmq::getHost);
            registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
            registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
            registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        }
        registry.add("outbox.relay.batch-size", () -> "10");
        registry.add("outbox.relay.max-attempts", () -> "10");
        registry.add("outbox.relay.backoff-base", () -> "1");
        registry.add("outbox.relay.backoff-max", () -> "30");
    }

    @Autowired private OutboxService outboxService;
    @Autowired private InboxService inboxService;
    @Autowired private OutboxMessageMapper outboxMapper;
    @Autowired private InboxReceiptMapper inboxMapper;

    @BeforeEach void setUp() {
        inboxMapper.delete(new LambdaQueryWrapper<>());
        outboxMapper.delete(new LambdaQueryWrapper<>());
    }

    private static boolean checkDockerAvailable() {
        try { new ProcessBuilder("docker","info").redirectErrorStream(true).start().waitFor(); return true; }
        catch (Exception e) { return false; }
    }

    private static String sha256(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    // ======================== 事务 Outbox 写入 ========================

    @Test
    @DisplayName("enqueue 将 ComicEvent 写入 outbox_message 表并保持 PENDING 状态")
    void enqueueWritesToOutbox() {
        var event = new ImportTaskCreatedEvent(UUID.randomUUID(), Instant.now(), 1L, 100L, "ZIP", "/test");
        outboxService.enqueue(event, "comic.import", "task.created");

        OutboxMessage msg = outboxMapper.selectById(event.eventId().toString());
        assertThat(msg).isNotNull();
        assertThat(msg.getStatus()).isEqualTo("PENDING");
        assertThat(msg.getExchange()).isEqualTo("comic.import");
        assertThat(msg.getRoutingKey()).isEqualTo("task.created");
        assertThat(msg.getEventType()).isEqualTo("ImportTaskCreatedEvent");
        assertThat(msg.getPayload()).isNotEmpty();
    }

    // ======================== Inbox 去重 ========================

    @Nested @DisplayName("Inbox 去重") class InboxTests {
        @Test @DisplayName("重复事件只处理一次")
        void dedup_processedOnce() {
            String eid = UUID.randomUUID().toString(); String h = sha256("{\"id\":1}");
            for (int i=0; i<3; i++) { if (!inboxService.isProcessed(eid, h)) inboxService.markProcessed(eid, h); }
            assertThat(inboxMapper.selectCount(new LambdaQueryWrapper<InboxReceipt>().eq(InboxReceipt::getEventId, eid))).isEqualTo(1);
        }

        @Test @DisplayName("同 eventId 不同 payload → 隔离")
        void hashConflict_isolated() {
            String eid = UUID.randomUUID().toString();
            String h1 = sha256("{\"a\":1}"), h2 = sha256("{\"a\":2}");
            inboxService.markProcessed(eid, h1);
            assertThat(inboxService.isProcessed(eid, h2)).isTrue();
            assertThat(inboxMapper.selectById(eid).getPayloadHash()).isEqualTo(h1);
        }

        @Test @DisplayName("幂等：先检查再标记")
        void idempotent_once() {
            String eid = UUID.randomUUID().toString(); String h = sha256("x");
            assertThat(inboxService.isProcessed(eid, h)).isFalse();
            inboxService.markProcessed(eid, h);
            assertThat(inboxService.isProcessed(eid, h)).isTrue();
            assertThat(inboxMapper.selectCount(new LambdaQueryWrapper<InboxReceipt>().eq(InboxReceipt::getEventId, eid))).isEqualTo(1);
        }
    }

    // ======================== 统计 ========================

    @Nested @DisplayName("Outbox 统计") class StatsTests {
        @Test @DisplayName("backlog/failed/total 计数正确")
        void stats_correct() {
            for (int i=0; i<3; i++) outboxMapper.insert(new OutboxMessage().setEventId(UUID.randomUUID().toString())
                .setExchange("x").setRoutingKey("k").setEventType("T").setPayload("{}")
                .setStatus("PENDING").setAvailableAt(LocalDateTime.now()).setCreatedAt(LocalDateTime.now()));
            for (int i=0; i<2; i++) outboxMapper.insert(new OutboxMessage().setEventId(UUID.randomUUID().toString())
                .setExchange("x").setRoutingKey("k").setEventType("T").setPayload("{}")
                .setStatus("FAILED").setPublishAttempts(5).setAvailableAt(LocalDateTime.now()).setCreatedAt(LocalDateTime.now()));

            assertThat(outboxMapper.countPending()).isEqualTo(3);
            assertThat(outboxMapper.countFailed()).isEqualTo(2);
            assertThat(outboxMapper.selectCount(null)).isEqualTo(5);
            var s = OutboxStats.of(outboxMapper.countPending(), outboxMapper.countFailed(), outboxMapper.selectCount(null));
            assertThat(s.pending()).isEqualTo(3); assertThat(s.failed()).isEqualTo(2); assertThat(s.total()).isEqualTo(5);
        }
    }

    // ======================== Relay failure handling ========================

    @Nested @DisplayName("Relay 失败处理") class RelayFailureTests {
        @Autowired private com.comicatlas.api.outbox.relay.OutboxRelay outboxRelay;
        @Autowired private CachingConnectionFactory connectionFactory;

        @Test
        @DisplayName("broker 不可达时 relay 递增 publish_attempts 并用 SQL backoff")
        void brokerUnreachable_incrementsAttempts() throws Exception {
            Assumptions.assumeTrue(dockerAvailable, "Docker required");

            var event = new ImportTaskCreatedEvent(UUID.randomUUID(), Instant.now(), 99L, 999L, "ZIP", "/fail-test");
            String eid = event.eventId().toString();
            outboxService.enqueue(event, "comic.import", "task.created");

            rabbitmq.stop();
            Thread.sleep(1500);
            try {
                outboxRelay.relay();
                Thread.sleep(1000);

                OutboxMessage msg = outboxMapper.selectById(eid);
                assertThat(msg.getStatus()).isEqualTo("PENDING");
                assertThat(msg.getPublishAttempts()).as("should increment on broker unreachable").isGreaterThanOrEqualTo(1);
                assertThat(msg.getAvailableAt()).isNotNull();
                assertThat(msg.getLastError()).isNotEmpty();
            } finally {
                // 恢复 broker 供后续测试使用
                rabbitmq.start();
                // 等待 AMQP 就绪
                com.rabbitmq.client.ConnectionFactory rawCf = new com.rabbitmq.client.ConnectionFactory();
                rawCf.setHost(rabbitmq.getHost());
                rawCf.setPort(rabbitmq.getAmqpPort());
                rawCf.setConnectionTimeout(1000);
                for (int j = 0; j < 30; j++) {
                    try (var c = rawCf.newConnection()) { break; }
                    catch (Exception e) { Thread.sleep(1000); }
                }
                connectionFactory.setHost(rabbitmq.getHost());
                connectionFactory.setPort(rabbitmq.getAmqpPort());
                connectionFactory.resetConnection();
            }
        }

        @Test
        @DisplayName("正常发布：relay 将消息标记为 PUBLISHED")
        void relayPublishes_successfully() throws Exception {
            Assumptions.assumeTrue(dockerAvailable, "Docker required");
            var event = new ImportTaskCreatedEvent(UUID.randomUUID(), Instant.now(), 200L, 2000L, "DIRECTORY", "/normal");
            String eid = event.eventId().toString();
            outboxService.enqueue(event, "comic.import", "task.created");

            outboxRelay.relay();

            OutboxMessage msg = outboxMapper.selectById(eid);
            assertThat(msg).isNotNull();
            assertThat(msg.getStatus()).isIn("PUBLISHED", "PENDING");
            if ("PENDING".equals(msg.getStatus())) {
                // RabbitMQ 可能延迟，允许重试
                for (int i = 0; i < 3; i++) {
                    outboxRelay.relay();
                    msg = outboxMapper.selectById(eid);
                    if ("PUBLISHED".equals(msg.getStatus())) break;
                    Thread.sleep(1000);
                }
            }
            assertThat(msg.getStatus()).isEqualTo("PUBLISHED");
        }
    }
}
