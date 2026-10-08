package com.comicatlas.api.recovery.engine;

/**
 * 图片尺寸 — 内部使用，表示从图片文件中解析出的宽高。
 */
class ImageDimensions {
        private final Integer width;
        private final Integer height;
        public ImageDimensions(Integer width, Integer height) {
            this.width = width;
            this.height = height;
        }
        public Integer width() { return width; }
        public Integer height() { return height; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof ImageDimensions)) { return false; }
            ImageDimensions that = (ImageDimensions) other;
            return java.util.Objects.equals(width, that.width) && java.util.Objects.equals(height, that.height);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(width, height); }
        @Override
        public String toString() { return "ImageDimensions[" + "width=" + width + ", " + "height=" + height + "]"; }}
