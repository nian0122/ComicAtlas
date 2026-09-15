package com.comicatlas.worker.exporter.archive;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.exporter.model.ExportManifest;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

/** 优化后的压缩、单次读取、内容校验和中断契约。 */
class ExportArchivePipelineTest {
    @Test
    void cleanupFailureIsSuppressedWithoutReplacingOriginalFailure() throws Exception {
        Path staging = Files.createDirectory(workspace.resolve("failed-staging"));
        IOException original = new IOException("原始写入错误");
        IOException cleanup = new IOException("删除失败");
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.deleteIfExists(staging)).thenThrow(cleanup);
            // CALLS_REAL_METHODS 的静态桩建立时会执行一次真实删除，恢复待清理夹具。
            Files.createDirectories(staging);
            ExportStagingCleanup.afterFailure(staging, original);
            assertEquals(1, original.getSuppressed().length);
            assertEquals(cleanup, original.getSuppressed()[0]);
            assertThrows(IOException.class, () -> ExportStagingCleanup.delete(staging));
        }
    }
    private static final int CONTENT_SIZE = 256 * 1024;
    @TempDir
    Path workspace;

    @Test
    void splitCbzRetainsRequestedMainNameAndCanBeVerified() throws Exception {
        Path source = Files.write(workspace.resolve("page.jpg"), new byte[CONTENT_SIZE]);
        WorkerConfig config = new WorkerConfig();
        config.getZip().setSplitSize(64L * 1024);
        ZipBuilder builder = new ZipBuilder(config);
        ExportManifest manifest = manifest(List.of(source));
        Path archive = workspace.resolve("staging/book.cbz");
        ZipBuilder.ZipBuildResult result = builder.build(manifest, archive);
        assertEquals(archive, result.mainZip());
        assertTrue(result.orderedVolumes().size() > 1);
        assertEquals("book.z01", result.orderedVolumes().getFirst().getFileName().toString());
        assertEquals(result, builder.verify(archive, manifest));
    }

    @Test
    void mediaSkipsCompressionWhileOtherEntriesRemainCompressedAndReadable() throws Exception {
        Path media = Files.write(workspace.resolve("page.JPG"), new byte[CONTENT_SIZE]);
        Path text = Files.write(workspace.resolve("text.txt"), new byte[CONTENT_SIZE]);
        ExportManifest manifest = manifest(List.of(media, text));
        Path archive = workspace.resolve("staging/book.cbz");
        new ZipBuilder(new WorkerConfig()).build(manifest, archive);
        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            ZipEntry mediaEntry = zipFile.getEntry("漫画/page.JPG");
            ZipEntry textEntry = zipFile.getEntry("漫画/text.txt");
            assertEquals(ZipEntry.STORED, mediaEntry.getMethod());
            assertEquals(CONTENT_SIZE, mediaEntry.getCompressedSize());
            assertTrue(textEntry.getCompressedSize() < CONTENT_SIZE / 10);
        }
    }

    @Test
    void mediaCompressionCanBeConfiguredWithoutChangingArchiveContract() throws Exception {
        Path media = Files.write(workspace.resolve("page.png"), new byte[CONTENT_SIZE]);
        WorkerConfig config = new WorkerConfig();
        config.getZip().setMediaCompressionLevel(6);
        Path archive = workspace.resolve("staging/book.zip");
        new ZipBuilder(config).build(manifest(List.of(media)), archive);
        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            assertTrue(zipFile.getEntry("漫画/page.png").getCompressedSize() < CONTENT_SIZE / 10);
        }
    }

    @Test
    void newBuildReadsSourceOnceButExistingArchiveVerificationReadsItAgain() throws Exception {
        Path source = Files.write(workspace.resolve("page.jpg"), new byte[CONTENT_SIZE]);
        ExportManifest manifest = manifest(List.of(source));
        ZipBuilder builder = new ZipBuilder(new WorkerConfig());
        Path archive = workspace.resolve("staging/book.zip");
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            builder.build(manifest, archive);
            files.verify(() -> Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS), times(1));
            builder.verify(archive, manifest);
            files.verify(() -> Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS), times(2));
        }
    }

    @Test
    void existingArchiveRejectsSameSizeSourceContentChange() throws Exception {
        Path source = Files.writeString(workspace.resolve("page.jpg"), "original");
        ExportManifest manifest = manifest(List.of(source));
        ZipBuilder builder = new ZipBuilder(new WorkerConfig());
        Path archive = workspace.resolve("staging/book.zip");
        builder.build(manifest, archive);
        Files.writeString(source, "modified");
        assertThrows(IOException.class, () -> builder.verify(archive, manifest));
        assertTrue(Files.exists(archive));
    }

    @Test
    void copyingRejectsGrowthAndTruncationWithoutWritingBeyondExpectedSize() {
        assertThrows(IOException.class, () -> ArchiveStreams.copy(new ByteArrayInputStream(new byte[10]),
                OutputStream.nullOutputStream(), 9, "媒体", ArchiveStreams.newBuffer()));
        assertThrows(IOException.class, () -> ArchiveStreams.copy(new ByteArrayInputStream(new byte[10]),
                OutputStream.nullOutputStream(), 11, "媒体", ArchiveStreams.newBuffer()));
    }

    @Test
    void sourceModificationDuringCopyIsRejected() throws Exception {
        Path source = Files.write(workspace.resolve("page.jpg"), new byte[CONTENT_SIZE]);
        FileTime changedTime = FileTime.fromMillis(Files.getLastModifiedTime(source).toMillis() + 10_000);
        ExportManifest.Entry entry = new ExportManifest.Entry("page.jpg", source, CONTENT_SIZE);
        OutputStream changingOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                Files.setLastModifiedTime(source, changedTime);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                Files.setLastModifiedTime(source, changedTime);
            }
        };
        assertThrows(IOException.class, () -> ArchiveStreams.copySource(entry, changingOutput, ArchiveStreams.newBuffer()));
    }

    @Test
    void interruptedBuildCleansStagingAndPreservesInterruptFlag() throws Exception {
        Path source = Files.writeString(workspace.resolve("page.jpg"), "image");
        ExportManifest manifest = manifest(List.of(source));
        Path archive = workspace.resolve("staging/book.zip");
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> new ZipBuilder(new WorkerConfig()).build(manifest, archive));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertFalse(Files.exists(archive.getParent()));
        assertTrue(Files.exists(source));
    }

    @Test
    void interruptionDuringCopyStopsAtNextBufferBoundary() {
        OutputStream interruptedOutput = new OutputStream() {
            @Override
            public void write(int value) {
                Thread.currentThread().interrupt();
            }

            @Override
            public void write(byte[] buffer, int offset, int length) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            assertThrows(InterruptedIOException.class, () -> ArchiveStreams.copy(
                    new ByteArrayInputStream(new byte[CONTENT_SIZE * 2]), interruptedOutput,
                    CONTENT_SIZE * 2, "媒体", ArchiveStreams.newBuffer()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void verificationRejectsDuplicateEntriesEvenWhenTheirNamesMatchManifest() throws Exception {
        Path source = Files.writeString(workspace.resolve("page.jpg"), "image");
        Path archive = workspace.resolve("duplicate.zip");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive.toFile())) {
            for (String name : List.of("漫画/metadata.json", "漫画/page.jpg", "漫画/page.jpg")) {
                output.putArchiveEntry(new ZipArchiveEntry(name));
                output.write(name.endsWith("json") ? "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        : Files.readAllBytes(source));
                output.closeArchiveEntry();
            }
        }
        assertThrows(IOException.class, () -> new ZipBuilder(new WorkerConfig()).verify(archive, manifest(List.of(source))));
    }

    @Test
    void oversizedAndUnsafeManifestCannotPublishPartialArchive() throws Exception {
        Path source = Files.writeString(workspace.resolve("page.jpg"), "image");
        for (String target : List.of("../page.jpg", "/page.jpg", "chapter/../../page.jpg", "chapter\\page.jpg")) {
            ExportManifest manifest = new ExportManifest("漫画", "{}",
                    List.of(new ExportManifest.Entry(target, source, Files.size(source))));
            Path archive = workspace.resolve("staging/book.zip");
            assertThrows(IOException.class, () -> new ZipBuilder(new WorkerConfig()).build(manifest, archive));
            assertFalse(Files.exists(archive.getParent()));
        }
        WorkerConfig config = new WorkerConfig();
        config.getZip().setMaxTotalSize(1);
        assertThrows(IOException.class, () -> new ZipBuilder(config)
                .build(manifest(List.of(source)), workspace.resolve("staging/book.zip")));
    }

    private ExportManifest manifest(List<Path> sources) throws IOException {
        java.util.ArrayList<ExportManifest.Entry> entries = new java.util.ArrayList<>(sources.size());
        for (Path source : sources) {
            entries.add(new ExportManifest.Entry(source.getFileName().toString(), source, Files.size(source)));
        }
        return new ExportManifest("漫画", "{}", entries);
    }
}
