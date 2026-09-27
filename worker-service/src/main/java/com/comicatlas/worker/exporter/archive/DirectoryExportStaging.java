package com.comicatlas.worker.exporter.archive;

import com.comicatlas.worker.exporter.model.ExportManifest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** 文件夹导出 staging 完整性标记，供发布失败后的同任务重试安全复用。 */
public final class DirectoryExportStaging {
    private static final String METADATA_FILE_NAME = "metadata.json";
    private static final String COMIC_INFO_FILE_NAME = "ComicInfo.xml";

    private DirectoryExportStaging() {
    }

    /** staging 目录旁的标记不进入最终导出目录。 */
    public static Path markerPath(Path stagingDirectory) {
        return stagingDirectory.resolveSibling(stagingDirectory.getFileName() + ".complete");
    }

    /** 持久化清单指纹，调用方只能在全部文件复制和大小校验完成后调用。 */
    public static void markComplete(Path stagingDirectory, ExportManifest manifest) throws IOException {
        Files.writeString(markerPath(stagingDirectory), fingerprint(manifest), StandardCharsets.US_ASCII);
    }

    /** 验证 staging 属于当前清单且所有预期文件完整；不读媒体文件内容。 */
    public static boolean matches(Path stagingDirectory, ExportManifest manifest) throws IOException {
        Path marker = markerPath(stagingDirectory);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(stagingDirectory, LinkOption.NOFOLLOW_LINKS)
                || !fingerprint(manifest).equals(Files.readString(marker, StandardCharsets.US_ASCII))) {
            return false;
        }
        Path stagingRoot = stagingDirectory.resolve(manifest.rootDirName()).normalize();
        if (!stagingRoot.startsWith(stagingDirectory)
                || !Files.isDirectory(stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!matchesBytes(stagingRoot.resolve(METADATA_FILE_NAME),
                manifest.metadataJson().getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        if (manifest.comicInfoXml() == null || manifest.comicInfoXml().isBlank()) {
            if (Files.exists(stagingRoot.resolve(COMIC_INFO_FILE_NAME), LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
        } else if (!matchesBytes(stagingRoot.resolve(COMIC_INFO_FILE_NAME),
                manifest.comicInfoXml().getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        for (ExportManifest.Entry entry : manifest.entries()) {
            Path target = stagingRoot.resolve(entry.targetPath()).normalize();
            if (!target.startsWith(stagingRoot)
                    || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(target) != entry.sourceSize()) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesBytes(Path path, byte[] expectedBytes) throws IOException {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.size(path) == expectedBytes.length
                && MessageDigest.isEqual(Files.readAllBytes(path), expectedBytes);
    }

    private static String fingerprint(ExportManifest manifest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, manifest.rootDirName());
            update(digest, manifest.metadataJson());
            update(digest, manifest.comicInfoXml() == null ? "" : manifest.comicInfoXml());
            List<ExportManifest.Entry> entries = manifest.entries().stream()
                    .sorted(Comparator.comparing(ExportManifest.Entry::targetPath))
                    .toList();
            for (ExportManifest.Entry entry : entries) {
                update(digest, entry.targetPath());
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(entry.sourceSize()).array());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
