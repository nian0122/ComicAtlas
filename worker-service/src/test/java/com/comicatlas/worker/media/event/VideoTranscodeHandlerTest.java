package com.comicatlas.worker.media.event;

import com.comicatlas.common.event.VideoTranscodeCompletedEvent;
import com.comicatlas.common.event.VideoTranscodeFailedEvent;
import com.comicatlas.common.event.VideoTranscodeRequestedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.media.transcode.FfmpegTranscoder;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VideoTranscodeHandler 单元测试。
 * 验证 Worker 正确调用 ffmpeg 转码并发布完成/失败事件，不访问数据库。
 */
@ExtendWith(MockitoExtension.class)
class VideoTranscodeHandlerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private Channel channel;

    private WorkerConfig config;
    private VideoTranscodeHandler handler;
    private FfmpegTranscoder ffmpegTranscoder;

    private Path tempRoot;   // 模拟 mangaRoot + tempDir 根目录
    private Path hqRoot;     // mangaRoot 下的 HQ 目录

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("vt-test-");

        // mangaRoot = tempRoot，HQ 文件位于 tempRoot/HQ/... 下
        hqRoot = Files.createDirectories(tempRoot.resolve("HQ"));

        config = new WorkerConfig();
        config.setMangaRoot(tempRoot.toString());
        config.setTempDir(tempRoot.resolve("temp").toString());
        Files.createDirectories(Path.of(config.getTempDir()));

        ffmpegTranscoder = mock(FfmpegTranscoder.class);

        handler = new VideoTranscodeHandler(rabbitTemplate, config, ffmpegTranscoder, new MqConsumerSupport());
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteRecursively(tempRoot);
    }

    /** 模拟转码：创建输出文件并返回指定退出码。 */
    private void stubTranscode(int exitCode) throws Exception {
        when(ffmpegTranscoder.transcode(any(Path.class), any(Path.class))).thenAnswer(inv -> {
            Path output = inv.getArgument(1);
            Files.writeString(output, "transcoded mp4");
            return exitCode;
        });
    }

    // ==================== Test 1: 转码成功 → 发送完成事件 ====================

    @Test
    void successfulTranscode_publishesCompletedEvent() throws Exception {
        // Arrange: 创建 HQ 源文件 + 假 ffmpeg
        Long pageId = 100L;
        Long comicId = 1L;
        String hqRootKey = "HQ";
        String hqPath = "1/ch01/test.webm";

        Path chapterDir = Files.createDirectories(hqRoot.resolve("1/ch01"));
        Path sourceFile = chapterDir.resolve("test.webm");
        Files.writeString(sourceFile, "fake video data");

        stubTranscode(0);

        VideoTranscodeRequestedEvent event = new VideoTranscodeRequestedEvent(
                UUID.randomUUID(), Instant.now(), comicId, pageId,
                hqRootKey, hqPath, "webm");

        // Act
        handler.handle(event, channel, 1L);

        // Assert: completed event 已发布
        ArgumentCaptor<VideoTranscodeCompletedEvent> captor =
                ArgumentCaptor.forClass(VideoTranscodeCompletedEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq("comic.video"), eq("video.transcode.completed"), captor.capture());

        VideoTranscodeCompletedEvent completed = captor.getValue();
        assertEquals(pageId, completed.pageId());
        assertEquals(comicId, completed.comicId());
        assertEquals("mp4", completed.container());
        assertEquals("h264", completed.videoCodec());
        assertEquals("aac", completed.audioCodec());
        assertTrue(completed.newHqPath().endsWith("test.mp4"));
        assertTrue(completed.fileSize() > 0);

        // 验证 channel ack
        verify(channel).basicAck(eq(1L), eq(false));

        // 验证旧 .webm 已删除，新 .mp4 已就位
        assertFalse(Files.exists(sourceFile), "old .webm should be deleted");
        Path newMp4 = chapterDir.resolve("test.mp4");
        assertTrue(Files.exists(newMp4), "new .mp4 should exist in HQ");
        assertTrue(Files.size(newMp4) > 0, "new .mp4 should not be empty");

        // 验证 temp 文件已被移走（不在 temp 目录下）
        Path tempDir = Path.of(config.getTempDir());
        assertFalse(Files.exists(tempDir.resolve(pageId + ".mp4")),
                "temp file should be moved to HQ (cleaned up)");
    }

    // ==================== Test 2: ffmpeg 非零退出 → 发送失败事件 ====================

    @Test
    void failedTranscode_publishesFailedEvent() throws Exception {
        // Arrange: 假 ffmpeg 退出码 1
        Long pageId = 200L;
        Long comicId = 2L;
        String hqRootKey = "HQ";
        String hqPath = "2/ch02/bad.avi";

        Path chapterDir = Files.createDirectories(hqRoot.resolve("2/ch02"));
        Files.writeString(chapterDir.resolve("bad.avi"), "untranscodable data");

        stubTranscode(1);

        VideoTranscodeRequestedEvent event = new VideoTranscodeRequestedEvent(
                UUID.randomUUID(), Instant.now(), comicId, pageId,
                hqRootKey, hqPath, "avi");

        // Act
        handler.handle(event, channel, 1L);

        // Assert: failed event 已发布
        ArgumentCaptor<VideoTranscodeFailedEvent> captor =
                ArgumentCaptor.forClass(VideoTranscodeFailedEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq("comic.video"), eq("video.transcode.failed"), captor.capture());

        VideoTranscodeFailedEvent failed = captor.getValue();
        assertEquals(pageId, failed.pageId());
        assertEquals(comicId, failed.comicId());
        assertNotNull(failed.errorMessage());
        assertTrue(failed.errorMessage().contains("exit code 1") ||
                failed.errorMessage().contains("pageId=" + pageId));

        // 验证 channel nack
        verify(channel).basicReject(eq(1L), eq(false));

        // 验证 temp 文件已清理
        Path tempDir = Path.of(config.getTempDir());
        assertFalse(Files.exists(tempDir.resolve(pageId + ".mp4")),
                "temp file should be cleaned up on failure");
    }

    // ==================== Test 3: HQ 文件不存在 → 发送失败事件 ====================

    @Test
    void hqFileNotFound_publishesFailedEvent() throws Exception {
        // Arrange: HQ 文件不存在
        Long pageId = 300L;
        Long comicId = 3L;
        String hqRootKey = "HQ";
        String hqPath = "3/ch03/missing.mkv";

        // 确保目录和文件都不存在
        Path expectedFile = hqRoot.resolve("3/ch03/missing.mkv");
        assertFalse(Files.exists(expectedFile), "precondition: HQ file does not exist");

        // 不需要 fake ffmpeg，因为校验会先失败
        config.setFfmpegPath("nonexistent-ffmpeg");

        VideoTranscodeRequestedEvent event = new VideoTranscodeRequestedEvent(
                UUID.randomUUID(), Instant.now(), comicId, pageId,
                hqRootKey, hqPath, "mkv");

        // Act
        handler.handle(event, channel, 1L);

        // Assert: failed event 已发布
        ArgumentCaptor<VideoTranscodeFailedEvent> captor =
                ArgumentCaptor.forClass(VideoTranscodeFailedEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq("comic.video"), eq("video.transcode.failed"), captor.capture());

        VideoTranscodeFailedEvent failed = captor.getValue();
        assertEquals(pageId, failed.pageId());
        assertEquals(comicId, failed.comicId());
        assertNotNull(failed.errorMessage());
        assertTrue(failed.errorMessage().contains("不存在") ||
                failed.errorMessage().contains("missing.mkv"));

        // 验证 channel nack
        verify(channel).basicReject(eq(1L), eq(false));
    }

    @Test
    void 同名Mp4已存在时不覆盖其他页面文件() throws Exception {
        Long pageId = 400L;
        Long comicId = 4L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("4/ch04"));
        Path sourceFile = chapterDir.resolve("same.avi");
        Path existingMp4 = chapterDir.resolve("same.mp4");
        Files.writeString(sourceFile, "avi video");
        Files.writeString(existingMp4, "existing mp4");
        stubTranscode(0);
        VideoTranscodeRequestedEvent event = new VideoTranscodeRequestedEvent(
                UUID.randomUUID(), Instant.now(), comicId, pageId,
                "HQ", "4/ch04/same.avi", "avi");

        handler.handle(event, channel, 1L);

        ArgumentCaptor<VideoTranscodeCompletedEvent> captor =
                ArgumentCaptor.forClass(VideoTranscodeCompletedEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq("comic.video"), eq("video.transcode.completed"), captor.capture());
        assertEquals("4/ch04/same.transcoded-400.mp4", captor.getValue().newHqPath());
        assertEquals("existing mp4", Files.readString(existingMp4));
        assertTrue(Files.exists(chapterDir.resolve("same.transcoded-400.mp4")));
        assertFalse(Files.exists(sourceFile));
    }

    // ==================== Test 4: 确认 Worker 零数据库依赖 ====================

    @Test
    void confirmNoDatabaseImports() throws Exception {
        // 通过源码级反射验证 VideoTranscodeHandler 不包含任何 Mapper/DataSource/JdbcTemplate 导入
        String sourcePath = "worker-service/src/main/java/com/comicatlas/worker/media/event/VideoTranscodeHandler.java";
        Path sourceFile = Path.of(System.getProperty("user.dir"), "..", sourcePath).normalize();
        if (!Files.exists(sourceFile)) {
            // 从 worker-service 模块内运行时路径可能不同
            sourceFile = Path.of(System.getProperty("user.dir"), sourcePath).normalize();
        }
        if (!Files.exists(sourceFile)) {
            // 直接从项目根
            sourceFile = Path.of("D:/projects/ComicAtlas", sourcePath).normalize();
        }
        assertTrue(Files.exists(sourceFile), "VideoTranscodeHandler source file must exist for import check");

        String source = Files.readString(sourceFile);

        // 这些关键符号绝不应出现在 import 中
        for (String banned : new String[]{"Mapper", "DataSource", "JdbcTemplate", "SqlSession",
                "MyBatis", "Repository", "com.mysql"}) {
            assertFalse(source.contains(banned),
                    "VideoTranscodeHandler must NOT import " + banned + " (worker should never touch DB)");
        }

        // 确认只使用允许的依赖
        assertTrue(source.contains("RabbitTemplate"), "must use RabbitTemplate for event publishing");
        assertTrue(source.contains("WorkerConfig"), "must use WorkerConfig");
        assertTrue(
                source.contains("ffmpegTranscoder.transcode("),
                "ffmpeg must run via FfmpegTranscoder (unified transcoding core)");
    }

    // ==================== helpers ====================

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
