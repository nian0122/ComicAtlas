package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ImportStorageFinalizeCompletedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeFailedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeRequestedEvent;
import com.comicatlas.common.event.payload.FinalizeMediaMapping;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.importer.ImportManifest;
import com.comicatlas.worker.importer.ImportManifestManager;
import com.comicatlas.worker.storage.SafeMoveStrategy;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StorageService;
import com.comicatlas.worker.storage.TransferService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ImportStorageFinalizeHandler 幂等存储最终化集成测试。
 * 真实文件系统 + 真实 TransferService/SafeMoveStrategy/ImportManifestManager，
 * mock RabbitTemplate/Channel 捕获事件发布与 ACK 语义。
 */
class ImportStorageFinalizeHandlerTest {

    private static final long TASK_ID = 1000L;
    private static final long COMIC_ID = 10L;

    private ObjectMapper objectMapper;
    private Path mangaRoot;
    private ImportManifestManager manifestManager;
    private StorageService storageService;
    private StorageProperties storageProperties;
    private RabbitTemplate rabbitTemplate;
    private Channel channel;
    private ImportStorageFinalizeHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        mangaRoot = Files.createTempDirectory("finalize-test-");

        storageProperties = new StorageProperties();
        storageProperties.setRoots(Map.of("HQ", new StorageRoot() {{
            setPath(mangaRoot.resolve("hq"));
            setEnabled(true);
        }}));

        manifestManager = new ImportManifestManager(objectMapper);
        storageService = new TransferService(storageProperties, new SafeMoveStrategy());
        rabbitTemplate = mock(RabbitTemplate.class);
        channel = mock(Channel.class);

