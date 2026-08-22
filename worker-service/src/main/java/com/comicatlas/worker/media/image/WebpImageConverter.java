package com.comicatlas.worker.media.image;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** JVM 基础转换器：把 ImageIO 可读的图片编码为 WebP（运行时需 WebP ImageIO 插件）。 */
@Component
public class WebpImageConverter implements ImageConverter {
    @Override
    public ConversionResult convert(InputStream source, OutputStream target, String sourceFormat) throws IOException {
        BufferedImage image = ImageIO.read(source);
        if (image == null) {
            return new ConversionResult("webp", 0, false, "图片无法解码");
        }
        if (!ImageIO.write(image, "webp", target)) {
            return new ConversionResult("webp", 0, false, "没有可用的 WebP 编码器");
        }
        return new ConversionResult("webp", 0, true, null);
    }
}
