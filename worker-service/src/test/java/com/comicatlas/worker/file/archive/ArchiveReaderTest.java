package com.comicatlas.worker.file.archive;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.extract.ZipExtractor;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveReaderTest {
    @TempDir
    Path tempDir;

    @Test
    void zipReaderListsAndStreamsSingleEntry() throws Exception {
        Path archive = tempDir.resolve("book.zip");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(Files.newOutputStream(archive))) {
            output.putArchiveEntry(new ZipArchiveEntry("chapter/001.jpg"));
            output.write("image-data".getBytes(StandardCharsets.UTF_8));
            output.closeArchiveEntry();
            output.putArchiveEntry(new ZipArchiveEntry("empty/"));
            output.closeArchiveEntry();
        }

        WorkerConfig config = new WorkerConfig();
        ZipExtractor reader = new ZipExtractor(config);
        try (ArchiveReader.ArchiveSession session = reader.open(archive, Duration.ofSeconds(5))) {
            List<ArchiveEntry> entries = session.listEntries();
            assertThat(entries).extracting(ArchiveEntry::name)
                    .contains("chapter/001.jpg", "empty/");
            try (InputStream input = session.readEntry("chapter/001.jpg")) {
                assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("image-data");
            }
            session.testIntegrity();
        }
    }

    @Test
    void scannerClassifiesMediaAndDuplicates() throws Exception {
        Path archive = tempDir.resolve("scan.zip");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(Files.newOutputStream(archive))) {
            write(output, "chapter/a.jpg");
            write(output, "chapter/a.jpg.copy");
            write(output, "chapter/a.jpg");
            write(output, "chapter/video.mp4");
            write(output, "chapter/readme.txt");
        }
        WorkerConfig config = new WorkerConfig();
        ArchiveScanner scanner = new ArchiveScanner(List.of(new ZipExtractor(config)));
        ArchiveScanResult result = scanner.scan(archive, Duration.ofSeconds(5));
        assertThat(result.images()).extracting(ArchiveScanResult.PathEntry::name).contains("chapter/a.jpg");
        assertThat(result.videos()).extracting(ArchiveScanResult.PathEntry::name).contains("chapter/video.mp4");
        assertThat(result.unsupportedFiles()).extracting(ArchiveScanResult.PathEntry::name)
                .contains("chapter/a.jpg.copy", "chapter/readme.txt");
        assertThat(result.duplicateFileNames()).contains("a.jpg");
        assertThat(result.integrityPassed()).isTrue();
    }

    private static void write(ZipArchiveOutputStream output, String name) throws Exception {
        output.putArchiveEntry(new ZipArchiveEntry(name));
        output.write(1);
        output.closeArchiveEntry();
    }
}