        WorkerConfig config = new WorkerConfig();
        config.setMangaRoot(mangaRoot.toString());
        handler = new ImportStorageFinalizeHandler(
                config, storageService, storageProperties, manifestManager, rabbitTemplate, new MqConsumerSupport());
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteRecursively(mangaRoot);
    }

    // ======================== happy path ========================

    @Test
    @DisplayName("单章最终化：源搬空、目标就位、Completed 发布一次、manifest 删除")
    void finalize_singleChapter_movesAllFilesPublishesCompletedOnceAndDeletesManifest() throws Exception {
        writeStaging(1, "001.jpg", "aaa");
        writeStaging(1, "002.jpg", "bbb");
        writeManifest(Map.of(1, List.of("001.jpg", "002.jpg")), 3);

        handler.handle(event(1, 100L, "001.jpg", "002.jpg"), channel, 1L);

        // 源被搬空
        assertFalse(existsStaging(1, "001.jpg"), "源 001 应被搬走");
        assertFalse(existsStaging(1, "002.jpg"), "源 002 应被搬走");
        // 目标就位且内容一致
        assertEquals("aaa", Files.readString(chapterFile(100L, "001.jpg")));
        assertEquals("bbb", Files.readString(chapterFile(100L, "002.jpg")));
        // Completed 事件仅发布一次，字段完整
        ImportStorageFinalizeCompletedEvent completed = captureCompleted();
        assertEquals(TASK_ID, completed.taskId());
        assertEquals(COMIC_ID, completed.comicId());
        assertEquals(100L, completed.chapterId());
        assertEquals(1, completed.globalOrder());
        assertEquals("hq/10/100", completed.targetDir());
        assertEquals(2, completed.mediaCount());
        // manifest 删除
        assertFalse(manifestManager.exists(mangaRoot, TASK_ID), "全部章完成后清单应删除");
        // ACK
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("三章最终化：前两章不发布 Completed，最后一章才发布一次并删除 manifest")
    void finalize_threeChapters_publishesCompletedOnlyAfterLastChapter() throws Exception {
        writeStaging(1, "001.jpg", "aaa");
        writeStaging(1, "002.jpg", "bbb");
        writeStaging(2, "003.jpg", "ccc");
        writeStaging(3, "004.jpg", "ddd");
        writeManifest(Map.of(
                1, List.of("001.jpg", "002.jpg"),
                2, List.of("003.jpg"),
                3, List.of("004.jpg")), 3);

        // 第一章：移动成功，但不得发布 Completed、清单保留
        handler.handle(event(1, 100L, "001.jpg", "002.jpg"), channel, 1L);
        assertTrue(Files.exists(chapterFile(100L, "001.jpg")), "第一章应已移动到 chapterId=100");
        verify(rabbitTemplate, never()).convertAndSend(
                eq(MqExchanges.IMPORT), eq(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED), any(Object.class));
        assertTrue(manifestManager.exists(mangaRoot, TASK_ID), "还有章节未完成时清单应保留");

        // 第二章：移动成功，仍不发布 Completed
        handler.handle(event(2, 200L, "003.jpg"), channel, 2L);
        assertTrue(Files.exists(chapterFile(200L, "003.jpg")), "第二章应已移动到 chapterId=200");
        verify(rabbitTemplate, never()).convertAndSend(
                eq(MqExchanges.IMPORT), eq(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED), any(Object.class));
        assertTrue(manifestManager.exists(mangaRoot, TASK_ID), "还有章节未完成时清单应保留");

        // 第三章（最后一章）：发布 Completed 一次 + 删除 manifest
        handler.handle(event(3, 300L, "004.jpg"), channel, 3L);
        assertTrue(Files.exists(chapterFile(300L, "004.jpg")), "第三章应已移动到 chapterId=300");
        assertNotNull(captureCompleted(), "最后一章完成后应发布 Completed");
        assertFalse(manifestManager.exists(mangaRoot, TASK_ID), "全部章完成后清单应删除");
    }

    // ======================== 幂等：target-only / 部分目标 / 重复事件 ========================

    @Test
    @DisplayName("目标已存在且尺寸匹配（源缺失）：不移动、视为已完成")
    void finalize_targetsAlreadyComplete_skipsMove() throws Exception {
        // 预置目标已存在（与清单尺寸一致），源缺失
        Files.createDirectories(chapterDir(100L));
        Files.writeString(chapterFile(100L, "001.jpg"), "aaa");
        Files.writeString(chapterFile(100L, "002.jpg"), "bbb");
        writeManifest(Map.of(1, List.of("001.jpg", "002.jpg")), 3);

        handler.handle(event(1, 100L, "001.jpg", "002.jpg"), channel, 1L);

        assertNotNull(captureCompleted(), "目标齐全应视为已最终化并发布 Completed");
        assertFalse(manifestManager.exists(mangaRoot, TASK_ID));
        // 目标文件未被覆盖
        assertEquals("aaa", Files.readString(chapterFile(100L, "001.jpg")));
        // 无重复移动（空暂存目录清理）
        assertFalse(Files.isDirectory(stagingDir(1)), "空暂存目录应被清理");
    }

    @Test
    @DisplayName("部分目标已就位（尺寸匹配）：只搬剩余文件，不重复移动")
    void finalize_partialTarget_movesOnlyRemainingFiles() throws Exception {
        writeStaging(1, "002.jpg", "bbb");
        Files.createDirectories(chapterDir(100L));
        Files.writeString(chapterFile(100L, "001.jpg"), "aaa");
        writeManifest(Map.of(1, List.of("001.jpg", "002.jpg")), 3);

        handler.handle(event(1, 100L, "001.jpg", "002.jpg"), channel, 1L);

        // 001 已在目标（未重复搬），002 从源续搬
        assertEquals("aaa", Files.readString(chapterFile(100L, "001.jpg")), "001 不应被重复移动");
        assertEquals("bbb", Files.readString(chapterFile(100L, "002.jpg")), "002 应被移动");
        assertFalse(existsStaging(1, "002.jpg"), "002 源应被搬走");
        assertNotNull(captureCompleted());
    }

    @Test
    @DisplayName("重复事件（目标齐全、清单仍在）：幂等跳过，Completed 不重复发布")
    void finalize_duplicateEventWhileManifestPresent_isIdempotent() throws Exception {
        writeStaging(1, "001.jpg", "aaa");
        writeStaging(1, "002.jpg", "bbb");
        writeStaging(2, "003.jpg", "ccc");
        writeManifest(Map.of(
                1, List.of("001.jpg", "002.jpg"),
                2, List.of("003.jpg")), 3);

        // 第一次投递：第一章移动完成，但第二章未完成 → 不发布 Completed
        handler.handle(event(1, 100L, "001.jpg", "002.jpg"), channel, 1L);
        verify(rabbitTemplate, never()).convertAndSend(
                eq(MqExchanges.IMPORT), eq(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED), any(Object.class));

        // 第二次投递（同一事件重投）：第一章目标齐全、源缺失 → 幂等跳过，仍不发布 Completed
        handler.handle(event(1, 100L, "001.jpg", "002.jpg"), channel, 2L);
        verify(rabbitTemplate, never()).convertAndSend(
                eq(MqExchanges.IMPORT), eq(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED), any(Object.class));
        assertTrue(manifestManager.exists(mangaRoot, TASK_ID), "重复事件不得删除清单");
        // 目标只有一个副本
        assertEquals("aaa", Files.readString(chapterFile(100L, "001.jpg")));
        assertFalse(existsStaging(1, "001.jpg"), "重复事件不得重新移动");

        // 第二章完成 → 全部章完成 → Completed 发布一次
        handler.handle(event(2, 200L, "003.jpg"), channel, 3L);
        assertNotNull(captureCompleted());
        assertFalse(manifestManager.exists(mangaRoot, TASK_ID));
    }

    @Test
    @DisplayName("完成后再投递同一事件（清单已删）：静默幂等，不重复发布 Completed")
    void finalize_duplicateEventAfterManifestDeleted_silentlyAcks() throws Exception {
        writeStaging(1, "001.jpg", "aaa");
        writeManifest(Map.of(1, List.of("001.jpg")), 3);

        // 第一次：单章全完成 → Completed 一次 + 删除清单
        handler.handle(event(1, 100L, "001.jpg"), channel, 1L);
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(MqExchanges.IMPORT), eq(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED), any(Object.class));
        assertFalse(manifestManager.exists(mangaRoot, TASK_ID));

        // 第二次（重复投递）：清单已删、目标齐全 → 静默 ACK，不再发布 Completed
        handler.handle(event(1, 100L, "001.jpg"), channel, 2L);
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(MqExchanges.IMPORT), eq(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED), any(Object.class));
        verify(rabbitTemplate, never()).convertAndSend(
                eq(MqExchanges.IMPORT), eq(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_FAILED), any(Object.class));
        verify(channel).basicAck(2L, false);
    }

    // ======================== 失败路径 ========================

    @Test
    @DisplayName("目标尺寸冲突：发布 Failed，清单与 staging 保留，不发布 Completed")
    void finalize_sizeConflict_publishesFailedKeepsManifestAndStaging() throws Exception {
        writeStaging(1, "001.jpg", "aaa");
        writeStaging(1, "002.jpg", "bbb");
        // 预置污染目标：尺寸与清单不符
        Files.createDirectories(chapterDir(100L));
        Files.writeString(chapterFile(100L, "001.jpg"), "corrupted-longer-content");
        writeManifest(Map.of(1, List.of("001.jpg", "002.jpg")), 3);

        handler.handle(event(1, 100L, "001.jpg", "002.jpg"), channel, 1L);

        ImportStorageFinalizeFailedEvent failed = captureFailed();
        assertEquals("STORAGE_FINALIZE_SIZE_CONFLICT", failed.errorCode());
        assertEquals(TASK_ID, failed.taskId());
        assertEquals(COMIC_ID, failed.comicId());
        assertEquals(100L, failed.chapterId());
        // 污染目标不被覆盖，源与清单保留供重试
        assertEquals("corrupted-longer-content", Files.readString(chapterFile(100L, "001.jpg")));
        assertTrue(existsStaging(1, "002.jpg"), "staging 应保留供重试");
        assertTrue(manifestManager.exists(mangaRoot, TASK_ID), "失败后清单应保留");
        verify(rabbitTemplate, never()).convertAndSend(
                eq(MqExchanges.IMPORT), eq(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED), any(Object.class));
    }

    @Test
    @DisplayName("源与目标均不存在：发布 Failed，保留清单与 staging")
    void finalize_sourceAndTargetMissing_publishesFailed() throws Exception {
        // 002 源被外部删除，目标也不存在
        writeStaging(1, "001.jpg", "aaa");
        writeManifest(Map.of(1, List.of("001.jpg", "002.jpg")), 3);

        handler.handle(event(1, 100L, "001.jpg", "002.jpg"), channel, 1L);

        ImportStorageFinalizeFailedEvent failed = captureFailed();
        assertEquals("STORAGE_FINALIZE_SOURCE_MISSING", failed.errorCode());
        assertTrue(manifestManager.exists(mangaRoot, TASK_ID), "失败后清单应保留");
        verify(rabbitTemplate, never()).convertAndSend(
                eq(MqExchanges.IMPORT), eq(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED), any(Object.class));
    }

    @Test
    @DisplayName("源与目标同时存在：冲突失败，保留清单与 staging")
    void finalize_sourceAndTargetBothExist_conflictFails() throws Exception {
        writeStaging(1, "001.jpg", "aaa");
        Files.createDirectories(chapterDir(100L));
        Files.writeString(chapterFile(100L, "001.jpg"), "aaa");
        writeManifest(Map.of(1, List.of("001.jpg")), 3);

        handler.handle(event(1, 100L, "001.jpg"), channel, 1L);

        ImportStorageFinalizeFailedEvent failed = captureFailed();
        assertEquals("STORAGE_FINALIZE_CONFLICT", failed.errorCode());
        assertTrue(manifestManager.exists(mangaRoot, TASK_ID), "冲突后清单应保留");
        assertTrue(existsStaging(1, "001.jpg"), "冲突后 staging 应保留供人工处理");
    }

    @Test
    @DisplayName("路径穿越（sourceDir 越出 HQ 根）：发布 Failed 且不产生任何文件移动")
    void finalize_pathTraversal_publishesFailedWithoutMoving() throws Exception {
        writeStaging(1, "001.jpg", "aaa");
        writeManifest(Map.of(1, List.of("001.jpg")), 3);

        var evil = new ImportStorageFinalizeRequestedEvent(
                UUID.randomUUID(), Instant.now(), TASK_ID, COMIC_ID, 1, 100L,
                "../staging/evil", "hq/10/100",
                List.of(new FinalizeMediaMapping("001.jpg", "001.jpg")));

        handler.handle(evil, channel, 1L);

        ImportStorageFinalizeFailedEvent failed = captureFailed();
        assertEquals("STORAGE_FINALIZE_PATH_OUTSIDE_HQ", failed.errorCode());
        assertTrue(existsStaging(1, "001.jpg"), "穿越路径不应触发移动");
        assertTrue(manifestManager.exists(mangaRoot, TASK_ID));
    }

    @Test
    @DisplayName("mediaMapping 相对路径越出 HQ 根：发布 Failed")
    void finalize_mappingPathTraversal_publishesFailed() throws Exception {
        writeStaging(1, "001.jpg", "aaa");
        writeManifest(Map.of(1, List.of("001.jpg")), 3);

        var evil = new ImportStorageFinalizeRequestedEvent(
                UUID.randomUUID(), Instant.now(), TASK_ID, COMIC_ID, 1, 100L,
                "hq/10/1", "hq/10/100",
                List.of(new FinalizeMediaMapping("../../../outside.jpg", "001.jpg")));

        handler.handle(evil, channel, 1L);

        ImportStorageFinalizeFailedEvent failed = captureFailed();
        assertEquals("STORAGE_FINALIZE_PATH_OUTSIDE_HQ", failed.errorCode());
        assertTrue(existsStaging(1, "001.jpg"));
    }

    // ======================== 中断恢复 ========================

    @Test
    @DisplayName("中断后重试：已搬文件按尺寸跳过，剩余文件续搬")
    void finalize_interruptedThenRetry_resumesRemainingFiles() throws Exception {
        // 预置中断态：001 已在目标（尺寸匹配），002 仍在 staging
        Files.createDirectories(chapterDir(100L));
        Files.writeString(chapterFile(100L, "001.jpg"), "aaa");
        writeStaging(1, "002.jpg", "bbb");
        writeManifest(Map.of(1, List.of("001.jpg", "002.jpg")), 3);

        handler.handle(event(1, 100L, "001.jpg", "002.jpg"), channel, 1L);

        assertEquals("aaa", Files.readString(chapterFile(100L, "001.jpg")), "已搬文件不得重复移动");
        assertEquals("bbb", Files.readString(chapterFile(100L, "002.jpg")), "剩余文件应续搬");
        assertFalse(existsStaging(1, "002.jpg"));
        assertNotNull(captureCompleted());
    }

    @Test
    @DisplayName("InterruptedException：恢复中断标志、不 ACK/不 Reject、不发布失败事件")
    void finalize_interrupted_restoresFlagAndTerminatesSilently() throws Exception {
        writeStaging(1, "001.jpg", "aaa");
        writeManifest(Map.of(1, List.of("001.jpg")), 3);

        StorageService throwingStorage = mock(StorageService.class);
        doAnswer(inv -> {
            throw new InterruptedException("mock interrupt");
        }).when(throwingStorage).transfer(any(), any(), any());
        WorkerConfig config = new WorkerConfig();
        config.setMangaRoot(mangaRoot.toString());
        ImportStorageFinalizeHandler interruptHandler = new ImportStorageFinalizeHandler(
                config, throwingStorage, storageProperties, manifestManager, rabbitTemplate, new MqConsumerSupport());

        try {
            interruptHandler.handle(event(1, 100L, "001.jpg"), channel, 1L);
        } finally {
            // MqConsumerSupport 恢复中断标志；本线程测试后清理标志
            assertTrue(Thread.interrupted(), "中断标志应被恢复");
        }

        // 不 ACK、不 Reject，不发布任何事件（不得误报普通业务失败）
        verify(channel, never()).basicAck(anyLong(), eq(false));
        verify(channel, never()).basicReject(anyLong(), eq(false));
        verifyNoInteractions(rabbitTemplate);
    }

    // ======================== 辅助 ========================

    private ImportStorageFinalizeRequestedEvent event(int globalOrder, long chapterId, String... fileNames) {
        List<FinalizeMediaMapping> mappings = new ArrayList<>();
        for (String name : fileNames) {
            mappings.add(new FinalizeMediaMapping(name, name));
        }
        return new ImportStorageFinalizeRequestedEvent(
                UUID.randomUUID(), Instant.now(), TASK_ID, COMIC_ID, globalOrder, chapterId,
                "hq/" + COMIC_ID + "/" + globalOrder,
                "hq/" + COMIC_ID + "/" + chapterId,
                mappings);
    }

    private void writeStaging(int globalOrder, String fileName, String content) throws IOException {
        Path dir = stagingDir(globalOrder);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), content);
    }

    private boolean existsStaging(int globalOrder, String fileName) {
        return Files.exists(stagingDir(globalOrder).resolve(fileName));
    }

    private Path stagingDir(int globalOrder) {
        return mangaRoot.resolve("hq").resolve(COMIC_ID + "/" + globalOrder);
    }

    private Path chapterDir(long chapterId) {
        return mangaRoot.resolve("hq").resolve(COMIC_ID + "/" + chapterId);
    }

    private Path chapterFile(long chapterId, String fileName) {
        return chapterDir(chapterId).resolve(fileName);
    }

    /** 写入清单：按章节 globalOrder 组织文件，所有文件统一 size 字节。 */
    private void writeManifest(Map<Integer, List<String>> filesByOrder, long size) throws Exception {
        List<ImportManifest.ImportFile> files = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> entry : filesByOrder.entrySet()) {
            int order = entry.getKey();
            for (String name : entry.getValue()) {
                files.add(new ImportManifest.ImportFile(
                        "src/" + order + "/" + name, COMIC_ID + "/" + order + "/" + name, size));
            }
        }
        ImportManifest manifest = new ImportManifest(
                1, TASK_ID, "DIRECTORY", mangaRoot.resolve("src").toString(),
                objectMapper.createObjectNode(), files);
        manifestManager.write(mangaRoot, TASK_ID, manifest);
    }

    private ImportStorageFinalizeCompletedEvent captureCompleted() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                eq(MqExchanges.IMPORT), eq(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED), captor.capture());
        return (ImportStorageFinalizeCompletedEvent) captor.getValue();
    }

    private ImportStorageFinalizeFailedEvent captureFailed() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                eq(MqExchanges.IMPORT), eq(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_FAILED), captor.capture());
        return (ImportStorageFinalizeFailedEvent) captor.getValue();
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) { return; }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }
}
