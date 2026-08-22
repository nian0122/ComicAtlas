package com.comicatlas.worker.integration;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.common.util.MetadataSnapshotRevision;
import com.comicatlas.worker.media.metadata.MetadataRefreshCommandHandler;
import com.comicatlas.worker.config.MetadataJsonBuilderConfig;
import com.comicatlas.worker.config.MqConsumerSupportConfig;
import com.comicatlas.worker.config.RabbitMqConfig;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.config.WorkerExecutorConfig;
import com.comicatlas.worker.exporter.ExportCollector;
import com.comicatlas.worker.exporter.MetadataJsonExporter;
import com.comicatlas.worker.exporter.MetadataModelMapper;
import com.comicatlas.worker.task.ManagementCommandPublisher;
import com.comicatlas.worker.media.event.MetadataRefreshHandler;
import com.comicatlas.worker.media.MediaAnalyzer;
import com.comicatlas.worker.shared.process.ExternalProcessRunner;
import com.comicatlas.worker.storage.StorageProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 元数据扫盘刷新 Worker 真实链路验收（Todo 8 / Wave 3，扫描段 + 重写段）。
 * <p>
 * 真实 Testcontainers MySQL（worker_user 只读 + api_user 可写）+ RabbitMQ + 真实 HQ/STAGING/METADATA
 * 临时根。扫描段：真实 {@link MetadataRefreshCommandHandler} 读取真实 DB 基线 + 真实磁盘 HQ 目录，
 * 原子落盘快照并发布 {@code MetadataRefreshScanCompletedEvent} 到真实 RabbitMQ。重写段：真实
 * {@link MetadataRefreshHandler} 消费 {@code MetadataRefreshEvent}，真实 MetadataJsonExporter 重写
 * {@code metadata/{comicId}.json}（chapterId 布局）；失败 → metadata.refresh.dlq。
 * <p>
 * 与 api-service 侧 MetadataRefreshRealChainIT（应用段）共享同一事件契约与快照文件布局，
 * 三段拼成完整闭环验收。
 */
