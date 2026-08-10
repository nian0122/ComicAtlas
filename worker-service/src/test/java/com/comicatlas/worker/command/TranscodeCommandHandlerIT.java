package com.comicatlas.worker.command;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import com.comicatlas.worker.process.ExternalProcessRunner;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TranscodeCommandHandler 集成测试（F6-09 回归，真实 MediaAnalyzer 不 mock）。
 * <p>
 * 缺陷根因：处理器把 ffmpeg 输出写到 {@code .mp4.tmp}，随后用 {@link MediaAnalyzer} probe
 * 该临时文件；而 {@code MediaAnalyzer.analyzeVideo} 有扩展名门禁（仅接受 VIDEO_EXT，
 * 如 {@code .mp4}），裸 {@code .tmp} 被拒绝 → probe 恒返回空 → 任务恒失败。
 * <p>
 * 修复：临时产物保留可被门禁识别的末尾 {@code .mp4}（如 {@code .probe.mp4}）。
 * 本测试验证：
 * <ul>
 *   <li>真实 MediaAnalyzer + 假 ffmpeg/ffprobe：临时文件需以可识别视频扩展名结尾才可被 probe 接受；</li>
 *   <li>真实 ffmpeg/ffprobe（系统工具）：完整转码 → ffprobe 验证 → 原子发布 → completed；
 *       最终 MP4 可被真实 ffprobe 识别；</li>
 *   <li>真实 ffmpeg 失败路径：不得发布最终文件、不留孤儿临时文件、旧源保留；</li>
 *   <li>中断路径：恢复中断标志、清理临时文件、发布 failed。</li>
 * </ul>
 * Worker 不写数据库（mediaMapper 仅 mock 只读）。
 */
class TranscodeCommandHandlerIT {

    private ExportMediaMapper mediaMapper;
    private ManagementCommandPublisher publisher;
    private WorkerConfig config;
    private StorageProperties storageProperties;
    private ExternalProcessRunner processRunner;
    private MediaAnalyzer analyzer;
    private TranscodeCommandHandler handler;

    private ThreadPoolTaskExecutor ioExecutor;
    private Path tempRoot;
    private Path hqRoot;

    /** 系统 ffmpeg 可执行文件（绝对路径，可能为 null）。 */
    private String ffmpeg;
    /** 系统 ffprobe 可执行文件（绝对路径，可能为 null）。 */
    private String ffprobe;

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("tch-it-");
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
        ioExecutor.setCorePoolSize(2);
        ioExecutor.setMaxPoolSize(2);
        ioExecutor.setThreadNamePrefix("tch-it-process-io-");
        ioExecutor.initialize();
        processRunner = new ExternalProcessRunner(ioExecutor);

        // 真实 MediaAnalyzer（关键：扩展名门禁真实生效，不 mock）
        analyzer = new MediaAnalyzer(config, new ObjectMapper(), processRunner);

        mediaMapper = mock(ExportMediaMapper.class);
        publisher = mock(ManagementCommandPublisher.class);

        handler = new TranscodeCommandHandler(mediaMapper, config, storageProperties,
                publisher, processRunner, analyzer);

