package com.comicatlas.worker.importer;

import com.comicatlas.worker.event.CancelHandler;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.storage.SafeMoveStrategy;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.TransferService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DirectoryImportHandler 清单驱动搬运集成测试。
 * mock parser/assembler/视频标准化/封面生成/取消，真实文件系统 + 真实 TransferService + 真实 ImportManifestManager。
 */
class DirectoryImportResumeTest {

    private ObjectMapper objectMapper;
    private ImportManifestManager manifestManager;
    private TransferService transferService;
    private Path mangaRoot;
    private Path sourceRoot;
    private DirectoryImportHandler handler;
    private CancelHandler cancelHandler;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        mangaRoot = Files.createTempDirectory("dir-test-");
        sourceRoot = Files.createDirectories(mangaRoot.resolve("src"));
        Files.createDirectories(sourceRoot.resolve("vol1/ch1"));

        StorageProperties props = new StorageProperties();
        props.setRoots(java.util.Map.of("HQ", new StorageRoot() {{
            setPath(mangaRoot.resolve("hq"));
            setEnabled(true);
        }}));
        manifestManager = new ImportManifestManager(objectMapper);
        transferService = new TransferService(props, new SafeMoveStrategy());
        cancelHandler = mock(CancelHandler.class);
        when(cancelHandler.isCancelled(anyLong())).thenReturn(false);

