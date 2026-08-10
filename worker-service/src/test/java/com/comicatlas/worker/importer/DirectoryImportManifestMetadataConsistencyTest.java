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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DirectoryImportHandler 清单与 metadata 文件集一致性测试（生产事故回归）。
 * <p>
 * 事故根因：buildManifestFiles 用「源文件存在且 fileSize > 0」过滤清单，
 * 而 buildMetadataMap 无条件收录全部页面，导致 metadata 含幽灵文件（0 字节空文件/源缺失文件）
 * → API 插入不存在的 DB 记录 → 最终化阶段 STORAGE_FINALIZE_SOURCE_MISSING 整章失败。
 * <p>
 * 本测试断言：manifest.files 与 metadata.mediaItems 必须一一对应——
 * 同一文件要么两处都出现，要么都不出现。
 */
class DirectoryImportManifestMetadataConsistencyTest {

    private static final long TASK_ID = 400L;
    private static final long COMIC_ID = 40L;

    private ObjectMapper objectMapper;
    private ImportManifestManager manifestManager;
    private TransferService transferService;
    private Path mangaRoot;
    private Path sourceRoot;
    private CancelHandler cancelHandler;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        mangaRoot = Files.createTempDirectory("consistency-");
        sourceRoot = Files.createDirectories(mangaRoot.resolve("src"));
        Files.createDirectories(sourceRoot.resolve("vol1/ch1"));

