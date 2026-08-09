package com.comicatlas.worker.scan;

import com.comicatlas.common.dto.ScanItemDTO;
import com.comicatlas.common.dto.ScanPreviewNodeDTO;
import com.comicatlas.common.dto.ScanResultDTO;
import com.comicatlas.common.dto.ScanWarningCode;
import com.comicatlas.worker.importer.DirectoryParseError;
import com.comicatlas.worker.importer.DirectoryParseException;
import com.comicatlas.worker.importer.DirectoryParser;
import com.comicatlas.worker.importer.DirectoryTree;
import com.comicatlas.worker.importer.ImportContext;
import com.comicatlas.worker.importer.MetadataAssembler;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DirectoryScanPreviews 单元测试（目录扫描规范化预览）。
 * <p>
 * 覆盖契约：
 * <ol>
 *   <li>每个候选返回递归图片/视频/unsupported/总媒体计数与规范化 preview tree；</li>
 *   <li>同一 fixture 的 preview tree 与实际 metadata tree 结构/计数一致（复用同一只读 parser）；</li>
 *   <li>UNREADABLE_DIRECTORY / LIMIT_EXCEEDED 为阻断（importable=false），其余为非阻断；</li>
 *   <li>错误消息脱敏，不含宿主机绝对路径。</li>
 * </ol>
 */
class DirectoryScanPreviewsTest {

    @TempDir
    Path tempDir;

    private DirectoryParser parser;
    private DirectoryScanPreviews previews;

    @BeforeEach
    void setUp() {
        parser = new DirectoryParser();
        previews = new DirectoryScanPreviews(parser);
    }

    // ---- helpers ----

    private static Path dir(Path parent, String name) throws Exception {
        return Files.createDirectories(parent.resolve(name));
    }

    private static void write(Path dir, String fileName) throws Exception {
        Files.writeString(dir.resolve(fileName), fileName);
    }

    private static boolean importable(ScanItemDTO item) {
        return item.warnings().stream().noneMatch(w -> w.code().isBlocking());
    }

    private static boolean hasCode(ScanItemDTO item, ScanWarningCode code) {
        return item.warnings().stream().anyMatch(w -> w.code() == code);
    }

    private static boolean hasCode(ScanPreviewNodeDTO node, ScanWarningCode code) {
        return node.warnings().stream().anyMatch(w -> w.code() == code);
    }

    private static long unsupportedCount(ScanItemDTO item) {
        return item.warnings().stream().filter(w -> w.code() == ScanWarningCode.UNSUPPORTED_FILE).count();
    }

