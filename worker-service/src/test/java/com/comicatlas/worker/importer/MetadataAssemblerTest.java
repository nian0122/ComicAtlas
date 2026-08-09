package com.comicatlas.worker.importer;

import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MetadataAssembler 单元测试。
 * <p>
 * 重点验证嵌套子目录结构：漫画根下章节目录含多个子目录（各含媒体文件）时，
 * 每个子目录都应生成为独立 Chapter，不得丢失（回归：杏子 NO.017 的
 * "赛博女仆装"子目录 41 张曾因旧逻辑丢失）。
 */
class MetadataAssemblerTest {

    @TempDir
    Path tempDir;

    private final MediaAnalyzer mediaAnalyzer = mock(MediaAnalyzer.class);

    private void stubAnalyze() throws Exception {
        when(mediaAnalyzer.analyze(any(Path.class))).thenAnswer(inv -> {
            Path file = inv.getArgument(0);
            return new ComicMetadata.MediaInfo(
                    file.getFileName().toString(), 1, "PENDING", "NOT_GENERATED",
                    100L, 800, 1200, "IMAGE", null, null, null, null);
        });
    }

    @Test
    void nestedSubdirs_becomeSeparateChapters() throws Exception {
        // 结构: root/章节A/图片/{1..120}.jpg + root/章节A/赛博女仆装/{1..41}.jpg
        stubAnalyze();
        Path chapterDir = Files.createDirectories(tempDir.resolve("章节A"));
        Path imgDir = Files.createDirectories(chapterDir.resolve("图片"));
        Path cosplayDir = Files.createDirectories(chapterDir.resolve("赛博女仆装"));
        for (int i = 1; i <= 120; i++) {
            Files.writeString(imgDir.resolve(String.format("%04d.jpg", i)), "img" + i);
        }
        for (int i = 1; i <= 41; i++) {
            Files.writeString(cosplayDir.resolve(String.format("%04d.jpg", i)), "cos" + i);
        }

        DirectoryTree tree = new DirectoryParser().parse(tempDir);
        ComicMetadata metadata = new MetadataAssembler(mediaAnalyzer).assemble(tree,
                new ImportContext("DIRECTORY", tempDir, false, false));

        // 两个子目录都应成为 Chapter：图片(120) + 赛博女仆装(41)
        assertEquals(2, metadata.chapters().size(),
                "嵌套子目录应各生成一个 Chapter，不得丢失");
        int totalPages = metadata.chapters().stream()
                .mapToInt(c -> c.pages().size()).sum();
        assertEquals(161, totalPages, "两个子目录页数总和应为 161");
    }

    @Test
    void directMediaFiles_becomeChapter() throws Exception {
        // 结构: root/章节B/{1..10}.jpg（无子目录）
        stubAnalyze();
        Path chapterDir = Files.createDirectories(tempDir.resolve("章节B"));
        for (int i = 1; i <= 10; i++) {
            Files.writeString(chapterDir.resolve(String.format("%03d.jpg", i)), "img" + i);
        }

        DirectoryTree tree = new DirectoryParser().parse(tempDir);
        ComicMetadata metadata = new MetadataAssembler(mediaAnalyzer).assemble(tree,
                new ImportContext("DIRECTORY", tempDir, false, false));

        assertEquals(1, metadata.chapters().size());
        assertEquals(10, metadata.chapters().get(0).pages().size());
        assertTrue(metadata.chapters().get(0).sourceDir().contains("章节B"));
    }
}
