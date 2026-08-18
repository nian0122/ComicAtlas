package com.comicatlas.worker.image;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图片优化器单元测试：force 参数必须传递为 Go 工具的 -force 标志。
 * 这是 LQ_REGENERATE 与 LQ_GENERATE 的唯一行为差异——regenerate 时强制重新压缩，
 * 忽略已存在的 LQ 产物。
 */
@DisplayName("ImageOptimizerTest — force 参数透传 -force")
class ImageOptimizerTest {

    @TempDir
    Path tempDir;

    private final WorkerConfig config = mock(WorkerConfig.class);
    private final ExternalProcessRunner processRunner = mock(ExternalProcessRunner.class);
    private final ImageOptimizer optimizer =
            new ImageOptimizer(config, new ObjectMapper(), processRunner);

    @BeforeEach
    void setUp() throws Exception {
        WorkerConfig.Image imageConfig = new WorkerConfig.Image();
        when(config.getImage()).thenReturn(imageConfig);
        when(config.getImageOptimizerPath()).thenReturn("tools/image-optimizer/image-optimizer.exe");
        when(config.resolveToolPath(anyString())).thenReturn(Path.of("C:/tools/image-optimizer.exe"));
        when(config.getLqQuality()).thenReturn(15);
        when(config.getLqWorkers()).thenReturn(4);
        when(processRunner.run(any(ProcessBuilder.class), anyLong(), anyString()))
                .thenReturn(new ExternalProcessRunner.ExternalProcessResult(0,
                        "{\"total\":0,\"processed\":0,\"skipped\":0,\"failed\":0,\"pages\":[]}"));
    }

    @Test
    @DisplayName("force=true 时命令行包含 -force")
    void generateLq_forceTrue_commandContainsForce() throws Exception {
        Path hqDir = Files.createDirectories(tempDir.resolve("hq"));

        optimizer.generateLq(1L, 2L, hqDir, tempDir.resolve("lq"), true);

        ArgumentCaptor<ProcessBuilder> captor = ArgumentCaptor.forClass(ProcessBuilder.class);
        verify(processRunner).run(captor.capture(), anyLong(), anyString());
        assertThat(captor.getValue().command()).as("force=true 应传 -force 强制重压")
                .contains("-force");
    }

    @Test
    @DisplayName("force=false 时命令行不含 -force（保留既有产物）")
    void generateLq_forceFalse_commandWithoutForce() throws Exception {
        Path hqDir = Files.createDirectories(tempDir.resolve("hq"));

        optimizer.generateLq(1L, 2L, hqDir, tempDir.resolve("lq"), false);

        ArgumentCaptor<ProcessBuilder> captor = ArgumentCaptor.forClass(ProcessBuilder.class);
        verify(processRunner).run(captor.capture(), anyLong(), anyString());
        assertThat(captor.getValue().command()).as("force=false 不应传 -force")
                .doesNotContain("-force");
    }

    @Test
    @DisplayName("命令行保留四路 worker 并传递在途像素预算")
    void generateLq_commandContainsWorkersAndPixelBudget() throws Exception {
        WorkerConfig.Image imageConfig = new WorkerConfig.Image();
        imageConfig.setMaxInflightPixels(80_000_000L);
        when(config.getImage()).thenReturn(imageConfig);
        Path hqDir = Files.createDirectories(tempDir.resolve("hq"));

        optimizer.generateLq(1L, 2L, hqDir, tempDir.resolve("lq"), false);

        ArgumentCaptor<ProcessBuilder> captor = ArgumentCaptor.forClass(ProcessBuilder.class);
        verify(processRunner).run(captor.capture(), anyLong(), anyString());
        assertThat(captor.getValue().command())
                .containsSubsequence("-workers", "4")
                .containsSubsequence("-max-inflight-pixels", "80000000");
    }

    @Test
    @DisplayName("stdout 为空且退出码非 2 时抛出带退出码的异常（工具异常退出）")
    void generateLq_emptyStdoutNon2Exit_throwsWithExitCode() throws Exception {
        // Windows 崩溃退出码（0xC0000005 = 访问违规，Java int 表示为 -1073741819）
        int crashExitCode = 0xC0000005;
        when(processRunner.run(any(ProcessBuilder.class), anyLong(), anyString()))
                .thenReturn(new ExternalProcessRunner.ExternalProcessResult(crashExitCode, ""));
        Path hqDir = Files.createDirectories(tempDir.resolve("hq"));

        assertThatThrownBy(() -> optimizer.generateLq(1L, 2L, hqDir, tempDir.resolve("lq"), false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(String.valueOf(crashExitCode))
                .hasMessageContaining("stdout 为空");
    }

    @Test
    @DisplayName("exitCode=137 时明确报告容器内存不足")
    void generateLq_exit137_reportsContainerOutOfMemory() throws Exception {
        when(processRunner.run(any(ProcessBuilder.class), anyLong(), anyString()))
                .thenReturn(new ExternalProcessRunner.ExternalProcessResult(137, ""));
        Path hqDir = Files.createDirectories(tempDir.resolve("hq"));

        assertThatThrownBy(() -> optimizer.generateLq(1L, 2L, hqDir, tempDir.resolve("lq"), false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("exitCode=137")
                .hasMessageContaining("容器内存不足");
    }

    @Test
    @DisplayName("exitCode=1 但 stdout 含合法 JSON 时仍正常解析（部分失败场景）")
    void generateLq_exit1WithValidJson_stillParses() throws Exception {
        when(processRunner.run(any(ProcessBuilder.class), anyLong(), anyString()))
                .thenReturn(new ExternalProcessRunner.ExternalProcessResult(1,
                        "{\"total\":1,\"processed\":0,\"skipped\":0,\"failed\":1,\"pages\":["
                                + "{\"pageNumber\":1,\"status\":\"failed\",\"reason\":\"decode error\"}]}"));
        Path hqDir = Files.createDirectories(tempDir.resolve("hq"));

        ImageOptimizer.RunResult parsed = optimizer.generateLq(1L, 2L, hqDir, tempDir.resolve("lq"), false);

        assertThat(parsed.getFailed()).isEqualTo(1);
        assertThat(parsed.getPages()).singleElement().satisfies(page ->
                assertThat(page.getReason()).isEqualTo("decode error"));
    }
}
