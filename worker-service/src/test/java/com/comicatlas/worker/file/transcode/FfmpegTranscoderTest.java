package com.comicatlas.worker.file.transcode;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FfmpegTranscoder 单元测试 — 编码器选择与命令构造。
 * <p>
 * 硬件加速探测（ffmpeg -encoders）在测试中不可用（fake 脚本无编码器输出），
 * 断言回退 CPU 参数；真实环境由探测逻辑启用 NVENC/QSV/AMF。
 */
@DisplayName("FfmpegTranscoder 编码器选择与命令构造")
class FfmpegTranscoderTest {

    private ThreadPoolTaskExecutor ioExecutor;
    private ExternalProcessRunner processRunner;
    private WorkerConfig config;
    private FfmpegTranscoder transcoder;
    private Path tempRoot;

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("fft-test-");
        config = new WorkerConfig();
        config.setMangaRoot(tempRoot.toString());
        config.setTempDir(tempRoot.resolve("temp").toString());
        Files.createDirectories(Path.of(config.getTempDir()));

        ioExecutor = new ThreadPoolTaskExecutor();
        ioExecutor.initialize();
        processRunner = new ExternalProcessRunner(ioExecutor);
        transcoder = new FfmpegTranscoder(config, processRunner);
    }

    @AfterEach
    void tearDown() {
        ioExecutor.shutdown();
        deleteRecursively(tempRoot);
    }

    @Test
    @DisplayName("硬件编码器不可用时回退 CPU libx264 参数")
    void noHwEncoder_fallsBackToCpuArgs() throws Exception {
        Path fakeFfmpeg = createFakeFfmpeg(tempRoot);
        config.setFfmpegPath(fakeFfmpeg.toString());

        List<String> cmd = transcoder.buildCommand(
                fakeFfmpeg.toString(), "in.mp4", "out.mp4");

        // fake ffmpeg -encoders 输出不含硬件编码器 → 回退 CPU
        assertThat(cmd).contains("-c:v", "libx264");
        assertThat(cmd).contains("-preset", "veryfast");
        assertThat(cmd).contains("-c:a", "aac");
        assertThat(cmd).contains("-movflags", "+faststart");
    }

    @Test
    @DisplayName("命令结构：ffmpeg -i input ...args output")
    void commandStructure_isFfmpegInputArgsOutput() throws Exception {
        Path fakeFfmpeg = createFakeFfmpeg(tempRoot);
        config.setFfmpegPath(fakeFfmpeg.toString());

        List<String> cmd = transcoder.buildCommand(
                fakeFfmpeg.toString(), "in.mp4", "out.mp4");

        assertThat(cmd.get(0)).isEqualTo(fakeFfmpeg.toString());
        assertThat(cmd).containsSubsequence("-i", "in.mp4");
        assertThat(cmd.get(cmd.size() - 1)).isEqualTo("out.mp4");
    }

    @Test
    @DisplayName("转码执行：fake ffmpeg 退出码 0 → 返回 0")
    void transcode_exitZero() throws Exception {
        Path fakeFfmpeg = createFakeFfmpeg(tempRoot);
        config.setFfmpegPath(fakeFfmpeg.toString());
        Path in = tempRoot.resolve("in.mp4");
        Path out = tempRoot.resolve("out.mp4");
        Files.writeString(in, "fake");

        int exit = transcoder.transcode(in, out);

        assertThat(exit).isZero();
        assertThat(Files.exists(out)).isTrue();
    }

    @Test
    @DisplayName("硬件编码器运行时失败 → 自动回退 CPU 重试成功")
    void hwEncoderFails_fallsBackToCpuRetry() throws Exception {
        // fake ffmpeg：-encoders 探测报告 h264_nvenc 存在；但用 h264_nvenc 转码失败（exit 1）
        // 且不产出文件，第二次调用（CPU libx264）成功——模拟驱动 API 版本不兼容的 NVENC
        Path fakeFfmpeg = createFakeFfmpegWithHwFailure(tempRoot);
        config.setFfmpegPath(fakeFfmpeg.toString());
        Path in = tempRoot.resolve("in.mp4");
        Path out = tempRoot.resolve("out.mp4");
        Files.writeString(in, "fake");

        int exit = transcoder.transcode(in, out);

        assertThat(exit).isZero();
        assertThat(Files.exists(out)).isTrue();
        // 首次硬件失败 + 回退 CPU：执行了两次
        assertThat(countInvocations(tempRoot, "invocations.log")).isEqualTo(2);
    }

    @Test
    @DisplayName("硬件编码器失败后禁用：后续转码不再尝试硬件")
    void hwEncoderDisabledAfterFailure_subsequentTranscodeSkipsHw() throws Exception {
        Path fakeFfmpeg = createFakeFfmpegWithHwFailure(tempRoot);
        config.setFfmpegPath(fakeFfmpeg.toString());
        Path in = tempRoot.resolve("in.mp4");
        Path out = tempRoot.resolve("out.mp4");
        Files.writeString(in, "fake");

        transcoder.transcode(in, out);   // 第一次：硬件失败 → CPU 成功 → 禁用 NVENC
        Files.deleteIfExists(out);
        transcoder.transcode(in, out);   // 第二次：直接 CPU，不再尝试硬件

        // 4 次调用 = 第1次(hw+cpu) + 第2次(cpu only)；无第3次 hw 尝试
        assertThat(countInvocations(tempRoot, "invocations.log")).isEqualTo(3);
        // CPU 参数出现在命令里
        assertThat(countInvocations(tempRoot, "cpu-calls.log")).isEqualTo(2);
    }

    private Path createFakeFfmpeg(Path dir) throws Exception {
        Path bat = dir.resolve("fake-ffmpeg.cmd");
        String script = """
                @echo off
                setlocal enabledelayedexpansion
                set "args=%*"
                if not "!args:-encoders=!"=="!args!" (
                    echo  V....D libx264  libx264 H.264
                    exit /b 0
                )
                set "output="
                for %%a in (%*) do (
                    if /i "%%~xa"==".mp4" set "output=%%~a"
                )
                if not "!output!"=="" echo fake-transcode-data > "!output!"
                exit /b 0
                """;
        Files.writeString(bat, script);
        return bat;
    }

    /**
     * fake ffmpeg: -encoders probe reports h264_nvenc (probe only checks output,
     * not driver); using h264_nvenc fails (exit 1, no output file) to simulate
     * driver API mismatch; CPU libx264 path succeeds. Invocation log written to
     * {dir}/invocations.log and {dir}/cpu-calls.log.
     */
    private Path createFakeFfmpegWithHwFailure(Path dir) throws Exception {
        Path bat = dir.resolve("fake-ffmpeg-hwfail.cmd");
        Path log = dir.resolve("invocations.log");
        Path cpuLog = dir.resolve("cpu-calls.log");
        String script = """
                @echo off
                setlocal enabledelayedexpansion
                set "args=%*"
                if not "!args:-encoders=!"=="!args!" (
                    echo  V....D h264_nvenc  NVIDIA NVENC H.264 encoder
                    echo  V....D libx264     libx264 H.264
                    exit /b 0
                )
                echo !args!>> "LOG_FILE"
                if not "!args:h264_nvenc=!"=="!args!" (
                    rem hardware encoder: driver mismatch -> fail, no output
                    exit /b 1
                )
                set "output="
                for %%a in (%*) do (
                    if /i "%%~xa"==".mp4" set "output=%%~a"
                )
                if not "!output!"=="" (
                    echo fake-transcode-data > "!output!"
                    echo cpu>> "CPU_LOG"
                )
                exit /b 0
                """.replace("LOG_FILE", log.toString())
                .replace("CPU_LOG", cpuLog.toString());
        Files.writeString(bat, script);
        return bat;
    }

    private static long countInvocations(Path dir, String logName) throws Exception {
        Path log = dir.resolve(logName);
        if (!Files.exists(log)) {
            return 0;
        }
        return Files.readAllLines(log).stream().filter(line -> !line.isBlank()).count();
    }

    private static void deleteRecursively(Path dir) {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
        }
    }
}
