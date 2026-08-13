package com.comicatlas.worker.media;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.mockito.Mockito;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MediaAnalyzer 冒烟测试（JUnit 版）：
 * 直接实例化 MediaAnalyzer，验证图片/视频/缺失文件的元数据解析。
 * 纳入 Surefire 门禁（原为 main() 独立程序，未进入 Maven 测试）。
 */
@DisplayName("MediaAnalyzerSmokeTest — 媒体元数据分析冒烟")
class MediaAnalyzerSmokeTest {

    @TempDir
    Path tmp;

    private WorkerConfig cfg;
    private ObjectMapper om;
    private ExternalProcessRunner runner;
    private ThreadPoolTaskExecutor executor;
    private MediaAnalyzer analyzer;

    @BeforeEach
    void setUp() throws Exception {
        cfg = new WorkerConfig();
        cfg.setFfprobePath("worker-service/ffmpeg/ffprobe.exe");  // 不存在 → 走 fallback
        om = new ObjectMapper();
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setThreadNamePrefix("smoke-test-process-io-");
        executor.initialize();
        runner = new ExternalProcessRunner(executor);
        analyzer = new MediaAnalyzer(cfg, om, runner);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("jpg → IMAGE 且读取宽高")
    void jpg_isImageWithDimensions() throws Exception {
        Path jpg = tmp.resolve("test.jpg");
        BufferedImage img = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "jpg", jpg.toFile());

        ComicMetadata.MediaInfo info = analyzer.analyze(jpg);
        assertThat(info.mediaType()).isEqualTo("IMAGE");
        assertThat(info.width()).isEqualTo(100);
        assertThat(info.height()).isEqualTo(80);
        assertThat(info.fileName()).isNotNull();
        assertThat(info.fileSize()).isGreaterThan(0);
        assertThat(info.hqStatus()).isEqualTo("READY");
        assertThat(info.duration()).isNull();
        assertThat(info.videoCodec()).isNull();
    }

    @Test
    @DisplayName("mp4 无 ffprobe → VIDEO 且视频字段为 null")
    void mp4_withoutFfprobe_isVideoWithNulls() throws Exception {
        Path mp4 = tmp.resolve("test.mp4");
        Files.write(mp4, new byte[]{0, 0, 0, 0});

        ComicMetadata.MediaInfo info = analyzer.analyze(mp4);
        assertThat(info.mediaType()).isEqualTo("VIDEO");
        assertThat(info.container()).isEqualTo("mp4");
        assertThat(info.duration()).isNull();
        assertThat(info.width()).isNull();
        assertThat(info.height()).isNull();
        assertThat(info.videoCodec()).isNull();
        assertThat(info.audioCodec()).isNull();
    }

    @Test
    @DisplayName("mkv 无 ffprobe → VIDEO")
    void mkv_withoutFfprobe_isVideo() throws Exception {
        Path mkv = tmp.resolve("test.mkv");
        Files.write(mkv, new byte[]{0, 0, 0, 0});

        ComicMetadata.MediaInfo info = analyzer.analyze(mkv);
        assertThat(info.mediaType()).isEqualTo("VIDEO");
        assertThat(info.container()).isEqualTo("mkv");
    }

    @Test
    @DisplayName("analyzeVideo 入口 container 无点（转码 probe 用，回归 .mp4 脏数据）")
    void analyzeVideo_entry_containerWithoutDot() throws Exception {
        Path mp4 = tmp.resolve("test.mp4");
        Files.write(mp4, new byte[]{0, 0, 0, 0});
        cfg.setFfprobePath("");  // 走 fallback，container 由扩展名派生

        ComicMetadata.MediaInfo info = analyzer.analyzeVideo(mp4).orElseThrow();
        assertThat(info.mediaType()).isEqualTo("VIDEO");
        assertThat(info.container()).isEqualTo("mp4");
        assertThat(info.container()).doesNotStartWith(".");
    }

    @Test
    @DisplayName("fake-ffprobe 返回 JSON → 解析视频元数据")
    void mp4_withFakeFfprobe_parsesVideoMetadata() throws Exception {
        Path mp4 = tmp.resolve("test.mp4");
        Files.write(mp4, new byte[]{0, 0, 0, 0});
        Path fakeScript = createFakeFfprobeScript(tmp);
        cfg.setFfprobePath(fakeScript.toString());
        MediaAnalyzer analyzer2 = new MediaAnalyzer(cfg, om, runner);

        ComicMetadata.MediaInfo info = analyzer2.analyze(mp4);
        assertThat(info.mediaType()).isEqualTo("VIDEO");
        assertThat(info.container()).isEqualTo("mp4");
        assertThat(info.width()).isEqualTo(1920);
        assertThat(info.height()).isEqualTo(1080);
        assertThat(info.videoCodec()).isEqualTo("h264");
        assertThat(info.audioCodec()).isEqualTo("aac");
    }

