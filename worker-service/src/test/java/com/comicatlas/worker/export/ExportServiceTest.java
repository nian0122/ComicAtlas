package com.comicatlas.worker.export;

import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportComic;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.file.storage.StorageProperties;
import com.comicatlas.worker.file.storage.StorageRef;
import com.comicatlas.worker.file.storage.StorageRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExportServiceTest {

    @TempDir
    Path tempDir;

    private ExportCollector exportCollector;
    private ExportFileResolver exportFileResolver;
    private ZipBuilder zipBuilder;
    private MetadataJsonExporter metadataJsonExporter;
    private StorageProperties storageProperties;
    private ExportService service;

    @BeforeEach
    void setUp() throws IOException {
        exportCollector = mock(ExportCollector.class);
        exportFileResolver = mock(ExportFileResolver.class);
        zipBuilder = mock(ZipBuilder.class);
        metadataJsonExporter = mock(MetadataJsonExporter.class);

        storageProperties = new StorageProperties();
        StorageRoot exportRoot = new StorageRoot();
        exportRoot.setPath(tempDir.resolve("export"));
        Files.createDirectories(exportRoot.getPath());
        storageProperties.setRoots(Map.of("EXPORT", exportRoot));

        service = new ExportService(exportCollector, exportFileResolver, zipBuilder,
                metadataJsonExporter, storageProperties);
    }

    private ExportComic comic(Long id, String title) {
        ExportComic c = new ExportComic();
        c.setId(id);
        c.setTitle(title);
        return c;
    }

    private ExportChapter chapter(Long id, String title, int globalOrder) {
        ExportChapter ch = new ExportChapter();
        ch.setId(id);
        ch.setTitle(title);
        ch.setGlobalOrder(globalOrder);
        return ch;
    }

    private ExportMedia media(Long id, Long chapterId, String hqPath, Integer pageNumber) {
        ExportMedia m = new ExportMedia();
        m.setId(id);
        m.setChapterId(chapterId);
        m.setHqPath(hqPath);
        m.setMediaType("IMAGE");
        m.setPageNumber(pageNumber);
        return m;
    }

    @Test
    void export_buildsManifestAndZip() throws Exception {
        ExportMedia m1 = media(1L, 10L, "1/10/001.jpg", 1);
        ExportMedia m2 = media(2L, 10L, "1/10/002.jpg", 2);
        ExportCollectResult result = new ExportCollectResult(
                comic(1L, "测试标题"),
                List.of(chapter(10L, "第一章", 1)),
                List.of(), List.of(m1, m2), null);

        when(exportCollector.collect(1L)).thenReturn(result);
        when(metadataJsonExporter.exportJson(1L)).thenReturn("{}");
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        when(exportFileResolver.resolve(m2)).thenReturn(new StorageRef("HQ", "1/10/002.jpg"));
        Path src1 = tempDir.resolve("hq/1/10/001.jpg");
        Path src2 = tempDir.resolve("hq/1/10/002.jpg");
        Files.createDirectories(src1.getParent());
        Files.writeString(src1, "a");
        Files.writeString(src2, "b");
        when(exportFileResolver.resolveToPath(any(StorageRef.class))).thenAnswer(inv ->
                tempDir.resolve("hq").resolve(inv.<StorageRef>getArgument(0).relativePath()));
        when(zipBuilder.build(any(), any())).thenReturn(1234L);

        ExportService.ExportOutput output = service.export(1L, 99L);

        assertEquals(99L, output.taskId());
        assertEquals(1234L, output.size());
        assertTrue(output.fileName().startsWith("测试标题_1_"), "输出文件名应含清理后标题+comicId");
        assertTrue(output.fileName().endsWith(".zip"));
        ArgumentCaptor<ExportManifest> manifestCaptor = ArgumentCaptor.forClass(ExportManifest.class);
        verify(zipBuilder).build(manifestCaptor.capture(), any());
        assertEquals(2, manifestCaptor.getValue().entries().size());
        assertEquals("第一章/001.jpg", manifestCaptor.getValue().entries().get(0).targetPath());
    }

    @Test
    void export_skipsMissingFilesAndDeduplicatesChapterDirs() throws Exception {
        ExportMedia m1 = media(1L, 10L, "1/10/001.jpg", 1);
        ExportMedia m2 = media(2L, 11L, "1/11/001.jpg", 1);
        ExportCollectResult result = new ExportCollectResult(
                comic(1L, "标题"),
                List.of(chapter(10L, "同名章", 1), chapter(11L, "同名章", 2)),
                List.of(), List.of(m1, m2), null);

        when(exportCollector.collect(1L)).thenReturn(result);
        when(metadataJsonExporter.exportJson(1L)).thenReturn("{}");
        // m1 源文件缺失（跳过）；m2 源存在
        when(exportFileResolver.resolve(m1)).thenReturn(new StorageRef("HQ", "1/10/001.jpg"));
        when(exportFileResolver.resolve(m2)).thenReturn(new StorageRef("HQ", "1/11/001.jpg"));
        Path src2 = tempDir.resolve("hq/1/11/001.jpg");
        Files.createDirectories(src2.getParent());
        Files.writeString(src2, "b");
        when(exportFileResolver.resolveToPath(any(StorageRef.class))).thenAnswer(inv ->
                tempDir.resolve("hq").resolve(inv.<StorageRef>getArgument(0).relativePath()));
        when(zipBuilder.build(any(), any())).thenReturn(10L);

        service.export(1L, 99L);

        ArgumentCaptor<ExportManifest> manifestCaptor = ArgumentCaptor.forClass(ExportManifest.class);
        verify(zipBuilder).build(manifestCaptor.capture(), any());
        assertEquals(1, manifestCaptor.getValue().entries().size(), "缺失文件应被跳过");
        assertEquals("同名章(1)/001.jpg", manifestCaptor.getValue().entries().get(0).targetPath(),
                "同名章节目录应去重为 同名章(1)");
    }

    @Test
    void classifyExportError_knownTypes() {
        assertEquals("ZIP_ERROR", service.classifyExportError(new RuntimeException("zip 损坏")));
        assertEquals("STORAGE_ERROR", service.classifyExportError(new RuntimeException("EXPORT 根目录")));
        assertEquals("EXPORT_ERROR", service.classifyExportError(new RuntimeException("其他")));
    }
}