        ffmpeg = findTool("ffmpeg", "FFMPEG_PATH");
        ffprobe = findTool("ffprobe", "FFPROBE_PATH");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ioExecutor != null) {
            ioExecutor.shutdown();
        }
        deleteRecursively(tempRoot);
    }

    // ==================== Test 1: 根因回归 —— 临时文件必须以可识别视频扩展名结尾 ====================

    @Test
    void tempProbeFile_endsWithRecognizedVideoExtension_realAnalyzerAcceptsIt() throws Exception {
        Long mediaId = 1L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("1/ch01"));
        Path sourceFile = chapterDir.resolve("source.webm");
        Files.writeString(sourceFile, "fake video data");
        when(mediaMapper.selectById(mediaId)).thenReturn(media("1/ch01/source.webm"));

        config.setFfmpegPath(createFakeFfmpeg(0).toString());
        config.setFfprobePath(createFakeFfprobe().toString());

        ManagementCommandRequestedEvent command = cmd(mediaId);
        handler.transcode(command);

        // 旧实现临时文件为 *.mp4.tmp，真实 MediaAnalyzer 门禁拒绝 → 只会发布 failed（本测试因此红）
        ArgumentCaptor<TranscodeMediaInfo> captor = ArgumentCaptor.forClass(TranscodeMediaInfo.class);
        verify(publisher).completed(eq(command), captor.capture());
        TranscodeMediaInfo info = captor.getValue();
        assertEquals("HQ", info.hqRoot());
        assertEquals("1/ch01/1-2-3-1.mp4", info.hqPath());
        assertEquals("mp4", info.container());
        assertEquals("h264", info.videoCodec());
        assertEquals("aac", info.audioCodec());
        assertTrue(Files.exists(chapterDir.resolve("1-2-3-1.mp4")), "确定性 mp4 产物应在 HQ 目录");
        assertTrue(Files.exists(sourceFile), "旧源文件不得删除");
        assertNoOrphanTempFiles();
    }

    // ==================== Test 2: 真实 ffmpeg/ffprobe 完整链路 ====================

    @Test
    void realTranscodeWithSystemTools_publishesCompleted_andProducesProbeableMp4() throws Exception {
        assumeTrue(ffmpeg != null && ffprobe != null,
                "系统缺少 ffmpeg/ffprobe，跳过真实工具集成测试（标记 BLOCKED）");

        Long mediaId = 2L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("2/ch02"));
        Path sourceFile = chapterDir.resolve("source.mp4");
        createTinySourceMp4(sourceFile);
        when(mediaMapper.selectById(mediaId)).thenReturn(media("2/ch02/source.mp4"));

        config.setFfmpegPath(ffmpeg);
        config.setFfprobePath(ffprobe);

        ManagementCommandRequestedEvent command = cmd(mediaId);
        handler.transcode(command);

        ArgumentCaptor<TranscodeMediaInfo> captor = ArgumentCaptor.forClass(TranscodeMediaInfo.class);
        verify(publisher).completed(eq(command), captor.capture());
        TranscodeMediaInfo info = captor.getValue();
        Path finalFile = chapterDir.resolve("1-2-3-2.mp4");
        assertTrue(Files.exists(finalFile), "最终 mp4 产物应生成");
        assertTrue(Files.size(finalFile) > 0, "最终产物不应为空");
        assertEquals("2/ch02/1-2-3-2.mp4", info.hqPath());
        assertEquals("mp4", info.container());
        assertEquals("h264", info.videoCodec());
        assertEquals("aac", info.audioCodec());
        assertNotNull(info.duration(), "真实 ffprobe 应读出时长");
        assertNotNull(info.width(), "真实 ffprobe 应读出宽");
        assertNotNull(info.height(), "真实 ffprobe 应读出高");
        assertTrue(Files.exists(sourceFile), "旧源文件不得删除");
        assertNoOrphanTempFiles();

        // 独立复核：最终 MP4 可被真实 MediaAnalyzer（ffprobe）识别
        ComicMetadata.MediaInfo probe = analyzer.analyzeVideo(finalFile).orElse(null);
        assertNotNull(probe, "最终产物必须能被 ffprobe 识别");
        assertEquals("mp4", probe.container());
        assertEquals("h264", probe.videoCodec());
    }

    // ==================== Test 3: 真实 ffmpeg 失败路径（非视频输入） → 不发布最终文件、清理临时、源保留 ====================

    @Test
    void realFfmpegFailure_publishesFailed_cleansTemp_andKeepsSource() throws Exception {
        assumeTrue(ffmpeg != null && ffprobe != null,
                "系统缺少 ffmpeg/ffprobe，跳过真实工具集成测试（标记 BLOCKED）");

        Long mediaId = 3L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("3/ch03"));
        Path sourceFile = chapterDir.resolve("broken.txt");
        Files.writeString(sourceFile, "not a video");
        when(mediaMapper.selectById(mediaId)).thenReturn(media("3/ch03/broken.txt"));

        config.setFfmpegPath(ffmpeg);
        config.setFfprobePath(ffprobe);

        ManagementCommandRequestedEvent command = cmd(mediaId);
        handler.transcode(command);

        verify(publisher).failed(eq(command), anyString());
        verify(publisher, never()).completed(eq(command), any(TranscodeMediaInfo.class));
        assertTrue(Files.exists(sourceFile), "失败后旧源保留");
        assertFalse(Files.exists(chapterDir.resolve("1-2-3-3.mp4")), "失败不得发布最终产物");
        assertNoOrphanTempFiles();
    }

    // ==================== Test 4: 中断 → 恢复中断标志 + failed + 临时清理 + 源保留（真实 MediaAnalyzer） ====================

    @Test
    void interruptedRealAnalyzer_restoresInterruptFlag_cleansTemp() throws Exception {
        Long mediaId = 4L;
        Path chapterDir = Files.createDirectories(hqRoot.resolve("4/ch04"));
        Path sourceFile = chapterDir.resolve("slow.mkv");
        Files.writeString(sourceFile, "slow video data");
        when(mediaMapper.selectById(mediaId)).thenReturn(media("4/ch04/slow.mkv"));

        config.setFfmpegPath(createSleepingFfmpeg(0).toString());
        config.setFfprobePath(createFakeFfprobe().toString());

        ManagementCommandRequestedEvent command = cmd(mediaId);
        Thread t = new Thread(() -> handler.transcode(command));
        t.start();
        Thread.sleep(500);
        t.interrupt();
        t.join(15000);
        assertFalse(t.isAlive(), "转码线程应已结束");
        assertTrue(t.isInterrupted(), "中断标志必须恢复");
        verify(publisher).failed(eq(command), eq("转码被中断"));
        verify(publisher, never()).completed(eq(command), any(TranscodeMediaInfo.class));
        assertTrue(Files.exists(sourceFile), "中断后旧源保留");
        assertNoOrphanTempFiles();
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

    /**
     * 查找外部工具可执行文件（返回绝对路径或 null）：
     * 1. 环境变量显式配置（相对路径按项目根目录解析）；
     * 2. 项目 {@code tools/ffmpeg/} 目录；
     * 3. 系统 PATH（where 命令）。
     */
    private String findTool(String name, String envVar) {
        String env = System.getenv(envVar);
        if (env != null && !env.isBlank()) {
            Path envPath = Path.of(env);
            if (envPath.isAbsolute() && Files.exists(envPath)) {
                return envPath.toString();
            }
            Path rel = Path.of(System.getProperty("user.dir"), "..").resolve(env).normalize();
            if (Files.exists(rel)) {
                return rel.toString();
            }
        }
        Path tools = Path.of(System.getProperty("user.dir"), "..", "tools", "ffmpeg", name + ".exe").normalize();
        if (Files.exists(tools)) {
            return tools.toString();
        }
        try {
            Process process = new ProcessBuilder("where", name).start();
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                String first = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                        .split("\\r?\\n")[0].trim();
                if (!first.isBlank() && Files.exists(Path.of(first))) {
                    return first;
                }
            }
            process.destroyForcibly();
        } catch (Exception ignored) {
            // 找不到工具时返回 null，由 assumeTrue 跳过真实工具用例
        }
        return null;
    }

    /** 用真实 ffmpeg 生成 1 秒、64x48 的最小有效 mp4 源文件（含 h264+aac）。 */
    private void createTinySourceMp4(Path out) throws Exception {
        List<String> command = List.of(
                ffmpeg,
                "-f", "lavfi", "-i", "testsrc2=duration=1:size=64x48:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", "-y",
                out.toAbsolutePath().toString());
        ExternalProcessRunner.ExternalProcessResult result =
                processRunner.run(new ProcessBuilder(command), 60);
        if (result.exitCode() != 0 || !Files.exists(out) || Files.size(out) == 0) {
            throw new IllegalStateException("生成测试源视频失败: exit=" + result.exitCode() + ", path=" + out);
        }
    }

    /** 假 ffprobe：无论输入，恒输出与容器兼容的 h264/aac 元数据 JSON。 */
    private Path createFakeFfprobe() throws Exception {
        String jsonBody = "{\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":1280,\"height\":720},"
                + "{\"codec_type\":\"audio\",\"codec_name\":\"aac\"}],\"format\":{\"duration\":\"12.340000\"}}";
        String os = System.getProperty("os.name").toLowerCase();
        Path script;
        if (os.contains("win")) {
            script = tempRoot.resolve("fake-ffprobe.cmd");
            Files.writeString(script, "@echo off\r\necho " + jsonBody + "\r\n");
        } else {
            script = tempRoot.resolve("fake-ffprobe.sh");
            Files.writeString(script, "#!/bin/sh\necho '" + jsonBody + "'\n");
            script.toFile().setExecutable(true);
        }
        return script;
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

    /** 睡眠假 ffmpeg：先 sleep 约 30 秒（供中断测试命中），再写输出。 */
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

    /** 断言临时目录无孤儿转码文件（.tmp 或 .probe.mp4）。 */
    private void assertNoOrphanTempFiles() throws Exception {
        Path temp = Path.of(config.getTempDir());
        if (!Files.exists(temp)) {
            return;
        }
        try (var stream = Files.list(temp)) {
            assertTrue(stream.noneMatch(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".tmp") || name.endsWith(".probe.mp4");
            }), "临时转码文件（.tmp/.probe.mp4）应已清理: temp=" + temp);
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
