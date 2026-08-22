package com.comicatlas.api.management.trash;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.management.mapper.ManagementTaskMapper;
import com.comicatlas.api.management.mapper.TrashManifestMapper;
import com.comicatlas.persistence.reader.entity.ReadingHistory;
import com.comicatlas.persistence.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.management.enums.TaskType;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.task.ManagementCommandPublisher;
import com.comicatlas.worker.recovery.command.PurgeCommandHandler;
import com.comicatlas.worker.recovery.command.RestoreCommandHandler;
import com.comicatlas.worker.recovery.command.TrashCommandHandler;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.recovery.trash.TrashManifestStore;
import com.comicatlas.worker.persistence.mapper.TrashManifestReadMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回收站生命周期集成测试（TDD）— 漫画/章节/媒体 7 天回收、恢复、对账、永久清理。
 * <p>
 * API 上下文 + 内嵌 Worker 处理器（同 JVM 直接调用，结果经真实 RabbitMQ 回传 API）。
 * 覆盖：三种粒度全流程、全部资产根、无文件、部分移动补偿、补偿失败保持 TRASHING、
 * 恢复冲突 RESTORE_CONFLICT、媒体页码冲突插入合法位置、7 天保留期、重复 purge、
 * 阅读面隐藏、reading_history 保留。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.mybatis.spring.annotation.MapperScan("com.comicatlas.worker.persistence.mapper")
