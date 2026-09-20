package com.comicatlas.ai.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystemException;
import java.util.Comparator;
import java.util.List;
import com.comicatlas.ai.config.AiProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 验证漫画目录边界和抽样页码，不依赖数据库或模型。 */
class DirectorySamplerTest {
    private Path temporaryRoot;

    @AfterEach
    void cleanup() throws Exception {
        if (temporaryRoot != null) {
            try (var paths = Files.walk(temporaryRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
        }
    }

    @Test
    void samplesAcrossWholeDirectoryInStableOrder() throws Exception {
        temporaryRoot = Files.createTempDirectory("comic-ai-sampler");
        Path comic = Files.createDirectories(temporaryRoot.resolve("comic"));
        for (int page = 1; page <= 20; page++) {
            Files.writeString(comic.resolve(String.format("%03d.jpg", page)), "page");
        }
        Files.writeString(comic.resolve("ignore.txt"), "ignore");
        DirectorySampler sampler = new DirectorySampler(new AiProperties(temporaryRoot, temporaryRoot, 5, 1,
                new AiProperties.Model("key", "http://localhost/v1", "model", 10)));
        List<SamplePage> pages = sampler.sample("comic");
        assertEquals(List.of(1, 6, 11, 15, 20), pages.stream().map(SamplePage::pageNumber).toList());
    }

    @Test
    void rejectsTraversalAndSymlinkEscape() throws Exception {
        temporaryRoot = Files.createTempDirectory("comic-ai-sampler");
        Path outside = Files.createTempDirectory("comic-ai-outside");
        try {
            Files.writeString(outside.resolve("001.jpg"), "page");
            DirectorySampler sampler = new DirectorySampler(new AiProperties(temporaryRoot, temporaryRoot, 5, 1,
                    new AiProperties.Model("key", "http://localhost/v1", "model", 10)));
            assertThrows(IllegalArgumentException.class, () -> sampler.sample("../" + outside.getFileName()));
            try {
                Files.createSymbolicLink(temporaryRoot.resolve("escape"), outside);
                assertThrows(IllegalArgumentException.class, () -> sampler.sample("escape"));
            } catch (FileSystemException exception) {
                // Windows 测试环境未授予创建符号链接权限时，仍验证路径穿越边界。
            }
        } finally {
            try (var paths = Files.walk(outside)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
        }
    }
}
