package com.comicatlas.worker.media.image;

import java.io.IOException;
import java.nio.file.Path;

/** 图片格式识别与解码探测接口。实现必须基于文件内容而非扩展名作最终判断。 */
public interface ImageDecoder {
    DecodeResult inspect(Path file) throws IOException;

    record DecodeResult(String format, boolean decodable, Integer width, Integer height,
                        String failureReason) {}
}
