package com.comicatlas.api.recovery.engine;

/**
 * 事务前解析出的单条媒体恢复信息 — 文件扫描/存在性校验结果的数据载体。
 *
 * @param fileName   文件名
 * @param pageNumber 页码（现代 metadata 提供；legacy 目录扫描按序 1..n）
 * @param fileSize   文件大小（文件存在时为真实大小，缺失时为 metadata 值）
 * @param width      图片宽度（可选）
 * @param height     图片高度（可选）
 * @param mediaType  IMAGE / VIDEO
 * @param hqPath     相对 HQ 根的正斜杠路径（现代 metadata 原样保留，legacy 按 globalOrder 布局拼装）
 * @param exists     文件在磁盘上是否存在（false 时恢复为 MISSING，不得标 READY）
 * @param lqStatus   LQ 状态（READY=LQ 文件存在 / NOT_GENERATED=不存在；仅图片有 LQ）
 * @param lqSize     LQ 文件字节数（未生成为 0）
 * @param lqPath     实际 LQ WebP 相对路径
 */
@lombok.Getter
public class ResolvedMediaItem {
        private final String fileName;
        private final int pageNumber;
        private final long fileSize;
        private final Integer width;
        private final Integer height;
        private final String mediaType;
        private final String hqPath;
        private final boolean exists;
        private final String lqStatus;
        private final long lqSize;
        private final String lqPath;
        public String fileName() { return fileName; }
        public int pageNumber() { return pageNumber; }
        public long fileSize() { return fileSize; }
        public Integer width() { return width; }
        public Integer height() { return height; }
        public String mediaType() { return mediaType; }
        public String hqPath() { return hqPath; }
        public boolean exists() { return exists; }
        public String lqStatus() { return lqStatus; }
        public long lqSize() { return lqSize; }
        public String lqPath() { return lqPath; }
        public ResolvedMediaItem(String fileName, int pageNumber, long fileSize, Integer width,
                                 Integer height, String mediaType, String hqPath, boolean exists,
                                 String lqStatus, long lqSize, String lqPath) {
            this.fileName = fileName;
            this.pageNumber = pageNumber;
            this.fileSize = fileSize;
            this.width = width;
            this.height = height;
            this.mediaType = mediaType;
            this.hqPath = hqPath;
            this.exists = exists;
            this.lqStatus = lqStatus;
            this.lqSize = lqSize;
            this.lqPath = lqPath;
        }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof ResolvedMediaItem)) { return false; }
            ResolvedMediaItem that = (ResolvedMediaItem) other;
            return java.util.Objects.equals(fileName, that.fileName) && java.util.Objects.equals(pageNumber, that.pageNumber) && java.util.Objects.equals(fileSize, that.fileSize) && java.util.Objects.equals(width, that.width) && java.util.Objects.equals(height, that.height) && java.util.Objects.equals(mediaType, that.mediaType) && java.util.Objects.equals(hqPath, that.hqPath) && java.util.Objects.equals(exists, that.exists) && java.util.Objects.equals(lqStatus, that.lqStatus) && java.util.Objects.equals(lqSize, that.lqSize) && java.util.Objects.equals(lqPath, that.lqPath);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(fileName, pageNumber, fileSize, width, height, mediaType, hqPath, exists, lqStatus, lqSize, lqPath); }
        @Override
        public String toString() { return "ResolvedMediaItem[" + "fileName=" + fileName + ", " + "pageNumber=" + pageNumber + ", " + "fileSize=" + fileSize + ", " + "width=" + width + ", " + "height=" + height + ", " + "mediaType=" + mediaType + ", " + "hqPath=" + hqPath + ", " + "exists=" + exists + ", " + "lqStatus=" + lqStatus + ", " + "lqSize=" + lqSize + ", " + "lqPath=" + lqPath + "]"; }

    /** LQ 未生成状态名（与 LqStatus 枚举一致）。 */
    private static final String LQ_STATUS_NOT_GENERATED = "NOT_GENERATED";

    /**
     * 旧构造入口（无 LQ 事实，lqStatus=NOT_GENERATED、lqSize=0），保持向后兼容。
     */
    public ResolvedMediaItem(
            String fileName,
            int pageNumber,
            long fileSize,
            Integer width,
            Integer height,
            String mediaType,
            String hqPath,
            boolean exists) {
        this(fileName, pageNumber, fileSize, width, height, mediaType, hqPath, exists,
                LQ_STATUS_NOT_GENERATED, 0L, null);
    }

    public ResolvedMediaItem(String fileName, int pageNumber, long fileSize, Integer width,
                             Integer height, String mediaType, String hqPath, boolean exists,
                             String lqStatus, long lqSize) {
        this(fileName, pageNumber, fileSize, width, height, mediaType, hqPath, exists,
                lqStatus, lqSize, null);
    }
}