    @Test
    @DisplayName("ffprobe stderr 输出解码错误（损坏 h264）时 stdout JSON 仍被正确解析")
    void corruptVideo_withFfprobeStderrErrors_parsesStdoutJson() throws Exception {
        Path mp4 = tmp.resolve("test.mp4");
        Files.write(mp4, new byte[]{0, 0, 0, 0});
        Path fakeScript = createFakeFfprobeWithStderrErrors(tmp);
        cfg.setFfprobePath(fakeScript.toString());
        MediaAnalyzer analyzer4 = new MediaAnalyzer(cfg, om, runner);

        // 回归：ffprobe 对损坏流会向 stderr 打印 NAL 解码错误（exit=0），
        // stderr 必须与 stdout 分离，否则合并流破坏 JSON 导致视频元数据全部丢失。
        ComicMetadata.MediaInfo info = analyzer4.analyze(mp4);
        assertThat(info.mediaType()).isEqualTo("VIDEO");
        assertThat(info.container()).isEqualTo("mp4");
        assertThat(info.width()).isEqualTo(1920);
        assertThat(info.height()).isEqualTo(1080);
        assertThat(info.videoCodec()).isEqualTo("h264");
        assertThat(info.audioCodec()).isEqualTo("aac");
        assertThat(info.duration()).isEqualByComparingTo("125.5");
    }

    @Test
    @DisplayName("ffprobe 路径为空时不启动外部进程，直接回退 VIDEO")
    void blankFfprobePath_skipsProcessAndFallsBack() throws Exception {
        Path mp4 = tmp.resolve("test.mp4");
        Files.write(mp4, new byte[]{0, 0, 0, 0});
        cfg.setFfprobePath("");

        ExternalProcessRunner mockRunner = Mockito.mock(ExternalProcessRunner.class);
        MediaAnalyzer analyzer5 = new MediaAnalyzer(cfg, om, mockRunner);

        ComicMetadata.MediaInfo info = analyzer5.analyze(mp4);
        assertThat(info.mediaType()).isEqualTo("VIDEO");
        assertThat(info.width()).isNull();
        assertThat(info.duration()).isNull();
        Mockito.verifyNoInteractions(mockRunner);
    }

    @Test
    @DisplayName("不存在的文件 → MISSING")
    void missingFile_isMissing() {
        ComicMetadata.MediaInfo info = analyzer.analyze(tmp.resolve("nope.jpg"));
        assertThat(info.hqStatus()).isEqualTo("MISSING");
        assertThat(info.fileSize()).isZero();
    }

    @Test
    @DisplayName("空 ffprobe 路径 → 走 fallback")
    void emptyFfprobePath_fallsBack() throws Exception {
        Path mp4 = tmp.resolve("test.mp4");
        Files.write(mp4, new byte[]{0, 0, 0, 0});
        cfg.setFfprobePath("");
        MediaAnalyzer analyzer3 = new MediaAnalyzer(cfg, om, runner);

        ComicMetadata.MediaInfo info = analyzer3.analyze(mp4);
        assertThat(info.mediaType()).isEqualTo("VIDEO");
        assertThat(info.duration()).isNull();
    }

    private Path createFakeFfprobeScript(Path dir) throws Exception {
        String jsonBody = "{\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":1920,\"height\":1080},{\"codec_type\":\"audio\",\"codec_name\":\"aac\"}],\"format\":{\"duration\":\"125.500000\"}}";
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            Path script = dir.resolve("fake-ffprobe.cmd");
            String content = "@echo off\r\necho " + jsonBody + "\r\n";
            Files.writeString(script, content);
            return script;
        }
        Path script = dir.resolve("fake-ffprobe.sh");
        String content = "#!/bin/sh\necho '" + jsonBody + "'\n";
        Files.writeString(script, content);
        script.toFile().setExecutable(true);
        return script;
    }

    /** fake ffprobe：stdout 输出合法 JSON，stderr 输出真实损坏 h264 的解码错误行（exit=0）。 */
    private Path createFakeFfprobeWithStderrErrors(Path dir) throws Exception {
        String jsonBody = "{\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":1920,\"height\":1080},{\"codec_type\":\"audio\",\"codec_name\":\"aac\"}],\"format\":{\"duration\":\"125.500000\"}}";
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            Path script = dir.resolve("fake-ffprobe-err.cmd");
            String content = "@echo off\r\n"
                    + "echo [h264 @ 000001eb8d40c240] Error splitting the input into NAL units. 1>&2\r\n"
                    + "echo [h264 @ 000001eb8d40c240] missing picture in access unit with size 25317 1>&2\r\n"
                    + "echo " + jsonBody + "\r\n";
            Files.writeString(script, content);
            return script;
        }
        Path script = dir.resolve("fake-ffprobe-err.sh");
        String content = "#!/bin/sh\n"
                + "echo '[h264 @ 000001eb8d40c240] Error splitting the input into NAL units.' >&2\n"
                + "echo '[h264 @ 000001eb8d40c240] missing picture in access unit with size 25317' >&2\n"
                + "echo '" + jsonBody + "'\n";
        Files.writeString(script, content);
        script.toFile().setExecutable(true);
        return script;
    }
}
