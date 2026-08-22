package com.comicatlas.worker.exporter;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.archive.ZipVolumeResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipSplitReadOnlySeekableByteChannel;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

/**
 * 根据 {@link ExportManifest} 构建（分卷）ZIP 文件 — 流式写入，避免内存爆炸。
 *
 * <p>清单总大小未超过 {@code worker.zip.splitSize} 时生成单个 {@code .zip}；超过阈值时使用
 * Commons Compress 分卷构造器生成 {@code .z01... .zip} 标准分卷。条目统一 DEFLATED + UTF-8 名称，
 * Zip64 按需启用，每个条目写入前设置已知大小。构建完成后使用 {@link ZipVolumeResolver} +
 * {@link ZipSplitReadOnlySeekableByteChannel}/{@link ZipFile} 完整读回，对条目集合、长度与 CRC
 * 逐一校验，返回主 .zip、有序分卷与全部卷总大小。任何异常清理整个 staging 目录（输出父目录）
 * 并保留原始 cause。
 */
@Slf4j
@Component
public class ZipBuilder {

    /** 构建结果 — 主 .zip、有序分卷（分卷为 .z01..zNN 后接主 .zip，单卷仅主 .zip）、全部卷总大小。 */
    public record ZipBuildResult(Path mainZip, List<Path> orderedVolumes, long totalSize) {
    }

    private static final String METADATA_FILE = "metadata.json";
    private static final String COMIC_INFO_FILE = "ComicInfo.xml";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final int MAX_LOG_PATHS = 10;

    private final WorkerConfig workerConfig;

    public ZipBuilder(WorkerConfig workerConfig) {
        this.workerConfig = workerConfig;
    }

    /**
     * @param manifest   导出清单（包含文件列表和元数据）
     * @param outputPath 输出 ZIP 文件路径（其父目录视为本构建的 staging 目录）
     * @return 主 .zip、有序分卷与全部卷总大小
     * @throws IOException 写入或回读校验失败
     */
    public ZipBuildResult build(ExportManifest manifest, Path outputPath) throws IOException {
        Path stagingDir = outputPath.toAbsolutePath().getParent();
        Files.createDirectories(stagingDir);
        try {
            if (manifestTotalSize(manifest) > workerConfig.getZip().getSplitSize()) {
                return buildSplit(manifest, outputPath);
            }
            return buildSingle(manifest, outputPath);
        } catch (IOException | RuntimeException ex) {
            deleteRecursively(stagingDir);
            throw ex;
        }
    }

    private ZipBuildResult buildSingle(ExportManifest manifest, Path outputPath) throws IOException {
        try (ZipArchiveOutputStream zipOutputStream = new ZipArchiveOutputStream(outputPath.toFile())) {
            configure(zipOutputStream);
            writeEntries(zipOutputStream, manifest);
        }
        return verifyAndReturn(outputPath, manifest);
    }

    private ZipBuildResult buildSplit(ExportManifest manifest, Path outputPath) throws IOException {
        long splitSize = workerConfig.getZip().getSplitSize();
        try (ZipArchiveOutputStream zipOutputStream = new ZipArchiveOutputStream(outputPath, splitSize)) {
            configure(zipOutputStream);
            writeEntries(zipOutputStream, manifest);
        }
        return verifyAndReturn(outputPath, manifest);
    }

