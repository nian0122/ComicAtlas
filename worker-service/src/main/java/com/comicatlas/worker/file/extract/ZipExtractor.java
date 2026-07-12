package com.comicatlas.worker.file.extract;

import com.comicatlas.worker.config.WorkerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class ZipExtractor implements ArchiveExtractor {

    private static final Set<String> RESERVED_NAMES = Set.of(
        "CON", "AUX", "NUL", "PRN",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private final WorkerConfig config;

    @Override
    public List<Path> extract(Path archive, Path destDir) throws Exception {
        Files.createDirectories(destDir);
        Path safeDest = destDir.toRealPath().normalize();

        List<Path> extracted = new ArrayList<>();
        int entryCount = 0;
        long totalSize = 0;

        try (InputStream fis = Files.newInputStream(archive);
             BufferedInputStream bis = new BufferedInputStream(fis);
             ZipInputStream zis = new ZipInputStream(bis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entryCount > config.getZip().getMaxEntries()) {
                    throw new IOException("ZIP entries exceed limit: " + config.getZip().getMaxEntries());
                }

                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                String entryName = entry.getName().replace('\\', '/');
                Path entryPath = safeDest.resolve(entryName).normalize();

                if (!entryPath.startsWith(safeDest)) {
                    throw new IOException("Zip Slip detected: " + entryName);
                }

                Path relative = safeDest.relativize(entryPath);
                if (relative.getNameCount() > config.getZip().getMaxDepth()) {
                    throw new IOException("Directory depth exceeds limit: " + entryName);
                }
                if (relative.toString().length() > 1024) {
                    throw new IOException("Path length exceeds limit: " + entryName);
                }

                String fileName = entryPath.getFileName().toString();
                String baseName = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
                if (RESERVED_NAMES.contains(baseName.toUpperCase())) {
                    throw new IOException("Windows reserved filename: " + fileName);
                }

                long entrySize = entry.getSize();
                if (entrySize > config.getZip().getMaxEntrySize()) {
                    throw new IOException(
                        "Single file exceeds size limit: " + entryName + " (" + entrySize + ")");
                }

                Files.createDirectories(entryPath.getParent());
                Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);

                long actualSize = entrySize >= 0 ? entrySize : Files.size(entryPath);
                totalSize += actualSize;
                if (totalSize > config.getZip().getMaxTotalSize()) {
                    throw new IOException("Total unpacked size exceeds limit: " + totalSize);
                }

                extracted.add(entryPath);
                zis.closeEntry();
            }
        }

        log.info("ZIP extracted: {} files, {} bytes, archive={}",
            extracted.size(), totalSize, archive.getFileName());
        return extracted;
    }

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".zip") || name.endsWith(".cbz");
    }
}
