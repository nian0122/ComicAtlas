package com.comicatlas.worker.media.image;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.shared.process.ExternalProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 封面生成器单元测试：必须拒绝 image-optimizer 的 0 字节空产物。
 * <p>
 * 回归场景：漫画 247 的 `1 (1).jpg`（44.5MB 大图）导致 Go 工具写出 0 字节
 * {@code 1 (1).webp} 且退出码为 0，旧实现把空文件直接 move 成 cover.webp，
 * 前端展示空封面。修复后空产物必须抛异常（触发 {@code generateCoverFromNode}
 * 的下一候选兜底）并清理残留。
 */
@DisplayName("CoverGeneratorTest — 封面生成必须拒绝空产物")
class CoverGeneratorTest {

    private static final long COMIC_ID = 50L;

    @TempDir
    Path tempDir;

    private final WorkerConfig config = mock(WorkerConfig.class);
    private final ExternalProcessRunner processRunner = mock(ExternalProcessRunner.class);
    private final CoverGenerator generator = new CoverGenerator(config, processRunner);

    @BeforeEach
    void setUp() {
        when(config.getMangaRoot()).thenReturn(tempDir.toString());
        when(config.resolveTempDir()).thenReturn(tempDir.resolve("temp"));
        when(config.getImageOptimizerPath()).thenReturn("tools/image-optimizer/image-optimizer.exe");
        when(config.resolveToolPath(anyString())).thenReturn(Path.of("C:/tools/image-optimizer.exe"));
        when(config.getCover()).thenReturn(new WorkerConfig.Cover());
    }

    @Test
    @DisplayName("optimizer 输出 0 字节 WebP（退出码 0）时抛异常、不产出 cover.webp、清理空产物")
    void generateCover_emptyOutput_rejectedAndCleaned() throws Exception {
        Path source = writeSource("1 (1).jpg", new byte[]{1, 2, 3, 4});
        // 复现事故场景：Go 工具对大图写出 0 字节产物且退出码仍为 0
        doAnswer(invocation -> {
            Path thumbsDir = tempDir.resolve("thumbs/" + COMIC_ID);
            Files.createDirectories(thumbsDir);
            Files.write(thumbsDir.resolve("1 (1).webp"), new byte[0]);
            return new ExternalProcessRunner.ExternalProcessResult(0, "{\"processed\":1}");
        }).when(processRunner).run(any(ProcessBuilder.class), anyLong(), anyString());

        assertThatThrownBy(() -> generator.generateCover(COMIC_ID, source))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("空");

        assertThat(Files.exists(tempDir.resolve("thumbs/" + COMIC_ID + "/cover.webp")))
                .as("空产物不得落位 cover.webp")
                .isFalse();
        assertThat(Files.exists(tempDir.resolve("thumbs/" + COMIC_ID + "/1 (1).webp")))
                .as("0 字节残留产物应被清理")
                .isFalse();
    }

    @Test
    @DisplayName("optimizer 未产生任何 WebP 输出时抛异常（不产出空 cover.webp）")
    void generateCover_missingOutput_rejected() throws Exception {
        Path source = writeSource("cover.jpg", new byte[]{1, 2, 3});

        doAnswer(invocation -> new ExternalProcessRunner.ExternalProcessResult(0, "{}"))
                .when(processRunner).run(any(ProcessBuilder.class), anyLong(), anyString());

        assertThatThrownBy(() -> generator.generateCover(COMIC_ID, source))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("未生成输出");
        assertThat(Files.exists(tempDir.resolve("thumbs/" + COMIC_ID + "/cover.webp")))
                .as("缺失产物时不得创建空 cover.webp")
                .isFalse();
    }

    @Test
    @DisplayName("optimizer 输出非空 WebP 时正常落位 cover.webp")
    void generateCover_validOutput_movedToCover() throws Exception {
        Path source = writeSource("1 (2).jpg", new byte[]{1, 2, 3});

        doAnswer(invocation -> {
            Path thumbsDir = tempDir.resolve("thumbs/" + COMIC_ID);
            Files.createDirectories(thumbsDir);
            Files.write(thumbsDir.resolve("1 (2).webp"), new byte[]{0x52, 0x49, 0x46, 0x46});
            return new ExternalProcessRunner.ExternalProcessResult(0, "{\"processed\":1}");
        }).when(processRunner).run(any(ProcessBuilder.class), anyLong(), anyString());

        generator.generateCover(COMIC_ID, source);

        assertThat(Files.readAllBytes(tempDir.resolve("thumbs/" + COMIC_ID + "/cover.webp")))
                .as("有效产物应按源文件名主干 move 为 cover.webp")
                .containsExactly(0x52, 0x49, 0x46, 0x46);
    }

    private Path writeSource(String name, byte[] content) throws Exception {
        Path source = tempDir.resolve("src").resolve(name);
        Files.createDirectories(source.getParent());
        Files.write(source, content);
        return source;
    }
}
