package com.comicatlas.worker.media.image;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 将可解码图片转换为阅读端兼容格式的统一接口。 */
public interface ImageConverter {
    ConversionResult convert(InputStream source, OutputStream target, String sourceFormat) throws IOException;

    record ConversionResult(String outputFormat, long outputSize, boolean success, String failureReason) {}
}
