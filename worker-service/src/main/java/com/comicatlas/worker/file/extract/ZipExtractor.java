package com.comicatlas.worker.file.extract;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

@Slf4j
@Component
public class ZipExtractor implements ArchiveExtractor {
    @Override
    public List<Path> extract(Path archive, Path destDir) throws Exception {
        Files.createDirectories(destDir);
        List<Path> extracted = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = new File(entry.getName()).getName();
                Path out = destDir.resolve(name);
                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                extracted.add(out);
                zis.closeEntry();
            }
        }
        log.info("Extracted {} files from {}", extracted.size(), archive.getFileName());
        return extracted;
    }

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".zip") || name.endsWith(".cbz");
    }
}
