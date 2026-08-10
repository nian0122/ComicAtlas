package com.comicatlas.worker.command;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;
import com.comicatlas.worker.command.TranscodeCommandHandler.TranscodeResultPublishException;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.comicatlas.worker.process.ExternalProcessRunner;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TranscodeCommandHandler 单元测试（Todo 7 新契约）。
 * <p>
 * 验证：
 * <ul>
 *   <li>只处理 MEDIA 目标（非 MEDIA 防御性 failed）；</li>
 *   <li>确定性临时/最终文件名 {@code {taskId}-{itemId}-{attempt}-{mediaId}.mp4}；</li>
 *   <li>源/TEMP/目标路径 containment（越界拒绝）；</li>
 *   <li>已有合法最终产物时重 probe 重发相同结果，不重复转码；</li>
 *   <li>旧源永不删除（结果可靠发布前更不删）；</li>
 *   <li>输出经 ffprobe 验证兼容后原子发布，completed 携带真实 hqRoot/hqPath/尺寸/元数据；</li>
 *   <li>completed 发布失败 → {@link TranscodeResultPublishException}（不发布 failed，命令 requeue）；</li>
 *   <li>ffmpeg 非零/超时/中断 → FAILED、原 HQ 可用、临时文件清理、中断标志恢复。</li>
 * </ul>
 * Worker 不写数据库（mediaMapper 仅 mock 只读）。
 */
@ExtendWith(MockitoExtension.class)
class TranscodeCommandHandlerTest {

    @Mock
    private ExportMediaMapper mediaMapper;

    @Mock
    private ManagementCommandPublisher publisher;

    @Mock
    private MediaAnalyzer mediaAnalyzer;

    private WorkerConfig config;
    private StorageProperties storageProperties;
    private TranscodeCommandHandler handler;
    private ThreadPoolTaskExecutor ioExecutor;
    private ExternalProcessRunner processRunner;

    private Path tempRoot;
    private Path hqRoot;

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("tch-test-");
        hqRoot = Files.createDirectories(tempRoot.resolve("hq"));

        config = new WorkerConfig();
        config.setMangaRoot(tempRoot.toString());
        config.setTempDir(tempRoot.resolve("temp").toString());
        Files.createDirectories(Path.of(config.getTempDir()));

        StorageRoot hqStorageRoot = new StorageRoot();
        hqStorageRoot.setPath(hqRoot);
        storageProperties = new StorageProperties();
        storageProperties.setRoots(Map.of("HQ", hqStorageRoot));

        ioExecutor = new ThreadPoolTaskExecutor();
        ioExecutor.initialize();
        processRunner = new ExternalProcessRunner(ioExecutor);