@DisplayName("回收站生命周期集成测试")
class TrashLifecycleIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.33")
            .withDatabaseName("comic_atlas_trash_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.12-management-alpine")
            .withAdminPassword("test_rabbit_pass");

    private static final Path MANGA_ROOT = createTempMangaRoot();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        registry.add("outbox.relay.scheduled", () -> "false");
        registry.add("outbox.relay.poll-interval-ms", () -> "600000");
        registry.add("MANGA_ROOT", () -> MANGA_ROOT.toString());
        registry.add("storage.roots.HQ.path", () -> MANGA_ROOT.resolve("hq").toString());
        registry.add("storage.roots.LQ.path", () -> MANGA_ROOT.resolve("lq").toString());
        registry.add("storage.roots.THUMBS.path", () -> MANGA_ROOT.resolve("thumbs").toString());
        registry.add("storage.roots.METADATA.path", () -> MANGA_ROOT.resolve("metadata").toString());
        registry.add("storage.roots.TRASH.path", () -> MANGA_ROOT.resolve("trash").toString());
        registry.add("storage.roots.TRASH.readOnly", () -> "false");
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ComicMapper comicMapper;
    @Autowired
    ChapterMapper chapterMapper;
    @Autowired
    MediaMapper mediaMapper;
    @Autowired
    ManagementTaskMapper taskMapper;
    @Autowired
    ManagementTaskItemMapper itemMapper;
    @Autowired
    TrashManifestMapper trashManifestMapper;
    @Autowired
    ReadingHistoryMapper historyMapper;
    @Autowired
    TrashCommandHandler trashCommandHandler;
    @Autowired
    RestoreCommandHandler restoreCommandHandler;
    @Autowired
    PurgeCommandHandler purgeCommandHandler;
    @Autowired
    RabbitTemplate rabbitTemplate;

    @TestConfiguration
    static class WorkerTrashConfig {

        @Bean
        StorageProperties workerStorage() {
            return new StorageProperties();
        }

        @Bean
        TrashManifestStore trashManifestStore(StorageProperties p, ObjectMapper om,
                                               TrashManifestReadMapper readMapper) {
            return new TrashManifestStore(p, readMapper, om);
        }

        @Bean
        ManagementCommandPublisher managementCommandPublisher(RabbitTemplate rt) {
            return new ManagementCommandPublisher(rt);
        }

        @Bean
        TrashCommandHandler trashCommandHandler(StorageProperties p, TrashManifestStore store,
                                                ManagementCommandPublisher pub) {
            return new TrashCommandHandler(p, store, pub);
        }

        @Bean
        RestoreCommandHandler restoreCommandHandler(StorageProperties p, TrashManifestStore store,
                                                    ManagementCommandPublisher pub) {
            return new RestoreCommandHandler(p, store, pub);
        }

        @Bean
        PurgeCommandHandler purgeCommandHandler(TrashManifestStore store, ManagementCommandPublisher pub) {
            return new PurgeCommandHandler(store, pub);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (itemMapper != null) { itemMapper.delete(new LambdaQueryWrapper<>()); }
        if (taskMapper != null) { taskMapper.delete(new LambdaQueryWrapper<>()); }
        if (historyMapper != null) { historyMapper.delete(new LambdaQueryWrapper<>()); }
        if (mediaMapper != null) { mediaMapper.delete(new LambdaQueryWrapper<>()); }
        if (chapterMapper != null) { chapterMapper.delete(new LambdaQueryWrapper<>()); }
        if (comicMapper != null) { comicMapper.delete(new LambdaQueryWrapper<>()); }
        if (trashManifestMapper != null) { trashManifestMapper.delete(new LambdaQueryWrapper<>()); }
        cleanDir(MANGA_ROOT.resolve("hq"));
        cleanDir(MANGA_ROOT.resolve("lq"));
        cleanDir(MANGA_ROOT.resolve("thumbs"));
        cleanDir(MANGA_ROOT.resolve("metadata"));
        cleanDir(MANGA_ROOT.resolve("trash"));
    }

    // ======================== 媒体全流程 ========================

    @Test
    @DisplayName("媒体：回收→恢复→7 天到期→清理，页码恢复原值")
    void mediaTrashRestorePurge_fullCycle() throws Exception {
        Long comicId = createComic("媒体全流程");
        Long chapterId = createChapter(comicId, "第 1 话");
        Chapter chapter = chapterMapper.selectById(chapterId);
        String hqPath = comicId + "/" + chapter.getGlobalOrder() + "/001.jpg";
        writeFile("hq", hqPath, "media-001");
        Long mediaId = insertMedia(chapterId, 1, hqPath);

        // 回收
        long trashTaskId = trashMedia(mediaId);
        assertThat(mediaMapper.selectById(mediaId).getStatus()).isEqualTo(MediaLifecycleStatus.TRASHING);
        runTrash(mediaId, TaskType.MEDIA_TRASH);
        awaitStatus("MEDIA", mediaId, "TRASHED");

        Media trashed = mediaMapper.selectById(mediaId);
        assertThat(trashed.getHqRoot()).isEqualTo("TRASH");
        assertThat(trashed.getHqPath()).isEqualTo("media/" + mediaId + "/" + trashTaskId + "/hq/" + hqPath);
        assertThat(trashed.getOriginalPageNumber()).isEqualTo(1);
        assertThat(trashed.getPageNumber()).isEqualTo(-mediaId.intValue());
        assertThat(Files.exists(MANGA_ROOT.resolve("hq").resolve(hqPath))).isFalse();
        assertThat(Files.exists(MANGA_ROOT.resolve("trash").resolve("media/" + mediaId + "/" + trashTaskId + "/hq/" + hqPath))).isTrue();
        // 清单与磁盘一致（manifest 存 DB）
        TrashManifestRecord manifestRecord = trashManifestMapper.selectById(trashTaskId);
        assertThat(manifestRecord).isNotNull();
        assertThat(objectMapper.readTree(manifestRecord.getManifestJson()).path("targetType").asText()).isEqualTo("MEDIA");
        assertThat(objectMapper.readTree(manifestRecord.getManifestJson()).path("targetId").asLong()).isEqualTo(mediaId);
        // 阅读面隐藏：READY 过滤器排除 TRASHED
        assertThat(listMediaPages(chapterId).stream().anyMatch(m -> m.getId().equals(mediaId))).isFalse();

        // 恢复
        long restoreTaskId = restore("MEDIA", mediaId, TaskType.MEDIA_RESTORE);
        assertThat(mediaMapper.selectById(mediaId).getStatus()).isEqualTo(MediaLifecycleStatus.RESTORING);
        runRestore(mediaId, trashTaskId, TaskType.MEDIA_RESTORE);
        awaitStatus("MEDIA", mediaId, "READY");

        Media restored = mediaMapper.selectById(mediaId);
        assertThat(restored.getHqRoot()).isEqualTo("HQ");
        assertThat(restored.getHqPath()).isEqualTo(hqPath);
        assertThat(restored.getPageNumber()).isEqualTo(1);
        assertThat(Files.exists(MANGA_ROOT.resolve("hq").resolve(hqPath))).isTrue();
        assertThat(Files.exists(MANGA_ROOT.resolve("trash").resolve("media/" + mediaId + "/" + trashTaskId + "/hq/" + hqPath))).isFalse();

        // 再次回收 → 到期 → 永久清理
        long secondTrash = trashMedia(mediaId);
        runTrash(mediaId, TaskType.MEDIA_TRASH);
        awaitStatus("MEDIA", mediaId, "TRASHED");
        expire(mediaId);

        long purgeTaskId = purge("MEDIA", mediaId);
        assertThat(mediaMapper.selectById(mediaId).getStatus()).isEqualTo(MediaLifecycleStatus.PURGING);
        runPurge(mediaId, secondTrash, TaskType.MEDIA_PURGE);
        awaitRowDeleted(mediaId);

        // 重复 purge → 409（媒体行已删除）
        mockMvc.perform(post("/api/trash/media/{id}/purge", mediaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"PURGE\"}"))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("媒体：恢复时原页码被占用 → 插入首个合法空位")
    void mediaRestore_pageNumberConflict_insertsLegalSlot() throws Exception {
        Long comicId = createComic("页码冲突");
        Long chapterId = createChapter(comicId, "第 1 话");
        Chapter chapter = chapterMapper.selectById(chapterId);
        String hqA = comicId + "/" + chapter.getGlobalOrder() + "/a.jpg";
        writeFile("hq", hqA, "media-a");
        Long mediaA = insertMedia(chapterId, 1, hqA);

        long trashTaskId = trashMedia(mediaA);
        runTrash(mediaA, TaskType.MEDIA_TRASH);
        awaitStatus("MEDIA", mediaA, "TRASHED");

        // 槽位 1 被新媒体占用
        String hqB = comicId + "/" + chapter.getGlobalOrder() + "/b.jpg";
        writeFile("hq", hqB, "media-b");
        insertMedia(chapterId, 1, hqB);

        long restoreTaskId = restore("MEDIA", mediaA, TaskType.MEDIA_RESTORE);
        runRestore(mediaA, trashTaskId, TaskType.MEDIA_RESTORE);
        awaitStatus("MEDIA", mediaA, "READY");

        Media restored = mediaMapper.selectById(mediaA);
        assertThat(restored.getPageNumber()).isEqualTo(2);
        assertThat(Files.exists(MANGA_ROOT.resolve("hq").resolve(hqA))).isTrue();
    }

    @Test
    @DisplayName("媒体：恢复目标路径被占用 → RESTORE_CONFLICT，回退 TRASHED")
    void mediaRestore_conflict_returnsRESTORE_CONFLICT() throws Exception {
        Long comicId = createComic("恢复冲突");
        Long chapterId = createChapter(comicId, "第 1 话");
        Chapter chapter = chapterMapper.selectById(chapterId);
        String hqPath = comicId + "/" + chapter.getGlobalOrder() + "/c.jpg";
        writeFile("hq", hqPath, "media-c");
        Long mediaId = insertMedia(chapterId, 1, hqPath);

        long trashTaskId = trashMedia(mediaId);
        runTrash(mediaId, TaskType.MEDIA_TRASH);
        awaitStatus("MEDIA", mediaId, "TRASHED");

        // 原路径被新文件占用 → 恢复冲突
        writeFile("hq", hqPath, "occupied-by-new");

        long restoreTaskId = restore("MEDIA", mediaId, TaskType.MEDIA_RESTORE);
        runRestore(mediaId, trashTaskId, TaskType.MEDIA_RESTORE);
        awaitStatus("MEDIA", mediaId, "TRASHED"); // 失败回退 tombstone

        assertThat(mediaMapper.selectById(mediaId).getHqRoot()).isEqualTo("TRASH");
    }

    // ======================== 章节全流程 ========================

    @Test
    @DisplayName("章节：回收（HQ/LQ 移入 TRASH）→ 恢复 → 到期清理级联删除媒体")
    void chapterTrashRestorePurge_fullCycle() throws Exception {
        Long comicId = createComic("章节全流程");
        Long chapterId = createChapter(comicId, "第 1 话");
        Chapter chapter = chapterMapper.selectById(chapterId);
        String rel = comicId + "/" + chapter.getGlobalOrder();
        writeFile("hq", rel + "/001.jpg", "ch-hq-1");
        writeFile("hq", rel + "/002.jpg", "ch-hq-2");
        writeFile("lq", rel + "/001.webp", "ch-lq-1");
        Long m1 = insertMedia(chapterId, 1, rel + "/001.jpg");
        Long m2 = insertMedia(chapterId, 2, rel + "/002.jpg");
        makeComicReady(comicId);

        long trashTaskId = trashChapter(comicId, chapterId);
        assertThat(chapterMapper.selectById(chapterId).getStatus()).isEqualTo(ChapterLifecycleStatus.TRASHING);
        runTrash(chapterId, TaskType.CHAPTER_TRASH);
        awaitStatus("CHAPTER", chapterId, "TRASHED");

        assertThat(Files.exists(MANGA_ROOT.resolve("hq").resolve(rel))).isFalse();
        assertThat(Files.exists(MANGA_ROOT.resolve("lq").resolve(rel))).isFalse();
        assertThat(Files.exists(MANGA_ROOT.resolve("trash").resolve("chapter/" + chapterId + "/" + trashTaskId + "/hq/" + rel + "/001.jpg"))).isTrue();
        assertThat(Files.exists(MANGA_ROOT.resolve("trash").resolve("chapter/" + chapterId + "/" + trashTaskId + "/lq/" + rel + "/001.webp"))).isTrue();
        // 阅读面隐藏
        // 清单与磁盘一致（manifest 存 DB）
        TrashManifestRecord chapterManifest = trashManifestMapper.selectById(trashTaskId);
        assertThat(chapterManifest).isNotNull();
        assertThat(objectMapper.readTree(chapterManifest.getManifestJson()).path("targetType").asText()).isEqualTo("CHAPTER");
        assertThat(objectMapper.readTree(chapterManifest.getManifestJson()).path("targetId").asLong()).isEqualTo(chapterId);
        assertThat(catalogChapterIds(comicId)).doesNotContain(chapterId);

        // 恢复
        long restoreTaskId = restoreChapter(comicId, chapterId);
        runRestore(chapterId, trashTaskId, TaskType.CHAPTER_RESTORE);
        awaitStatus("CHAPTER", chapterId, "READY");
        assertThat(Files.exists(MANGA_ROOT.resolve("hq").resolve(rel + "/001.jpg"))).isTrue();
        assertThat(Files.exists(MANGA_ROOT.resolve("lq").resolve(rel + "/001.webp"))).isTrue();
        assertThat(catalogChapterIds(comicId)).contains(chapterId);

        // 到期清理
        long secondTrash = trashChapter(comicId, chapterId);
        runTrash(chapterId, TaskType.CHAPTER_TRASH);
        awaitStatus("CHAPTER", chapterId, "TRASHED");
        expireChapter(chapterId);

        long purgeTaskId = purgeChapter(comicId, chapterId);
        runPurge(chapterId, secondTrash, TaskType.CHAPTER_PURGE);
        awaitStatus("CHAPTER", chapterId, "DELETED");
        assertThat(mediaMapper.selectById(m1)).isNull();
        assertThat(mediaMapper.selectById(m2)).isNull();
    }

    // ======================== 漫画全流程 ========================

    @Test
    @DisplayName("漫画：回收全部资产根 → 恢复 → 到期清理级联 DB + reading_history 保留")
    void comicTrashRestorePurge_fullCycle() throws Exception {
        Long comicId = createComic("漫画全流程");
        Long chapterId = createChapter(comicId, "第 1 话");
        Chapter chapter = chapterMapper.selectById(chapterId);
        String rel = comicId + "/" + chapter.getGlobalOrder();
        writeFile("hq", rel + "/001.jpg", "comic-hq");
        writeFile("lq", rel + "/001.webp", "comic-lq");
        writeFile("thumbs", comicId + "/cover.webp", "comic-thumb");
        writeFile("metadata", comicId + ".json", "comic-meta");
        insertMedia(chapterId, 1, rel + "/001.jpg");
        insertReadingHistory(comicId, chapterId);

        // 回收
        long trashTaskId = trashComic(comicId);
        assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.TRASHING);
        runTrash(comicId, TaskType.COMIC_DELETE);
        awaitStatus("COMIC", comicId, "TRASHED");

        assertThat(Files.exists(MANGA_ROOT.resolve("hq").resolve(comicId.toString()))).isFalse();
        assertThat(Files.exists(MANGA_ROOT.resolve("lq").resolve(comicId.toString()))).isFalse();
        assertThat(Files.exists(MANGA_ROOT.resolve("thumbs").resolve(comicId.toString()))).isFalse();
        assertThat(Files.exists(MANGA_ROOT.resolve("metadata").resolve(comicId + ".json"))).isFalse();
        assertThat(Files.exists(MANGA_ROOT.resolve("trash").resolve("comic/" + comicId + "/" + trashTaskId + "/hq/" + comicId))).isTrue();
        assertThat(Files.exists(MANGA_ROOT.resolve("trash").resolve("comic/" + comicId + "/" + trashTaskId + "/lq/" + comicId))).isTrue();
        assertThat(Files.exists(MANGA_ROOT.resolve("trash").resolve("comic/" + comicId + "/" + trashTaskId + "/thumbs/" + comicId))).isTrue();
        assertThat(Files.exists(MANGA_ROOT.resolve("trash").resolve("comic/" + comicId + "/" + trashTaskId + "/metadata/" + comicId + ".json"))).isTrue();
        // reading_history 保留
        assertThat(historyMapper.selectCount(new LambdaQueryWrapper<ReadingHistory>()
                .eq(ReadingHistory::getComicId, comicId))).isEqualTo(1);

        // 恢复
        long restoreTaskId = restore("COMIC", comicId, TaskType.COMIC_RESTORE);
        runRestore(comicId, trashTaskId, TaskType.COMIC_RESTORE);
        awaitStatus("COMIC", comicId, "READY");
        assertThat(Files.exists(MANGA_ROOT.resolve("hq").resolve(rel + "/001.jpg"))).isTrue();
        assertThat(historyMapper.selectCount(new LambdaQueryWrapper<ReadingHistory>()
                .eq(ReadingHistory::getComicId, comicId))).isEqualTo(1);

        // 到期清理
        long secondTrash = trashComic(comicId);
        runTrash(comicId, TaskType.COMIC_DELETE);
        awaitStatus("COMIC", comicId, "TRASHED");
        expireComic(comicId);

        long purgeTaskId = purge("COMIC", comicId);
        runPurge(comicId, secondTrash, TaskType.COMIC_PURGE);
        awaitStatus("COMIC", comicId, "DELETED");
        assertThat(chapterMapper.selectById(chapterId)).isNull();
        assertThat(historyMapper.selectCount(new LambdaQueryWrapper<ReadingHistory>()
                .eq(ReadingHistory::getComicId, comicId))).isZero();
    }

    @Test
    @DisplayName("漫画：无章节无文件回收 → Worker 直接成功 → TRASHED")
    void comicTrash_noFiles_workerCompletes() throws Exception {
        Long comicId = createComic("无文件");
        long trashTaskId = trashComic(comicId);
        runTrash(comicId, TaskType.COMIC_DELETE);
        awaitStatus("COMIC", comicId, "TRASHED");
        assertThat(Files.exists(MANGA_ROOT.resolve("trash").resolve("comic/" + comicId + "/" + trashTaskId + "/actual.json"))).isTrue();
    }

    // ======================== 部分移动 / 补偿 ========================

    @Test
    @DisplayName("部分移动失败且补偿完整 → 回退 READY")
    void partialMove_compensated_revertsToReady() throws Exception {
        Long comicId = createComic("补偿完整");
        Long chapterId = createChapter(comicId, "第 1 话");
        Chapter chapter = chapterMapper.selectById(chapterId);
        writeFile("hq", comicId + "/" + chapter.getGlobalOrder() + "/001.jpg", "hq-data");
        writeFile("lq", comicId + "/" + chapter.getGlobalOrder() + "/001.webp", "lq-data");
        insertMedia(chapterId, 1, comicId + "/" + chapter.getGlobalOrder() + "/001.jpg");

        long trashTaskId = trashComic(comicId);
        // 阻塞 LQ 条目（manifest 顺序：HQ→LQ→THUMBS→METADATA）
        createTrashTarget(comicId, trashTaskId, "lq", comicId.toString());

        runTrash(comicId, TaskType.COMIC_DELETE);
        awaitStatus("COMIC", comicId, "READY"); // 补偿完整 → 回退

        assertThat(Files.exists(MANGA_ROOT.resolve("hq").resolve(comicId.toString() + "/" + chapter.getGlobalOrder() + "/001.jpg"))).isTrue();
        assertThat(actualStatus(comicId, trashTaskId)).isEqualTo("COMPENSATED");
    }

    @Test
    @DisplayName("部分移动失败且补偿不完整 → 保持 TRASHING，仅 RECONCILE，可对账")
    void partialMove_compensationFailure_keepsTrashing() throws Exception {
        Long comicId = createComic("补偿失败");
        Long chapterId = createChapter(comicId, "第 1 话");
        Chapter chapter = chapterMapper.selectById(chapterId);
        writeFile("hq", comicId + "/" + chapter.getGlobalOrder() + "/001.jpg", "hq-data");
        insertMedia(chapterId, 1, comicId + "/" + chapter.getGlobalOrder() + "/001.jpg");

        long trashTaskId = trashComic(comicId);
        assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.TRASHING);

        // 模拟 Worker 补偿不完整：写入 actual.json=PARTIAL 并回传 failed
        ManagementTaskItem item = latestItem(comicId, TaskType.COMIC_DELETE);
        writeActualJson("comic", comicId, trashTaskId, "PARTIAL");
        rabbitTemplate.convertAndSend("comic.management", "command.failed",
                new com.comicatlas.common.event.ManagementCommandFailedEvent(
                        UUID.randomUUID(), java.time.Instant.now(), 1,
                        item.getTaskId(), item.getId(), item.getAttempt(),
                        "COMIC_DELETE", "COMIC", comicId, "回收失败且补偿不完整"));
        awaitTrue(() -> itemMapper.selectById(item.getId()).getStatus().name().equals("FAILED"), 30000);

        Comic comic = comicMapper.selectById(comicId);
        assertThat(comic.getStatus()).isEqualTo(ComicStatus.TRASHING);
        assertThat(actualStatus(comicId, trashTaskId)).isEqualTo("PARTIAL");

        // 仅允许 RECONCILE
        mockMvc.perform(get("/api/comics/{id}", comicId))
                .andExpect(jsonPath("$.data.allowedOperations.allowed.length()").value(1))
                .andExpect(jsonPath("$.data.allowedOperations.allowed[0]").value("RECONCILE"));

        // 对账报告一致且可修复
        MvcResult rep = mockMvc.perform(get("/api/trash/COMIC/{id}/reconcile", comicId))
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        JsonNode report = readData(rep);
        assertThat(report.get("dbStatus").asText()).isEqualTo("TRASHING");
        assertThat(report.get("manifestStatus").asText()).isEqualTo("PARTIAL");
        assertThat(report.get("consistent").asBoolean()).isTrue();
    }

    // ======================== 保留期 / token / 重复 purge ========================

    @Test
    @DisplayName("未到 7 天保留期 → 拒绝 purge；错误 token → 拒绝")
    void purge_beforeRetention_andWrongToken_rejected() throws Exception {
        Long comicId = createComic("保留期");
        long trashTaskId = trashComic(comicId);
        runTrash(comicId, TaskType.COMIC_DELETE);
        awaitStatus("COMIC", comicId, "TRASHED");

        // 错误 token
        mockMvc.perform(post("/api/trash/comics/{id}/purge", comicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"WRONG\"}"))
                .andExpect(jsonPath("$.code").value(400));

        // 未到保留期
        mockMvc.perform(post("/api/trash/comics/{id}/purge", comicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"PURGE\"}"))
                .andExpect(jsonPath("$.code").value(409));

        assertThat(comicMapper.selectById(comicId).getStatus()).isEqualTo(ComicStatus.TRASHED);
        // 清理该次回收（供 tearDown 幂等）不必要，trashTaskId 仅用于可读性
    }

    @Test
    @DisplayName("purge 时清单目录已不存在 → Worker 仍成功，DB 级联")
    void purge_withoutDirectory_stillSucceeds() throws Exception {
        Long comicId = createComic("无目录清理");
        Long chapterId = createChapter(comicId, "第 1 话");
        Chapter chapter = chapterMapper.selectById(chapterId);
        writeFile("hq", comicId + "/" + chapter.getGlobalOrder() + "/001.jpg", "data");
        insertMedia(chapterId, 1, comicId + "/" + chapter.getGlobalOrder() + "/001.jpg");

        long trashTaskId = trashComic(comicId);
        runTrash(comicId, TaskType.COMIC_DELETE);
        awaitStatus("COMIC", comicId, "TRASHED");
        expireComic(comicId);

        // 手工删除清单目录
        cleanDir(MANGA_ROOT.resolve("trash").resolve("comic/" + comicId));

        long purgeTaskId = purge("COMIC", comicId);
        runPurge(comicId, trashTaskId, TaskType.COMIC_PURGE);
        awaitStatus("COMIC", comicId, "DELETED");
        assertThat(chapterMapper.selectById(chapterId)).isNull();
    }

    // ======================== Worker 执行 ========================

    private void runTrash(Long targetId, TaskType op) {
        ManagementCommandRequestedEvent cmd = buildCommand(targetId, op, null);
        trashCommandHandler.trash(cmd);
    }

    private void runRestore(Long targetId, Long manifestTaskId, TaskType op) {
        ManagementTaskItem item = latestItem(targetId, op);
        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), java.time.Instant.now(), 1,
                item.getTaskId(), item.getId(), item.getAttempt(),
                op.name(), item.getTargetType(), targetId, manifestTaskId);
        restoreCommandHandler.restore(cmd);
    }

    private void runPurge(Long targetId, Long manifestTaskId, TaskType op) {
        ManagementTaskItem item = latestItem(targetId, op);
        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), java.time.Instant.now(), 1,
                item.getTaskId(), item.getId(), item.getAttempt(),
                op.name(), item.getTargetType(), targetId, manifestTaskId);
        purgeCommandHandler.purge(cmd);
    }

    private ManagementCommandRequestedEvent buildCommand(Long targetId, TaskType op, Long manifestTaskId) {
        ManagementTaskItem item = latestItem(targetId, op);
        return new ManagementCommandRequestedEvent(
                UUID.randomUUID(), java.time.Instant.now(), 1,
                item.getTaskId(), item.getId(), item.getAttempt(),
                op.name(), item.getTargetType(), targetId, manifestTaskId);
    }

    private ManagementTaskItem latestItem(Long targetId, TaskType op) {
        return itemMapper.selectOne(new LambdaQueryWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getTargetType, targetTypeOf(op))
                .eq(ManagementTaskItem::getTargetId, targetId)
                .eq(ManagementTaskItem::getOperationType, op)
                .orderByDesc(ManagementTaskItem::getId)
                .last("LIMIT 1"));
    }

    private static String targetTypeOf(TaskType op) {
        return switch (op) {
            case COMIC_DELETE, COMIC_RESTORE, COMIC_PURGE -> "COMIC";
            case CHAPTER_TRASH, CHAPTER_RESTORE, CHAPTER_PURGE -> "CHAPTER";
            case MEDIA_TRASH, MEDIA_RESTORE, MEDIA_PURGE -> "MEDIA";
            default -> throw new IllegalStateException("非回收操作: " + op);
        };
    }

    // ======================== 入口调用 ========================

    private long trashComic(Long comicId) throws Exception {
        MvcResult r = mockMvc.perform(delete("/api/comics/{id}", comicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        return readData(r).get("id").asLong();
    }

    private long trashChapter(Long comicId, Long chapterId) throws Exception {
        mockMvc.perform(delete("/api/comics/{cid}/chapters/{id}", comicId, chapterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        return latestItem(chapterId, TaskType.CHAPTER_TRASH).getTaskId();
    }

    private long trashMedia(Long mediaId) throws Exception {
        MvcResult r = mockMvc.perform(delete("/api/media/{id}", mediaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        return readData(r).get("taskId").asLong();
    }

    private long restore(String targetType, Long targetId, TaskType op) throws Exception {
        String url = switch (targetType) {
            case "COMIC" -> "/api/trash/comics/" + targetId + "/restore";
            case "MEDIA" -> "/api/trash/media/" + targetId + "/restore";
            default -> throw new IllegalStateException("章节恢复请使用 restoreChapter");
        };
        MvcResult r = mockMvc.perform(post(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        return readData(r).get("taskId").asLong();
    }

    private long restoreChapter(Long comicId, Long chapterId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/trash/comics/{cid}/chapters/{id}/restore", comicId, chapterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        return readData(r).get("taskId").asLong();
    }

    private long purge(String targetType, Long targetId) throws Exception {
        String url = switch (targetType) {
            case "COMIC" -> "/api/trash/comics/" + targetId + "/purge";
            case "MEDIA" -> "/api/trash/media/" + targetId + "/purge";
            default -> throw new IllegalStateException("章节清理请使用 purgeChapter");
        };
        MvcResult r = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"PURGE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        return readData(r).get("taskId").asLong();
    }

    private long purgeChapter(Long comicId, Long chapterId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/trash/comics/{cid}/chapters/{id}/purge", comicId, chapterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"PURGE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        return readData(r).get("taskId").asLong();
    }

    // ======================== 种子与断言辅助 ========================

    private Long createComic(String title) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/comics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        return readData(r).get("id").asLong();
    }

    private Long createChapter(Long comicId, String title) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/comics/{cid}/chapters", comicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        return readData(r).get("id").asLong();
    }

    private Long insertMedia(Long chapterId, int pageNumber, String hqPath) {
        Media m = new Media();
        m.setChapterId(chapterId);
        m.setPageNumber(pageNumber);
        m.setHqRoot("HQ");
        m.setHqPath(hqPath);
        m.setHqStatus(HqStatus.READY);
        m.setLqStatus(LqStatus.NOT_GENERATED);
        m.setTranscodeStatus(TranscodeStatus.NOT_NEEDED);
        m.setStatus(MediaLifecycleStatus.READY);
        m.setMediaType("IMAGE");
        m.setHqSize(1024L);
        m.setVersion(1);
        mediaMapper.insert(m);
        return m.getId();
    }

    private void insertReadingHistory(Long comicId, Long chapterId) {
        ReadingHistory h = new ReadingHistory();
        h.setComicId(comicId);
        h.setChapterId(chapterId);
        h.setPageNumber(1);
        historyMapper.insert(h);
    }

    private void makeComicReady(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        comic.setStatus(ComicStatus.READY);
        comicMapper.updateById(comic);
    }

    private void writeFile(String root, String relative, String content) throws Exception {
        Path p = MANGA_ROOT.resolve(root).resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private void createTrashTarget(Long comicId, Long taskId, String root, String rel) throws Exception {
        Path p = MANGA_ROOT.resolve("trash").resolve("comic/" + comicId + "/" + taskId + "/" + root + "/" + rel);
        Files.createDirectories(p);
        Files.writeString(p.resolve("blocker"), "block");
    }

    private void expire(Long mediaId) {
        Media m = mediaMapper.selectById(mediaId);
        m.setTrashedAt(LocalDateTime.now().minusDays(8));
        mediaMapper.updateById(m);
    }

    private void expireChapter(Long chapterId) {
        Chapter c = chapterMapper.selectById(chapterId);
        c.setTrashedAt(LocalDateTime.now().minusDays(8));
        chapterMapper.updateById(c);
    }

    private void expireComic(Long comicId) {
        Comic c = comicMapper.selectById(comicId);
        c.setTrashedAt(LocalDateTime.now().minusDays(8));
        comicMapper.updateById(c);
    }

    private List<Media> listMediaPages(Long chapterId) {
        return mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getStatus, "READY")
                .orderByAsc(Media::getPageNumber));
    }

    private List<Long> catalogChapterIds(Long comicId) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/comics/{id}/catalog", comicId))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = readData(r);
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (data.isArray() && data.size() > 0) {
            for (JsonNode ref : data.get(0).path("chapters")) {
                ids.add(ref.path("id").asLong());
            }
        }
        return ids;
    }

    private String actualStatus(Long comicId, Long taskId) throws Exception {
        Path actual = MANGA_ROOT.resolve("trash").resolve("comic/" + comicId + "/" + taskId + "/actual.json");
        if (!Files.exists(actual)) {
            return null;
        }
        return objectMapper.readTree(Files.readString(actual)).path("status").asText();
    }

    private void writeActualJson(String targetType, Long targetId, Long taskId, String status) throws Exception {
        Path dir = MANGA_ROOT.resolve("trash").resolve(targetType + "/" + targetId + "/" + taskId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("actual.json"),
                objectMapper.writeValueAsString(new com.comicatlas.common.dto.TrashManifestItemDTO(
                        com.comicatlas.common.dto.TrashManifestItemDTO.CURRENT_VERSION,
                        targetType.toUpperCase(), targetId, taskId, status,
                        "模拟补偿不完整", java.time.Instant.now(), null)),
                StandardCharsets.UTF_8);
    }

    private void awaitStatus(String targetType, Long targetId, String expected) {
        awaitTrue(() -> {
            String status = resolveStatus(targetType, targetId);
            return expected.equals(status);
        }, 30000);
    }

    private void awaitRowDeleted(Long mediaId) {
        awaitTrue(() -> mediaMapper.selectById(mediaId) == null, 30000);
    }

    private String resolveStatus(String targetType, Long targetId) {
        return switch (targetType) {
            case "COMIC" -> {
                Comic c = comicMapper.selectById(targetId);
                yield c == null ? "DELETED" : (c.getStatus() == null ? null : c.getStatus().name());
            }
            case "CHAPTER" -> {
                Chapter c = chapterMapper.selectById(targetId);
                yield c == null ? "DELETED" : (c.getStatus() == null ? null : c.getStatus().name());
            }
            case "MEDIA" -> {
                Media m = mediaMapper.selectById(targetId);
                yield m == null ? "DELETED" : (m.getStatus() == null ? null : m.getStatus().name());
            }
            default -> null;
        };
    }

    private void awaitTrue(BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待被中断");
            }
        }
        throw new AssertionError("等待条件超时");
    }

    private JsonNode readData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private static Path createTempMangaRoot() {
        try {
            Path root = Files.createTempDirectory("comicatlas-trash-it-");
            Files.createDirectories(root.resolve("hq"));
            Files.createDirectories(root.resolve("lq"));
            Files.createDirectories(root.resolve("thumbs"));
            Files.createDirectories(root.resolve("metadata"));
            Files.createDirectories(root.resolve("trash"));
            return root;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void cleanDir(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
