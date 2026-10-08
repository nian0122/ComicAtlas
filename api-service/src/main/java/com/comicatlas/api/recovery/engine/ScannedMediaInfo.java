package com.comicatlas.api.recovery.engine;

/**
 * 扫描到的媒体文件信息（图片或视频）。
 */
@lombok.Getter
public class ScannedMediaInfo {
        private final String imageName;
        private final long fileSize;
        private final Integer width;
        private final Integer height;
        private final String mediaType;
        public ScannedMediaInfo(String imageName, long fileSize, Integer width, Integer height, String mediaType) {
            this.imageName = imageName;
            this.fileSize = fileSize;
            this.width = width;
            this.height = height;
            this.mediaType = mediaType;
        }
        public String imageName() { return imageName; }
        public long fileSize() { return fileSize; }
        public Integer width() { return width; }
        public Integer height() { return height; }
        public String mediaType() { return mediaType; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof ScannedMediaInfo)) { return false; }
            ScannedMediaInfo that = (ScannedMediaInfo) other;
            return java.util.Objects.equals(imageName, that.imageName) && java.util.Objects.equals(fileSize, that.fileSize) && java.util.Objects.equals(width, that.width) && java.util.Objects.equals(height, that.height) && java.util.Objects.equals(mediaType, that.mediaType);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(imageName, fileSize, width, height, mediaType); }
        @Override
        public String toString() { return "ScannedMediaInfo[" + "imageName=" + imageName + ", " + "fileSize=" + fileSize + ", " + "width=" + width + ", " + "height=" + height + ", " + "mediaType=" + mediaType + "]"; }}