        handler = new DirectoryImportHandler(
                mock(DirectoryParser.class),
                mock(MetadataAssembler.class),
                transferService,
                objectMapper,
                mock(com.comicatlas.worker.image.CoverGenerator.class),
                new CoverCandidateSelector(),
                cancelHandler,
                manifestManager);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteRecursively(mangaRoot);
    }

    @Test
    void freshImport_movesAllFilesAndWritesMetadata() throws Exception {
        Files.writeString(sourceRoot.resolve("vol1/ch1/001.jpg"), "content-1");
        Files.writeString(sourceRoot.resolve("vol1/ch1/002.jpg"), "content-2");
        stubParseAndAssemble();

        handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), 100L, 10L, mangaRoot);

        // 源被搬空
        assertFalse(Files.exists(sourceRoot.resolve("vol1/ch1/001.jpg")), "源 001 应被搬走");
        assertFalse(Files.exists(sourceRoot.resolve("vol1/ch1/002.jpg")), "源 002 应被搬走");
        // HQ 落位 — 新布局保留原始文件名
        Path hqChapterDir = mangaRoot.resolve("hq/10/1");
        assertTrue(Files.exists(hqChapterDir), "HQ 章节目录应存在");
        List<Path> hqFiles;
        try (var stream = Files.list(hqChapterDir)) {
            hqFiles = stream.filter(Files::isRegularFile).toList();
        }
        assertEquals(2, hqFiles.size(), "HQ 应有 2 个文件");
        // 文件名应保留原始文件名（禁止 UUID 化）
        var hqNames = hqFiles.stream().map(f -> f.getFileName().toString()).sorted().toList();
        assertEquals(List.of("001.jpg", "002.jpg"), hqNames, "HQ 文件名应保留原始文件名");
        // metadata 完整 — 包含 hqPath
        JsonNode meta = objectMapper.readTree(mangaRoot.resolve("metadata/100.json").toFile());
        assertEquals(3, meta.path("version").asInt());
        JsonNode items = meta.path("chapters").get(0).path("mediaItems");
        assertEquals(2, items.size());
        assertTrue(items.get(0).has("hqPath"), "mediaItems 应包含 hqPath");
        // 恢复点（清单）须保留到最终化阶段：文件已按 {comicId}/{globalOrder} 暂存，
        // Worker 逐章搬运到 {comicId}/{chapterId} 时仍需清单做尺寸校验，
        // 清单由 ImportStorageFinalizeHandler 全部章节完成后清空删除。
        assertTrue(manifestManager.exists(mangaRoot, 100L), "handle 成功后恢复点应保留（供最终化阶段使用）");
    }

    @Test
    void resumeImport_skipsAlreadyMovedFiles() throws Exception {
        Files.writeString(sourceRoot.resolve("vol1/ch1/001.jpg"), "content-1");
        Files.writeString(sourceRoot.resolve("vol1/ch1/002.jpg"), "content-2");
        stubParseAndAssemble();

        // 先获取 handler.buildManifestFiles 会生成的文件名（通过实际构建清单）
        Path importRoot = sourceRoot;
        ComicMetadata metadata = new ComicMetadata("test", "author", "",
                List.of(), List.of(),
                List.of(new ComicMetadata.ChapterInfo(
                        "第01话", "1", 1, 1, -1, "vol1/ch1",
                        List.of(
                            media("001.jpg", 1),
                            media("002.jpg", 2)
                        ))));
        // 使用反射或直接调用构建方法获取生成的文件路径
        // 这里通过第一次执行 handler 并在删除前捕获清单
        handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), 200L, 20L, mangaRoot);
        // 从 HQ 目录获取生成的文件名
        Path hqTempDir = mangaRoot.resolve("hq/20/1");
        List<Path> genFiles;
        try (var stream = Files.list(hqTempDir)) {
            genFiles = stream.sorted().toList();
        }
        String genName1 = genFiles.get(0).getFileName().toString();
        String genName2 = genFiles.get(1).getFileName().toString();
        // 使用 comicId=10 的路径（与第二次导入匹配）
        String generatedName1 = "10/1/" + genName1;
        String generatedName2 = "10/1/" + genName2;
        long size1 = Files.size(genFiles.get(0));
        long size2 = Files.size(genFiles.get(1));
        // 清理第一次导入
        deleteRecursively(mangaRoot.resolve("hq/20"));
        deleteRecursively(mangaRoot.resolve("imports/200"));
        Files.deleteIfExists(mangaRoot.resolve("metadata/200.json"));

        // 重新创建源文件
        Files.writeString(sourceRoot.resolve("vol1/ch1/001.jpg"), "content-1");
        Files.writeString(sourceRoot.resolve("vol1/ch1/002.jpg"), "content-2");

        // 模拟中断态：001 已搬入 HQ 且文件大小匹配，002 仍在源目录
        Path hqDir = mangaRoot.resolve("hq/10/1");
        Files.createDirectories(hqDir);
        Path file1 = hqDir.resolve(genFiles.get(0).getFileName().toString());
        Files.move(sourceRoot.resolve("vol1/ch1/001.jpg"), file1);
        manifestManager.write(mangaRoot, 100L,
                rebuiltManifestWithGeneratedNames(generatedName1, generatedName2, size1, size2));

        handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), 100L, 10L, mangaRoot);

        // 001 未被重复搬（跳过），002 被续搬
        assertTrue(Files.exists(file1), "001 应保留在 HQ");
        Path file2 = hqDir.resolve(genFiles.get(1).getFileName().toString());
        assertTrue(Files.exists(file2), "002 应被续搬: " + file2);
        assertFalse(Files.exists(sourceRoot.resolve("vol1/ch1/002.jpg")), "源 002 应被搬走");
        JsonNode meta = objectMapper.readTree(mangaRoot.resolve("metadata/100.json").toFile());
        assertEquals(2, meta.path("chapters").get(0).path("mediaItems").size());
        assertTrue(manifestManager.exists(mangaRoot, 100L), "续搬完成后恢复点应保留（最终化阶段仍需清单）");
    }

    @Test
    void resumeImport_targetSizeMismatch_throws() throws Exception {
        Files.writeString(sourceRoot.resolve("vol1/ch1/001.jpg"), "content-1");
        Files.writeString(sourceRoot.resolve("vol1/ch1/002.jpg"), "content-2");
        stubParseAndAssemble();

        // 预置：目标文件存在但大小不符（污染）
        Path hqDir = mangaRoot.resolve("hq/10/1");
        Files.createDirectories(hqDir);
        // 使用 rebuiltManifestWithOneMoved 中的硬编码路径保持一致
        Files.writeString(hqDir.resolve("001.jpg"), "corrupted!!!");
        manifestManager.write(mangaRoot, 100L, rebuiltManifestWithOneMoved());

        IOException ex = assertThrows(IOException.class,
                () -> handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), 100L, 10L, mangaRoot));
        assertTrue(ex.getMessage().contains("大小不匹配"), "应报大小不匹配: " + ex.getMessage());
        assertTrue(Files.exists(hqDir.resolve("001.jpg")), "污染目标不应被覆盖");
    }

    @Test
    void resumeImport_sourceMissing_throws() throws Exception {
        Files.writeString(sourceRoot.resolve("vol1/ch1/001.jpg"), "content-1");
        Files.writeString(sourceRoot.resolve("vol1/ch1/002.jpg"), "content-2");
        stubParseAndAssemble();
        // 源 001 被外部删除，目标也不存在
        Files.delete(sourceRoot.resolve("vol1/ch1/001.jpg"));
        manifestManager.write(mangaRoot, 100L, rebuiltManifestWithOneMoved());

        IOException ex = assertThrows(IOException.class,
                () -> handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), 100L, 10L, mangaRoot));
        assertTrue(ex.getMessage().contains("源文件缺失"), "应报源文件缺失: " + ex.getMessage());
    }

    @Test
    void interruptedThenRetry_resumesFromCheckpoint() throws Exception {
        Files.writeString(sourceRoot.resolve("vol1/ch1/001.jpg"), "content-1");
        Files.writeString(sourceRoot.resolve("vol1/ch1/002.jpg"), "content-2");
        stubParseAndAssemble();

        // 第一次执行：搬到第 2 个文件前"取消"（isCancelled 第 3 次调用返回 true）
        AtomicInteger calls = new AtomicInteger();
        when(cancelHandler.isCancelled(anyLong())).thenAnswer(inv -> calls.incrementAndGet() >= 3);
        assertThrows(RuntimeException.class,
                () -> handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), 100L, 10L, mangaRoot));
        assertTrue(manifestManager.exists(mangaRoot, 100L), "中断后恢复点应保留");

        // 第二次执行：取消标记被 API 删除 → isCancelled 恒 false → 续搬
        when(cancelHandler.isCancelled(anyLong())).thenReturn(false);
        handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), 100L, 10L, mangaRoot);

        // 新布局使用 UUID 文件名，不检查硬编码文件名
        Path hqChapterDir = mangaRoot.resolve("hq/10/1");
        assertTrue(Files.exists(hqChapterDir), "HQ 章节目录应存在");
        List<Path> hqFiles;
        try (var stream = Files.list(hqChapterDir)) {
            hqFiles = stream.filter(Files::isRegularFile).toList();
        }
        assertEquals(2, hqFiles.size(), "续搬后应有 2 个文件");
        JsonNode meta = objectMapper.readTree(mangaRoot.resolve("metadata/100.json").toFile());
        assertEquals(2, meta.path("chapters").get(0).path("mediaItems").size());
        assertTrue(manifestManager.exists(mangaRoot, 100L), "中断重试续搬完成后恢复点应保留（最终化阶段仍需清单）");
    }

    // ---- helpers ----

    private void stubParseAndAssemble() throws Exception {
        DirectoryParser parser = mock(DirectoryParser.class);
        MetadataAssembler assembler = mock(MetadataAssembler.class);
        when(parser.parse(any(Path.class), any(String.class))).thenReturn(
                new DirectoryTree(sourceRoot, "src", List.of(), List.of()));
        when(assembler.assemble(any(DirectoryTree.class), any(ImportContext.class)))
                .thenReturn(sampleMetadata());
        // 重新装配 handler（@RequiredArgsConstructor 无 setter，用新实例）
        com.comicatlas.worker.image.CoverGenerator coverGen = mock(com.comicatlas.worker.image.CoverGenerator.class);
        handler = new DirectoryImportHandler(parser, assembler, transferService, objectMapper,
                coverGen, new CoverCandidateSelector(), cancelHandler, manifestManager);
    }

    private ComicMetadata sampleMetadata() throws IOException {
        return new ComicMetadata(
                "测试漫画", "", "",
                List.of(),
                List.of(),
                List.of(new ComicMetadata.ChapterInfo(
                        "第01话", "1", 1, 1, -1, "vol1/ch1",
                        List.of(
                            media("001.jpg", 1),
                            media("002.jpg", 2)
                        ))));
    }

    private ComicMetadata.MediaInfo media(String fileName, int pageNumber) throws IOException {
        return new ComicMetadata.MediaInfo(
                fileName, pageNumber, "PENDING", "NOT_GENERATED",
                Files.size(sourceRoot.resolve("vol1/ch1").resolve(fileName)),
                800, 1200, "IMAGE", null, null, null, null);
    }

    private ImportManifest rebuiltManifestWithOneMoved() throws Exception {
        return rebuiltManifestWithGeneratedNames("10/1/001.jpg", "10/1/002.jpg", 9, 9);
    }

    private ImportManifest rebuiltManifestWithGeneratedNames(String target1, String target2) throws Exception {
        return rebuiltManifestWithGeneratedNames(target1, target2,
                Files.exists(sourceRoot.resolve("vol1/ch1/001.jpg"))
                    ? Files.size(sourceRoot.resolve("vol1/ch1/001.jpg")) : 9,
                Files.exists(sourceRoot.resolve("vol1/ch1/002.jpg"))
                    ? Files.size(sourceRoot.resolve("vol1/ch1/002.jpg")) : 9);
    }

    private ImportManifest rebuiltManifestWithGeneratedNames(
            String target1, String target2, long size1, long size2) throws Exception {
        String fileName1 = Path.of(target1).getFileName().toString();
        String fileName2 = Path.of(target2).getFileName().toString();
        String json = String.format("""
            {
              "version": 3,
              "comic": {"title": "测试漫画", "author": "", "tags": []},
              "catalogs": [],
              "chapters": [
                {"title": "第01话", "chapterNo": "1", "sortOrder": 1, "globalOrder": 1,
                 "catalogIndex": -1, "sourceDir": "vol1/ch1",
                 "mediaItems": [
                   {"fileName": "%s", "pageNumber": 1, "hqStatus": "PENDING",
                    "lqStatus": "NOT_GENERATED", "fileSize": %d, "width": 800, "height": 1200, "mediaType": "IMAGE",
                    "hqPath": "%s"},
                   {"fileName": "%s", "pageNumber": 2, "hqStatus": "PENDING",
                    "lqStatus": "NOT_GENERATED", "fileSize": %d, "width": 800, "height": 1200, "mediaType": "IMAGE",
                    "hqPath": "%s"}
                 ]}
              ]
            }
            """, fileName1, size1, target1, fileName2, size2, target2);
        JsonNode metadata = objectMapper.readTree(json);
        return new ImportManifest(1, 100L, "DIRECTORY", sourceRoot.toString(), metadata,
                List.of(
                    new ImportManifest.ImportFile("vol1/ch1/001.jpg", target1, size1),
                    new ImportManifest.ImportFile("vol1/ch1/002.jpg", target2, size2)
                ));
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) { return; }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }
}
