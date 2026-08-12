package com.comicatlas.worker.command;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;
import com.comicatlas.worker.command.TranscodeCommandHandler;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.file.transcode.FfmpegTranscoder;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.comicatlas.worker.process.ExternalProcessRunner;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TranscodeCommandHandler 单元测试。
 * <p>
 * 验证转码完成后 ffprobe 实测元数据随 completed 事件回传（mock MediaAnalyzer）、
 * 探测失败降级为 null、失败分支发布 failed 事件，不访问数据库。
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
    private TranscodeCommandHandler handler;
    private ThreadPoolTaskExecutor ioExecutor;
    private ExternalProcessRunner processRunner;

    private Path tempRoot;
    private Path hqRoot;

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("tch-test-");
        hqRoot = Files.createDirectories(tempRoot.resolve("HQ"));

        config = new WorkerConfig();
        config.setMangaRoot(tempRoot.toString());
        config.setTempDir(tempRoot.resolve("temp").toString());
        Files.createDirectories(Path.of(config.getTempDir()));

        ioExecutor = new ThreadPoolTaskExecutor();
        ioExecutor.initialize();
        processRunner = new ExternalProcessRunner(ioExecutor);
        FfmpegTranscoder ffmpegTranscoder = new FfmpegTranscoder(config, processRunner);

        handler = new TranscodeCommandHandler(mediaMapper, config, publisher, mediaAnalyzer, ffmpegTranscoder);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ioExecutor != null) {
            ioExecutor.shutdown();
        }
        deleteRecursively(tempRoot);
    }

    // ==================== Test 1: 转码成功 → completed 携带实测元数据 ====================

    @Test
    void successfulTranscode_publishesCompletedWithProbedMetadata() throws Exception {
        Long pageId = 100L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("1/ch01"));
        Path sourceFile = chapterDir.resolve("test.webm");
        Files.writeString(sourceFile, "fake video data");

        ExportMedia media = new ExportMedia();
        media.setId(pageId);
        media.setHqRoot("HQ");
        media.setHqPath("1/ch01/test.webm");
        media.setMediaType("VIDEO");
        media.setContainer("webm");
        when(mediaMapper.selectById(pageId)).thenReturn(media);

        config.setFfmpegPath(createFakeFfmpeg(0).toString());
        when(mediaAnalyzer.analyzeVideo(any(Path.class))).thenReturn(Optional.of(
                new ComicMetadata.MediaInfo("test.mp4", 0, "READY", "NOT_GENERATED",
                        2048000L, 1280, 720, "VIDEO",
                        new BigDecimal("12.34"), "mp4", "h264", "aac")));

        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, pageId, 1,
                "TRANSCODE", "MEDIA", pageId);

        handler.transcode(cmd);

        ArgumentCaptor<TranscodeMediaInfo> captor = ArgumentCaptor.forClass(TranscodeMediaInfo.class);
        verify(publisher).completed(eq(cmd), captor.capture());
        TranscodeMediaInfo info = captor.getValue();
        assertEquals(new BigDecimal("12.34"), info.duration());
        assertEquals("mp4", info.container());
        assertEquals("h264", info.videoCodec());
        assertEquals("aac", info.audioCodec());
        assertEquals(Long.valueOf(2048000L), info.fileSize());

        verify(publisher).progress(eq(cmd), eq(100), eq("转码完成"));
        assertTrue(Files.exists(chapterDir.resolve("test.mp4")), "new .mp4 should exist in HQ");
        assertFalse(Files.exists(sourceFile), "old .webm should be deleted");
    }

    // ==================== Test 2: ffprobe 探测失败 → completed 携带 null（API 侧回退硬编码） ====================

    @Test
    void probeEmpty_transcodeDegradesToNull() throws Exception {
        Long pageId = 200L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("2/ch02"));
        Files.writeString(chapterDir.resolve("bad.mkv"), "fake video data");

        ExportMedia media = new ExportMedia();
        media.setId(pageId);
        media.setHqRoot("HQ");
        media.setHqPath("2/ch02/bad.mkv");
        media.setMediaType("VIDEO");
        media.setContainer("mkv");
        when(mediaMapper.selectById(pageId)).thenReturn(media);

        config.setFfmpegPath(createFakeFfmpeg(0).toString());
        when(mediaAnalyzer.analyzeVideo(any(Path.class))).thenReturn(Optional.empty());

        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, pageId, 1,
                "TRANSCODE", "MEDIA", pageId);

        handler.transcode(cmd);

        ArgumentCaptor<TranscodeMediaInfo> captor = ArgumentCaptor.forClass(TranscodeMediaInfo.class);
        verify(publisher).completed(eq(cmd), captor.capture());
        assertNull(captor.getValue());
    }

    // ==================== Test 3: ffmpeg 非零退出 → failed 事件，不发布 completed ====================

    @Test
    void failedTranscode_publishesFailedEvent() throws Exception {
        Long pageId = 300L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("3/ch03"));
        Files.writeString(chapterDir.resolve("bad.avi"), "untranscodable data");

        ExportMedia media = new ExportMedia();
        media.setId(pageId);
        media.setHqRoot("HQ");
        media.setHqPath("3/ch03/bad.avi");
        media.setMediaType("VIDEO");
        media.setContainer("avi");
        when(mediaMapper.selectById(pageId)).thenReturn(media);

        config.setFfmpegPath(createFakeFfmpeg(1).toString());

        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, pageId, 1,
                "TRANSCODE", "MEDIA", pageId);

        handler.transcode(cmd);

        verify(publisher).failed(eq(cmd), anyString());
        verify(publisher, never()).completed(any(ManagementCommandRequestedEvent.class), any());
    }

    // ==================== Test 4: 漫画级成功 → 无单页实测元数据可携带，completed 传 null ====================

    @Test
    void comicScope_completedWithoutTranscode() throws Exception {
        Long comicId = 1L;
        Long pageId = 400L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("1/ch04"));
        Files.writeString(chapterDir.resolve("test.mkv"), "fake video data");

        ExportMedia media = new ExportMedia();
        media.setId(pageId);
        media.setHqRoot("HQ");
        media.setHqPath("1/ch04/test.mkv");
        media.setMediaType("VIDEO");
        media.setContainer("mkv");
        when(mediaMapper.selectByComicId(comicId)).thenReturn(List.of(media));
        when(mediaMapper.selectById(pageId)).thenReturn(media);

        config.setFfmpegPath(createFakeFfmpeg(0).toString());
        when(mediaAnalyzer.analyzeVideo(any(Path.class))).thenReturn(Optional.of(
                new ComicMetadata.MediaInfo("test.mp4", 0, "READY", "NOT_GENERATED",
                        1024L, 640, 480, "VIDEO",
                        new BigDecimal("5.5"), "mp4", "h264", "aac")));

        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, pageId, 1,
                "TRANSCODE", "COMIC", comicId);

        handler.transcode(cmd);

        // 漫画级单事件无法携带逐页元数据：走无 transcode 的 completed(cmd) 重载
        verify(publisher).completed(cmd);
        verify(publisher, never()).completed(any(ManagementCommandRequestedEvent.class), any());
        verify(publisher).progress(eq(cmd), eq(100), eq("转码完成"));
    }

    // ==================== Test 5: 漫画级 mpeg4-in-mp4 应被选中转码（回归） ====================

    @Test
    void comicScope_mp4ContainerMpeg4Codec_isSelectedForTranscode() throws Exception {
        Long comicId = 1L;
        Long pageId = 500L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("1/ch05"));
        Files.writeString(chapterDir.resolve("test.mp4"), "fake video data");

        ExportMedia media = new ExportMedia();
        media.setId(pageId);
        media.setHqRoot("HQ");
        media.setHqPath("1/ch05/test.mp4");
        media.setMediaType("VIDEO");
        media.setContainer("mp4");
        media.setVideoCodec("mpeg4");
        when(mediaMapper.selectByComicId(comicId)).thenReturn(List.of(media));
        when(mediaMapper.selectById(pageId)).thenReturn(media);

        config.setFfmpegPath(createFakeFfmpeg(0).toString());
        when(mediaAnalyzer.analyzeVideo(any(Path.class))).thenReturn(Optional.of(
                new ComicMetadata.MediaInfo("test.mp4", 0, "READY", "NOT_GENERATED",
                        1024L, 1280, 720, "VIDEO",
                        new BigDecimal("5.5"), "mp4", "h264", "aac")));

        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, pageId, 1,
                "TRANSCODE", "COMIC", comicId);

        handler.transcode(cmd);

        // mp4 容器 + mpeg4 编码（MPEG-4 Part 2）浏览器无法解码，必须被选中转码
        verify(publisher).completed(cmd);
        verify(publisher).progress(eq(cmd), eq(100), eq("转码完成"));
        assertTrue(Files.exists(chapterDir.resolve("test.mp4")), "转码产物应存在");
    }

    // ==================== helpers ====================

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
            if EXIT_CODE==0 (
                if defined output echo fake-transcode-data > "!output!"
            )
            exit /b EXIT_CODE
            """.replace("EXIT_CODE", String.valueOf(exitCode));
        Files.writeString(bat, script);
        return bat;
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