    private static ScanPreviewNodeDTO findNode(ScanPreviewNodeDTO node, String relativePath) {
        if (node.relativePath().equals(relativePath)) {
            return node;
        }
        for (ScanPreviewNodeDTO child : node.children()) {
            ScanPreviewNodeDTO found = findNode(child, relativePath);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 节点直接媒体数 = 递归 fileCount - 各子节点递归 fileCount 之和。 */
    private static int directMedia(ScanPreviewNodeDTO node) {
        int childTotal = node.children().stream().mapToInt(ScanPreviewNodeDTO::fileCount).sum();
        return node.fileCount() - childTotal;
    }

    // ============================================================
    // 1) 计数与警告（真实 parser）
    // ============================================================

    @Test
    void scan_mixedNestedFixture_countsImagesVideosUnsupportedAndTotal() throws Exception {
        // parent/comic1: root.jpg + root.mp4 + note.txt + vol1/1.jpg,2.jpg + vol2/ch1/3.jpg
        // parent/emptyDir: 空目录
        Path comic1 = dir(tempDir, "comic1");
        write(comic1, "root.jpg");
        write(comic1, "root.mp4");
        write(comic1, "note.txt");
        write(dir(comic1, "vol1"), "1.jpg");
        write(comic1.resolve("vol1"), "2.jpg");
        write(dir(dir(comic1, "vol2"), "ch1"), "3.jpg");
        dir(tempDir, "emptyDir");

        ScanResultDTO result = previews.scan(tempDir);

        assertEquals(2, result.total());
        assertEquals(List.of("comic1", "emptyDir"),
                result.items().stream().map(ScanItemDTO::name).toList(), "候选应按自然排序");

        ScanItemDTO comic = result.items().get(0);
        assertEquals(4, comic.imageCount(), "递归图片数应为 4");
        assertTrue(importable(comic), "普通混合目录应可导入");
        assertTrue(hasCode(comic, ScanWarningCode.MIXED_DIRECTORY), "混排目录应有 MIXED_DIRECTORY 警告");
        assertTrue(hasCode(comic, ScanWarningCode.UNSUPPORTED_FILE), "含非媒体文件应有 UNSUPPORTED_FILE 警告");
        assertEquals(1, unsupportedCount(comic), "unsupported 数应为 1（note.txt）");

        ScanPreviewNodeDTO preview = result.preview().get(0);
        assertEquals("comic1", preview.name());
        assertEquals(5, preview.fileCount(), "总媒体数应为 5（图片 4 + 视频 1）");
        assertTrue(hasCode(preview, ScanWarningCode.MIXED_DIRECTORY), "根节点应标记混排");

        ScanItemDTO empty = result.items().get(1);
        assertEquals(0, empty.imageCount());
        assertTrue(hasCode(empty, ScanWarningCode.EMPTY_DIRECTORY), "空目录应有 EMPTY_DIRECTORY 警告");
        assertTrue(importable(empty), "EMPTY_DIRECTORY 非阻断");
    }

    @Test
    void scan_previewTree_matchesMetadataTree_structureAndCounts() throws Exception {
        // 同一 fixture：preview 树（复用 DirectoryParser）应与 MetadataAssembler 的 metadata 树一致
        Path comic1 = dir(tempDir, "comic1");
        write(comic1, "root.jpg");
        write(comic1, "root.mp4");
        write(dir(comic1, "vol1"), "1.jpg");
        write(comic1.resolve("vol1"), "2.jpg");
        write(dir(dir(comic1, "vol2"), "ch1"), "3.jpg");

        ScanResultDTO result = previews.scan(tempDir);
        ScanPreviewNodeDTO preview = result.preview().get(0);

        DirectoryTree tree = parser.parse(comic1, "DIRECTORY");
        MediaAnalyzer analyzer = mock(MediaAnalyzer.class);
        when(analyzer.analyze(any(Path.class))).thenAnswer(inv -> {
            Path file = inv.getArgument(0);
            String name = file.getFileName().toString();
            boolean video = name.toLowerCase().endsWith(".mp4");
            return new ComicMetadata.MediaInfo(name, 1, "PENDING", "NOT_GENERATED",
                    100L, 800, 1200, video ? "VIDEO" : "IMAGE",
                    video ? new BigDecimal("12.500") : null,
                    video ? "mp4" : null, video ? "h264" : null, video ? "aac" : null);
        });
        ComicMetadata metadata = new MetadataAssembler(analyzer)
                .assembleWithWarnings(tree, new ImportContext("DIRECTORY", comic1, false, false))
                .metadata();

        int totalPages = metadata.chapters().stream().mapToInt(c -> c.pages().size()).sum();
        assertEquals(totalPages, preview.fileCount(), "preview 根 fileCount 应等于 metadata 总媒体数");

        for (ComicMetadata.ChapterInfo chapter : metadata.chapters()) {
            String nodeRelPath = chapter.sourceDir().isEmpty() ? "comic1" : "comic1/" + chapter.sourceDir();
            ScanPreviewNodeDTO node = findNode(preview, nodeRelPath);
            assertNotNull(node, "preview 树缺少节点: " + nodeRelPath);
            assertEquals(chapter.pages().size(), directMedia(node),
                    "节点直接媒体数应与对应 chapter 页数一致: " + nodeRelPath);
        }
    }

    // ============================================================
    // 2) 阻断与非阻断 warning
    // ============================================================

    @Test
    void scan_limitExceeded_isBlockingAndImportableFalse() throws Exception {
        DirectoryParser failing = mock(DirectoryParser.class);
        when(failing.parse(any(Path.class), any(String.class)))
                .thenThrow(new DirectoryParseException(DirectoryParseError.MAX_DIRS_EXCEEDED, "目录总数超过上限"));
        DirectoryScanPreviews limited = new DirectoryScanPreviews(failing);

        dir(tempDir, "big");
        ScanResultDTO result = limited.scan(tempDir);

        ScanItemDTO item = result.items().get(0);
        assertEquals(ScanWarningCode.LIMIT_EXCEEDED, item.warnings().get(0).code());
        assertEquals(0, item.imageCount());
        assertFalse(importable(item), "LIMIT_EXCEEDED 应阻断导入");
        assertEquals(1, result.preview().size(), "阻断项仍应返回一条预览根节点");
    }

    @Test
    void scan_unreadable_isBlockingAndImportableFalse() throws Exception {
        DirectoryParser failing = mock(DirectoryParser.class);
        when(failing.parse(any(Path.class), any(String.class)))
                .thenThrow(new DirectoryParseException(DirectoryParseError.UNREADABLE, "目录不可读"));
        DirectoryScanPreviews blocked = new DirectoryScanPreviews(failing);

        dir(tempDir, "locked");
        ScanResultDTO result = blocked.scan(tempDir);

        ScanItemDTO item = result.items().get(0);
        assertEquals(ScanWarningCode.UNREADABLE_DIRECTORY, item.warnings().get(0).code());
        assertFalse(importable(item), "UNREADABLE_DIRECTORY 应阻断导入");
    }

    @Test
    void scan_symlinkRejected_isNonBlockingWarning() throws Exception {
        DirectoryParser failing = mock(DirectoryParser.class);
        when(failing.parse(any(Path.class), any(String.class)))
                .thenThrow(new DirectoryParseException(DirectoryParseError.SYMLINK_REJECTED, "拒绝跟随符号链接"));
        DirectoryScanPreviews linked = new DirectoryScanPreviews(failing);

        dir(tempDir, "linked");
        ScanResultDTO result = linked.scan(tempDir);

        ScanItemDTO item = result.items().get(0);
        assertEquals(ScanWarningCode.SYMLINK_SKIPPED, item.warnings().get(0).code());
        assertTrue(importable(item), "SYMLINK_SKIPPED 非阻断，应可导入");
    }

    @Test
    void scan_emptyCandidate_isNonBlocking() throws Exception {
        dir(tempDir, "empty");
        ScanResultDTO result = previews.scan(tempDir);

        ScanItemDTO item = result.items().get(0);
        assertEquals(ScanWarningCode.EMPTY_DIRECTORY, item.warnings().get(0).code());
        assertTrue(importable(item), "EMPTY_DIRECTORY 非阻断");
    }

    // ============================================================
    // 3) 父目录校验与符号链接跳过
    // ============================================================

    @Test
    void scan_parentInvalid_throwsSanitizedMessage() throws Exception {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> previews.scan(tempDir.resolve("not-exist")));
        assertEquals("父目录不存在", missing.getMessage());
        assertFalse(missing.getMessage().contains(tempDir.toAbsolutePath().toString()),
                "错误消息不得包含完整本地绝对路径");

        Path file = tempDir.resolve("a-file.jpg");
        Files.writeString(file, "x");
        IllegalArgumentException notDir = assertThrows(IllegalArgumentException.class,
                () -> previews.scan(file));
        assertEquals("路径不是目录", notDir.getMessage());

        IllegalArgumentException nullDir = assertThrows(IllegalArgumentException.class,
                () -> previews.scan(null));
        assertEquals("父目录不存在", nullDir.getMessage());
    }

    @Test
    void scan_symlinkEntryInParent_isSkippedWithScanWarning() throws Exception {
        dir(tempDir, "real");
        Path link = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(link, tempDir.resolve("real"));
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "当前环境无法创建符号链接: " + e.getMessage());
        }
        if (!Files.isSymbolicLink(link)) {
            Assumptions.assumeTrue(false, "符号链接创建失败");
        }
        write(dir(tempDir, "comic2"), "001.jpg");

