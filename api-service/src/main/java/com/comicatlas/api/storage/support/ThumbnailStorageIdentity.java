package com.comicatlas.api.storage.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 仅编码配置路径，不访问磁盘；更换根目录后旧容量不能用于新目录。 */
public final class ThumbnailStorageIdentity {
    private ThumbnailStorageIdentity() { }

    public static String fingerprint(Path root) {
        try {
            byte[] encodedPath = root.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encodedPath));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持存储根摘要", exception);
        }
    }
}
