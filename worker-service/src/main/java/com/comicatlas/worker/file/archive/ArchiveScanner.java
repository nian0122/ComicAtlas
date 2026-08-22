package com.comicatlas.worker.file.archive;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 基于 ArchiveReader 的格式无关扫描器。 */
@Component
@RequiredArgsConstructor
public class ArchiveScanner {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".tif", ".tiff", ".jxl", ".avif");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            ".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v");

    private final List<ArchiveReader> readers;

    public ArchiveScanResult scan(Path archive, Duration timeout) throws IOException {
        ArchiveReader reader = readers.stream().filter(item -> item.supports(archive)).findFirst()
                .orElseThrow(() -> new IOException("不支持的压缩包格式: " + archive.getFileName()));
        List<String> missingVolumes = new ArrayList<>();
        try {
            reader.detectVolumes(archive).stream()
                    .filter(path -> !java.nio.file.Files.exists(path))
                    .map(path -> path.getFileName().toString())
                    .forEach(missingVolumes::add);
        } catch (IllegalArgumentException e) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("缺少 (\\.z\\d+)").matcher(e.getMessage());
            if (matcher.find()) {
                missingVolumes.add(matcher.group(1));
            } else {
                missingVolumes.add("分卷解析失败");
            }
        }
        List<ArchiveScanResult.PathEntry> images = new ArrayList<>();
        List<ArchiveScanResult.PathEntry> videos = new ArrayList<>();
        List<ArchiveScanResult.PathEntry> unsupported = new ArrayList<>();
        List<String> directories = new ArrayList<>();
        Set<String> directorySet = new HashSet<>();
        Map<String, Integer> names = new HashMap<>();
        boolean integrityPassed = missingVolumes.isEmpty();
        List<String> damaged = new ArrayList<>();
        if (!missingVolumes.isEmpty()) {
            return new ArchiveScanResult(formatFallback(archive, reader), images, videos, unsupported,
                    directories, List.of(), List.of(), List.of(), missingVolumes, false);
        }
        try (ArchiveReader.ArchiveSession session = reader.open(archive, timeout)) {
            List<ArchiveEntry> entries = session.listEntries();
            for (ArchiveEntry entry : entries) {
                if (entry.directory()) {
                    directories.add(entry.name());
                    directorySet.add(entry.name().replaceAll("/+$", ""));
                    continue;
                }
                names.merge(entry.name().substring(entry.name().lastIndexOf('/') + 1).toLowerCase(Locale.ROOT), 1, Integer::sum);
                String extension = extension(entry.name());
                ArchiveScanResult.PathEntry pathEntry = new ArchiveScanResult.PathEntry(entry.name(), entry.size());
                if (IMAGE_EXTENSIONS.contains(extension)) {
                    images.add(pathEntry);
                } else if (VIDEO_EXTENSIONS.contains(extension)) {
                    videos.add(pathEntry);
                } else {
                    unsupported.add(pathEntry);
                }
            }
            for (ArchiveEntry entry : entries) {
                if (!entry.directory() && (IMAGE_EXTENSIONS.contains(extension(entry.name()))
                        || VIDEO_EXTENSIONS.contains(extension(entry.name())))) {
                    try (InputStream ignored = session.readEntry(entry.name())) {
                        ignored.transferTo(java.io.OutputStream.nullOutputStream());
                    } catch (IOException e) {
                        damaged.add(entry.name());
                    }
                }
            }
            try {
                session.testIntegrity();
            } catch (IOException e) {
                integrityPassed = false;
            }
        }
        List<String> duplicates = names.entrySet().stream().filter(item -> item.getValue() > 1)
                .map(Map.Entry::getKey).toList();
        List<String> empty = directories.stream().filter(directory -> entriesUnder(directory, images, videos) == 0)
                .toList();
        return new ArchiveScanResult(reader.detectFormat(archive), images, videos, unsupported,
                directories, empty, duplicates, damaged, missingVolumes, integrityPassed && damaged.isEmpty());
    }

    private static long entriesUnder(String directory, List<ArchiveScanResult.PathEntry> images,
                                     List<ArchiveScanResult.PathEntry> videos) {
        String prefix = directory.replaceAll("/+$", "") + "/";
        return java.util.stream.Stream.concat(images.stream(), videos.stream())
                .filter(item -> item.name().startsWith(prefix)).count();
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static ArchiveFormat formatFallback(Path archive, ArchiveReader reader) {
        try {
            return reader.detectFormat(archive);
        } catch (IOException | IllegalArgumentException ignored) {
            String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".zip") || name.endsWith(".cbz")) {
                return ArchiveFormat.SPLIT_ZIP;
            }
            return ArchiveFormat.UNKNOWN;
        }
    }
}
