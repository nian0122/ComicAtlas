package com.comicatlas.worker.media.image;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/** 基于 ImageIO 的首个解码器，覆盖 JPEG/PNG/GIF/BMP/TIFF/WebP（由插件提供）。 */
@Component
public class ImageIoDecoder implements ImageDecoder {
    @Override
    public DecodeResult inspect(Path file) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) {
                return new DecodeResult(null, false, null, null, "无法创建图像输入流");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return new DecodeResult(null, false, null, null, "没有可用的图像解码器");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                return new DecodeResult(reader.getFormatName(), true,
                        reader.getWidth(0), reader.getHeight(0), null);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return new DecodeResult(null, false, null, null, e.getMessage());
        }
    }
}
