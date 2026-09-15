package com.comicatlas.worker.exporter.archive;

import com.comicatlas.worker.exporter.model.ExportManifest;
import com.comicatlas.worker.config.WorkerConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.Deflater;

/**
 * 流式构建标准 ZIP/CBZ 与分卷 ZIP，媒体默认跳过重复压缩，其他条目快速压缩。
 * 写入时记录实际 CRC，构建后完整读回验证长度、内容和条目集合，避免再次读取源文件。
 * 不使用中间压缩文件或额外线程；内存占用与文件大小无关。失败时清理 staging。
 */
@Slf4j
@Component
public class ZipBuilder {

    /** 构建结果 — 主 .zip、有序分卷（分卷为 .z01..zNN 后接主 .zip，单卷仅主 .zip）、全部卷总大小。 */
    public record ZipBuildResult(Path mainZip, List<Path> orderedVolumes, long totalSize) {
    }

    private static final String METADATA_FILE = "metadata.json";
    private static final String COMIC_INFO_FILE = "ComicInfo.xml";
    /** 这些格式已经包含媒体编码，默认使用 STORE 直接打包。 */
    private static final Set<String> COMPRESSED_MEDIA_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "gif", "avif", "jxl", "heic", "heif",
            "mp4", "m4v", "webm", "mkv", "mov", "avi", "mpeg", "mpg", "3gp", "ogv");

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
            ArchiveStreams.checkInterrupted();
            long sourceSize = validateManifest(manifest);
            long started = System.nanoTime();
            boolean requiresSplit = sourceSize > workerConfig.getZip().getSplitSize();
            Path archivePath = requiresSplit ? splitArchivePath(outputPath) : outputPath;
            Map<String, Long> writtenChecksums;
            try (ZipArchiveOutputStream output = requiresSplit
                    ? new ZipArchiveOutputStream(archivePath, workerConfig.getZip().getSplitSize())
                    : new ZipArchiveOutputStream(archivePath.toFile())) {
                configure(output);
                writtenChecksums = writeEntries(output, manifest);
            }
            if (!archivePath.equals(outputPath)) {
                // Commons 分卷关闭时固定生成 .zip 主卷；只在未发布的 staging 内恢复请求的 .cbz 名称。
                Files.move(archivePath, outputPath);
            }
            long written = System.nanoTime();
            ZipBuildResult result = ZipVerifier.verify(outputPath, manifest, writtenChecksums);
            log.info("导出 ZIP 校验通过：entries={}, sourceBytes={}, outputBytes={}, volumes={}, writeMs={}, verifyMs={}",
                    writtenChecksums.size(), sourceSize, result.totalSize(), result.orderedVolumes().size(),
                    TimeUnit.NANOSECONDS.toMillis(written - started),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - written));
            return result;
        } catch (IOException | RuntimeException exception) {
            ExportStagingCleanup.afterFailure(stagingDir, exception);
            throw exception;
        }
    }

    private Path splitArchivePath(Path outputPath) {
        String fileName = outputPath.getFileName().toString();
        int extensionStart = fileName.lastIndexOf('.');
        String baseName = extensionStart >= 0 ? fileName.substring(0, extensionStart) : fileName;
        return outputPath.resolveSibling(baseName + ".zip");
    }

    private void configure(ZipArchiveOutputStream zipOutputStream) {
        zipOutputStream.setUseZip64(Zip64Mode.AsNeeded);
        zipOutputStream.setEncoding(StandardCharsets.UTF_8.name());
        zipOutputStream.setUseLanguageEncodingFlag(true);
        zipOutputStream.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER);
        zipOutputStream.setFallbackToUTF8(true);
    }

    private Map<String, Long> writeEntries(ZipArchiveOutputStream zipOutputStream, ExportManifest manifest)
            throws IOException {
        Map<String, Long> writtenChecksums = new HashMap<>(manifest.entries().size());
        byte[] buffer = ArchiveStreams.newBuffer();
        String prefix = manifest.rootDirName() + "/";
        writeBytesEntry(zipOutputStream, prefix + METADATA_FILE, manifest.metadataJson().getBytes(StandardCharsets.UTF_8));
        if (manifest.comicInfoXml() != null && !manifest.comicInfoXml().isBlank()) {
            writeBytesEntry(zipOutputStream, prefix + COMIC_INFO_FILE,
                    manifest.comicInfoXml().getBytes(StandardCharsets.UTF_8));
        }
        for (ExportManifest.Entry entry : manifest.entries()) {
            ZipArchiveEntry zipArchiveEntry = new ZipArchiveEntry(prefix + entry.targetPath());
            zipArchiveEntry.setSize(entry.sourceSize());
            int level = compressionLevel(entry.targetPath());
            // 1.28.0 的普通文件和分卷输出均可回写 CRC；仅不可回写的输出退回 0 级 DEFLATE。
            zipArchiveEntry.setMethod(level == Deflater.NO_COMPRESSION && zipOutputStream.isSeekable()
                    ? ZipEntry.STORED : ZipEntry.DEFLATED);
            zipOutputStream.setLevel(level);
            zipOutputStream.putArchiveEntry(zipArchiveEntry);
            long checksum = ArchiveStreams.copySource(entry, zipOutputStream, buffer);
            zipOutputStream.closeArchiveEntry();
            writtenChecksums.put(zipArchiveEntry.getName(), checksum);
        }
        return writtenChecksums;
    }

    private int compressionLevel(String targetPath) {
        int extensionStart = targetPath.lastIndexOf('.');
        String extension = targetPath.substring(extensionStart + 1).toLowerCase(Locale.ROOT);
        return COMPRESSED_MEDIA_EXTENSIONS.contains(extension)
                ? workerConfig.getZip().getMediaCompressionLevel() : workerConfig.getZip().getCompressionLevel();
    }

    private void writeBytesEntry(ZipArchiveOutputStream zipOutputStream, String name, byte[] content) throws IOException {
        ZipArchiveEntry zipArchiveEntry = new ZipArchiveEntry(name);
        zipArchiveEntry.setSize(content.length);
        zipArchiveEntry.setMethod(ZipEntry.DEFLATED);
        ArchiveStreams.checkInterrupted();
        zipOutputStream.setLevel(workerConfig.getZip().getCompressionLevel());
        zipOutputStream.putArchiveEntry(zipArchiveEntry);
        zipOutputStream.write(content);
        zipOutputStream.closeArchiveEntry();
    }

    private long validateManifest(ExportManifest manifest) throws IOException {
        Set<String> names = ZipVerifier.expectedNames(manifest);
        if (names.size() > workerConfig.getZip().getMaxEntries()) {
            throw new IOException("ZIP 清单条目数超过上限");
        }
        Set<String> foldedNames = new HashSet<>(names.size());
        for (String name : names) {
            if (name.startsWith("/") || name.contains("\\") || name.contains(":") || name.indexOf('\0') >= 0
                    || !foldedNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new IOException("ZIP 清单路径无效或大小写冲突");
            }
            String[] segments = name.split("/", -1);
            if (segments.length > workerConfig.getZip().getMaxDepth()) {
                throw new IOException("ZIP 清单路径深度超过上限");
            }
            for (String segment : segments) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    throw new IOException("ZIP 清单路径不允许空段或目录穿越");
                }
            }
        }
        long total = metadataSize(manifest.metadataJson());
        if (manifest.comicInfoXml() != null && !manifest.comicInfoXml().isBlank()) {
            total = Math.addExact(total, metadataSize(manifest.comicInfoXml()));
        }
        for (ExportManifest.Entry entry : manifest.entries()) {
            if (entry.sourceSize() < 0 || entry.sourceSize() > workerConfig.getZip().getMaxEntrySize()) {
                throw new IOException("ZIP 清单单文件大小超过上限");
            }
            total = Math.addExact(total, entry.sourceSize());
        }
        if (total > workerConfig.getZip().getMaxTotalSize()) {
            throw new IOException("ZIP 清单总大小超过上限");
        }
        return total;
    }

    private long metadataSize(String content) throws IOException {
        long size = content.getBytes(StandardCharsets.UTF_8).length;
        if (size > workerConfig.getZip().getMaxEntrySize()) {
            throw new IOException("ZIP 元数据条目大小超过上限");
        }
        return size;
    }

    /** 独立校验既有产物，读取当前源文件确认幂等复用的内容仍然一致。 */
    public ZipBuildResult verify(Path mainZip, ExportManifest manifest) throws IOException {
        return ZipVerifier.verify(mainZip, manifest, Map.of());
    }

}
