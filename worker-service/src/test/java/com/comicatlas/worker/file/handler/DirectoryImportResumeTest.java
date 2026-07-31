package com.comicatlas.worker.file.handler;

import com.comicatlas.worker.event.CancelHandler;
import com.comicatlas.worker.file.manifest.ImportManifest;
import com.comicatlas.worker.file.manifest.ImportManifestManager;
import com.comicatlas.worker.file.parse.ComicMetadata;
import com.comicatlas.worker.file.parse.DirectoryTree;
import com.comicatlas.worker.file.parse.ImportContext;
import com.comicatlas.worker.file.storage.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

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
                mock(com.comicatlas.worker.file.parse.DirectoryParser.class),
                mock(com.comicatlas.worker.file.parse.MetadataAssembler.class),
                transferService,
                objectMapper,
                mock(com.comicatlas.worker.image.ImageOptimizer.class),
                cancelHandler,
                mock(com.comicatlas.worker.file.transcode.VideoNormalizer.class),
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
        // HQ 落位
        assertTrue(Files.exists(mangaRoot.resolve("hq/10/1/001.jpg")), "HQ 应有 001");
        assertTrue(Files.exists(mangaRoot.resolve("hq/10/1/002.jpg")), "HQ 应有 002");
        // metadata 完整
        JsonNode meta = objectMapper.readTree(mangaRoot.resolve("metadata/100.json").toFile());
        assertEquals(3, meta.path("version").asInt());
        assertEquals(2, meta.path("chapters").get(0).path("mediaItems").size());
        // 恢复点清理
        assertFalse(manifestManager.exists(mangaRoot, 100L), "成功后恢复点应删除");
    }

    @Test
    void resumeImport_skipsAlreadyMovedFiles() throws Exception {
        Files.writeString(sourceRoot.resolve("vol1/ch1/001.jpg"), "content-1");
        Files.writeString(sourceRoot.resolve("vol1/ch1/002.jpg"), "content-2");
        stubParseAndAssemble();

        // 模拟中断态：001 已搬入 HQ 且文件大小匹配，002 仍在源目录，清单保留
        // 先把 001 搬入 HQ 再手工写清单（模拟"中断时已搬部分"）
        Path hqDir = mangaRoot.resolve("hq/10/1");
        Files.createDirectories(hqDir);
        Files.move(sourceRoot.resolve("vol1/ch1/001.jpg"), hqDir.resolve("001.jpg"));
        manifestManager.write(mangaRoot, 100L, rebuiltManifestWithOneMoved());

        handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), 100L, 10L, mangaRoot);

        // 001 未被重复搬（跳过），002 被续搬
        assertTrue(Files.exists(hqDir.resolve("001.jpg")), "001 应保留在 HQ");
        assertTrue(Files.exists(hqDir.resolve("002.jpg")), "002 应被续搬");
        assertFalse(Files.exists(sourceRoot.resolve("vol1/ch1/002.jpg")), "源 002 应被搬走");
        // metadata 仍是完整 2 页
        JsonNode meta = objectMapper.readTree(mangaRoot.resolve("metadata/100.json").toFile());
        assertEquals(2, meta.path("chapters").get(0).path("mediaItems").size());
        // 成功则清理恢复点
        assertFalse(manifestManager.exists(mangaRoot, 100L), "续搬完成后恢复点应清理");
    }

    // ---- helpers ----

    private void stubParseAndAssemble() throws Exception {
        com.comicatlas.worker.file.parse.DirectoryParser parser =
                mock(com.comicatlas.worker.file.parse.DirectoryParser.class);
        com.comicatlas.worker.file.parse.MetadataAssembler assembler =
                mock(com.comicatlas.worker.file.parse.MetadataAssembler.class);
        when(parser.parse(any(Path.class))).thenReturn(
                new DirectoryTree(sourceRoot, "src", List.of(), List.of()));
        when(assembler.assemble(any(DirectoryTree.class), any(ImportContext.class)))
                .thenReturn(sampleMetadata());
        // 重新装配 handler（@RequiredArgsConstructor 无 setter，用新实例）
        com.comicatlas.worker.image.ImageOptimizer imgOpt = mock(com.comicatlas.worker.image.ImageOptimizer.class);
        com.comicatlas.worker.file.transcode.VideoNormalizer vn = mock(com.comicatlas.worker.file.transcode.VideoNormalizer.class);
        when(vn.normalize(any(Path.class))).thenReturn(0);
        handler = new DirectoryImportHandler(parser, assembler, transferService, objectMapper,
                imgOpt, cancelHandler, vn, manifestManager);
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
        JsonNode metadata = objectMapper.readTree("""
            {
              "version": 3,
              "comic": {"title": "测试漫画", "author": "", "tags": []},
              "catalogs": [],
              "chapters": [
                {"title": "第01话", "chapterNo": "1", "sortOrder": 1, "globalOrder": 1,
                 "catalogIndex": -1, "sourceDir": "vol1/ch1",
                 "mediaItems": [
                   {"fileName": "001.jpg", "pageNumber": 1, "hqStatus": "PENDING",
                    "lqStatus": "NOT_GENERATED", "fileSize": 9, "width": 800, "height": 1200, "mediaType": "IMAGE"},
                   {"fileName": "002.jpg", "pageNumber": 2, "hqStatus": "PENDING",
                    "lqStatus": "NOT_GENERATED", "fileSize": 9, "width": 800, "height": 1200, "mediaType": "IMAGE"}
                 ]}
              ]
            }
            """);
        return new ImportManifest(1, 100L, "DIRECTORY", sourceRoot.toString(), metadata,
                List.of(
                    new ImportManifest.ImportFile("vol1/ch1/001.jpg", "10/1/001.jpg", 9),
                    new ImportManifest.ImportFile("vol1/ch1/002.jpg", "10/1/002.jpg", 9)
                ));
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }
}
