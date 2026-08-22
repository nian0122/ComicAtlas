package com.comicatlas.worker.media.metadata;

import com.comicatlas.common.storage.InvalidRelativePathException;
import com.comicatlas.common.storage.RelativePathValidator;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.common.constant.MediaTypes;

/** 元数据扫描使用的无状态路径、媒体类型和记录判定工具。 */
public final class MetadataScanSupport {

    public static final String IMAGE_TYPE = MediaTypes.IMAGE;
    public static final String LQ_STATUS_NOT_GENERATED = "NOT_GENERATED";
    public static final String HQ_STATUS_DELETED = "DELETED";

    private MetadataScanSupport() {
    }

    public static boolean isLqOnlyRow(MediaRecord row) {
        return IMAGE_TYPE.equals(row.getMediaType())
                && HQ_STATUS_DELETED.equals(row.getHqStatus())
                && row.getLqPath() != null
                && !row.getLqPath().isBlank();
    }

    /** 校验并提取 {@code comicId/dirKey/fileName} 中的目录键。 */
    public static String extractDirKey(String relativePath, Long comicId) {
        if (relativePath == null) {
            return null;
        }
        try {
            RelativePathValidator.requireRelativeForwardSlash(relativePath);
        } catch (InvalidRelativePathException exception) {
            return null;
        }
        String[] segments = relativePath.split("/");
        if (segments.length != 3 || !segments[0].equals(String.valueOf(comicId))
                || segments[1].isBlank() || segments[2].isBlank()) {
            return null;
        }
        return segments[1];
    }

    public static String basenameOf(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        int index = relativePath.lastIndexOf('/');
        return index >= 0 ? relativePath.substring(index + 1) : relativePath;
    }

    public static String extensionOf(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index >= 0 ? fileName.substring(index).toLowerCase(java.util.Locale.ROOT) : "";
    }

    public static String mediaTypeOf(String extension) {
        if (SetHolder.IMAGE_EXTENSIONS.contains(extension)) {
            return IMAGE_TYPE;
        }
        return SetHolder.VIDEO_EXTENSIONS.contains(extension) ? MediaTypes.VIDEO : null;
    }

    public static int versionOrZero(Integer version) {
        return version == null ? 0 : version;
    }

    private static final class SetHolder {
        private static final java.util.Set<String> IMAGE_EXTENSIONS =
                java.util.Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");
        private static final java.util.Set<String> VIDEO_EXTENSIONS =
                java.util.Set.of(".mp4", ".mkv", ".webm", ".mov", ".avi");

        private SetHolder() {
        }
    }
}
