package com.comicatlas.worker.file.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImportManifestManager 单元测试。
 * 验证原子写、读写往返、版本校验、损坏清单报错、删除。
 */
class ImportManifestManagerTest {

    private ImportManifestManager manager;
    private ObjectMapper objectMapper;
    private Path mangaRoot;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        manager = new ImportManifestManager(objectMapper);
        mangaRoot = Files.createTempDirectory("imm-test-");
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteRecursively(mangaRoot);
    }

    @Test
    void writeThenRead_roundTrips() throws Exception {
        ImportManifest original = sampleManifest();

        manager.write(mangaRoot, 42L, original);
        ImportManifest loaded = manager.read(mangaRoot, 42L);

        assertEquals(1, loaded.version());
        assertEquals(42L, loaded.taskId());
        assertEquals("DIRECTORY", loaded.sourceType());
        assertEquals("D:/download/naruto", loaded.sourceRoot());
        assertEquals("第01话", loaded.metadata().path("chapters").get(0).path("title").asText());
        assertEquals(2, loaded.files().size());
        assertEquals("vol1/ch1/001.jpg", loaded.files().get(0).source());
        assertEquals("10/20/001.jpg", loaded.files().get(0).target());
        assertEquals(123456L, loaded.files().get(0).size());
    }

    @Test
    void exists_returnsTrueAfterWrite_falseBefore() throws Exception {
        assertFalse(manager.exists(mangaRoot, 1L));
        manager.write(mangaRoot, 1L, sampleManifest());
        assertTrue(manager.exists(mangaRoot, 1L));
    }

    @Test
    void read_corruptJson_throws() throws Exception {
        Path path = manager.manifestPath(mangaRoot, 7L);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{ not valid json !!!");

        assertThrows(IOException.class, () -> manager.read(mangaRoot, 7L));
    }

    @Test
    void read_wrongVersion_throws() throws Exception {
        Path path = manager.manifestPath(mangaRoot, 8L);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"version\":99}");

        assertThrows(IOException.class, () -> manager.read(mangaRoot, 8L));
    }

    @Test
    void delete_removesDirectory() throws Exception {
        manager.write(mangaRoot, 9L, sampleManifest());
        assertTrue(manager.exists(mangaRoot, 9L));

        manager.delete(mangaRoot, 9L);

        assertFalse(manager.exists(mangaRoot, 9L));
        assertFalse(Files.exists(manager.manifestPath(mangaRoot, 9L).getParent()));
    }

    private ImportManifest sampleManifest() throws Exception {
        JsonNode metadata = objectMapper.readTree("""
            {
              "version": 3,
              "comic": {"title": "火影忍者", "author": "", "tags": []},
              "catalogs": [],
              "chapters": [
                {"title": "第01话", "chapterNo": "1", "sortOrder": 1, "globalOrder": 1,
                 "catalogIndex": -1, "sourceDir": "vol1/ch1",
                 "mediaItems": [
                   {"fileName": "001.jpg", "pageNumber": 1, "hqStatus": "PENDING",
                    "lqStatus": "NOT_GENERATED", "fileSize": 123456,
                    "width": 800, "height": 1200, "mediaType": "IMAGE"}
                 ]}
              ]
            }
            """);
        return new ImportManifest(1, 42L, "DIRECTORY", "D:/download/naruto",
                metadata,
                List.of(
                    new ImportManifest.ImportFile("vol1/ch1/001.jpg", "10/20/001.jpg", 123456),
                    new ImportManifest.ImportFile("vol1/ch1/002.jpg", "10/20/002.jpg", 654321)
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
