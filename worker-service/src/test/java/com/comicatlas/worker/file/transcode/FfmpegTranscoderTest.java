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
        assertThat(cmd).contains("-preset", "medium");
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

    private Path createFakeFfmpeg(Path dir) throws Exception {
        Path bat = dir.resolve("fake-ffmpeg.cmd");
        // fake ffmpeg：-encoders 探测输出仅 libx264（无硬件编码器）；其他调用把输出写到
        // 参数中最后一个 ".mp4" 文件（外部进程执行用 stdout 收集，必须保证进程正常退出）
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
