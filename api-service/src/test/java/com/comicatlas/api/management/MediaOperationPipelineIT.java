package com.comicatlas.api.management;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.LqStatus;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.dto.OperationSubmitResultDTO;
import com.comicatlas.api.management.entity.ManagementTask;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import com.comicatlas.api.management.policy.AllowedOperations;
import com.comicatlas.api.management.policy.MediaOperationEligibilityService;
import com.comicatlas.api.management.policy.OperationPolicyService;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.outbox.entity.OutboxMessage;
import com.comicatlas.api.outbox.mapper.InboxReceiptMapper;
import com.comicatlas.api.outbox.mapper.OutboxMessageMapper;
import com.comicatlas.api.outbox.relay.OutboxRelay;
import com.comicatlas.api.common.enums.ChapterLifecycleStatus;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.api.common.enums.TranscodeStatus;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.ChapterSnapshot;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.MediaSnapshot;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.common.util.MetadataSnapshotRevision;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;


/**
 * 媒体操作统一任务管线集成测试（TDD）。
 *
 * <p>验证：
 * <ul>
 *   <li>LQ/HQ/转码/刷新命令创建 ManagementTask 并返回 taskId</li>
 *   <li>命令恰一次生效：重复/乱序/旧 attempt 结果不重复扣减或改写业务</li>
 *   <li>并发双 POST 只创建一条当前命令（target lock）</li>
 *   <li>HQ 前置条件（全部图片 LQ READY）不满足返回 409</li>
 *   <li>worker crash/retry 后任务终态可查、统计与页面 refs 重算一致</li>
 *   <li>按钮所需状态（allowedOperations）可查询</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("MediaOperationPipelineIT — 媒体操作统一任务管线")
class MediaOperationPipelineIT {

    private static boolean dockerAvailable;
    static { dockerAvailable = checkDockerAvailable(); }

    /** 集成测试专用 STAGING 根（快照产物落盘/清理校验）。 */
    private static final Path STAGING_TMP = createStagingTempDir();

    private static Path createStagingTempDir() {
        try {
            return Files.createTempDirectory("comic-atlas-staging-it");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

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
    }

    @Autowired private MediaOperationCommandService commandService;
    @Autowired private ManagementTaskService managementTaskService;
    @Autowired private MediaOperationEligibilityService eligibilityService;
    @Autowired private MediaMapper mediaMapper;
    @Autowired private ChapterMapper chapterMapper;
    @Autowired private ComicMapper comicMapper;
    @Autowired private OutboxMessageMapper outboxMapper;
    @Autowired private InboxReceiptMapper inboxMapper;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.comicatlas.api.management.mapper.ManagementTaskMapper taskMapper;
    @Autowired private com.comicatlas.api.management.mapper.ManagementTaskItemMapper taskItemMapper;

    private Comic comic;
    private Chapter chapter1;
    private Chapter chapter2;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(dockerAvailable, "Docker 不可用，跳过容器化集成测试");
        cleanup();
        cleanupStaging();
        comic = new Comic();
        comic.setTitle("测试漫画");
        comic.setStatus(ComicStatus.READY);
        comic.setStoragePolicy("MANAGED");
        comic.setHqSize(7000L);
        comic.setFileSize(7000L);
        comicMapper.insert(comic);

        chapter1 = chapter(comic.getId(), 1);
        chapter2 = chapter(comic.getId(), 2);
        chapterMapper.insert(chapter1);
        chapterMapper.insert(chapter2);

        // chapter1: 2 张 IMAGE 页（lq NOT_GENERATED, hq READY）
        mediaMapper.insert(image(chapter1.getId(), 1, "1/1/001.jpg", 1000L));
        mediaMapper.insert(image(chapter1.getId(), 2, "1/1/002.jpg", 2000L));
        // chapter2: 1 张 IMAGE 页 + 1 个 VIDEO 页（avi 需转码）
        mediaMapper.insert(image(chapter2.getId(), 1, "1/2/001.jpg", 4000L));
        mediaMapper.insert(video(chapter2.getId(), 2, "1/2/002.avi"));
    }

    @AfterEach
    void tearDown() {
        if (dockerAvailable) {
            cleanup();
        }
    }

    private void cleanup() {
        try {
            if (taskItemMapper != null) { taskItemMapper.delete(new LambdaQueryWrapper<>()); }
            if (taskMapper != null) { taskMapper.delete(new LambdaQueryWrapper<>()); }
            if (inboxMapper != null) { inboxMapper.delete(new LambdaQueryWrapper<>()); }
            if (outboxMapper != null) { outboxMapper.delete(new LambdaQueryWrapper<>()); }
            if (mediaMapper != null) { mediaMapper.delete(new LambdaQueryWrapper<>()); }
            if (chapterMapper != null) { chapterMapper.delete(new LambdaQueryWrapper<>()); }
            if (comicMapper != null) { comicMapper.delete(new LambdaQueryWrapper<>()); }
        } catch (Exception ignored) {
        }
    }

    // ======================== LQ 命令恰一次生效 ========================

    @Test
    @DisplayName("LQ 命令：创建任务、命令入 outbox、完成恰好一次生效")
    void lqCommand_appliesExactlyOnce() throws Exception {
        OperationSubmitResultDTO result = commandService.requestLqForChapter(chapter1.getId(), false);
        assertThat(result.getTaskId()).isNotNull();
        assertThat(result.getItemCount()).isEqualTo(1);

        // 页面标记 QUEUED（仅非 READY）
        assertThat(lqStatuses(chapter1.getId())).containsExactly("QUEUED", "QUEUED");

        // outbox 中有一条命令
        ManagementCommandRequestedEvent cmd = readSingleCommand(result.getTaskId());
        assertThat(cmd.operationType()).isEqualTo("LQ_GENERATE");
        assertThat(cmd.targetType()).isEqualTo("CHAPTER");
        assertThat(cmd.targetId()).isEqualTo(chapter1.getId());
        assertThat(cmd.attempt()).isEqualTo(1);

        outboxRelay.relay();
        await(() -> outboxMapper.selectById(cmd.eventId().toString()).getStatus().equals("PUBLISHED"), "命令发布");

        // Worker 进度 → RUNNING + GENERATING
        publishProgress(cmd.taskId(), cmd.itemId(), cmd.attempt(), "LQ_GENERATE", "CHAPTER", chapter1.getId(), 50, "生成中");
        await(() -> managementTaskService.getTask(cmd.taskId()).getStatus() == ManagementTaskStatus.RUNNING, "任务 RUNNING");
        await(() -> lqStatuses(chapter1.getId()).stream().allMatch("GENERATING"::equals), "页面 GENERATING");

        // Worker 完成 → SUCCEEDED + READY
        ManagementCommandCompletedEvent completed = new ManagementCommandCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                cmd.taskId(), cmd.itemId(), cmd.attempt(),
                "LQ_GENERATE", "CHAPTER", chapter1.getId(), null);
        rabbitTemplate.convertAndSend("comic.management", "command.completed", completed);
        await(() -> managementTaskService.getTask(cmd.taskId()).getStatus() == ManagementTaskStatus.SUCCEEDED, "任务 SUCCEEDED");
        await(() -> lqStatuses(chapter1.getId()).stream().allMatch("READY"::equals), "页面 READY");

        // 重复投递同一事件 → Inbox 幂等，业务不二次生效
        rabbitTemplate.convertAndSend("comic.management", "command.completed", completed);
        await(() -> inboxMapper.selectById(completed.eventId().toString()) != null, "Inbox 记录");
        Thread.sleep(800);
        assertThat(lqStatuses(chapter1.getId())).containsExactly("READY", "READY");
        // 该完成事件只有 1 条 receipt（重复投递被幂等跳过）
        assertThat(inboxMapper.selectCount(new LambdaQueryWrapper<com.comicatlas.api.outbox.entity.InboxReceipt>()
                .eq(com.comicatlas.api.outbox.entity.InboxReceipt::getEventId, completed.eventId().toString())))
                .isEqualTo(1);
    }

    // ======================== 旧 attempt 结果不生效 + retry ========================

    @Test
    @DisplayName("旧 attempt 结果不生效；retry 后新 attempt 生效且任务终态可查")
    void staleAttemptResultIgnored_andRetryWorks() throws Exception {
        OperationSubmitResultDTO result = commandService.requestLqForChapter(chapter2.getId(), false);
        ManagementCommandRequestedEvent cmd = readSingleCommand(result.getTaskId());

        // 第一次失败
        rabbitTemplate.convertAndSend("comic.management", "command.failed",
                new ManagementCommandFailedEvent(UUID.randomUUID(), Instant.now(), 1,
                        cmd.taskId(), cmd.itemId(), 1, "LQ_GENERATE", "CHAPTER", chapter2.getId(), "磁盘不足"));
        await(() -> managementTaskService.getTask(cmd.taskId()).getStatus() == ManagementTaskStatus.FAILED, "任务 FAILED");
        await(() -> lqStatuses(chapter2.getId()).stream().allMatch("FAILED"::equals), "页面 FAILED");

        // retry → attempt=2，命令重新入 outbox
        ManagementTaskResponse retried = managementTaskService.retryTask(cmd.taskId());
        assertThat(retried.getAttempt()).isEqualTo(2);
        await(() -> outboxMapper.selectCount(new LambdaQueryWrapper<OutboxMessage>()
                .eq(OutboxMessage::getTaskId, cmd.taskId())) == 2, "retry 重新发布命令");

        // 旧 attempt=1 的完成结果 → 忽略，业务不生效
        rabbitTemplate.convertAndSend("comic.management", "command.completed",
                new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                        cmd.taskId(), cmd.itemId(), 1, "LQ_GENERATE", "CHAPTER", chapter2.getId(), null));
        Thread.sleep(800);
        ManagementTaskItemResponse itemAfterStale = managementTaskService.getTaskItems(cmd.taskId()).get(0);
        assertThat(itemAfterStale.getAttempt()).isEqualTo(2);
        assertThat(itemAfterStale.getStatus()).isEqualTo(ManagementTaskStatus.QUEUED);
        assertThat(lqStatuses(chapter2.getId())).containsExactly("FAILED");

        // 新 attempt=2 完成 → SUCCEEDED + READY
        rabbitTemplate.convertAndSend("comic.management", "command.completed",
                new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                        cmd.taskId(), cmd.itemId(), 2, "LQ_GENERATE", "CHAPTER", chapter2.getId(), null));
        await(() -> managementTaskService.getTask(cmd.taskId()).getStatus() == ManagementTaskStatus.SUCCEEDED, "任务 SUCCEEDED");
        await(() -> lqStatuses(chapter2.getId()).contains("READY"), "页面 READY");
        assertThat(managementTaskService.getTask(cmd.taskId()).getAttempt()).isEqualTo(2);
    }

    // ======================== HQ 前置条件 409 ========================

    @Test
    @DisplayName("HQ 删除前置条件：LQ 非全部 READY 时 409")
    void hqDelete_preconditionFails_throws409() {
        // chapter1 的 LQ 仍是 NOT_GENERATED
        assertThatThrownBy(() -> commandService.requestHqDeleteForChapter(chapter1.getId()))
                .isInstanceOf(ConflictException.class);
    }

    // ======================== HQ 删除 + 统计重算 ========================

    @Test
    @DisplayName("HQ 删除完成：页面 DELETED、comic.hqSize 从剩余 refs 重算")
    void hqDelete_completed_recomputesHqSize() throws Exception {
        // 让 chapter1 的 LQ 全部 READY（满足前置条件）
        setLqReady(chapter1.getId());

        OperationSubmitResultDTO result = commandService.requestHqDeleteForChapter(chapter1.getId());
        assertThat(result.getTaskId()).isNotNull();
        assertThat(hqStatuses(chapter1.getId())).containsExactly("DELETE_QUEUED", "DELETE_QUEUED");

        ManagementCommandRequestedEvent cmd = readSingleCommand(result.getTaskId());

        publishProgress(cmd.taskId(), cmd.itemId(), cmd.attempt(), "HQ_DELETE", "CHAPTER", chapter1.getId(), 30, "删除中");
        await(() -> hqStatuses(chapter1.getId()).stream().allMatch("DELETING"::equals), "页面 DELETING");

        rabbitTemplate.convertAndSend("comic.management", "command.completed",
                new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                        cmd.taskId(), cmd.itemId(), 1, "HQ_DELETE", "CHAPTER", chapter1.getId(), null));
        await(() -> managementTaskService.getTask(cmd.taskId()).getStatus() == ManagementTaskStatus.SUCCEEDED, "任务 SUCCEEDED");
        await(() -> hqStatuses(chapter1.getId()).stream().allMatch("DELETED"::equals), "页面 DELETED");

        // chapter1 的页面 hq_path 已清空
        List<Media> ch1Pages = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapter1.getId()));
        assertThat(ch1Pages).allSatisfy(p -> {
            assertThat(p.getHqPath()).isNull();
            assertThat(p.getHqRoot()).isNull();
        });

        // 统计与重扫一致：comic.hqSize = 剩余未删除页面 fileSize 之和（chapter2 图片 4000 + 视频 5000）
        Comic reloaded = comicMapper.selectById(comic.getId());
        assertThat(reloaded.getHqSize()).isEqualTo(9000L);
    }

    // ======================== 并发双 POST 只发一条当前命令 ========================

    @Test
    @DisplayName("并发双 POST：target lock 只允许一个活跃命令")
    void concurrentPosts_onlyOneActiveCommand() throws Exception {
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ConcurrentLinkedQueue<OperationSubmitResultDTO> ok = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    ok.add(commandService.requestLqForChapter(chapter1.getId(), false));
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(ok).hasSize(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.peek()).isInstanceOf(ConflictException.class);

        // 只产生一条命令
        assertThat(outboxMapper.selectCount(new LambdaQueryWrapper<>())).isEqualTo(1);
    }

    // ======================== 转码命令 ========================

    @Test
    @DisplayName("转码命令：逐视频页 item，完成置 READY 且 hqPath 更新为 mp4")
    void transcodeCommand_updatesVideo() throws Exception {
        Media video = mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                .eq(Media::getMediaType, "VIDEO")).get(0);

        OperationSubmitResultDTO result = commandService.requestTranscodeForComic(comic.getId());
        assertThat(result.getTaskId()).isNotNull();
        assertThat(result.getItemCount()).isEqualTo(1);

        ManagementCommandRequestedEvent cmd = readSingleCommand(result.getTaskId());
        assertThat(cmd.targetType()).isEqualTo("MEDIA");
        assertThat(cmd.targetId()).isEqualTo(video.getId());

        assertThat(mediaMapper.selectById(video.getId()).getTranscodeStatus()).isEqualTo(TranscodeStatus.QUEUED);

        publishProgress(cmd.taskId(), cmd.itemId(), cmd.attempt(), "TRANSCODE", "MEDIA", video.getId(), 40, "转码中");
        await(() -> mediaMapper.selectById(video.getId()).getTranscodeStatus() == TranscodeStatus.TRANSCODING, "视频 TRANSCODING");

        rabbitTemplate.convertAndSend("comic.management", "command.completed",
                new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                        cmd.taskId(), cmd.itemId(), 1, "TRANSCODE", "MEDIA", video.getId(), null));
        await(() -> managementTaskService.getTask(cmd.taskId()).getStatus() == ManagementTaskStatus.SUCCEEDED, "任务 SUCCEEDED");
        await(() -> mediaMapper.selectById(video.getId()).getTranscodeStatus() == TranscodeStatus.READY, "视频 READY");

        Media reloaded = mediaMapper.selectById(video.getId());
        assertThat(reloaded.getContainer()).isEqualTo("mp4");
        assertThat(reloaded.getHqPath()).endsWith(".mp4");
    }

    @Test
    @DisplayName("转码并发双提交：CAS 只允许一个成功，另一个 409，只产生一条命令")
    void transcodeConcurrentPosts_onlyOneSucceeds() throws Exception {
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ConcurrentLinkedQueue<OperationSubmitResultDTO> ok = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    ok.add(commandService.requestTranscodeForComic(comic.getId()));
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(ok).hasSize(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.peek()).isInstanceOf(ConflictException.class);

        // 只产生一条命令
        assertThat(outboxMapper.selectCount(new LambdaQueryWrapper<>())).isEqualTo(1);
    }

    // ======================== 元数据刷新命令 ========================

    @Test
    @DisplayName("元数据刷新命令：创建 COMIC item + comic READY→REFRESHING + 命令入 outbox")
    void metadataRefreshCommand_createsTaskAndLocksComic() throws Exception {
        OperationSubmitResultDTO result = commandService.requestMetadataRefresh(comic.getId());
        assertThat(result.getTaskId()).isNotNull();
        assertThat(result.getItemCount()).isEqualTo(1);
        assertThat(comicMapper.selectById(comic.getId()).getStatus()).isEqualTo(ComicStatus.REFRESHING);

        ManagementCommandRequestedEvent cmd = readSingleCommand(result.getTaskId());
        assertThat(cmd.operationType()).isEqualTo("METADATA_REFRESH");
        assertThat(cmd.targetType()).isEqualTo("COMIC");
        assertThat(cmd.targetId()).isEqualTo(comic.getId());
        assertThat(cmd.attempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("元数据刷新命令：漫画不存在 → 404")
    void metadataRefresh_comicNotFound_throws404() {
        assertThatThrownBy(() -> commandService.requestMetadataRefresh(99999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("元数据刷新命令：非 READY 漫画 → 409")
    void metadataRefresh_nonReadyComic_throws409() {
        comic.setStatus(ComicStatus.IMPORTING);
        comicMapper.updateById(comic);

        assertThatThrownBy(() -> commandService.requestMetadataRefresh(comic.getId()))
                .isInstanceOf(ConflictException.class);
        assertThat(taskMapper.selectCount(new LambdaQueryWrapper<>())).isZero();
        assertThat(outboxMapper.selectCount(new LambdaQueryWrapper<>())).isZero();
    }

    @Test
    @DisplayName("元数据刷新命令：并发双提交只有一个成功另一个 409")
    void metadataRefresh_concurrentPosts_onlyOneActiveCommand() throws Exception {
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ConcurrentLinkedQueue<OperationSubmitResultDTO> ok = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    ok.add(commandService.requestMetadataRefresh(comic.getId()));
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(ok).hasSize(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.peek()).isInstanceOf(ConflictException.class);

        // 只产生一条命令
        assertThat(outboxMapper.selectCount(new LambdaQueryWrapper<>())).isEqualTo(1);
    }

    // ======================== 元数据刷新完成事件（Todo 5 专用流程） ========================

    @Test
    @DisplayName("元数据刷新完成：completed → item SUCCEEDED、comic READY、Outbox 入箱并 relay 发布、快照清理")
    void metadataRefreshCompleted_appliesAndPublishes() throws Exception {
        comic.setStatus(ComicStatus.REFRESHING);
        comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                .eq(Comic::getId, comic.getId()).set(Comic::getStatus, ComicStatus.REFRESHING));
        // 使媒体 hqPath 匹配真实 comicId/chapterId（快照结构校验要求 {comicId}/{chapterId}/{fileName}）
        for (Media m : mediaMapper.selectList(new LambdaQueryWrapper<>())) {
            String fileName = m.getHqPath().substring(m.getHqPath().lastIndexOf('/') + 1);
            m.setHqPath(comic.getId() + "/" + m.getChapterId() + "/" + fileName);
            mediaMapper.updateById(m);
        }

        ManagementTask task = metadataRefreshTask();
        taskMapper.insert(task);
        ManagementTaskItem item = metadataRefreshItem(task.getId());
        taskItemMapper.insert(item);

        MetadataRefreshSnapshotDTO snapshot = buildSnapshot(comic.getId());
        String revision = MetadataSnapshotRevision.compute(snapshot);
        MetadataRefreshSnapshotDTO withRevision = new MetadataRefreshSnapshotDTO(
                snapshot.schemaVersion(), snapshot.comicId(), snapshot.generatedAt(), revision, snapshot.chapters());
        byte[] bytes = objectMapper.writeValueAsBytes(withRevision);
        String ref = "metadata-refresh/" + task.getId() + "/" + item.getId() + "/1/snapshot.json";
        Path snapshotFile = STAGING_TMP.resolve(ref);
        Files.createDirectories(snapshotFile.getParent());
        Files.write(snapshotFile, bytes);

        var completed = new MetadataRefreshScanCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                task.getId(), item.getId(), 1, "METADATA_REFRESH", "COMIC", comic.getId(),
                ref, sha256(bytes), bytes.length, 1);
        rabbitTemplate.convertAndSend("comic.management", "command.completed", completed);

        await(() -> taskItemMapper.selectById(item.getId()).getStatus() == ManagementTaskStatus.SUCCEEDED,
                "item SUCCEEDED");
        await(() -> taskMapper.selectById(task.getId()).getStatus() == ManagementTaskStatus.SUCCEEDED,
                "task SUCCEEDED");
        assertThat(comicMapper.selectById(comic.getId()).getStatus()).isEqualTo(ComicStatus.READY);

        // metadata 重导出走 Outbox（MetadataRefreshEvent 入箱，relay 后发 MQ）
        OutboxMessage msg = outboxMapper.selectOne(new LambdaQueryWrapper<OutboxMessage>()
                .eq(OutboxMessage::getEventType, "MetadataRefreshEvent"));
        assertThat(msg).isNotNull();
        assertThat(msg.getExchange()).isEqualTo("comic.export");
        assertThat(msg.getRoutingKey()).isEqualTo("metadata.refresh.requested");

        // 提交后快照目录已清理
        assertThat(Files.exists(snapshotFile.getParent())).isFalse();

        // relay 发布成功
        outboxRelay.relay();
        await(() -> outboxMapper.selectById(msg.getEventId()).getStatus().equals("PUBLISHED"), "relay 发布");
    }

    @Test
    @DisplayName("元数据刷新完成：快照文件不可用 → 基础设施故障 reject/DLQ，不伪造成功")
    void metadataRefreshCompleted_snapshotMissing_goesToDlq() throws Exception {
        comic.setStatus(ComicStatus.REFRESHING);
        comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                .eq(Comic::getId, comic.getId()).set(Comic::getStatus, ComicStatus.REFRESHING));

        ManagementTask task = metadataRefreshTask();
        taskMapper.insert(task);
        ManagementTaskItem item = metadataRefreshItem(task.getId());
        taskItemMapper.insert(item);

        // 快照产物缺失（Worker 未落盘或已被清理）→ loadAndValidate 文件 IO 异常 → DLQ
        String ref = "metadata-refresh/" + task.getId() + "/" + item.getId() + "/1/snapshot.json";
        var completed = new MetadataRefreshScanCompletedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                task.getId(), item.getId(), 1, "METADATA_REFRESH", "COMIC", comic.getId(),
                ref, "deadbeef", 0L, 1);
        rabbitTemplate.convertAndSend("comic.management", "command.completed", completed);

        // 事件进入 management.result.dlq；item/comic 保持原状态（不伪造成功）
        await(() -> rabbitTemplate.receive("management.result.dlq", 500) != null, "事件进入 DLQ");
        assertThat(taskItemMapper.selectById(item.getId()).getStatus()).isEqualTo(ManagementTaskStatus.RUNNING);
        assertThat(comicMapper.selectById(comic.getId()).getStatus()).isEqualTo(ComicStatus.REFRESHING);
        assertThat(outboxMapper.selectCount(new LambdaQueryWrapper<OutboxMessage>()
                .eq(OutboxMessage::getEventType, "MetadataRefreshEvent"))).isZero();
    }

    private ManagementTask metadataRefreshTask() {
        ManagementTask task = new ManagementTask();
        task.setTaskType(TaskType.METADATA_REFRESH);
        task.setOperation("元数据刷新");
        task.setTargetType("COMIC");
        task.setStatus(ManagementTaskStatus.RUNNING);
        task.setTotalCount(1);
        task.setAttempt(1);
        return task;
    }

    private ManagementTaskItem metadataRefreshItem(Long taskId) {
        ManagementTaskItem item = new ManagementTaskItem();
        item.setTaskId(taskId);
        item.setTargetType("COMIC");
        item.setTargetId(comic.getId());
        item.setOperationType(TaskType.METADATA_REFRESH);
        item.setStatus(ManagementTaskStatus.RUNNING);
        item.setAttempt(1);
        item.setLockKey(ManagementTaskItem.buildLockKey("COMIC", comic.getId(), TaskType.METADATA_REFRESH));
        return item;
    }

    /** 从 DB 真实章节/媒体行构建快照（版本与 hqPath 取自 DB，保证 revision 一致）。 */
    private MetadataRefreshSnapshotDTO buildSnapshot(Long comicId) {
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        List<ChapterSnapshot> chapterSnapshots = new ArrayList<>();
        for (Chapter ch : chapters) {
            List<Media> mediaItems = mediaMapper.selectList(
                    new LambdaQueryWrapper<Media>().eq(Media::getChapterId, ch.getId()));
            List<MediaSnapshot> mediaSnapshots = mediaItems.stream().map(m -> new MediaSnapshot(
                    m.getId(),
                    m.getVersion() == null ? 0 : m.getVersion(),
                    m.getHqPath(),
                    m.getHqStatus() == null ? "READY" : m.getHqStatus().name(),
                    m.getStatus() == null ? "READY" : m.getStatus().name(),
                    m.getPageNumber() == null ? 0 : m.getPageNumber(),
                    m.getFileSize() == null ? 0L : m.getFileSize(),
                    m.getMediaType(),
                    m.getWidth(), m.getHeight(),
                    m.getDuration(), m.getContainer(), m.getVideoCodec(), m.getAudioCodec()
            )).toList();
            chapterSnapshots.add(new ChapterSnapshot(
                    ch.getId(), ch.getVersion() == null ? 0 : ch.getVersion(), mediaSnapshots, List.of()));
        }
        return new MetadataRefreshSnapshotDTO(1, comicId, Instant.now(), null, chapterSnapshots);
    }

    private void cleanupStaging() {
        if (STAGING_TMP == null || !Files.exists(STAGING_TMP)) {
            return;
        }
        try (var entries = Files.list(STAGING_TMP)) {
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

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // ======================== allowedOperations 可查询 ========================

    @Test
    @DisplayName("按钮所需状态可查询：LQ 前/后与 HQ 前后 allowedOperations 正确")
    void allowedOperations_queryable() {
        AllowedOperations before = eligibilityService.forChapter(chapter1.getId());
        assertThat(before.isAllowed(OperationPolicyService.OP_LQ_GENERATE)).isTrue();
        assertThat(before.isAllowed(OperationPolicyService.OP_HQ_DELETE)).isFalse();
        assertThat(before.blockedReasons()).containsKey(OperationPolicyService.OP_HQ_DELETE);

        // 全部 LQ READY 后 HQ_DELETE 可用
        setLqReady(chapter1.getId());
        AllowedOperations after = eligibilityService.forChapter(chapter1.getId());
        assertThat(after.isAllowed(OperationPolicyService.OP_HQ_DELETE)).isTrue();
        assertThat(after.isAllowed(OperationPolicyService.OP_LQ_GENERATE)).isFalse();
        assertThat(after.isAllowed(OperationPolicyService.OP_LQ_REGENERATE)).isTrue();

        // 视频页 TRANSCODE 可用
        Media video = mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                .eq(Media::getMediaType, "VIDEO")).get(0);
        assertThat(eligibilityService.forMedia(video.getId()).isAllowed(OperationPolicyService.OP_TRANSCODE)).isTrue();

        AllowedOperations comicOps = eligibilityService.forComic(comic.getId());
        assertThat(comicOps.isAllowed(OperationPolicyService.OP_METADATA_REFRESH)).isTrue();
    }

    // ======================== 辅助 ========================

    private void publishProgress(Long taskId, Long itemId, int attempt, String op,
                                 String targetType, Long targetId, int progress, String stage) {
        rabbitTemplate.convertAndSend("comic.management", "command.progress",
                new ManagementCommandProgressEvent(UUID.randomUUID(), Instant.now(), 1,
                        taskId, itemId, attempt, op, targetType, targetId, progress, stage));
    }

    private ManagementCommandRequestedEvent readSingleCommand(Long taskId) throws Exception {
        List<OutboxMessage> rows = outboxMapper.selectList(
                new LambdaQueryWrapper<OutboxMessage>().eq(OutboxMessage::getTaskId, taskId));
        assertThat(rows).hasSize(1);
        return objectMapper.readValue(rows.get(0).getPayload(), ManagementCommandRequestedEvent.class);
    }

    private List<String> lqStatuses(Long chapterId) {
        return mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                        .eq(Media::getChapterId, chapterId)
                        .eq(Media::getMediaType, "IMAGE"))
                .stream().map(m -> m.getLqStatus() == null ? null : m.getLqStatus().name()).toList();
    }

    private List<String> hqStatuses(Long chapterId) {
        return mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                        .eq(Media::getChapterId, chapterId)
                        .eq(Media::getMediaType, "IMAGE"))
                .stream().map(m -> m.getHqStatus() == null ? null : m.getHqStatus().name()).toList();
    }

    private void setLqReady(Long chapterId) {
        List<Media> pages = mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getMediaType, "IMAGE"));
        for (Media p : pages) {
            p.setLqStatus(LqStatus.READY);
            mediaMapper.updateById(p);
        }
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

    private Chapter chapter(Long comicId, int order) {
        Chapter ch = new Chapter();
        ch.setComicId(comicId);
        ch.setTitle("章节" + order);
        ch.setChapterNo(String.valueOf(order));
        ch.setGlobalOrder(order);
        ch.setSortOrder(order);
        ch.setStatus(ChapterLifecycleStatus.READY);
        ch.setPageCount(1);
        return ch;
    }

    private static Media image(Long chapterId, int pageNumber, String hqPath, long fileSize) {
        Media m = new Media();
        m.setChapterId(chapterId);
        m.setPageNumber(pageNumber);
        m.setMediaType("IMAGE");
        m.setHqRoot("HQ");
        m.setHqPath(hqPath);
        m.setHqStatus(HqStatus.READY);
        m.setLqStatus(LqStatus.NOT_GENERATED);
        m.setTranscodeStatus(TranscodeStatus.NOT_NEEDED);
        m.setFileSize(fileSize);
        m.setStatus(MediaLifecycleStatus.READY);
        return m;
    }

    private static Media video(Long chapterId, int pageNumber, String hqPath) {
        Media m = new Media();
        m.setChapterId(chapterId);
        m.setPageNumber(pageNumber);
        m.setMediaType("VIDEO");
        m.setHqRoot("HQ");
        m.setHqPath(hqPath);
        m.setHqStatus(HqStatus.READY);
        m.setLqStatus(LqStatus.NOT_GENERATED);
        // avi 不兼容 → REQUIRED（与导入 VideoCompatibilityPolicy 判定一致，手动转码可选中）
        m.setTranscodeStatus(TranscodeStatus.REQUIRED);
        m.setContainer("avi");
        m.setFileSize(5000L);
        m.setStatus(MediaLifecycleStatus.READY);
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
