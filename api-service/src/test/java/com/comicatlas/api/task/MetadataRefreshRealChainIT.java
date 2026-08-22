package com.comicatlas.api.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.api.task.dto.OperationSubmitResultDTO;
import com.comicatlas.api.task.entity.ManagementTask;
import com.comicatlas.api.task.entity.ManagementTaskItem;
import com.comicatlas.api.task.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.mapper.ManagementTaskMapper;
import com.comicatlas.api.media.operation.MediaOperationCommandService;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.api.outbox.entity.OutboxMessage;
import com.comicatlas.api.outbox.mapper.InboxReceiptMapper;
import com.comicatlas.api.outbox.mapper.OutboxMessageMapper;
import com.comicatlas.api.outbox.relay.OutboxRelay;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.ChapterSnapshot;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.MediaSnapshot;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.common.util.MetadataSnapshotRevision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 元数据扫盘刷新真实链路闭环验收（Todo 8 / Wave 3）。
 * <p>
 * 真实 Testcontainers MySQL + RabbitMQ + 共享 STAGING/HQ 临时根。分段验收的应用段：
 * 直接命令触发 → 模拟 Worker 落盘快照（按 Worker 契约：STAGING/metadata-refresh/
 * {task}/{item}/{attempt}/snapshot.json + SHA-256）→ 真实 {@code MetadataRefreshScanCompletedEvent}
 * 经真实 RabbitMQ → API {@code ManagementCommandResultHandler} 真实消费 → 真实 DB 合并 →
 * Outbox 入箱 → relay 发布。扫描段由 worker-service 侧（MetadataRefreshCommandHandlerTest +
 * MetadataRefreshWorkerChainIT）验证，重写段由 worker-service 侧验证，三段共享同一契约。
 * <p>
 * fixture 覆盖：两章（chapterId=41 globalOrder=0、chapterId=42 globalOrder=1）、已有图片（尺寸变化）、
 * 新增图片（mediaId=null）、缺失 HQ、视频（尺寸变化 + 视频字段）、LQ READY 保留、TRASHED/DELETED 同名行
 * 不复活、隐藏文件/未知扩展名/symlink（Windows 不可建则记录跳过）、真实磁盘文件。
 * <p>
 * 失败场景：篡改摘要、未知章节、revision 并发漂移、重复事件幂等、旧 attempt 忽略、
 * 快照缺失 → DLQ、FAILED 后 retry → attempt+1 成功。
 * <p>
 * 无 HTTP 文件传输：WebEnvironment.NONE，快照走本地文件系统 + MQ 只传引用（不传字节进事件体）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("MetadataRefreshRealChainIT — 元数据刷新真实链路闭环验收")
class MetadataRefreshRealChainIT {

    private static boolean dockerAvailable;

    static {
        dockerAvailable = checkDockerAvailable();
    }

    /** API 与 Worker 共享的 STAGING 根（模拟 Worker 落盘快照 + API 读取 + 提交后清理）。 */
    private static final Path STAGING_TMP = createTempDir("comic-atlas-meta-staging-it");
    /** fixture 的 HQ 根（真实磁盘文件）。 */
    private static final Path HQ_TMP = createTempDir("comic-atlas-meta-hq-it");