        handler = new TranscodeCommandHandler(mediaMapper, config, storageProperties,
                publisher, processRunner, mediaAnalyzer);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ioExecutor != null) {
            ioExecutor.shutdown();
        }
        deleteRecursively(tempRoot);
    }

    // ==================== Test 1: 转码成功 → 确定性产物 + completed 携带真实产物元数据 + 旧源保留 ====================

    @Test
    void successfulTranscode_publishesCompletedWithRealArtifactAndKeepsSource() throws Exception {
        Long mediaId = 100L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("1/ch01"));
        Path sourceFile = chapterDir.resolve("test.webm");
        Files.writeString(sourceFile, "fake video data");

        ExportMedia media = media("1/ch01/test.webm");
        when(mediaMapper.selectById(mediaId)).thenReturn(media);

        config.setFfmpegPath(createFakeFfmpeg(0).toString());
        when(mediaAnalyzer.analyzeVideo(any(Path.class))).thenReturn(Optional.of(compatibleInfo(2048000L)));

        ManagementCommandRequestedEvent cmd = cmd(mediaId);

        handler.transcode(cmd);

        ArgumentCaptor<TranscodeMediaInfo> captor = ArgumentCaptor.forClass(TranscodeMediaInfo.class);
        verify(publisher).completed(eq(cmd), captor.capture());
        TranscodeMediaInfo info = captor.getValue();
        assertEquals("HQ", info.hqRoot());
        assertEquals("1/ch01/1-2-3-100.mp4", info.hqPath());
        assertEquals(new BigDecimal("12.34"), info.duration());
        assertEquals("mp4", info.container());
        assertEquals("h264", info.videoCodec());
        assertEquals("aac", info.audioCodec());
        assertEquals(Long.valueOf(2048000L), info.fileSize());
        assertEquals(Integer.valueOf(1280), info.width());
        assertEquals(Integer.valueOf(720), info.height());

        verify(publisher).progress(eq(cmd), eq(100), eq("转码完成"));
        // 确定性最终文件名（taskId-itemId-attempt-mediaId），避免与源冲突
        Path finalFile = chapterDir.resolve("1-2-3-100.mp4");
        assertTrue(Files.exists(finalFile), "确定性 mp4 产物应在 HQ 目录");
        // 旧源必须保留：DB 由 API 更新指向新路径，旧文件由后续清理策略处理，本 Todo 不删除
        assertTrue(Files.exists(sourceFile), "旧源文件不得删除");
        // 临时文件已清理
        assertNoTempFiles();
    }

    // ==================== Test 2: 确定性文件名不与旧"test.mp4"冲突 ====================

    @Test
    void deterministicName_doesNotConflictWithDerivedName() throws Exception {
        Long mediaId = 200L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("2/ch02"));
        Path sourceFile = chapterDir.resolve("test.webm");
        Files.writeString(sourceFile, "fake video data");
        // 旧实现会推导出的 test.mp4 已存在（占位）——确定性命名必须不冲突
        Files.writeString(chapterDir.resolve("test.mp4"), "old placeholder");

        ExportMedia media = media("2/ch02/test.webm");
        when(mediaMapper.selectById(mediaId)).thenReturn(media);

        config.setFfmpegPath(createFakeFfmpeg(0).toString());
        when(mediaAnalyzer.analyzeVideo(any(Path.class))).thenReturn(Optional.of(compatibleInfo(1024L)));

        ManagementCommandRequestedEvent command = cmd(mediaId);
        handler.transcode(command);

        verify(publisher).completed(eq(command), any(TranscodeMediaInfo.class));
        assertTrue(Files.exists(chapterDir.resolve("1-2-3-200.mp4")), "确定性产物应生成");
        assertTrue(Files.exists(chapterDir.resolve("test.mp4")), "旧占位文件不受影响");
        assertTrue(Files.exists(sourceFile), "旧源保留");
    }

    // ==================== Test 3: 已有合法最终产物 → 重 probe 重发相同结果，不重复转码 ====================

    @Test
    void existingCompatibleProduct_reprobesAndReusesWithoutTranscoding() throws Exception {
        Long mediaId = 300L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("3/ch03"));
        Path sourceFile = chapterDir.resolve("test.webm");
        Files.writeString(sourceFile, "fake video data");
        // 确定性最终产物已存在且 ffprobe 兼容
        Files.writeString(chapterDir.resolve("1-2-3-300.mp4"), "existing mp4 product");

        ExportMedia media = media("3/ch03/test.webm");
        when(mediaMapper.selectById(mediaId)).thenReturn(media);

        // ffmpeg 若被调用将 exit 1 失败：重用路径不调用 ffmpeg 才能通过
        config.setFfmpegPath(createFakeFfmpeg(1).toString());
        when(mediaAnalyzer.analyzeVideo(any(Path.class))).thenReturn(Optional.of(compatibleInfo(999L)));

        ManagementCommandRequestedEvent command = cmd(mediaId);
        handler.transcode(command);

        ArgumentCaptor<TranscodeMediaInfo> captor = ArgumentCaptor.forClass(TranscodeMediaInfo.class);
        verify(publisher).completed(eq(command), captor.capture());
        TranscodeMediaInfo info = captor.getValue();
        assertEquals("3/ch03/1-2-3-300.mp4", info.hqPath());
        assertEquals(Long.valueOf(999L), info.fileSize());
        // 未产生新的 .tmp 临时文件（未重复转码）
        assertNoTempFiles();
        assertTrue(Files.exists(sourceFile), "旧源保留");
    }

    // ==================== Test 4: 已有产物但 ffprobe 不兼容 → 重新转码覆盖 ====================

    @Test
    void existingProduct_incompatible_retranscodes() throws Exception {
        Long mediaId = 400L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("4/ch04"));
        Path sourceFile = chapterDir.resolve("test.webm");
        Files.writeString(sourceFile, "fake video data");
        Path existingFinal = chapterDir.resolve("1-2-3-400.mp4");
        Files.writeString(existingFinal, "broken product");

        ExportMedia media = media("4/ch04/test.webm");
        when(mediaMapper.selectById(mediaId)).thenReturn(media);

        config.setFfmpegPath(createFakeFfmpeg(0).toString());
        // 第一次 probe（既有产物）→ 不兼容；后续 probe（新 temp/最终产物）→ 兼容
        AtomicInteger probeCalls = new AtomicInteger();
        when(mediaAnalyzer.analyzeVideo(any(Path.class))).thenAnswer(inv -> {
            if (probeCalls.getAndIncrement() == 0) {
                return Optional.of(incompatibleInfo());
            }
            return Optional.of(compatibleInfo(512L));
        });

        ManagementCommandRequestedEvent command = cmd(mediaId);
        handler.transcode(command);

        verify(publisher).completed(eq(command), any(TranscodeMediaInfo.class));
        assertTrue(Files.exists(existingFinal), "不兼容产物被覆盖重转");
        assertTrue(Files.exists(sourceFile), "旧源保留");
    }

    // ==================== Test 5: ffmpeg 非零退出 → FAILED、原 HQ 可用、临时文件清理 ====================

    @Test
    void failedTranscode_publishesFailedEvent_andKeepsSource() throws Exception {
        Long mediaId = 500L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("5/ch05"));
        Path sourceFile = chapterDir.resolve("bad.avi");
        Files.writeString(sourceFile, "untranscodable data");

        ExportMedia media = media("5/ch05/bad.avi");
        when(mediaMapper.selectById(mediaId)).thenReturn(media);

        config.setFfmpegPath(createFakeFfmpeg(1).toString());

        ManagementCommandRequestedEvent command = cmd(mediaId);
        handler.transcode(command);

        verify(publisher).failed(eq(command), anyString());
        verify(publisher, never()).completed(any(ManagementCommandRequestedEvent.class), any());
        assertTrue(Files.exists(sourceFile), "失败后原 HQ 文件保留");
        assertNoTempFiles();
    }

    // ==================== Test 6: ffmpeg 超时 → FAILED、原 HQ 可用、临时文件清理 ====================

    @Test
    void timeoutTranscode_publishesFailed_andKeepsSource() throws Exception {
        Long mediaId = 600L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("6/ch06"));
        Path sourceFile = chapterDir.resolve("slow.mkv");
        Files.writeString(sourceFile, "slow video data");

        ExportMedia media = media("6/ch06/slow.mkv");
        when(mediaMapper.selectById(mediaId)).thenReturn(media);

        config.setTranscodeTimeoutSeconds(1);
        config.setFfmpegPath(createSleepingFfmpeg(0).toString());

        ManagementCommandRequestedEvent command = cmd(mediaId);
        handler.transcode(command);

        verify(publisher).failed(eq(command), anyString());
        verify(publisher, never()).completed(any(ManagementCommandRequestedEvent.class), any());
        assertTrue(Files.exists(sourceFile), "超时后原 HQ 文件保留");
        assertNoTempFiles();
    }

    // ==================== Test 7: 输出经 ffprobe 验证不兼容 → 不发布成功 ====================

    @Test
    void incompatibleOutput_doesNotPublishSuccess() throws Exception {
        Long mediaId = 700L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("7/ch07"));
        Path sourceFile = chapterDir.resolve("in.webm");
        Files.writeString(sourceFile, "fake video data");

        ExportMedia media = media("7/ch07/in.webm");
        when(mediaMapper.selectById(mediaId)).thenReturn(media);

        config.setFfmpegPath(createFakeFfmpeg(0).toString());
        when(mediaAnalyzer.analyzeVideo(any(Path.class))).thenReturn(Optional.of(incompatibleInfo()));

        ManagementCommandRequestedEvent command = cmd(mediaId);
        handler.transcode(command);

        verify(publisher).failed(eq(command), anyString());
        verify(publisher, never()).completed(any(ManagementCommandRequestedEvent.class), any());
        assertTrue(Files.exists(sourceFile), "旧源保留");
        // 不发布成功则不应把不兼容产物移动到 HQ
        assertFalse(Files.exists(chapterDir.resolve("1-2-3-700.mp4")), "不兼容输出不得发布为最终产物");
    }

    // ==================== Test 8: 路径 containment —— 源越界拒绝 ====================

    @Test
    void pathTraversal_rejected() throws Exception {
        Long mediaId = 800L;
        ExportMedia media = media("../escape.webm");
        when(mediaMapper.selectById(mediaId)).thenReturn(media);

        ManagementCommandRequestedEvent command = cmd(mediaId);
        handler.transcode(command);

        verify(publisher).failed(eq(command), anyString());
        verify(publisher, never()).completed(any(ManagementCommandRequestedEvent.class), any());
        assertNoTempFiles();
    }

    // ==================== Test 9: 非 HQ 存储根拒绝 ====================

    @Test
    void nonHqRoot_rejected() throws Exception {
        Long mediaId = 900L;
        ExportMedia media = media("1/ch01/test.webm");
        media.setHqRoot("TRASH");
        when(mediaMapper.selectById(mediaId)).thenReturn(media);

        ManagementCommandRequestedEvent command = cmd(mediaId);
        handler.transcode(command);

        verify(publisher).failed(eq(command), anyString());
        verify(publisher, never()).completed(any(ManagementCommandRequestedEvent.class), any());
    }

    // ==================== Test 10: 非 MEDIA 目标 → 防御性 failed ====================

    @Test
    void nonMediaTarget_rejected() {
        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, 1L, 1,
                "TRANSCODE", "COMIC", 42L);

        handler.transcode(cmd);

        verify(publisher).failed(eq(cmd), anyString());
        verify(mediaMapper, never()).selectById(any());
    }

    // ==================== Test 11: completed 发布失败 → 保留源与确定性产物，抛异常 requeue（不发布 failed） ====================

    @Test
    void completedPublishFailure_preservesArtifacts_andRequeues() throws Exception {
        Long mediaId = 1000L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("10/ch10"));
        Path sourceFile = chapterDir.resolve("test.webm");
        Files.writeString(sourceFile, "fake video data");

        ExportMedia media = media("10/ch10/test.webm");
        when(mediaMapper.selectById(mediaId)).thenReturn(media);

        config.setFfmpegPath(createFakeFfmpeg(0).toString());
        when(mediaAnalyzer.analyzeVideo(any(Path.class))).thenReturn(Optional.of(compatibleInfo(777L)));
        ManagementCommandRequestedEvent command = cmd(mediaId);
        doThrow(new RuntimeException("broker down"))
                .when(publisher).completed(eq(command), any(TranscodeMediaInfo.class));

        // 文件已成功但结果没发出：不得误报业务失败（failed），必须抛发布异常让命令 requeue
        assertThrows(TranscodeResultPublishException.class, () -> handler.transcode(command));
        verify(publisher, never()).failed(eq(command), anyString());
        assertTrue(Files.exists(sourceFile), "发布失败后旧源保留");
        assertTrue(Files.exists(chapterDir.resolve("1-2-3-1000.mp4")), "发布失败后确定性产物保留");
        assertNoTempFiles();
    }

    // ==================== Test 12: 中断 → 线程中断标志恢复 + failed + 临时清理 + 旧源保留 ====================

    @Test
    void interruptedTranscode_restoresInterruptFlag_andCleansTemp() throws Exception {
        Long mediaId = 1100L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("11/ch11"));
        Path sourceFile = chapterDir.resolve("test.webm");
        Files.writeString(sourceFile, "fake video data");

        ExportMedia media = media("11/ch11/test.webm");
        when(mediaMapper.selectById(mediaId)).thenReturn(media);

        config.setFfmpegPath(createSleepingFfmpeg(0).toString());

        ManagementCommandRequestedEvent cmd = cmd(mediaId);
        Thread t = new Thread(() -> handler.transcode(cmd));
        t.start();
        Thread.sleep(500);
        t.interrupt();
        t.join(15000);
        assertFalse(t.isAlive(), "转码线程应已结束");
        assertTrue(t.isInterrupted(), "中断标志必须恢复");
        verify(publisher).failed(eq(cmd), eq("转码被中断"));
        verify(publisher, never()).completed(any(ManagementCommandRequestedEvent.class), any());
        assertTrue(Files.exists(sourceFile), "中断后旧源保留");
        assertNoTempFiles();
    }

    // ==================== helpers ====================

    private ManagementCommandRequestedEvent cmd(Long mediaId) {
        return new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, 2L, 3,
                "TRANSCODE", "MEDIA", mediaId);
    }

    private ExportMedia media(String hqPath) {
        ExportMedia media = new ExportMedia();
        media.setId(1L);
        media.setHqRoot("HQ");
        media.setHqPath(hqPath);
        media.setMediaType("VIDEO");
        media.setContainer("webm");
        return media;
    }

    private ComicMetadata.MediaInfo compatibleInfo(long fileSize) {
        return new ComicMetadata.MediaInfo("out.mp4", 0, "READY", "NOT_GENERATED",
                fileSize, 1280, 720, "VIDEO",
                new BigDecimal("12.34"), "mp4", "h264", "aac");
    }

    private ComicMetadata.MediaInfo incompatibleInfo() {
        return new ComicMetadata.MediaInfo("out.webm", 0, "READY", "NOT_GENERATED",
                512L, 640, 480, "VIDEO",
                new BigDecimal("5.5"), "webm", "vp9", "opus");
    }

    /** 普通假 ffmpeg：始终把内容写入最后一个参数（输出路径），再以指定退出码结束。 */
    private Path createFakeFfmpeg(int exitCode) throws Exception {
        Path bat = tempRoot.resolve("fake-ffmpeg.bat");
        String script = """
            @echo off
            setlocal enabledelayedexpansion
            set "output="
            :loop
            if "%~1"=="" goto done
            set "output=%~1"
            shift
            goto loop
            :done
            if defined output echo fake-transcode-data > "!output!"
            exit /b EXIT_CODE
            """.replace("EXIT_CODE", String.valueOf(exitCode));
        Files.writeString(bat, script);
        return bat;
    }

    /** 睡眠假 ffmpeg：先 sleep 约 30 秒（供超时/中断测试命中），再写输出。 */
    private Path createSleepingFfmpeg(int exitCode) throws Exception {
        Path bat = tempRoot.resolve("fake-ffmpeg-sleep.bat");
        String script = """
            @echo off
            ping -n 30 127.0.0.1 >nul
            setlocal enabledelayedexpansion
            set "output="
            :loop
            if "%~1"=="" goto done
            set "output=%~1"
            shift
            goto loop
            :done
            if defined output echo fake-transcode-data > "!output!"
            exit /b EXIT_CODE
            """.replace("EXIT_CODE", String.valueOf(exitCode));
        Files.writeString(bat, script);
        return bat;
    }

    private void assertNoTempFiles() throws Exception {
        Path temp = Path.of(config.getTempDir());
        if (!Files.exists(temp)) {
            return;
        }
        try (var stream = Files.list(temp)) {
            assertTrue(stream.noneMatch(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".tmp") || name.endsWith(".probe.mp4");
            }), "临时转码文件（.tmp/.probe.mp4）应已清理");
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
    }
}