        StorageProperties props = new StorageProperties();
        props.setRoots(Map.of("HQ", new StorageRoot() {{
            setPath(mangaRoot.resolve("hq"));
            setEnabled(true);
        }}));
        manifestManager = new ImportManifestManager(objectMapper);
        transferService = new TransferService(props, new SafeMoveStrategy());
        cancelHandler = mock(CancelHandler.class);
        when(cancelHandler.isCancelled(anyLong())).thenReturn(false);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteRecursively(mangaRoot);
    }

    @Test
    void emptyAndMissingFiles_excludedFromManifestAndMetadata_consistently() throws Exception {
        // 根章节：1 个正常文件 + 1 个 0 字节空文件 + 1 个源缺失文件（fileSize>0 但磁盘不存在）
        Files.writeString(sourceRoot.resolve("001.jpg"), "content");
        Files.write(sourceRoot.resolve("empty.jpg"), new byte[0]);

        ComicMetadata metadata = new ComicMetadata("测试", "", "", List.of(), List.of(),
                List.of(chapter("", 1, List.of(
                        media("001.jpg", 1, 7),
                        media("empty.jpg", 2, 0),
                        media("ghost.jpg", 3, 5)))));

        DirectoryImportHandler handler = newHandler(metadata);
        handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), TASK_ID, COMIC_ID, mangaRoot);

        ImportManifest manifest = manifestManager.read(mangaRoot, TASK_ID);
        JsonNode meta = objectMapper.readTree(mangaRoot.resolve("metadata/" + TASK_ID + ".json").toFile());

        int mediaCount = totalMediaCount(meta);
        assertEquals(manifest.files().size(), mediaCount,
                "manifest.files 与 metadata.mediaItems 必须一一对应（空/缺失文件两处都不出现）");
        assertEquals(1, manifest.files().size(), "仅正常文件应进入清单");
        assertEquals(1, mediaCount, "仅正常文件应进入 metadata.mediaItems");

        // 空文件与幽灵文件都不应出现在媒体项
        assertFalse(containsFile(meta, "empty.jpg"), "0 字节空文件不得出现在 mediaItems");
        assertFalse(containsFile(meta, "ghost.jpg"), "源缺失文件不得出现在 mediaItems");

        // 内容一一对应：唯一媒体项 fileName 与 hqPath 与清单一致
        ImportManifest.ImportFile only = manifest.files().get(0);
        JsonNode onlyItem = firstMediaItem(meta);
        assertEquals("001.jpg", onlyItem.path("fileName").asText());
        assertEquals(only.target(), onlyItem.path("hqPath").asText(), "mediaItems.hqPath 应与清单 target 一致");

        // 搬运结果：空文件不搬走（留在源目录），HQ 仅 1 个文件
        assertTrue(Files.exists(sourceRoot.resolve("empty.jpg")), "空文件不应被搬走");
        assertFalse(Files.exists(sourceRoot.resolve("001.jpg")), "正常文件应被搬走");
        Path hqDir = mangaRoot.resolve("hq/40/1");
        try (var stream = Files.list(hqDir)) {
            assertEquals(1, stream.filter(Files::isRegularFile).count(), "HQ 应仅 1 个文件");
        }
    }

    @Test
    void subdirChapter_emptyFileExcluded_sourcePathCorrect() throws Exception {
        Files.writeString(sourceRoot.resolve("vol1/ch1/001.jpg"), "content");
        Files.write(sourceRoot.resolve("vol1/ch1/empty.jpg"), new byte[0]);

        ComicMetadata metadata = new ComicMetadata("测试", "", "", List.of(), List.of(),
                List.of(chapter("vol1/ch1", 1, List.of(
                        media("001.jpg", 1, 7),
                        media("empty.jpg", 2, 0)))));

        DirectoryImportHandler handler = newHandler(metadata);
        handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), TASK_ID, COMIC_ID, mangaRoot);

        ImportManifest manifest = manifestManager.read(mangaRoot, TASK_ID);
        JsonNode meta = objectMapper.readTree(mangaRoot.resolve("metadata/" + TASK_ID + ".json").toFile());

        assertEquals(1, manifest.files().size(), "子目录仅正常文件应进入清单");
        assertEquals(1, totalMediaCount(meta), "子目录仅正常文件应进入 metadata.mediaItems");
        assertEquals("vol1/ch1/001.jpg", manifest.files().get(0).source(),
                "子目录章节清单 source 应为 sourceDir/fileName");
        assertEquals("vol1/ch1", meta.path("chapters").get(0).path("sourceDir").asText(),
                "子目录章节 sourceDir 应原样保留");
        assertFalse(containsFile(meta, "empty.jpg"), "子目录 0 字节空文件不得出现在 mediaItems");
        assertTrue(Files.exists(sourceRoot.resolve("vol1/ch1/empty.jpg")), "子目录空文件不应被搬走");
        assertFalse(Files.exists(sourceRoot.resolve("vol1/ch1/001.jpg")), "子目录正常文件应被搬走");
    }

    // ---- helpers ----

    private DirectoryImportHandler newHandler(ComicMetadata metadata) throws Exception {
        DirectoryParser parser = mock(DirectoryParser.class);
        when(parser.parse(any(Path.class), any(String.class)))
                .thenReturn(new DirectoryTree(sourceRoot, "src", List.of(), List.of()));
        MetadataAssembler assembler = mock(MetadataAssembler.class);
        when(assembler.assemble(any(DirectoryTree.class), any(ImportContext.class))).thenReturn(metadata);
        return new DirectoryImportHandler(parser, assembler, transferService, objectMapper,
                mock(com.comicatlas.worker.image.CoverGenerator.class), new CoverCandidateSelector(),
                cancelHandler, manifestManager);
    }

    private ComicMetadata.ChapterInfo chapter(String sourceDir, int globalOrder,
                                              List<ComicMetadata.MediaInfo> pages) {
        return new ComicMetadata.ChapterInfo("章节" + globalOrder, String.valueOf(globalOrder),
                globalOrder, globalOrder, -1, sourceDir, pages);
    }

    private ComicMetadata.MediaInfo media(String fileName, int pageNumber, long fileSize) {
        return new ComicMetadata.MediaInfo(
                fileName, pageNumber, "PENDING", "NOT_GENERATED",
                fileSize, 800, 1200, "IMAGE", null, null, null, null);
    }

    private int totalMediaCount(JsonNode metadata) {
        int count = 0;
        for (JsonNode chapter : metadata.path("chapters")) {
            count += chapter.path("mediaItems").size();
        }
        return count;
    }

    private boolean containsFile(JsonNode metadata, String fileName) {
        for (JsonNode chapter : metadata.path("chapters")) {
            for (JsonNode item : chapter.path("mediaItems")) {
                if (fileName.equals(item.path("fileName").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private JsonNode firstMediaItem(JsonNode metadata) {
        return metadata.path("chapters").get(0).path("mediaItems").get(0);
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) { return; }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }
}
