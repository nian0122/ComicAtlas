package com.comicatlas.worker.file.extract;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.archive.ZipVolumeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipSplitReadOnlySeekableByteChannel;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * ZIP/CBZ 解压器 — 基于 Commons {@link ZipFile} 随机访问，支持标准分卷 ZIP。
 *
 * <p>入口规则：普通 {@code .zip/.cbz} 直接打开；带 {@code .zNN} 兄弟分卷的最后 {@code .zip}
 * 经 {@link ZipVolumeResolver} 解析有序卷后，用 {@link ZipSplitReadOnlySeekableByteChannel}
 * 打开；{@code .z01} 永不作为入口（{@link #supports} 与 {@link #extract} 均拒绝）。
 *
 * <p>安全校验：maxEntries/maxDepth/路径长度/Windows 保留名/Zip Slip 之外，额外拒绝 Unix
 * symlink 条目、重复或大小写冲突目标。先检查中央目录声明 size 是否超 {@code maxEntrySize}，
 * 再用 64 KiB 有界缓冲循环累计实际 entry/total 字节（{@link Math#addExact}），超限立即停止；
 * 不先完整落盘再检查。声明 size 与实读字节不一致（伪造 size）或 CRC 不匹配时抛异常。
 * 失败时清理本次已创建的文件与目录，不留残余。
 *
 * <p>异常消息只包含条目名/相对路径，日志用 {@code archive.getFileName()} 脱敏，不含完整源路径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZipExtractor implements ArchiveExtractor {

    private static final Set<String> RESERVED_NAMES = Set.of(
        "CON", "AUX", "NUL", "PRN",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    /** 有界复制缓冲（64 KiB），配合逐块累计实现"边读边查限、超限立即停止"。 */
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    /** 相对路径最大长度上限。 */
    private static final int MAX_PATH_LENGTH = 1024;

    private final WorkerConfig config;

    @Override
    public List<Path> extract(Path archive, Path destDir) throws Exception {
        List<Path> volumes = resolveVolumes(archive);

        Files.createDirectories(destDir);
        Path safeDest = destDir.toRealPath().normalize();

        List<Path> createdFiles = new ArrayList<>();
        Set<Path> createdDirs = new HashSet<>();
        try {
            return extractEntries(volumes, archive, safeDest, createdFiles, createdDirs);
        } catch (Exception e) {
            cleanupPartial(createdFiles, createdDirs);
            throw e;
        }
    }

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        // .z01 永不作为入口：既不以 .zip/.cbz 结尾，也显式排除
        return (name.endsWith(".zip") || name.endsWith(".cbz")) && !name.endsWith(".z01");
    }

    /**
     * 解析归档入口为有序卷列表：{@code .zip} 交 {@link ZipVolumeResolver}（单卷仅主文件，
     * 分卷为 .z01..zNN+主文件）；{@code .cbz} 视为单卷；其他扩展名（含 .z01）拒绝。
     */
    private List<Path> resolveVolumes(Path archive) throws IOException {
        String lowerName = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".zip") || lowerName.endsWith(".cbz")) {
            return ZipVolumeResolver.resolve(archive);
        }
        throw new IllegalArgumentException(
            "不支持的归档入口（须 .zip/.cbz，分卷以最后 .zip 为入口）: " + archive.getFileName());
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

    private List<Path> extractEntries(List<Path> volumes, Path archive, Path safeDest,
                                      List<Path> createdFiles, Set<Path> createdDirs) throws Exception {
        List<Path> extracted = new ArrayList<>();
        Set<String> seenTargets = new HashSet<>();
        int entryCount = 0;
        long[] totalBytes = new long[1];
        int maxEntries = config.getZip().getMaxEntries();
        int maxDepth = config.getZip().getMaxDepth();
        long maxEntrySize = config.getZip().getMaxEntrySize();
        long maxTotalSize = config.getZip().getMaxTotalSize();

        try (ZipFile zipFile = openZipFile(volumes)) {
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (++entryCount > maxEntries) {
                    throw new IOException("ZIP entries exceed limit: " + maxEntries);
                }

                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.isUnixSymlink()) {
                    throw new IOException("Unix symlink entry is rejected: " + entry.getName());
                }

                String entryName = entry.getName().replace('\\', '/');
                Path entryPath = safeDest.resolve(entryName).normalize();
                if (!entryPath.startsWith(safeDest)) {
                    throw new IOException("Zip Slip detected: " + entryName);
                }

                Path relative = safeDest.relativize(entryPath);
                if (relative.getNameCount() > maxDepth) {
                    throw new IOException("Directory depth exceeds limit: " + entryName);
                }
                String relativeKey = relative.toString();
                if (relativeKey.length() > MAX_PATH_LENGTH) {
                    throw new IOException("Path length exceeds limit: " + entryName);
                }
                String targetKey = relativeKey.toUpperCase(Locale.ROOT);
                if (!seenTargets.add(targetKey)) {
                    throw new IOException("Duplicate target (case conflict) detected: " + entryName);
                }

                String fileName = entryPath.getFileName().toString();
                String baseName = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
                if (RESERVED_NAMES.contains(baseName.toUpperCase(Locale.ROOT))) {
                    throw new IOException("Windows reserved filename: " + fileName);
                }

                long declaredSize = entry.getSize();
                if (declaredSize > maxEntrySize) {
                    throw new IOException(
                        "Single file exceeds size limit: " + entryName + " (" + declaredSize + ")");
                }

                createdFiles.add(entryPath);
                long actualSize = copyEntry(zipFile, entry, entryName, entryPath,
                        maxEntrySize, maxTotalSize, totalBytes, createdDirs);
                if (declaredSize >= 0 && actualSize != declaredSize) {
                    throw new IOException("Entry size mismatch (forged size?): " + entryName);
                }

                extracted.add(entryPath);
            }
        }

        log.info("ZIP extracted: {} files, {} bytes, archive={}",
            extracted.size(), totalBytes[0], archive.getFileName());
        return extracted;
    }

    /**
     * 以 64 KiB 有界缓冲将单个条目边读边写，逐块累计实际 entry/total 字节并立即查限
     * （{@link Math#addExact} 防溢出），同时累计 CRC。声明 size 与实读不一致由调用方判定。
     *
     * @return 实际读取（写入）的字节数
     */
    private long copyEntry(ZipFile zipFile, ZipArchiveEntry entry, String entryName, Path entryPath,
                           long maxEntrySize, long maxTotalSize, long[] totalBytes,
                           Set<Path> createdDirs) throws IOException {
        createParentDirectories(entryPath, createdDirs);

        long actualSize = 0;
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream in = zipFile.getInputStream(entry);
             OutputStream out = Files.newOutputStream(entryPath,
                 StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                actualSize = Math.addExact(actualSize, read);
                if (actualSize > maxEntrySize) {
                    throw new IOException("Single file exceeds size limit: " + entryName);
                }
                totalBytes[0] = Math.addExact(totalBytes[0], read);
                if (totalBytes[0] > maxTotalSize) {
                    throw new IOException("Total unpacked size exceeds limit: " + totalBytes[0]);
                }
                crc.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        if (entry.getCrc() != -1 && crc.getValue() != entry.getCrc()) {
            throw new IOException("Entry CRC mismatch: " + entryName);
        }
        return actualSize;
    }

    /**
     * 按需创建条目父目录链，记录本调用新建的目录（供失败清理）。
     * 已有路径必须是真实目录；路径组件被文件/符号链接占用时拒绝。
     */
    private void createParentDirectories(Path entryPath, Set<Path> createdDirs) throws IOException {
        Path current = entryPath.getParent();
        List<Path> toCreate = new ArrayList<>();
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Path component is not a directory: " + entryPath.getFileName());
                }
                break;
            }
            toCreate.add(current);
            current = current.getParent();
        }
        for (int i = toCreate.size() - 1; i >= 0; i--) {
            Files.createDirectory(toCreate.get(i));
            createdDirs.add(toCreate.get(i));
        }
    }

    /**
     * 解压失败时清理本次已创建的文件与目录（目录按深度逆序），保留原始异常。
     * 清理自身失败只记录日志，不掩盖主异常。
     */
    private void cleanupPartial(List<Path> createdFiles, Set<Path> createdDirs) {
        for (Path file : createdFiles) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("清理部分解压文件失败: {}", file, e);
            }
        }
        createdDirs.stream()
            .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
            .forEach(dir -> {
                try {
                    Files.deleteIfExists(dir);
                } catch (IOException e) {
                    log.warn("清理部分解压目录失败: {}", dir, e);
                }
            });
    }
}