        ScanResultDTO result = previews.scan(tempDir);

        assertTrue(result.warnings().stream().anyMatch(w -> w.code() == ScanWarningCode.SYMLINK_SKIPPED),
                "扫描级应有 SYMLINK_SKIPPED 警告");
        assertTrue(result.items().stream().noneMatch(i -> i.name().equals("link")),
                "符号链接子目录不得作为候选");
        assertEquals(2, result.total(), "候选应为 comic2 与 real 两个真实目录");
    }

    @Test
    void scan_junctionEntryInParent_isSkippedWithScanWarning() throws Exception {
        // Windows junction 不被 Files.isSymbolicLink 识别（reparse point），需 realPath 比对跳过
        Assumptions.assumeTrue(
                System.getProperty("os.name").toLowerCase().contains("win"), "仅 Windows 验证 junction");

        Path real = dir(tempDir, "real");
        write(real, "001.jpg");
        Path link = tempDir.resolve("link");
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toAbsolutePath().toString(), real.toAbsolutePath().toString());
        Process process = pb.start();
        int exit = process.waitFor();
        if (exit != 0 || !Files.exists(link) || !Files.isDirectory(link, LinkOption.NOFOLLOW_LINKS)) {
            Assumptions.assumeTrue(false, "当前环境无法创建 junction");
        }
        write(dir(tempDir, "comic2"), "001.jpg");

        ScanResultDTO result = previews.scan(tempDir);

        assertTrue(result.warnings().stream().anyMatch(w -> w.code() == ScanWarningCode.SYMLINK_SKIPPED),
                "junction 应给出扫描级 SYMLINK_SKIPPED 警告");
        assertTrue(result.items().stream().noneMatch(i -> i.name().equals("link")),
                "junction 子目录不得作为候选");
        assertEquals(2, result.total(), "候选应为 comic2 与 real 两个真实目录");
    }
}