    private void configure(ZipArchiveOutputStream zipOutputStream) {
        zipOutputStream.setUseZip64(Zip64Mode.AsNeeded);
        zipOutputStream.setEncoding(StandardCharsets.UTF_8.name());
        zipOutputStream.setUseLanguageEncodingFlag(true);
        zipOutputStream.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER);
        zipOutputStream.setFallbackToUTF8(true);
    }

    private void writeEntries(ZipArchiveOutputStream zipOutputStream, ExportManifest manifest) throws IOException {
        String prefix = manifest.rootDirName() + "/";
        writeBytesEntry(zipOutputStream, prefix + METADATA_FILE, manifest.metadataJson().getBytes(StandardCharsets.UTF_8));
        if (manifest.comicInfoXml() != null && !manifest.comicInfoXml().isBlank()) {
            writeBytesEntry(zipOutputStream, prefix + COMIC_INFO_FILE,
                    manifest.comicInfoXml().getBytes(StandardCharsets.UTF_8));
        }
        for (ExportManifest.Entry entry : manifest.entries()) {
            ZipArchiveEntry zipArchiveEntry = new ZipArchiveEntry(prefix + entry.targetPath());
            zipArchiveEntry.setSize(entry.sourceSize());
            zipArchiveEntry.setMethod(ZipEntry.DEFLATED);
            zipOutputStream.putArchiveEntry(zipArchiveEntry);
            try (InputStream in = Files.newInputStream(entry.sourceFile())) {
                in.transferTo(zipOutputStream);
            }
            zipOutputStream.closeArchiveEntry();
        }
    }

    private void writeBytesEntry(ZipArchiveOutputStream zipOutputStream, String name, byte[] content) throws IOException {
        ZipArchiveEntry zipArchiveEntry = new ZipArchiveEntry(name);
        zipArchiveEntry.setSize(content.length);
        zipArchiveEntry.setMethod(ZipEntry.DEFLATED);
        zipOutputStream.putArchiveEntry(zipArchiveEntry);
        zipOutputStream.write(content);
        zipOutputStream.closeArchiveEntry();
    }

    private long manifestTotalSize(ExportManifest manifest) {
        long total = manifest.metadataJson().getBytes(StandardCharsets.UTF_8).length;
        if (manifest.comicInfoXml() != null && !manifest.comicInfoXml().isBlank()) {
            total = Math.addExact(total, manifest.comicInfoXml().getBytes(StandardCharsets.UTF_8).length);
        }
        for (ExportManifest.Entry entry : manifest.entries()) {
            total = Math.addExact(total, entry.sourceSize());
        }
        return total;
    }

    /**
     * 对既有 ZIP 产物执行与构建一致的读回校验（条目集合、长度与 CRC）。
     *
     * <p>供发布器在最终任务目录已存在时判断是否与本次 manifest 完全一致（幂等复用）。
     *
     * @param mainZip  最终 .zip 文件路径（其父目录内同 basename 的 .zNN 视为分卷）
     * @param manifest 当前导出清单
     * @return 主 .zip、有序分卷与全部卷总大小
     * @throws IOException 回读校验失败或分卷解析失败
     */
    public ZipBuildResult verify(Path mainZip, ExportManifest manifest) throws IOException {
        return verifyAndReturn(mainZip, manifest);
    }

    /**
     * 回读校验：用 {@link ZipVolumeResolver} 解析有序分卷，{@link ZipSplitReadOnlySeekableByteChannel}/
     * {@link ZipFile} 读回每个条目，逐一校验条目集合、长度与 CRC。
     */
    private ZipBuildResult verifyAndReturn(Path mainZip, ExportManifest manifest) throws IOException {
        List<Path> volumes = ZipVolumeResolver.resolve(mainZip);
        long totalSize = 0L;
        for (Path volume : volumes) {
            totalSize = Math.addExact(totalSize, Files.size(volume));
        }

        String prefix = manifest.rootDirName() + "/";
        int metadataEntryCount = manifest.comicInfoXml() == null || manifest.comicInfoXml().isBlank() ? 1 : 2;
        int expectedSize = manifest.entries().size() + metadataEntryCount;
        Set<String> expectedNames = new HashSet<>(expectedSize);
        expectedNames.add(prefix + METADATA_FILE);
        if (manifest.comicInfoXml() != null && !manifest.comicInfoXml().isBlank()) {
            expectedNames.add(prefix + COMIC_INFO_FILE);
        }
        for (ExportManifest.Entry entry : manifest.entries()) {
            expectedNames.add(prefix + entry.targetPath());
        }

        try (ZipFile zipFile = openZipFile(volumes)) {
            Set<String> actualNames = new HashSet<>(expectedSize);
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                actualNames.add(entries.nextElement().getName());
            }
            if (!actualNames.equals(expectedNames)) {
                throw new IOException("ZIP 回读校验失败：条目集合不一致 expected=" + expectedNames + ", actual=" + actualNames);
            }

            String metaName = prefix + METADATA_FILE;
            verifyBytesEntry(zipFile, metaName, manifest.metadataJson().getBytes(StandardCharsets.UTF_8));
            if (manifest.comicInfoXml() != null && !manifest.comicInfoXml().isBlank()) {
                verifyBytesEntry(zipFile, prefix + COMIC_INFO_FILE,
                        manifest.comicInfoXml().getBytes(StandardCharsets.UTF_8));
            }

            for (ExportManifest.Entry entry : manifest.entries()) {
                verifyFileEntry(zipFile, prefix + entry.targetPath(), entry);
            }
        }
        return new ZipBuildResult(mainZip, volumes, totalSize);
    }

    private ZipFile openZipFile(List<Path> volumes) throws IOException {
        ZipFile.Builder builder = ZipFile.builder().setUseUnicodeExtraFields(true);
        if (volumes.size() == 1) {
            SeekableByteChannel channel = Files.newByteChannel(volumes.get(0), StandardOpenOption.READ);
            return builder.setSeekableByteChannel(channel).get();
        }
        return builder.setSeekableByteChannel(
                ZipSplitReadOnlySeekableByteChannel.forPaths(volumes.toArray(Path[]::new))).get();
    }

    private void verifyBytesEntry(ZipFile zipFile, String name, byte[] expected) throws IOException {
        ZipArchiveEntry zipArchiveEntry = zipFile.getEntry(name);
        if (zipArchiveEntry == null) {
            throw new IOException("ZIP 回读校验失败：缺少条目 " + name);
        }
        if (zipArchiveEntry.getSize() != expected.length) {
            throw new IOException("ZIP 回读校验失败：条目长度不一致 name=" + name
                    + ", stored=" + zipArchiveEntry.getSize() + ", expected=" + expected.length);
        }
        byte[] actual;
        try (InputStream in = zipFile.getInputStream(zipArchiveEntry)) {
            actual = in.readAllBytes();
        }
        if (!Arrays.equals(actual, expected)) {
            throw new IOException("ZIP 回读校验失败：条目内容不一致 name=" + name);
        }
    }

    private void verifyFileEntry(ZipFile zipFile, String name, ExportManifest.Entry entry) throws IOException {
        ZipArchiveEntry zipArchiveEntry = zipFile.getEntry(name);
        if (zipArchiveEntry == null) {
            throw new IOException("ZIP 回读校验失败：缺少条目 " + name);
        }
        if (zipArchiveEntry.getSize() != entry.sourceSize()) {
            throw new IOException("ZIP 回读校验失败：条目长度不一致 name=" + name
                    + ", stored=" + zipArchiveEntry.getSize() + ", expected=" + entry.sourceSize());
        }
        long sourceCrc;
        try (InputStream sourceIn = Files.newInputStream(entry.sourceFile())) {
            sourceCrc = calculateCrc32(sourceIn);
        }
        if (zipArchiveEntry.getCrc() != sourceCrc) {
            throw new IOException("ZIP 回读校验失败：条目 CRC 与源文件不一致 name=" + name);
        }
        long readCrc;
        try (InputStream storedIn = zipFile.getInputStream(zipArchiveEntry)) {
            readCrc = calculateCrc32(storedIn);
        }
        if (readCrc != zipArchiveEntry.getCrc()) {
            throw new IOException("ZIP 回读校验失败：条目读回 CRC 与存储 CRC 不一致 name=" + name);
        }
    }

    private static long calculateCrc32(InputStream in) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            crc.update(buffer, 0, read);
        }
        return crc.getValue();
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> undeleted = new ArrayList<>();
        IOException[] firstFailure = new IOException[1];
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    if (firstFailure[0] == null) {
                        firstFailure[0] = ex;
                    }
                    undeleted.add(path);
                }
            });
        } catch (IOException ex) {
            log.warn("清理 staging 目录失败: {}", dir, ex);
            return;
        }
        if (!undeleted.isEmpty()) {
            log.warn("清理 staging 目录失败，共 {} 个文件未删除，示例: {}，首个失败原因: {}",
                    undeleted.size(), undeleted.stream().limit(MAX_LOG_PATHS).toList(), firstFailure[0]);
        }
    }
}
