package com.comicatlas.worker.export;

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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

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
        } catch (Exception e) {
            deleteRecursively(stagingDir);
            throw e;
        }
    }

    private ZipBuildResult buildSingle(ExportManifest manifest, Path outputPath) throws IOException {
        try (ZipArchiveOutputStream zipOut = new ZipArchiveOutputStream(outputPath.toFile())) {
            configure(zipOut);
            writeEntries(zipOut, manifest);
        }
        return verifyAndReturn(outputPath, manifest);
    }

    private ZipBuildResult buildSplit(ExportManifest manifest, Path outputPath) throws IOException {
        long splitSize = workerConfig.getZip().getSplitSize();
        try (ZipArchiveOutputStream zipOut = new ZipArchiveOutputStream(outputPath, splitSize)) {
            configure(zipOut);
            writeEntries(zipOut, manifest);
        }
        return verifyAndReturn(outputPath, manifest);
    }

    private void configure(ZipArchiveOutputStream zipOut) {
        zipOut.setUseZip64(Zip64Mode.AsNeeded);
        zipOut.setEncoding(StandardCharsets.UTF_8.name());
        zipOut.setUseLanguageEncodingFlag(true);
        zipOut.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER);
        zipOut.setFallbackToUTF8(true);
    }

    private void writeEntries(ZipArchiveOutputStream zipOut, ExportManifest manifest) throws IOException {
        String prefix = manifest.rootDirName() + "/";
        writeBytesEntry(zipOut, prefix + METADATA_FILE, manifest.metadataJson().getBytes(StandardCharsets.UTF_8));
        for (ExportManifest.Entry entry : manifest.entries()) {
            ZipArchiveEntry zipEntry = new ZipArchiveEntry(prefix + entry.targetPath());
            zipEntry.setSize(entry.sourceSize());
            zipEntry.setMethod(ZipEntry.DEFLATED);
            zipOut.putArchiveEntry(zipEntry);
            try (InputStream in = Files.newInputStream(entry.sourceFile())) {
                in.transferTo(zipOut);
            }
            zipOut.closeArchiveEntry();
        }
    }

    private void writeBytesEntry(ZipArchiveOutputStream zipOut, String name, byte[] content) throws IOException {
        ZipArchiveEntry zipEntry = new ZipArchiveEntry(name);
        zipEntry.setSize(content.length);
        zipEntry.setMethod(ZipEntry.DEFLATED);
        zipOut.putArchiveEntry(zipEntry);
        zipOut.write(content);
        zipOut.closeArchiveEntry();
    }

    private long manifestTotalSize(ExportManifest manifest) {
        long total = manifest.metadataJson().getBytes(StandardCharsets.UTF_8).length;
        for (ExportManifest.Entry entry : manifest.entries()) {
            total = Math.addExact(total, entry.sourceSize());
        }
        return total;
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
        Set<String> expectedNames = new HashSet<>();
        expectedNames.add(prefix + METADATA_FILE);
        for (ExportManifest.Entry entry : manifest.entries()) {
            expectedNames.add(prefix + entry.targetPath());
        }

        try (ZipFile zipFile = openZipFile(volumes)) {
            Set<String> actualNames = new HashSet<>();
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                actualNames.add(entries.nextElement().getName());
            }
            if (!actualNames.equals(expectedNames)) {
                throw new IOException("ZIP 回读校验失败：条目集合不一致 expected=" + expectedNames + ", actual=" + actualNames);
            }

            String metaName = prefix + METADATA_FILE;
            verifyBytesEntry(zipFile, metaName, manifest.metadataJson().getBytes(StandardCharsets.UTF_8));

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
        ZipArchiveEntry zipEntry = zipFile.getEntry(name);
        if (zipEntry == null) {
            throw new IOException("ZIP 回读校验失败：缺少条目 " + name);
        }
        if (zipEntry.getSize() != expected.length) {
            throw new IOException("ZIP 回读校验失败：条目长度不一致 name=" + name
                    + ", stored=" + zipEntry.getSize() + ", expected=" + expected.length);
        }
        byte[] actual;
        try (InputStream in = zipFile.getInputStream(zipEntry)) {
            actual = in.readAllBytes();
        }
        if (!Arrays.equals(actual, expected)) {
            throw new IOException("ZIP 回读校验失败：条目内容不一致 name=" + name);
        }
    }

    private void verifyFileEntry(ZipFile zipFile, String name, ExportManifest.Entry entry) throws IOException {
        ZipArchiveEntry zipEntry = zipFile.getEntry(name);
        if (zipEntry == null) {
            throw new IOException("ZIP 回读校验失败：缺少条目 " + name);
        }
        if (zipEntry.getSize() != entry.sourceSize()) {
            throw new IOException("ZIP 回读校验失败：条目长度不一致 name=" + name
                    + ", stored=" + zipEntry.getSize() + ", expected=" + entry.sourceSize());
        }
        long sourceCrc = crc32(Files.newInputStream(entry.sourceFile()));
        if (zipEntry.getCrc() != sourceCrc) {
            throw new IOException("ZIP 回读校验失败：条目 CRC 与源文件不一致 name=" + name);
        }
        long readCrc = crc32(zipFile.getInputStream(zipEntry));
        if (readCrc != zipEntry.getCrc()) {
            throw new IOException("ZIP 回读校验失败：条目读回 CRC 与存储 CRC 不一致 name=" + name);
        }
    }

    private static long crc32(InputStream in) throws IOException {
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
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("清理 staging 目录失败: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("清理 staging 目录失败: {}", dir, e);
        }
    }
}