    private static Path createTempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Container
    static MySQLContainer<?> mysql = dockerAvailable
            ? new MySQLContainer<>("mysql:8.0.33").withDatabaseName("comic_atlas_test")
                    .withUsername("test").withPassword("test")
            : null;
    @Container
    static RabbitMQContainer rabbitmq = dockerAvailable
            ? new RabbitMQContainer("rabbitmq:3.12-management-alpine").withAdminPassword("test_rabbit_pass")
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
            registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        }
        registry.add("outbox.relay.scheduled", () -> "false");
        registry.add("outbox.relay.poll-interval-ms", () -> "600000");
        registry.add("outbox.relay.batch-size", () -> "50");
        registry.add("storage.roots.STAGING.path", () -> STAGING_TMP.toString());
        registry.add("storage.roots.HQ.path", () -> HQ_TMP.toString());
    }

    @Autowired
    private MediaOperationCommandService commandService;
    @Autowired
    private ManagementTaskService managementTaskService;
    @Autowired
    private MediaMapper mediaMapper;
    @Autowired
    private ChapterMapper chapterMapper;
    @Autowired
    private ComicMapper comicMapper;
    @Autowired
    private OutboxMessageMapper outboxMapper;
    @Autowired
    private InboxReceiptMapper inboxMapper;
    @Autowired
    private OutboxRelay outboxRelay;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ManagementTaskMapper taskMapper;
    @Autowired
    private ManagementTaskItemMapper taskItemMapper;

    private Comic comic;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(dockerAvailable, "Docker 不可用，跳过容器化集成测试");
        cleanup();
        cleanupRoot(STAGING_TMP);
        cleanupRoot(HQ_TMP);

        // 固定 fixture：comicId=1、chapterId=41(globalOrder=0)/42(globalOrder=1)
        comic = new Comic();
        comic.setId(1L);
        comic.setTitle("元数据刷新真实链路验收漫画");
        comic.setStatus(ComicStatus.READY);
        comic.setStoragePolicy("MANAGED");
        comic.setTotalPages(0);
        comic.setHqSize(0L);
        comic.setHqSize(0L);
        comicMapper.insert(comic);

        Chapter ch41 = chapter(41L, 1L, 0);
        Chapter ch42 = chapter(42L, 1L, 1);
        chapterMapper.insert(ch41);
        chapterMapper.insert(ch42);

        // 章节 41：1 张已有图片（DB 尺寸与实际磁盘一致）
        mediaMapper.insert(image(1001L, 41L, 1, "1/41/001.jpg", 1000L, LqStatus.NOT_GENERATED, "READY"));
        writeFile(hq("1/41/001.jpg"), 1000);

        // 章节 42：全矩阵
        // m101 已有图片，尺寸变化（DB fileSize=1000，磁盘实际 2500），LQ READY 保留
        mediaMapper.insert(image(101L, 42L, 1, "1/42/001.jpg", 1000L, LqStatus.READY, "READY"));
        writeFile(hq("1/42/001.jpg"), 2500);
        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, 101L)
                .set(Media::getLqRoot, "LQ").set(Media::getLqPath, "1/42/001.webp"));
        // m102 已有视频，尺寸/大小变化 + 视频字段
        mediaMapper.insert(video(102L, 42L, 2, "1/42/002.mp4", 5000L));
        writeFile(hq("1/42/002.mp4"), 6000);
        // m103 缺失 HQ（DB 有行，磁盘无文件）
        mediaMapper.insert(image(103L, 42L, 3, "1/42/003.jpg", 3000L, LqStatus.NOT_GENERATED, "READY"));
        // m105 TRASHED 同名行（hqPath 已改写为 TRASH 引用）——磁盘 005.jpg 成为孤儿，不得复活
        mediaMapper.insert(image(105L, 42L, -105, "media/105/999/hq/1/42/005.jpg", 0L,
                LqStatus.NOT_GENERATED, "TRASHED"));
        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, 105L)
                .set(Media::getHqRoot, "TRASH").set(Media::getHqStatus, HqStatus.DELETED));
        writeFile(hq("1/42/005.jpg"), 777);
        // m106 DELETED 同名行（hqPath 仍为原始路径）——快照携带 lifecycleStatus=DELETED，不得复活插入
        mediaMapper.insert(image(106L, 42L, -106, "1/42/006.jpg", 0L,
                LqStatus.NOT_GENERATED, "DELETED"));
        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, 106L).set(Media::getHqStatus, HqStatus.DELETED));
        writeFile(hq("1/42/006.jpg"), 888);
        // 磁盘新增：004.jpg（无 DB 行 → 快照 mediaId=null 项 → API 插入分支）
        writeFile(hq("1/42/004.jpg"), 8888);
        // 过滤项：隐藏文件、未知扩展名、symlink（Windows 不可建则记录）
        writeFile(hq("1/42/.hidden.jpg"), 66);
        writeFile(hq("1/42/notes.txt"), 44);
        try {
            Files.createSymbolicLink(hq("1/42/link.jpg"), hq("1/42/001.jpg"));
        } catch (UnsupportedOperationException | IOException e) {
            System.out.println("[fixture] 当前环境无法创建符号链接，跳过: " + e.getMessage());
        }
    }

    @AfterEach
    void tearDown() {
        if (dockerAvailable) {
            cleanup();
        }
    }

    private void cleanup() {
        try {
            if (taskItemMapper != null) {
                taskItemMapper.delete(new LambdaQueryWrapper<>());
            }
            if (taskMapper != null) {
                taskMapper.delete(new LambdaQueryWrapper<>());
            }
            if (inboxMapper != null) {
                inboxMapper.delete(new LambdaQueryWrapper<>());
            }
            if (outboxMapper != null) {
                outboxMapper.delete(new LambdaQueryWrapper<>());
            }
            if (mediaMapper != null) {
                mediaMapper.delete(new LambdaQueryWrapper<>());
            }
            if (chapterMapper != null) {
                chapterMapper.delete(new LambdaQueryWrapper<>());
            }
            if (comicMapper != null) {
                comicMapper.delete(new LambdaQueryWrapper<>());
            }
        } catch (Exception ignored) {
        }
    }

    private static void cleanupRoot(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var entries = Files.list(root)) {
            for (Path p : entries.toList()) {
                try (var walk = Files.walk(p)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(f -> {
                        try {
                            Files.deleteIfExists(f);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ======================== happy：真实链路闭环 ========================

    @Test
    @DisplayName("真实链路：命令 → 快照 → completed → API 合并 → Outbox relay → 断言")
    void realChain_commandSnapshotCompleted_appliesAndPublishes() throws Exception {
        OperationSubmitResultDTO result = commandService.requestMetadataRefresh(comic.getId());
        assertThat(comicMapper.selectById(comic.getId()).getStatus()).isEqualTo(ComicStatus.REFRESHING);

        ManagementCommandRequestedEvent cmd = readSingleCommand(result.getTaskId());
        assertThat(cmd.operationType()).isEqualTo("METADATA_REFRESH");
        assertThat(cmd.attempt()).isEqualTo(1);

        // 模拟 Worker 按契约落盘快照（相对 STAGING 根，无 "STAGING/" 前缀——契约一致性校验）
        MetadataRefreshSnapshotDTO snapshot = buildFixtureSnapshot();
        String revision = MetadataSnapshotRevision.compute(snapshot);
        MetadataRefreshSnapshotDTO withRevision = new MetadataRefreshSnapshotDTO(
                snapshot.schemaVersion(), snapshot.comicId(), snapshot.generatedAt(), revision, snapshot.chapters());
        byte[] bytes = objectMapper.writeValueAsBytes(withRevision);
        String ref = "metadata-refresh/" + cmd.taskId() + "/" + cmd.itemId() + "/1/snapshot.json";
        Path snapshotFile = STAGING_TMP.resolve(ref);
        Files.createDirectories(snapshotFile.getParent());
        Files.write(snapshotFile, bytes);
        // 无 HTTP 文件传输断言：快照引用是本地文件系统相对路径，事件体不含文件字节
        assertThat(ref).startsWith("metadata-refresh/").doesNotContain("http");

        var completed = new MetadataRefreshScanCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                cmd.taskId(), cmd.itemId(), 1, "METADATA_REFRESH", "COMIC", comic.getId(),
                ref, sha256(bytes), bytes.length, 1);
        rabbitTemplate.convertAndSend("comic.management", "command.completed", completed);

        await(() -> taskItemMapper.selectById(cmd.itemId()).getStatus() == ManagementTaskStatus.SUCCEEDED,
                "item SUCCEEDED");
        await(() -> taskMapper.selectById(cmd.taskId()).getStatus() == ManagementTaskStatus.SUCCEEDED,
                "task SUCCEEDED");
        assertThat(comicMapper.selectById(comic.getId()).getStatus()).isEqualTo(ComicStatus.READY);

        // ---- 差异合并断言 ----
        Media m101 = mediaMapper.selectById(101L);
        assertThat(m101.getHqSize()).isEqualTo(2500L);          // 尺寸变化已更新
        assertThat(m101.getHqStatus()).isEqualTo(HqStatus.READY);
        assertThat(m101.getMediaType()).isEqualTo("IMAGE");
        assertThat(m101.getPageNumber()).isEqualTo(1);
        assertThat(m101.getLqStatus()).isEqualTo(LqStatus.READY); // LQ 保留
        assertThat(m101.getLqRoot()).isEqualTo("LQ");
        assertThat(m101.getLqPath()).isEqualTo("1/42/001.webp");

        Media m102 = mediaMapper.selectById(102L);
        assertThat(m102.getHqSize()).isEqualTo(6000L);
        assertThat(m102.getMediaType()).isEqualTo("VIDEO");
        assertThat(m102.getWidth()).isEqualTo(1280);
        assertThat(m102.getHeight()).isEqualTo(720);
        assertThat(m102.getDuration()).isEqualByComparingTo("12.500");
        assertThat(m102.getContainer()).isEqualTo("mp4");
        assertThat(m102.getVideoCodec()).isEqualTo("h264");
        assertThat(m102.getAudioCodec()).isEqualTo("aac");

        Media m103 = mediaMapper.selectById(103L);
        assertThat(m103.getHqStatus()).isEqualTo(HqStatus.MISSING); // 缺失 HQ → MISSING
        assertThat(m103.getHqSize()).isZero();

        // 新增图片 004.jpg：pageNumber 从本章最大页码 +1 追加
        Media new004 = mediaMapper.selectOne(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, 42L).eq(Media::getHqPath, "1/42/004.jpg"));
        assertThat(new004).isNotNull();
        assertThat(new004.getPageNumber()).isEqualTo(4);
        assertThat(new004.getHqStatus()).isEqualTo(HqStatus.READY);
        assertThat(new004.getHqSize()).isEqualTo(8888L);
        assertThat(new004.getLqStatus()).isEqualTo(LqStatus.NOT_GENERATED);
        assertThat(new004.getStatus()).isEqualTo(MediaLifecycleStatus.READY);

        // TRASHED/DELETED 同名行不复活
        assertThat(mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getHqPath, "1/42/005.jpg"))).isZero();
        Media m106 = mediaMapper.selectById(106L);
        assertThat(m106.getStatus()).isEqualTo(MediaLifecycleStatus.DELETED);
        assertThat(m106.getHqPath()).isEqualTo("1/42/006.jpg");
        assertThat(mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getHqPath, "1/42/006.jpg"))).isEqualTo(1);

        // 章节页数与漫画统计
        assertThat(chapterMapper.selectById(41L).getPageCount()).isEqualTo(1);
        assertThat(chapterMapper.selectById(42L).getPageCount()).isEqualTo(4);
        Comic reloaded = comicMapper.selectById(comic.getId());
        assertThat(reloaded.getTotalPages()).isEqualTo(5);
        assertThat(reloaded.getHqSize()).isEqualTo(18388L);
        assertThat(reloaded.getHqSize()).isEqualTo(18388L);

        // Outbox：metadata 重导出事件入箱（exchange/routingKey 契约）
        OutboxMessage msg = outboxMapper.selectOne(new LambdaQueryWrapper<OutboxMessage>()
                .eq(OutboxMessage::getEventType, "MetadataRefreshEvent"));
        assertThat(msg).isNotNull();
        assertThat(msg.getExchange()).isEqualTo("comic.export");
        assertThat(msg.getRoutingKey()).isEqualTo("metadata.refresh.requested");
        assertThat(msg.getPayload()).doesNotContain("/files/"); // 无 HTTP 文件引用

        // 提交后快照目录已清理
        assertThat(Files.exists(snapshotFile.getParent())).isFalse();

        // relay 发布
        outboxRelay.relay();
        await(() -> outboxMapper.selectById(msg.getEventId()).getStatus().equals("PUBLISHED"), "relay 发布");
    }

    // ======================== 失败：篡改摘要 ========================

    @Test
    @DisplayName("篡改摘要：API 零提交 + item/task FAILED + comic READY + 快照保留")
    void tamperedDigest_zeroCommitAndFailed() throws Exception {
        setComicRefreshing();
        ManagementTask task = createRefreshTask();
        ManagementTaskItem item = createRefreshItem(task.getId());

        byte[] bytes = writeValidSnapshot(task.getId(), item.getId(), 1, null);
        var completed = new MetadataRefreshScanCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                task.getId(), item.getId(), 1, "METADATA_REFRESH", "COMIC", comic.getId(),
                snapshotRef(task.getId(), item.getId(), 1), "deadbeef", bytes.length, 1);
        rabbitTemplate.convertAndSend("comic.management", "command.completed", completed);

        await(() -> taskItemMapper.selectById(item.getId()).getStatus() == ManagementTaskStatus.FAILED,
                "item FAILED");
        await(() -> taskMapper.selectById(task.getId()).getStatus() == ManagementTaskStatus.FAILED,
                "task FAILED");
        assertThat(comicMapper.selectById(comic.getId()).getStatus()).isEqualTo(ComicStatus.READY);
        // 零提交
        assertThat(mediaMapper.selectById(101L).getHqSize()).isEqualTo(1000L);
        assertThat(mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getHqPath, "1/42/004.jpg"))).isZero();
        assertThat(outboxMapper.selectCount(new LambdaQueryWrapper<OutboxMessage>()
                .eq(OutboxMessage::getEventType, "MetadataRefreshEvent"))).isZero();
        // 快照保留供重试/排查
        assertThat(Files.exists(STAGING_TMP.resolve(snapshotRef(task.getId(), item.getId(), 1)))).isTrue();
    }

    // ======================== 失败：未知章节 ========================

    @Test
    @DisplayName("未知章节：FAILED + 零提交")
    void unknownChapter_failedAndZeroCommit() throws Exception {
        setComicRefreshing();
        ManagementTask task = createRefreshTask();
        ManagementTaskItem item = createRefreshItem(task.getId());

        // 快照额外携带 DB 不存在的 chapterId=999
        byte[] bytes = writeValidSnapshot(task.getId(), item.getId(), 1,
                List.of(new MediaSnapshot(null, 0, "1/999/001.jpg", "READY", "READY", 0,
                        123L, "IMAGE", null, null, null, null, null, null)));
        var completed = new MetadataRefreshScanCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                task.getId(), item.getId(), 1, "METADATA_REFRESH", "COMIC", comic.getId(),
                snapshotRef(task.getId(), item.getId(), 1), sha256(bytes), bytes.length, 1);
        rabbitTemplate.convertAndSend("comic.management", "command.completed", completed);

        await(() -> taskItemMapper.selectById(item.getId()).getStatus() == ManagementTaskStatus.FAILED,
                "item FAILED");
        assertThat(mediaMapper.selectById(101L).getHqSize()).isEqualTo(1000L);
        assertThat(mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, 999L))).isZero();
    }

    // ======================== 失败：revision 并发漂移 ========================

    @Test
    @DisplayName("apply 前章节 version 并发变化：漂移 FAILED + 零提交")
    void revisionDrift_failedAndZeroCommit() throws Exception {
        setComicRefreshing();
        ManagementTask task = createRefreshTask();
        ManagementTaskItem item = createRefreshItem(task.getId());

        byte[] bytes = writeValidSnapshot(task.getId(), item.getId(), 1, null);
        // 快照构建后、事件消费前并发修改章节版本
        chapterMapper.update(null, new LambdaUpdateWrapper<Chapter>()
                .eq(Chapter::getId, 42L).set(Chapter::getVersion, 99));
        var completed = new MetadataRefreshScanCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                task.getId(), item.getId(), 1, "METADATA_REFRESH", "COMIC", comic.getId(),
                snapshotRef(task.getId(), item.getId(), 1), sha256(bytes), bytes.length, 1);
        rabbitTemplate.convertAndSend("comic.management", "command.completed", completed);

        await(() -> taskItemMapper.selectById(item.getId()).getStatus() == ManagementTaskStatus.FAILED,
                "item FAILED");
        assertThat(mediaMapper.selectById(101L).getHqSize()).isEqualTo(1000L);
        assertThat(mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getHqPath, "1/42/004.jpg"))).isZero();
    }

    // ======================== 幂等：重复事件 ========================

    @Test
    @DisplayName("重复事件重放：Inbox 幂等，业务不二次 apply")
    void duplicateEvent_notAppliedTwice() throws Exception {
        OperationSubmitResultDTO result = commandService.requestMetadataRefresh(comic.getId());
        ManagementCommandRequestedEvent cmd = readSingleCommand(result.getTaskId());

        byte[] bytes = writeValidSnapshot(cmd.taskId(), cmd.itemId(), 1, null);
        MetadataRefreshScanCompletedEvent completed = new MetadataRefreshScanCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                cmd.taskId(), cmd.itemId(), 1, "METADATA_REFRESH", "COMIC", comic.getId(),
                snapshotRef(cmd.taskId(), cmd.itemId(), 1), sha256(bytes), bytes.length, 1);
        rabbitTemplate.convertAndSend("comic.management", "command.completed", completed);
        await(() -> taskMapper.selectById(cmd.taskId()).getStatus() == ManagementTaskStatus.SUCCEEDED,
                "task SUCCEEDED");

        // 重放同一事件（同 eventId）
        rabbitTemplate.convertAndSend("comic.management", "command.completed", completed);
        await(() -> inboxMapper.selectCount(new LambdaQueryWrapper<com.comicatlas.api.outbox.entity.InboxReceipt>()
                .eq(com.comicatlas.api.outbox.entity.InboxReceipt::getEventId,
                        completed.eventId().toString())) == 1, "Inbox 记录");
        Thread.sleep(800);
        // 不二次 apply：新增 004.jpg 仍只有 1 行、101 尺寸不被覆盖
        assertThat(mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getHqPath, "1/42/004.jpg"))).isEqualTo(1);
        assertThat(mediaMapper.selectById(101L).getHqSize()).isEqualTo(2500L);
        assertThat(outboxMapper.selectCount(new LambdaQueryWrapper<OutboxMessage>()
                .eq(OutboxMessage::getEventType, "MetadataRefreshEvent"))).isEqualTo(1);
    }

    // ======================== 幂等：旧 attempt 忽略 ========================

    @Test
    @DisplayName("旧 attempt 的 completed → ACK 不 apply")
    void oldAttemptCompleted_ignored() throws Exception {
        setComicRefreshing();
        ManagementTask task = createRefreshTask();
        ManagementTaskItem item = createRefreshItem(task.getId());
        // 模拟 retry 后 attempt 已推进到 2
        taskItemMapper.update(null, new LambdaUpdateWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getId, item.getId())
                .set(ManagementTaskItem::getAttempt, 2));

        byte[] bytes = writeValidSnapshot(task.getId(), item.getId(), 1, null);
        var completed = new MetadataRefreshScanCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                task.getId(), item.getId(), 1, "METADATA_REFRESH", "COMIC", comic.getId(),
                snapshotRef(task.getId(), item.getId(), 1), sha256(bytes), bytes.length, 1);
        rabbitTemplate.convertAndSend("comic.management", "command.completed", completed);

        Thread.sleep(800);
        assertThat(taskItemMapper.selectById(item.getId()).getAttempt()).isEqualTo(2);
        assertThat(taskItemMapper.selectById(item.getId()).getStatus()).isEqualTo(ManagementTaskStatus.RUNNING);
        assertThat(comicMapper.selectById(comic.getId()).getStatus()).isEqualTo(ComicStatus.REFRESHING);
        assertThat(mediaMapper.selectById(101L).getHqSize()).isEqualTo(1000L);
    }

    // ======================== 基础设施：快照缺失 → DLQ ========================

    @Test
    @DisplayName("快照产物缺失 → 基础设施故障 DLQ，不伪造成功")
    void snapshotMissing_goesToDlq() throws Exception {
        setComicRefreshing();
        ManagementTask task = createRefreshTask();
        ManagementTaskItem item = createRefreshItem(task.getId());

        // 引用不存在的快照文件
        String ref = snapshotRef(task.getId(), item.getId(), 1);
        var completed = new MetadataRefreshScanCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                task.getId(), item.getId(), 1, "METADATA_REFRESH", "COMIC", comic.getId(),
                ref, "deadbeef", 0L, 1);
        rabbitTemplate.convertAndSend("comic.management", "command.completed", completed);

        await(() -> rabbitTemplate.receive("management.result.dlq", 500) != null, "事件进入 DLQ");
        assertThat(taskItemMapper.selectById(item.getId()).getStatus()).isEqualTo(ManagementTaskStatus.RUNNING);
        assertThat(comicMapper.selectById(comic.getId()).getStatus()).isEqualTo(ComicStatus.REFRESHING);
        assertThat(outboxMapper.selectCount(new LambdaQueryWrapper<OutboxMessage>()
                .eq(OutboxMessage::getEventType, "MetadataRefreshEvent"))).isZero();
    }

    // ======================== retry ========================

    @Test
    @DisplayName("FAILED 后 retry：attempt+1 重新扫描并成功")
    void retryAfterFailure_succeedsOnNewAttempt() throws Exception {
        OperationSubmitResultDTO result = commandService.requestMetadataRefresh(comic.getId());
        ManagementCommandRequestedEvent cmd = readSingleCommand(result.getTaskId());

        // 第一次：篡改摘要 → FAILED
        byte[] bytes1 = writeValidSnapshot(cmd.taskId(), cmd.itemId(), 1, null);
        var bad = new MetadataRefreshScanCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                cmd.taskId(), cmd.itemId(), 1, "METADATA_REFRESH", "COMIC", comic.getId(),
                snapshotRef(cmd.taskId(), cmd.itemId(), 1), "deadbeef", bytes1.length, 1);
        rabbitTemplate.convertAndSend("comic.management", "command.completed", bad);
        await(() -> taskMapper.selectById(cmd.taskId()).getStatus() == ManagementTaskStatus.FAILED,
                "task FAILED");

        // retry → attempt=2，命令重新入 outbox，comic 回到 REFRESHING
        assertThat(managementTaskService.retryTask(cmd.taskId()).getAttempt()).isEqualTo(2);
        await(() -> outboxMapper.selectCount(new LambdaQueryWrapper<OutboxMessage>()
                .eq(OutboxMessage::getTaskId, cmd.taskId())) == 2, "retry 重新发布命令");
        assertThat(comicMapper.selectById(comic.getId()).getStatus()).isEqualTo(ComicStatus.REFRESHING);

        // 第二次：attempt=2 有效快照 → SUCCEEDED
        byte[] bytes2 = writeValidSnapshot(cmd.taskId(), cmd.itemId(), 2, null);
        var good = new MetadataRefreshScanCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                cmd.taskId(), cmd.itemId(), 2, "METADATA_REFRESH", "COMIC", comic.getId(),
                snapshotRef(cmd.taskId(), cmd.itemId(), 2), sha256(bytes2), bytes2.length, 1);
        rabbitTemplate.convertAndSend("comic.management", "command.completed", good);
        await(() -> taskMapper.selectById(cmd.taskId()).getStatus() == ManagementTaskStatus.SUCCEEDED,
                "task SUCCEEDED");
        assertThat(taskMapper.selectById(cmd.taskId()).getAttempt()).isEqualTo(2);
        assertThat(comicMapper.selectById(comic.getId()).getStatus()).isEqualTo(ComicStatus.READY);
        assertThat(mediaMapper.selectById(101L).getHqSize()).isEqualTo(2500L);
        assertThat(mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getHqPath, "1/42/004.jpg"))).isEqualTo(1);
    }

    // ======================== 辅助 ========================

    /** 按 Worker 契约构建 fixture 快照（媒体身份/状态取自 DB，尺寸/大小来自磁盘或扫描实测语义）。 */
    private MetadataRefreshSnapshotDTO buildFixtureSnapshot() throws Exception {
        List<ChapterSnapshot> chapters = List.of(
                new ChapterSnapshot(41L, versionOfChapter(41L), List.of(
                        new MediaSnapshot(1001L, versionOfMedia(1001L), "1/41/001.jpg",
                                "READY", "READY", 1, Files.size(hq("1/41/001.jpg")),
                                "IMAGE", 800, 1200, null, null, null, null)),
                        List.of("无告警")),
                new ChapterSnapshot(42L, versionOfChapter(42L), List.of(
                        new MediaSnapshot(101L, versionOfMedia(101L), "1/42/001.jpg",
                                "READY", "READY", 1, Files.size(hq("1/42/001.jpg")),
                                "IMAGE", 800, 1200, null, null, null, null),
                        new MediaSnapshot(102L, versionOfMedia(102L), "1/42/002.mp4",
                                "READY", "READY", 2, Files.size(hq("1/42/002.mp4")),
                                "VIDEO", 1280, 720, new BigDecimal("12.500"), "mp4", "h264", "aac"),
                        new MediaSnapshot(null, 0, "1/42/004.jpg",
                                "READY", "READY", 0, Files.size(hq("1/42/004.jpg")),
                                "IMAGE", 400, 600, null, null, null, null),
                        new MediaSnapshot(106L, versionOfMedia(106L), "1/42/006.jpg",
                                "DELETED", "DELETED", -106, Files.size(hq("1/42/006.jpg")),
                                "IMAGE", null, null, null, null, null, null)),
                        List.of("忽略隐藏文件: .hidden.jpg", "忽略未知扩展名: notes.txt",
                                "物理文件无对应DB记录: 005.jpg", "物理文件无对应DB记录: link.jpg")));
        return new MetadataRefreshSnapshotDTO(1, comic.getId(), Instant.now(), "", chapters);
    }

    private byte[] writeValidSnapshot(Long taskId, Long itemId, int attempt,
                                      List<MediaSnapshot> extraMediaItems) throws Exception {
        MetadataRefreshSnapshotDTO snapshot = buildFixtureSnapshot();
        if (extraMediaItems != null) {
            List<ChapterSnapshot> chapters = new java.util.ArrayList<>(snapshot.chapters());
            List<MediaSnapshot> ch42Items = new java.util.ArrayList<>(chapters.get(1).mediaItems());
            ch42Items.addAll(extraMediaItems);
            chapters.set(1, new ChapterSnapshot(chapters.get(1).chapterId(), chapters.get(1).chapterVersion(),
                    ch42Items, chapters.get(1).warnings()));
            snapshot = new MetadataRefreshSnapshotDTO(snapshot.schemaVersion(), snapshot.comicId(),
                    snapshot.generatedAt(), "", chapters);
        }
        String revision = MetadataSnapshotRevision.compute(snapshot);
        MetadataRefreshSnapshotDTO withRevision = new MetadataRefreshSnapshotDTO(
                snapshot.schemaVersion(), snapshot.comicId(), snapshot.generatedAt(), revision, snapshot.chapters());
        byte[] bytes = objectMapper.writeValueAsBytes(withRevision);
        Path snapshotFile = STAGING_TMP.resolve(snapshotRef(taskId, itemId, attempt));
        Files.createDirectories(snapshotFile.getParent());
        Files.write(snapshotFile, bytes);
        return bytes;
    }

    private void setComicRefreshing() {
        comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                .eq(Comic::getId, comic.getId()).set(Comic::getStatus, ComicStatus.REFRESHING));
    }

    private ManagementTask createRefreshTask() {
        ManagementTask task = new ManagementTask();
        task.setTaskType(TaskType.METADATA_REFRESH);
        task.setOperation("元数据刷新");
        task.setTargetType("COMIC");
        task.setStatus(ManagementTaskStatus.RUNNING);
        task.setTotalCount(1);
        task.setAttempt(1);
        taskMapper.insert(task);
        return task;
    }

    private ManagementTaskItem createRefreshItem(Long taskId) {
        ManagementTaskItem item = new ManagementTaskItem();
        item.setTaskId(taskId);
        item.setTargetType("COMIC");
        item.setTargetId(comic.getId());
        item.setOperationType(TaskType.METADATA_REFRESH);
        item.setStatus(ManagementTaskStatus.RUNNING);
        item.setAttempt(1);
        item.setLockKey(ManagementTaskItem.buildLockKey("COMIC", comic.getId(), TaskType.METADATA_REFRESH));
        taskItemMapper.insert(item);
        return item;
    }

    private String snapshotRef(Long taskId, Long itemId, int attempt) {
        return "metadata-refresh/" + taskId + "/" + itemId + "/" + attempt + "/snapshot.json";
    }

    private int versionOfChapter(Long chapterId) {
        Chapter ch = chapterMapper.selectById(chapterId);
        return ch.getVersion() == null ? 0 : ch.getVersion();
    }

    private int versionOfMedia(Long mediaId) {
        Media m = mediaMapper.selectById(mediaId);
        return m.getVersion() == null ? 0 : m.getVersion();
    }

    private static Path hq(String relative) {
        return HQ_TMP.resolve(relative);
    }

    private static void writeFile(Path path, int bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "x".repeat(bytes));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private ManagementCommandRequestedEvent readSingleCommand(Long taskId) throws Exception {
        List<OutboxMessage> rows = outboxMapper.selectList(
                new LambdaQueryWrapper<OutboxMessage>().eq(OutboxMessage::getTaskId, taskId));
        assertThat(rows).hasSize(1);
        return objectMapper.readValue(rows.get(0).getPayload(), ManagementCommandRequestedEvent.class);
    }

    private void await(Supplier<Boolean> cond, String desc) {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(cond.get())) {
                    return;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待被中断: " + desc);
            }
        }
        fail("等待超时: " + desc);
    }

    private Chapter chapter(Long id, Long comicId, int globalOrder) {
        Chapter ch = new Chapter();
        ch.setId(id);
        ch.setComicId(comicId);
        ch.setTitle("章节" + globalOrder);
        ch.setChapterNo(String.valueOf(globalOrder));
        ch.setGlobalOrder(globalOrder);
        ch.setSortOrder(globalOrder);
        ch.setStatus(ChapterLifecycleStatus.READY);
        ch.setPageCount(1);
        ch.setVersion(1);
        return ch;
    }

    private static Media image(Long id, Long chapterId, int pageNumber, String hqPath,
                               long fileSize, LqStatus lqStatus, String lifecycleStatus) {
        Media m = new Media();
        m.setId(id);
        m.setChapterId(chapterId);
        m.setPageNumber(pageNumber);
        m.setMediaType("IMAGE");
        m.setHqRoot("HQ");
        m.setHqPath(hqPath);
        m.setHqStatus(HqStatus.READY);
        m.setLqStatus(lqStatus);
        m.setTranscodeStatus(TranscodeStatus.NOT_NEEDED);
        m.setHqSize(fileSize);
        m.setStatus(MediaLifecycleStatus.valueOf(lifecycleStatus));
        m.setWidth(800);
        m.setHeight(1200);
        m.setVersion(1);
        return m;
    }

    private static Media video(Long id, Long chapterId, int pageNumber, String hqPath, long fileSize) {
        Media m = image(id, chapterId, pageNumber, hqPath, fileSize, LqStatus.NOT_GENERATED, "READY");
        m.setMediaType("VIDEO");
        m.setDuration(new BigDecimal("12.500"));
        m.setContainer("mp4");
        m.setVideoCodec("h264");
        m.setAudioCodec("aac");
        return m;
    }

    private static boolean checkDockerAvailable() {
        try {
            new ProcessBuilder("docker", "info").redirectErrorStream(true).start().waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
