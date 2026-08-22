package com.comicatlas.worker.command;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.task.ManagementCommandPublisher;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;
import com.comicatlas.worker.media.command.TranscodeCommandHandler;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.media.transcode.FfmpegTranscoder;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import com.comicatlas.worker.persistence.mapper.MediaReadMapper;
import com.comicatlas.worker.shared.process.ExternalProcessRunner;
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
import static org.mockito.ArgumentMatchers.anyList;
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
    private MediaReadMapper mediaMapper;

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

        MediaRecord media = new MediaRecord();
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
        assertEquals("1/ch01/test.mp4", info.newHqPath());

        verify(publisher).progress(eq(cmd), eq(100), eq("转码完成"));
        assertTrue(Files.exists(chapterDir.resolve("test.mp4")), "new .mp4 should exist in HQ");
        assertFalse(Files.exists(sourceFile), "old .webm should be deleted");
    }

    // ==================== Test 2: ffprobe 探测失败 → 元数据降级为 null，但 newHqPath 仍随事件回传（API 落库依赖） ====================

    @Test
    void probeEmpty_metadataDegradesButCarriesNewHqPath() throws Exception {
        Long pageId = 200L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("2/ch02"));
        Files.writeString(chapterDir.resolve("bad.mkv"), "fake video data");

        MediaRecord media = new MediaRecord();
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
        TranscodeMediaInfo info = captor.getValue();
        assertNull(info.duration());
        assertNull(info.fileSize());
        assertEquals("2/ch02/bad.mp4", info.newHqPath());
    }

    // ==================== Test 2.5: 目标 {base}.mp4 已存在 → 防撞名 newHqPath 随事件回传（回归：修复 basename 冲突） ====================

    @Test
    void targetMp4Exists_usesTranscodedSuffixAndCarriesIt() throws Exception {
        Long pageId = 600L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("6/ch06"));
        Files.writeString(chapterDir.resolve("movie.mp4"), "existing other media");
        Files.writeString(chapterDir.resolve("movie.mkv"), "source to transcode");

        MediaRecord media = new MediaRecord();
        media.setId(pageId);
        media.setHqRoot("HQ");
        media.setHqPath("6/ch06/movie.mkv");
        media.setMediaType("VIDEO");
        media.setContainer("mkv");
        when(mediaMapper.selectById(pageId)).thenReturn(media);

        config.setFfmpegPath(createFakeFfmpeg(0).toString());
        when(mediaAnalyzer.analyzeVideo(any(Path.class))).thenReturn(Optional.of(
                new ComicMetadata.MediaInfo("movie.transcoded-600.mp4", 0, "READY", "NOT_GENERATED",
                        2048L, 1280, 720, "VIDEO",
                        new BigDecimal("9.9"), "mp4", "h264", "aac")));

        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, pageId, 1,
                "TRANSCODE", "MEDIA", pageId);

        handler.transcode(cmd);

        ArgumentCaptor<TranscodeMediaInfo> captor = ArgumentCaptor.forClass(TranscodeMediaInfo.class);
        verify(publisher).completed(eq(cmd), captor.capture());
        assertEquals("6/ch06/movie.transcoded-600.mp4", captor.getValue().newHqPath());
        assertTrue(Files.exists(chapterDir.resolve("movie.transcoded-600.mp4")), "防撞名产物应存在");
        assertTrue(Files.exists(chapterDir.resolve("movie.mp4")), "既有 mp4 不得被覆盖");
        assertFalse(Files.exists(chapterDir.resolve("movie.mkv")), "源文件应删除");
    }

    // ==================== Test 3: ffmpeg 非零退出 → failed 事件，不发布 completed ====================

    @Test
    void failedTranscode_publishesFailedEvent() throws Exception {
        Long pageId = 300L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("3/ch03"));
        Files.writeString(chapterDir.resolve("bad.avi"), "untranscodable data");

        MediaRecord media = new MediaRecord();
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
        verify(publisher, never()).completed(any(ManagementCommandRequestedEvent.class), any(TranscodeMediaInfo.class));
        verify(publisher, never()).completed(any(ManagementCommandRequestedEvent.class), anyList());
    }

    // ==================== Test 4: 漫画级成功 → 无单页实测元数据可携带，completed 传 null ====================

    @Test
    void comicScope_completedWithoutTranscode() throws Exception {
        Long comicId = 1L;
        Long pageId = 400L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("1/ch04"));
        Files.writeString(chapterDir.resolve("test.mkv"), "fake video data");

        MediaRecord media = new MediaRecord();
        media.setId(pageId);
        media.setHqRoot("HQ");
        media.setHqPath("1/ch04/test.mkv");
        media.setMediaType("VIDEO");
        media.setContainer("mkv");
        when(mediaMapper.selectByComicId(comicId)).thenReturn(List.of(media));

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
        verify(publisher, never()).completed(any(ManagementCommandRequestedEvent.class), any(TranscodeMediaInfo.class));
        verify(publisher, never()).completed(any(ManagementCommandRequestedEvent.class), anyList());
        verify(publisher).progress(eq(cmd), eq(100), eq("转码完成"));
    }

    // ==================== Test 5: 漫画级 mpeg4-in-mp4 应被选中转码（回归） ====================

    @Test
    void comicScope_mp4ContainerMpeg4Codec_isSelectedForTranscode() throws Exception {
        Long comicId = 1L;
        Long pageId = 500L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("1/ch05"));
        Files.writeString(chapterDir.resolve("test.mp4"), "fake video data");

        MediaRecord media = new MediaRecord();
        media.setId(pageId);
        media.setHqRoot("HQ");
        media.setHqPath("1/ch05/test.mp4");
        media.setMediaType("VIDEO");
        media.setContainer("mp4");
        media.setVideoCodec("mpeg4");
        when(mediaMapper.selectByComicId(comicId)).thenReturn(List.of(media));

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

    // ==================== Test 6: 漫画级不得逐页 selectById（N+1 回归） ====================

    @Test
    void comicScope_doesNotRequeryEachPage() throws Exception {
        Long comicId = 1L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("1/ch06"));
        Files.writeString(chapterDir.resolve("a.webm"), "fake video data");
        Files.writeString(chapterDir.resolve("b.webm"), "fake video data");

        MediaRecord mediaA = new MediaRecord();
        mediaA.setId(601L);
        mediaA.setHqRoot("HQ");
        mediaA.setHqPath("1/ch06/a.webm");
        mediaA.setMediaType("VIDEO");
        mediaA.setContainer("webm");
        mediaA.setVideoCodec("mpeg4");

        MediaRecord mediaB = new MediaRecord();
        mediaB.setId(602L);
        mediaB.setHqRoot("HQ");
        mediaB.setHqPath("1/ch06/b.webm");
        mediaB.setMediaType("VIDEO");
        mediaB.setContainer("webm");
        mediaB.setVideoCodec("mpeg4");

        when(mediaMapper.selectByComicId(comicId)).thenReturn(List.of(mediaA, mediaB));

        config.setFfmpegPath(createFakeFfmpeg(0).toString());
        when(mediaAnalyzer.analyzeVideo(any(Path.class))).thenReturn(Optional.of(
                new ComicMetadata.MediaInfo("x.mp4", 0, "READY", "NOT_GENERATED",
                        1024L, 640, 480, "VIDEO",
                        new BigDecimal("3.3"), "mp4", "h264", "aac")));

        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, 601L, 1,
                "TRANSCODE", "COMIC", comicId);

        handler.transcode(cmd);

        // N+1 回归：漫画级转码一次 selectByComicId 取回全部页数据后，
        // 不得在循环内对每个视频页重复 selectById
        verify(mediaMapper).selectByComicId(comicId);
        verify(mediaMapper, never()).selectById(any(Long.class));
        verify(publisher).completed(cmd);
        verify(publisher).progress(eq(cmd), eq(100), eq("转码完成"));
        assertTrue(Files.exists(chapterDir.resolve("a.mp4")), "第一个视频转码产物应存在");
        assertTrue(Files.exists(chapterDir.resolve("b.mp4")), "第二个视频转码产物应存在");
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
            if /i "%~x1"==".mp4" set "output=%~1"
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
