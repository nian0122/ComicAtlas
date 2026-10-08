package com.comicatlas.worker.exporter.archive;

import com.comicatlas.worker.exporter.model.ExportManifest;
import com.comicatlas.worker.shared.archive.ZipVolumeResolver;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipSplitReadOnlySeekableByteChannel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 对新建和既有归档使用同一完整校验；新建归档复用写入时的 CRC，既有归档独立读取当前源文件。 */
final class ZipVerifier {
    static final String METADATA_FILE = "metadata.json";
    static final String COMIC_INFO_FILE = "ComicInfo.xml";

    private ZipVerifier() {
    }

    static ZipBuilder.ZipBuildResult verify(Path mainZip, ExportManifest manifest,
                                            Map<String, Long> writtenChecksums) throws IOException {
        ArchiveStreams.checkInterrupted();
        List<Path> volumes = ZipVolumeResolver.resolve(mainZip);
        long totalSize = 0;
        for (Path volume : volumes) {
            totalSize = Math.addExact(totalSize, Files.size(volume));
        }
        String prefix = manifest.rootDirName() + "/";
        Set<String> expectedNames = expectedNames(manifest);
        byte[] buffer = ArchiveStreams.newBuffer();
        // 显式持有 channel，确保 ZipFile 初始化失败时也关闭所有分卷句柄。
        try (SeekableByteChannel channel = openChannel(volumes);
             ZipFile zipFile = ZipFile.builder().setSeekableByteChannel(channel)
                     .setUseUnicodeExtraFields(true).get()) {
            Set<String> actualNames = new HashSet<>(expectedNames.size());
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                ArchiveStreams.checkInterrupted();
                String name = entries.nextElement().getName();
                if (!expectedNames.contains(name) || !actualNames.add(name)) {
                    throw new IOException("ZIP 回读校验失败：存在额外或重复条目");
                }
            }
            if (!actualNames.equals(expectedNames)) {
                throw new IOException("ZIP 回读校验失败：条目集合不一致");
            }
            verifyBytes(zipFile, prefix + METADATA_FILE, manifest.metadataJson().getBytes(StandardCharsets.UTF_8), buffer);
            if (hasComicInfo(manifest)) {
                verifyBytes(zipFile, prefix + COMIC_INFO_FILE,
                        manifest.comicInfoXml().getBytes(StandardCharsets.UTF_8), buffer);
            }
            for (ExportManifest.Entry entry : manifest.entries()) {
                String name = prefix + entry.targetPath();
                ZipArchiveEntry archiveEntry = requireEntry(zipFile, name, entry.sourceSize());
                Long writtenChecksum = writtenChecksums.get(name);
                long expectedChecksum = writtenChecksum != null ? writtenChecksum
                        : ArchiveStreams.copySource(entry, OutputStream.nullOutputStream(), buffer);
                if (archiveEntry.getCrc() != expectedChecksum) {
                    throw new IOException("ZIP 回读校验失败：CRC 与源文件不一致：" + name);
                }
                try (InputStream stored = zipFile.getInputStream(archiveEntry)) {
                    long actualChecksum = ArchiveStreams.copy(stored, OutputStream.nullOutputStream(),
                            entry.sourceSize(), name, buffer);
                    if (actualChecksum != expectedChecksum) {
                        throw new IOException("ZIP 回读校验失败：条目内容损坏：" + name);
                    }
                }
            }
        }
        return new ZipBuilder.ZipBuildResult(mainZip, List.copyOf(volumes), totalSize);
    }

    static Set<String> expectedNames(ExportManifest manifest) throws IOException {
        String prefix = manifest.rootDirName() + "/";
        Set<String> expectedNames = new HashSet<>(manifest.entries().size() + 2);
        expectedNames.add(prefix + METADATA_FILE);
        if (hasComicInfo(manifest)) {
            expectedNames.add(prefix + COMIC_INFO_FILE);
        }
        for (ExportManifest.Entry entry : manifest.entries()) {
            if (!expectedNames.add(prefix + entry.targetPath())) {
                throw new IOException("ZIP 清单含重复条目：" + entry.targetPath());
            }
        }
        return expectedNames;
    }

    static boolean hasComicInfo(ExportManifest manifest) {
        return manifest.comicInfoXml() != null && !manifest.comicInfoXml().isBlank();
    }

    private static SeekableByteChannel openChannel(List<Path> volumes) throws IOException {
        if (volumes.size() == 1) {
            return Files.newByteChannel(volumes.getFirst(), StandardOpenOption.READ);
        }
        return ZipSplitReadOnlySeekableByteChannel.forPaths(volumes.toArray(Path[]::new));
    }

    private static ZipArchiveEntry requireEntry(ZipFile zipFile, String name, long expectedSize) throws IOException {
        ZipArchiveEntry entry = zipFile.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.isUnixSymlink()
                || !zipFile.canReadEntryData(entry) || entry.getSize() != expectedSize) {
            throw new IOException("ZIP 回读校验失败：条目类型、长度或编码不符：" + name);
        }
        return entry;
    }

    private static void verifyBytes(ZipFile zipFile, String name, byte[] expected, byte[] buffer) throws IOException {
        ZipArchiveEntry entry = requireEntry(zipFile, name, expected.length);
        ByteArrayOutputStream content = new ByteArrayOutputStream(expected.length);
        try (InputStream stored = zipFile.getInputStream(entry)) {
            long checksum = ArchiveStreams.copy(stored, content, expected.length, name, buffer);
            if (checksum != entry.getCrc() || !Arrays.equals(content.toByteArray(), expected)) {
                throw new IOException("ZIP 回读校验失败：元数据内容不一致：" + name);
            }
        }
    }
}
