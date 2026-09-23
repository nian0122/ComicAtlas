package com.comicatlas.worker.media.image;

import java.io.IOException;
import java.nio.file.Path;

/** 图片格式识别与解码探测接口。实现必须基于文件内容而非扩展名作最终判断。 */
public interface ImageDecoder {
    DecodeResult inspect(Path file) throws IOException;

    final class DecodeResult {
        private final String format;
        private final boolean decodable;
        private final Integer width;
        private final Integer height;
        private final String failureReason;

        public DecodeResult(String format, boolean decodable, Integer width, Integer height,
                            String failureReason) {
            this.format = format;
            this.decodable = decodable;
            this.width = width;
            this.height = height;
            this.failureReason = failureReason;
        }

        public String format() { return format; }
        public boolean decodable() { return decodable; }
        public Integer width() { return width; }
        public Integer height() { return height; }
        public String failureReason() { return failureReason; }
    }
}
