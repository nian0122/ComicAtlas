package com.comicatlas.worker.importer;

import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MetadataAssembler 单元测试。
 * <p>
 * 覆盖无损规范化语义：
 * <ul>
 *   <li>漫画根不创建具名 Catalog：纯媒体根保持单 Chapter；混合根生成 catalogIndex=null
 *       的"本书散页"Chapter 并递归顶层子目录；</li>
 *   <li>嵌套混合节点生成 Catalog，并先生成挂在该 Catalog 下的"本目录散页"Chapter，再递归 children；</li>
 *   <li>空子树不建 Catalog 但返回 EMPTY_DIRECTORY 警告；</li>
 *   <li>globalOrder 按规范化 DFS 连续 1..N，sortOrder 每个父作用域从 0 连续。</li>
 * </ul>
 * 每个夹具都断言：输入受支持媒体总数 = metadata mediaItems 总数、parentIndex/catalogIndex 均有效、
 * globalOrder=1..N、同父 sortOrder 连续。
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

    /** 图片视频混排 stub：.mp4 返回 VIDEO，其余返回 IMAGE。 */
    private void stubAnalyzeWithVideo() throws Exception {
        when(mediaAnalyzer.analyze(any(Path.class))).thenAnswer(inv -> {
            Path file = inv.getArgument(0);
            String name = file.getFileName().toString();
            boolean video = name.toLowerCase().endsWith(".mp4");
            return new ComicMetadata.MediaInfo(
                    name, 1, "PENDING", "NOT_GENERATED",
                    100L, 800, 1200, video ? "VIDEO" : "IMAGE",
                    video ? new BigDecimal("12.500") : null,
                    video ? "mp4" : null, video ? "h264" : null, video ? "aac" : null);
        });
    }

    // ---- 通用不变量断言 ----

    private static long countMedia(DirectoryTree node) {
        long count = node.mediaFiles() == null ? 0 : node.mediaFiles().size();
        for (DirectoryTree child : node.children()) {
            count += countMedia(child);
        }
        return count;
    }

    /**
     * 对每个夹具断言四类不变量：
     * 1) 输入受支持媒体总数 = metadata mediaItems 总数（不得丢媒体、不得重复分析）；
     * 2) parentIndex/catalogIndex 均有效（null 或指向存在的 catalog 索引）；
     * 3) globalOrder = 1..N 连续；
     * 4) 同父作用域 sortOrder 从 0 连续。
     */
    private static void assertInvariants(DirectoryTree tree, ComicMetadata metadata) {
        long inputMedia = countMedia(tree);
        long outputMedia = metadata.chapters().stream().mapToLong(c -> c.pages().size()).sum();
        assertEquals(inputMedia, outputMedia, "输入媒体总数应与 metadata mediaItems 总数一致");

        int catalogCount = metadata.catalogs().size();
        for (ComicMetadata.CatalogInfo catalog : metadata.catalogs()) {
            if (catalog.parentIndex() != null) {
                assertTrue(catalog.parentIndex() >= 0 && catalog.parentIndex() < catalogCount,
                        "catalog parentIndex 越界: " + catalog.parentIndex());
            }
        }
        for (ComicMetadata.ChapterInfo chapter : metadata.chapters()) {
            if (chapter.catalogIndex() != null) {
                assertTrue(chapter.catalogIndex() >= 0 && chapter.catalogIndex() < catalogCount,
                        "chapter catalogIndex 越界: " + chapter.catalogIndex());
            }
        }

        List<Integer> globalOrders = metadata.chapters().stream()
                .map(ComicMetadata.ChapterInfo::globalOrder).sorted().toList();
        assertEquals(metadata.chapters().size(), globalOrders.size(), "globalOrder 数量应与章节数一致");
        for (int i = 0; i < globalOrders.size(); i++) {
            assertEquals(i + 1, globalOrders.get(i), "globalOrder 应为连续 1..N");
        }

        // 同父作用域 sortOrder 连续：catalog 按 parentIndex、chapter 按 catalogIndex 归组（null 记为 -1）
        Map<Integer, List<Integer>> scopeOrders = new HashMap<>();
        for (ComicMetadata.CatalogInfo catalog : metadata.catalogs()) {
            scopeOrders.computeIfAbsent(catalog.parentIndex() == null ? -1 : catalog.parentIndex(),
                            k -> new ArrayList<>())
                    .add(catalog.sortOrder());
        }
        for (ComicMetadata.ChapterInfo chapter : metadata.chapters()) {
            scopeOrders.computeIfAbsent(chapter.catalogIndex() == null ? -1 : chapter.catalogIndex(),
                            k -> new ArrayList<>())
                    .add(chapter.sortOrder());
        }
        for (Map.Entry<Integer, List<Integer>> entry : scopeOrders.entrySet()) {
            List<Integer> sorted = entry.getValue().stream().sorted().toList();
            assertEquals(entry.getValue().size(), sorted.size(), "scope " + entry.getKey() + " sortOrder 数量异常");
            for (int i = 0; i < sorted.size(); i++) {
                assertEquals(i, sorted.get(i), "scope " + entry.getKey() + " sortOrder 应从 0 连续");
            }
        }
    }

    private ComicMetadata assembleTree() {
        DirectoryTree tree = new DirectoryParser().parse(tempDir);
        return new MetadataAssembler(mediaAnalyzer).assemble(tree,
                new ImportContext("DIRECTORY", tempDir, false, false));
    }

    // ---- 场景测试 ----

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

        assertInvariants(tree, metadata);

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

        assertInvariants(tree, metadata);

        assertEquals(1, metadata.chapters().size());
        assertEquals(10, metadata.chapters().get(0).pages().size());
        assertTrue(metadata.chapters().get(0).sourceDir().contains("章节B"));
    }

    @Test
    void rootMixedMediaAndSubdirs_keepsRootLoosePagesAndSubdirs() throws Exception {
        // 结构: root/根散页.jpg + 第一卷/{根内散页.png + 第1话/1.jpg + 第2话/2.jpg + 第10话/10.jpg} + 特典/op.mp4
        stubAnalyzeWithVideo();
        Files.writeString(tempDir.resolve("根散页.jpg"), "root");
        Path vol1 = Files.createDirectories(tempDir.resolve("第一卷"));
        Files.writeString(vol1.resolve("根内散页.png"), "p");
        Path ch1 = Files.createDirectories(vol1.resolve("第1话"));
        Files.writeString(ch1.resolve("1.jpg"), "1");
        Path ch2 = Files.createDirectories(vol1.resolve("第2话"));
        Files.writeString(ch2.resolve("2.jpg"), "2");
        Path ch10 = Files.createDirectories(vol1.resolve("第10话"));
        Files.writeString(ch10.resolve("10.jpg"), "10");
        Path bonus = Files.createDirectories(tempDir.resolve("特典"));
        Files.writeString(bonus.resolve("op.mp4"), "v");

        DirectoryTree tree = new DirectoryParser().parse(tempDir);
        ComicMetadata metadata = new MetadataAssembler(mediaAnalyzer).assemble(tree,
                new ImportContext("DIRECTORY", tempDir, false, false));

        assertInvariants(tree, metadata);

        assertRootMixedFixtureShape(metadata);
    }

    /** MANUAL-QA 夹具树断言：根散页 + 第一卷 Catalog/散页 + 第1/2/10话 + 特典视频。 */
    private static void assertRootMixedFixtureShape(ComicMetadata metadata) {
        assertEquals(6, metadata.chapters().size());

        // 根散页 Chapter：catalogIndex = null，globalOrder = 1（DFS 最先）
        ComicMetadata.ChapterInfo rootChapter = metadata.chapters().stream()
                .filter(c -> c.pages().size() == 1 && "根散页.jpg".equals(c.pages().get(0).fileName()))
                .findFirst().orElseThrow();
        assertNull(rootChapter.catalogIndex(), "根散页 Chapter 应 catalogIndex=null");
        assertEquals(1, rootChapter.globalOrder(), "根散页应为 globalOrder=1");

        // 第一卷 Catalog + 挂在其下的本目录散页 Chapter
        assertEquals(1, metadata.catalogs().size());
        ComicMetadata.CatalogInfo vol1Catalog = metadata.catalogs().get(0);
        assertEquals("第一卷", vol1Catalog.title());
        assertNull(vol1Catalog.parentIndex(), "根级 Catalog 的 parentIndex 应为 null");

        ComicMetadata.ChapterInfo vol1Loose = metadata.chapters().stream()
                .filter(c -> c.pages().size() == 1 && "根内散页.png".equals(c.pages().get(0).fileName()))
                .findFirst().orElseThrow();
        assertEquals(0, (int) vol1Loose.catalogIndex(), "第一卷散页应挂在第一卷 Catalog 下");
        assertEquals("根内散页.png", vol1Loose.pages().get(0).fileName());

        // 第1/2/10话按自然序挂第一卷 Catalog 下，globalOrder 递增
        Map<String, ComicMetadata.ChapterInfo> byTitle = new HashMap<>();
        for (ComicMetadata.ChapterInfo c : metadata.chapters()) {
            byTitle.put(c.title(), c);
        }
        for (String title : List.of("第1话", "第2话", "第10话")) {
            ComicMetadata.ChapterInfo c = byTitle.get(title);
            assertEquals(0, (int) c.catalogIndex(), title + " 应挂第一卷 Catalog 下");
            assertEquals(1, c.pages().size());
        }
        assertEquals("1.jpg", byTitle.get("第1话").pages().get(0).fileName());
        assertTrue(byTitle.get("第1话").globalOrder() < byTitle.get("第2话").globalOrder(),
                "第1话 globalOrder 应小于第2话");
        assertTrue(byTitle.get("第2话").globalOrder() < byTitle.get("第10话").globalOrder(),
                "第2话 globalOrder 应小于第10话");

        // 特典视频：根级 Chapter，媒体类型为 VIDEO
        ComicMetadata.ChapterInfo bonusChapter = byTitle.get("特典");
        assertNull(bonusChapter.catalogIndex(), "特典 Chapter 应为根级");
        assertEquals(1, bonusChapter.pages().size());
        assertEquals("VIDEO", bonusChapter.pages().get(0).mediaType());
        assertEquals("op.mp4", bonusChapter.pages().get(0).fileName());
    }

    @Test
    void threeLevelNestedMixed_createsNestedCatalogsWithOwnPages() throws Exception {
        // 结构: root/root.jpg + 卷/{卷内.jpg + 章/{章内.jpg + 节/{节1.jpg + 节2.jpg}}}
        stubAnalyze();
        Files.writeString(tempDir.resolve("root.jpg"), "r");
        Path vol = Files.createDirectories(tempDir.resolve("卷"));
        Files.writeString(vol.resolve("卷内.jpg"), "v");
        Path chapter = Files.createDirectories(vol.resolve("章"));
        Files.writeString(chapter.resolve("章内.jpg"), "c");
        Path section = Files.createDirectories(chapter.resolve("节"));
        Files.writeString(section.resolve("节1.jpg"), "1");
        Files.writeString(section.resolve("节2.jpg"), "2");

        DirectoryTree tree = new DirectoryParser().parse(tempDir);
        ComicMetadata metadata = new MetadataAssembler(mediaAnalyzer).assemble(tree,
                new ImportContext("DIRECTORY", tempDir, false, false));

        assertInvariants(tree, metadata);

        // catalogs: 卷(0, null) → 章(1, parent 0)；节为纯媒体节点不建 Catalog
        assertEquals(2, metadata.catalogs().size());
        assertEquals("卷", metadata.catalogs().get(0).title());
        assertNull(metadata.catalogs().get(0).parentIndex());
        assertEquals("章", metadata.catalogs().get(1).title());
        assertEquals(0, metadata.catalogs().get(1).parentIndex().intValue());

        // chapters DFS：本书散页(g1, root) → 卷散页(g2, catalog 0) → 章散页(g3, catalog 1) → 节(g4, catalog 1)
        assertEquals(4, metadata.chapters().size());
        assertNull(metadata.chapters().get(0).catalogIndex(), "根散页应为根级");
        assertEquals(0, metadata.chapters().get(1).catalogIndex().intValue(), "卷散页应挂卷 Catalog");
        assertEquals(1, metadata.chapters().get(2).catalogIndex().intValue(), "章散页应挂章 Catalog");
        assertEquals(1, metadata.chapters().get(3).catalogIndex().intValue(), "节应挂章 Catalog");
        assertEquals(2, metadata.chapters().get(3).pages().size(), "节应含节1/节2 两张图");
    }

    @Test
    void sameNamedMediaInDifferentDirs_allPreserved() throws Exception {
        // 结构: root/001.jpg + A/001.jpg + B/001.jpg（同名媒体不得去重）
        stubAnalyze();
        Files.writeString(tempDir.resolve("001.jpg"), "root");
        Path a = Files.createDirectories(tempDir.resolve("A"));
        Path b = Files.createDirectories(tempDir.resolve("B"));
        Files.writeString(a.resolve("001.jpg"), "a");
        Files.writeString(b.resolve("001.jpg"), "b");

        DirectoryTree tree = new DirectoryParser().parse(tempDir);
        ComicMetadata metadata = new MetadataAssembler(mediaAnalyzer).assemble(tree,
                new ImportContext("DIRECTORY", tempDir, false, false));

        assertInvariants(tree, metadata);

        assertEquals(3, metadata.chapters().size(), "根散页 + A + B 应各生成一个 Chapter");
        assertEquals(3, metadata.chapters().stream().mapToLong(c -> c.pages().size()).sum(),
                "同名媒体不得去重，三份 001.jpg 全部保留");
        for (ComicMetadata.ChapterInfo chapter : metadata.chapters()) {
            assertEquals(1, chapter.pages().size());
            assertEquals("001.jpg", chapter.pages().get(0).fileName());
        }
    }

    @Test
    void emptySubdirectory_warnsWithoutCatalogOrChapter() throws Exception {
        // 结构: root/空目录（无媒体无子目录）+ 内容/1.jpg
        stubAnalyze();
        Files.createDirectories(tempDir.resolve("空目录"));
        Path content = Files.createDirectories(tempDir.resolve("内容"));
        Files.writeString(content.resolve("1.jpg"), "1");

        DirectoryTree tree = new DirectoryParser().parse(tempDir);
        AssembleResult result = new MetadataAssembler(mediaAnalyzer)
                .assembleWithWarnings(tree, new ImportContext("DIRECTORY", tempDir, false, false));

        ComicMetadata metadata = result.metadata();
        assertInvariants(tree, metadata);

        assertEquals(0, metadata.catalogs().size(), "空目录不生成 Catalog");
        assertEquals(1, metadata.chapters().size(), "空目录不生成 Chapter，仅内容目录生成 Chapter");
        assertEquals(1, metadata.chapters().get(0).pages().size());

        assertFalse(result.warnings().isEmpty(), "空目录应返回 warning");
        boolean hasEmptyDirWarning = result.warnings().stream().anyMatch(w ->
                AssembleResult.CODE_EMPTY_DIRECTORY.equals(w.code())
                        && "空目录".equals(w.relativePath()));
        assertTrue(hasEmptyDirWarning, "应返回指向空目录的 EMPTY_DIRECTORY 警告");
    }

    @Test
    void imageAndVideoMixedInSameChapter_bothPreserved() throws Exception {
        // 结构: root/混合/1.jpg + 2.mp4（图片视频混排）
        stubAnalyzeWithVideo();
        Path chapterDir = Files.createDirectories(tempDir.resolve("混合"));
        Files.writeString(chapterDir.resolve("1.jpg"), "img");
        Files.writeString(chapterDir.resolve("2.mp4"), "video");

        DirectoryTree tree = new DirectoryParser().parse(tempDir);
        ComicMetadata metadata = new MetadataAssembler(mediaAnalyzer).assemble(tree,
                new ImportContext("DIRECTORY", tempDir, false, false));

        assertInvariants(tree, metadata);

        assertEquals(1, metadata.chapters().size());
        assertEquals(2, metadata.chapters().get(0).pages().size(), "图片与视频同章混排，两张媒体均保留");
        assertEquals("IMAGE", metadata.chapters().get(0).pages().get(0).mediaType());
        assertEquals("VIDEO", metadata.chapters().get(0).pages().get(1).mediaType());
        assertEquals("2.mp4", metadata.chapters().get(0).pages().get(1).fileName());
    }

    /**
     * MANUAL-QA happy 场景：由 PowerShell 创建的真实夹具目录
     * （-Dnormalizer.fixture=... 指向，缺省跳过），断言规范化输出树。
     */
    @Test
    void manualQa_fixtureDirectory_normalizesExpectedTree() throws Exception {
        String fixture = System.getProperty("normalizer.fixture");
        Assumptions.assumeTrue(fixture != null && !fixture.isBlank(),
                "未提供 -Dnormalizer.fixture，跳过 MANUAL-QA 夹具验证");
        stubAnalyzeWithVideo();
        Path fixtureRoot = Path.of(fixture);

        DirectoryTree tree = new DirectoryParser().parse(fixtureRoot);
        ComicMetadata metadata = new MetadataAssembler(mediaAnalyzer).assemble(tree,
                new ImportContext("DIRECTORY", fixtureRoot, false, false));

        assertInvariants(tree, metadata);
        assertRootMixedFixtureShape(metadata);
        dumpMetadataTree(metadata);
    }

    /** MANUAL-QA failure 场景：全空树（无任何媒体）必须明确失败，不得静默产出空元数据。 */
    @Test
    void manualQa_emptyTree_failsClearlyWithoutChapters() throws Exception {
        stubAnalyze();
        Files.createDirectories(tempDir.resolve("空目录1"));
        Files.createDirectories(tempDir.resolve("空目录2"));

        DirectoryTree tree = new DirectoryParser().parse(tempDir);
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                new MetadataAssembler(mediaAnalyzer).assemble(tree,
                        new ImportContext("DIRECTORY", tempDir, false, false)));
        assertTrue(ex.getMessage().contains("无可用章节"), "空树应抛出'无可用章节'错误: " + ex.getMessage());
    }

    private static void dumpMetadataTree(ComicMetadata metadata) {
        System.out.println("=== MANUAL-QA 规范化输出树 ===");
        for (ComicMetadata.CatalogInfo catalog : metadata.catalogs()) {
            System.out.printf("catalog title=%s sortOrder=%d parentIndex=%s%n",
                    catalog.title(), catalog.sortOrder(), catalog.parentIndex());
        }
        for (ComicMetadata.ChapterInfo chapter : metadata.chapters()) {
            System.out.printf("chapter title=%s globalOrder=%d sortOrder=%d catalogIndex=%s pages=%d sourceDir=%s%n",
                    chapter.title(), chapter.globalOrder(), chapter.sortOrder(),
                    chapter.catalogIndex(), chapter.pages().size(), chapter.sourceDir());
            for (ComicMetadata.MediaInfo page : chapter.pages()) {
                System.out.printf("  page=%s pageNumber=%d mediaType=%s%n",
                        page.fileName(), page.pageNumber(), page.mediaType());
            }
        }
    }
}