@SpringBootTest(classes = MetadataRefreshWorkerChainIT.WorkerChainTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("MetadataRefreshWorkerChainIT — 元数据刷新 Worker 真实链路验收")
class MetadataRefreshWorkerChainIT {

    /** 共享临时 MANGA 根（HQ/STAGING/METADATA 统一布局，与生产 MANGA_ROOT 语义一致）。 */
    private static final Path MANGA_TMP = createTempDir("comic-atlas-meta-worker-it");

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class})
    @org.mybatis.spring.annotation.MapperScan("com.comicatlas.worker.persistence.mapper")
    @org.springframework.context.annotation.Import({
            WorkerConfig.class,
            WorkerExecutorConfig.class,
            StorageProperties.class,
            MetadataJsonBuilderConfig.class,
            MqConsumerSupportConfig.class,
            MediaAnalyzer.class,
            ExternalProcessRunner.class,
            ManagementCommandPublisher.class,
            MetadataRefreshCommandHandler.class,
            ExportCollector.class,
            MetadataModelMapper.class,
            MetadataJsonExporter.class,
            MetadataRefreshHandler.class,
            RabbitMqConfig.class
    })
    static class WorkerChainTestConfig {
    }

    private static Path createTempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.33")
            .withUsername("root")
            .withPassword("")
            .withDatabaseName("comic_atlas_test")
            .withInitScript("sql/init-metadata-refresh-it.sql");
    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.12-management-alpine")
            .withAdminPassword("test_rabbit_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl() +
                "?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai");
        registry.add("spring.datasource.username", () -> "worker_user");
        registry.add("spring.datasource.password", () -> "worker_test_pass");
        registry.add("spring.datasource.hikari.read-only", () -> "true");
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        registry.add("worker.manga-root", () -> MANGA_TMP.toString());
        registry.add("storage.roots.HQ.path", () -> MANGA_TMP.resolve("hq").toString());
        registry.add("storage.roots.LQ.path", () -> MANGA_TMP.resolve("lq").toString());
        registry.add("storage.roots.METADATA.path", () -> MANGA_TMP.resolve("metadata").toString());
        registry.add("storage.roots.STAGING.path", () -> MANGA_TMP.resolve("staging").toString());
    }

    @Autowired
    private MetadataRefreshCommandHandler commandHandler;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private DataSource workerDataSource;
    @Autowired
    private ObjectMapper objectMapper;

    private JdbcTemplate apiJdbc;
    private HikariDataSource apiDs;

    private static final long COMIC_ID = 1L;
    private static final long TASK_ID = 100L;
    private static final long ITEM_ID = 200L;
    private static final int ATTEMPT = 1;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker 不可用，跳过 Worker 容器化集成测试");
        apiDs = new HikariDataSource();
        apiDs.setJdbcUrl("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) +
                "/comic_atlas_test?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai");
        apiDs.setUsername("api_user");
        apiDs.setPassword("api_test_pass");
        apiDs.setMaximumPoolSize(2);
        apiJdbc = new JdbcTemplate(apiDs);
        cleanTables();
        cleanupRoot(MANGA_TMP);
    }

    @AfterEach
    void tearDown() {
        if (apiDs != null && !apiDs.isClosed()) {
            apiDs.close();
        }
    }

    private void cleanTables() {
        apiJdbc.update("DELETE FROM page");
        apiJdbc.update("DELETE FROM catalog");
        apiJdbc.update("DELETE FROM chapter");
        apiJdbc.update("DELETE FROM comic");
    }

    private static void cleanupRoot(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    // ======================== 扫描段：真实扫盘 ========================

    @Test
    @DisplayName("真实扫盘：读真实 DB 基线 + 真实磁盘 → 快照落盘 + completed 事件发布")
    void realScan_writesSnapshotAndPublishesCompleted() throws Exception {
        seedScanFixture();

        // 测试断言队列：绑定 management 交换器的 completed/progress/failed 键
        Queue assertQueue = new Queue("task8.metadata.result.assert", true);
        rabbitAdmin.declareQueue(assertQueue);
        DirectExchange managementExchange = new DirectExchange(MqExchanges.MANAGEMENT);
        for (String key : new String[]{MqRoutingKeys.COMMAND_COMPLETED, MqRoutingKeys.COMMAND_PROGRESS,
                MqRoutingKeys.COMMAND_FAILED}) {
            rabbitAdmin.declareBinding(BindingBuilder.bind(assertQueue).to(managementExchange).with(key));
        }

        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, TASK_ID, ITEM_ID, ATTEMPT,
                "METADATA_REFRESH", "COMIC", COMIC_ID);
        commandHandler.refresh(cmd);

        // 快照落盘（相对 STAGING 根，无 "STAGING/" 前缀）
        Path snapshot = MANGA_TMP.resolve("staging").resolve(
                "metadata-refresh/" + TASK_ID + "/" + ITEM_ID + "/" + ATTEMPT + "/snapshot.json");
        assertTrue(Files.exists(snapshot), "快照应已原子落盘");
        byte[] fileBytes = Files.readAllBytes(snapshot);
        assertEquals(0, countTmpResidue(snapshot.getParent()), "STAGING 不得残留 .tmp");

        // 解析快照并校验结构 + 摘要
        JsonNode root = objectMapper.readTree(snapshot.toFile());
        assertEquals(1, root.get("schemaVersion").asInt());
        assertEquals(COMIC_ID, root.get("comicId").asLong());
        JsonNode chapter = root.get("chapters").get(0);
        assertEquals(42L, chapter.get("chapterId").asLong());
        assertEquals(7, chapter.get("chapterVersion").asInt());
        JsonNode items = chapter.get("mediaItems");
        assertEquals(2, items.size(), "快照只含磁盘∩DB 的媒体（001.jpg 与 003.mp4）");
        assertEquals("1/42/001.jpg", items.get(0).get("hqPath").asText());
        assertEquals(101L, items.get(0).get("mediaId").asLong());
        assertEquals("1/42/003.mp4", items.get(1).get("hqPath").asText());
        assertEquals(103L, items.get(1).get("mediaId").asLong());
        assertEquals("VIDEO", items.get(1).get("mediaType").asText());
        assertTrue(items.get(1).get("container").asText().equals("mp4"), "视频容器来自扩展名回退");
        boolean orphanWarning = false;
        for (JsonNode w : chapter.get("warnings")) {
            if (w.asText().contains("002.jpg") && w.asText().contains("无对应DB记录")) {
                orphanWarning = true;
            }
        }
        assertTrue(orphanWarning, "孤儿文件 002.jpg 应记 warning");
        // 诱饵 globalOrder 目录 HQ/1/1 绝不进入快照
        for (JsonNode item : items) {
            assertTrue(!item.get("hqPath").asText().startsWith("1/1/"),
                    "不得访问 globalOrder 目录 HQ/1/1");
        }

        // 摘要自洽：databaseRevision 与重算一致
        MetadataRefreshSnapshotDTO parsed = objectMapper.readValue(fileBytes, MetadataRefreshSnapshotDTO.class);
        assertEquals(parsed.databaseRevision(), MetadataSnapshotRevision.compute(parsed),
                "databaseRevision 必须与结构重算一致");

        // completed 事件发布到真实 RabbitMQ
        MetadataRefreshScanCompletedEvent received = receiveCompleted(assertQueue.getName());
        assertNotNull(received, "应收到 MetadataRefreshScanCompletedEvent");
        assertEquals("metadata-refresh/" + TASK_ID + "/" + ITEM_ID + "/" + ATTEMPT + "/snapshot.json",
                received.snapshotRef());
        assertEquals(sha256Hex(fileBytes), received.snapshotSha256());
        assertEquals(fileBytes.length, received.snapshotBytes());
        assertEquals(1, received.schemaVersion());
        assertEquals(ATTEMPT, received.attempt());
    }

    // ======================== Worker 只读边界 ========================

    @Test
    @DisplayName("Worker 只读：worker_user 的 DML/DDL 被拒绝，SELECT 可用")
    void workerReadOnly_boundary() {
        JdbcTemplate workerJdbc = new JdbcTemplate(workerDataSource);
        Integer count = workerJdbc.queryForObject(
                "SELECT COUNT(*) FROM comic WHERE id = " + COMIC_ID, Integer.class);
        assertEquals(0, count, "Worker SELECT 应可用");
        try {
            workerJdbc.update("INSERT INTO comic (id, title, status) VALUES (9999, 'hack', 'READY')");
            fail("Worker INSERT 应被拒绝");
        } catch (Exception e) {
            assertAccessDenied(e);
        }
        Integer hacked = apiJdbc.queryForObject(
                "SELECT COUNT(*) FROM comic WHERE id = 9999", Integer.class);
        assertEquals(0, hacked, "Worker INSERT 失败后数据不应存在");
    }

    // ======================== 重写段：metadata.json ========================

    @Test
    @DisplayName("真实 metadata 重写：metadata/{comicId}.json 按 chapterId 布局落盘")
    void metadataRewrite_writesChapterIdLayoutFile() throws Exception {
        seedRewriteFixture();

        rabbitTemplate.convertAndSend(MqExchanges.EXPORT, MqRoutingKeys.METADATA_REFRESH_REQUESTED,
                new MetadataRefreshEvent(null, null, COMIC_ID));

        Path metadataFile = MANGA_TMP.resolve("metadata").resolve(COMIC_ID + ".json");
        await(() -> Files.exists(metadataFile), "metadata/{comicId}.json 应被真实 Handler 重写");
        assertEquals(0, countTmpResidue(metadataFile.getParent()), "metadata 目录不得残留 .tmp");

        JsonNode root = objectMapper.readTree(metadataFile.toFile());
        assertEquals(3, root.get("version").asInt());
        JsonNode chapters = root.get("chapters");
        assertEquals(2, chapters.size());
        boolean anyChapterIdLayout = false;
        for (JsonNode ch : chapters) {
            for (JsonNode item : ch.get("mediaItems")) {
                String hqPath = item.get("hqPath").asText();
                assertTrue(hqPath.matches("^" + COMIC_ID + "/\\d+/.*"),
                        "metadata hqPath 必须是 {comicId}/{chapterId}/{fileName}: " + hqPath);
                anyChapterIdLayout = true;
            }
        }
        assertTrue(anyChapterIdLayout, "metadata 应包含 chapterId 布局的媒体项");
    }

    @Test
    @DisplayName("metadata 重写失败：漫画不存在 → metadata.refresh.dlq，不写文件")
    void metadataRewrite_unknownComic_goesToDlq() {
        long unknownComic = 9999L;
        rabbitTemplate.convertAndSend(MqExchanges.EXPORT, MqRoutingKeys.METADATA_REFRESH_REQUESTED,
                new MetadataRefreshEvent(null, null, unknownComic));

        Object dlqMsg = awaitReceive("metadata.refresh.dlq", 15000);
        assertNotNull(dlqMsg, "重写失败应进入 metadata.refresh.dlq");
        assertTrue(!Files.exists(MANGA_TMP.resolve("metadata").resolve(unknownComic + ".json")),
                "失败不得写 metadata 文件");
    }

    // ======================== 辅助 ========================

    private void seedScanFixture() throws IOException {
        apiJdbc.update("INSERT INTO comic (id, title, status, storage_policy) VALUES ("
                + COMIC_ID + ", '元数据刷新验收', 'READY', 'MANAGED')");
        apiJdbc.update("INSERT INTO chapter (id, comic_id, title, chapter_no, global_order, version, status) "
                + "VALUES (42, " + COMIC_ID + ", '第42章', '42', 1, 7, 'READY')");
        apiJdbc.update("INSERT INTO page (id, chapter_id, page_number, media_type, hq_root, hq_path, "
                + "hq_status, lq_status, status, version) "
                + "VALUES (101, 42, 1, 'IMAGE', 'HQ', '1/42/001.jpg', 'READY', 'NOT_GENERATED', 'READY', 1),"
                + "(103, 42, 3, 'VIDEO', 'HQ', '1/42/003.mp4', 'READY', 'NOT_GENERATED', 'READY', 2)");
        writeFile(MANGA_TMP.resolve("hq/1/42/001.jpg"), "img-001");
        writeFile(MANGA_TMP.resolve("hq/1/42/003.mp4"), "video-003");
        writeFile(MANGA_TMP.resolve("hq/1/42/002.jpg"), "orphan-002");
        writeFile(MANGA_TMP.resolve("hq/1/42/.hidden.jpg"), "hidden");
        writeFile(MANGA_TMP.resolve("hq/1/42/notes.txt"), "text");
        writeFile(MANGA_TMP.resolve("hq/1/1/decoy.jpg"), "decoy"); // globalOrder 目录诱饵
        try {
            Files.createSymbolicLink(MANGA_TMP.resolve("hq/1/42/link.jpg"),
                    MANGA_TMP.resolve("hq/1/42/001.jpg"));
        } catch (UnsupportedOperationException | IOException e) {
            System.out.println("[fixture] 当前环境无法创建符号链接，跳过: " + e.getMessage());
        }
    }

    private void seedRewriteFixture() {
        apiJdbc.update("INSERT INTO comic (id, title, status, storage_policy) VALUES ("
                + COMIC_ID + ", '元数据刷新验收', 'READY', 'MANAGED')");
        apiJdbc.update("INSERT INTO chapter (id, comic_id, title, chapter_no, global_order, version, status) "
                + "VALUES (41, " + COMIC_ID + ", '第41章', '41', 0, 1, 'READY'),"
                + "(42, " + COMIC_ID + ", '第42章', '42', 1, 1, 'READY')");
        apiJdbc.update("INSERT INTO page (id, chapter_id, page_number, media_type, hq_root, hq_path, "
                + "hq_status, lq_status, status, version) "
                + "VALUES (1001, 41, 1, 'IMAGE', 'HQ', '1/41/001.jpg', 'READY', 'NOT_GENERATED', 'READY', 1),"
                + "(101, 42, 1, 'IMAGE', 'HQ', '1/42/001.jpg', 'READY', 'NOT_GENERATED', 'READY', 1),"
                + "(102, 42, 2, 'VIDEO', 'HQ', '1/42/002.mp4', 'READY', 'NOT_GENERATED', 'READY', 1)");
    }

    private MetadataRefreshScanCompletedEvent receiveCompleted(String queue) {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            Object msg = rabbitTemplate.receiveAndConvert(queue, 3000);
            if (msg instanceof MetadataRefreshScanCompletedEvent ev) {
                return ev;
            }
        }
        return null;
    }

    private Object awaitReceive(String queue, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            org.springframework.amqp.core.Message msg = rabbitTemplate.receive(queue, 300);
            if (msg != null) {
                return msg;
            }
            sleepQuietly(300);
        }
        return null;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static long countTmpResidue(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void await(java.util.function.Supplier<Boolean> cond, String desc) {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(cond.get())) {
                    return;
                }
            } catch (Exception ignored) {
            }
            sleepQuietly(200);
        }
        fail("等待超时: " + desc);
    }

    private static void assertAccessDenied(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("access denied") || lower.contains("command denied")
                        || lower.contains("read-only") || lower.contains("read only")
                        || lower.contains("modification are not allowed")) {
                    return;
                }
            }
            current = current.getCause();
        }
        fail("异常链中未找到 access denied / command denied / read-only: "
                + ex.getClass().getName() + ": " + ex.getMessage());
    }

    private static boolean isDockerAvailable() {
        try {
            new ProcessBuilder("docker", "info").redirectErrorStream(true).start().waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
